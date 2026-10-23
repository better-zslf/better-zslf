package io.github.betterzslf.feature.run

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.platform.LocationPhase
import io.github.betterzslf.platform.LocationTrackingState
import io.github.betterzslf.platform.NativeLocationTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RunSessionCoordinatorTest {
    private val metadata = RunMetadata("session-fixture", "schedule-fixture", "owner-fixture")
    private val origin = GeoPoint(10.0, 20.0)
    private val base = 1_000_000L
    private fun request() = RunSessionStart(metadata, listOf(RunCheckpoint("start", origin), RunCheckpoint("next", RunGeo.offset(origin, 100.0, 0.0))), batchIdPrefix = "session-batch")
    private fun sample(index: Int) = RunLocationSample(RunGeo.offset(origin, index * 20.0, 0.0), base + index * 10_000L, 10.0, 2.0)
    private fun applied(result: RunSessionAction) = assertIs<RunSessionAction.Applied>(result).state
    private fun rejected(result: RunSessionAction) = assertIs<RunSessionAction.Rejected>(result).problem

    private class FakeTracker : NativeLocationTracker {
        override val state = MutableStateFlow(LocationTrackingState())
        private val events = MutableSharedFlow<RunLocationSample>(extraBufferCapacity = 8)
        override val samples = events.asSharedFlow()
        var starts = 0
        var stops = 0
        var closes = 0
        var startPhase = LocationPhase.Tracking
        var requestedInterval = 0
        var subscribedBeforeStart = false
        override fun start(samplingSeconds: Int) {
            starts++
            requestedInterval = samplingSeconds
            subscribedBeforeStart = events.subscriptionCount.value > 0
            check(subscribedBeforeStart) { "coordinator must subscribe before native start" }
            state.value = LocationTrackingState(startPhase, if (startPhase == LocationPhase.Tracking) null else "fixture permission required")
        }
        override fun stop() { stops++; state.value = LocationTrackingState(LocationPhase.Idle) }
        override fun close() { closes++; stop() }
        suspend fun emit(sample: RunLocationSample) { events.emit(sample) }
    }

    private class TestStore : KeyValueStore {
        private val backing = MemoryKeyValueStore()
        var failNextSessionWrite = false
        var failNextQueueWrite = false
        var failFinishedWrite = false
        override fun get(key: String) = backing.get(key)
        override fun put(key: String, value: String) {
            if (key == RunSessionCoordinator.STORAGE_KEY && failNextSessionWrite) { failNextSessionWrite = false; error("fixture session disk failure") }
            if (key == "run.upload.queue.v1" && failNextQueueWrite) { failNextQueueWrite = false; error("fixture queue disk failure") }
            if (key == RunSessionCoordinator.STORAGE_KEY && failFinishedWrite && value.contains("\"phase\":\"FINISHED\"")) { failFinishedWrite = false; error("fixture finished checkpoint failure") }
            backing.put(key, value)
        }
        override fun remove(key: String) = backing.remove(key)
        override fun clear() = backing.clear()
    }

    private inner class Harness(scope: CoroutineScope, val storage: KeyValueStore = TestStore(), limit: Int = RunSessionCoordinator.MAX_SAMPLES_PER_SESSION) {
        val tracker = FakeTracker()
        val queue = RunUploadQueue(storage)
        var now = base
        val coordinator = RunSessionCoordinator(scope, tracker, storage, queue, { now }, limit)
        suspend fun start(confirmed: Boolean = true) = coordinator.start(request(), metadata.userId, confirmed)
        suspend fun close() { coordinator.close() }
        fun persistedCopy(): MemoryKeyValueStore = MemoryKeyValueStore().also { copy ->
            for (key in listOf(RunSessionCoordinator.STORAGE_KEY, "run.upload.queue.v1")) storage.get(key)?.let { copy.put(key, it) }
        }
    }
    private suspend fun TestScope.emit(harness: Harness, value: RunLocationSample, clock: Long = value.timestampMillis) {
        harness.now = clock
        harness.tracker.emit(value)
        runCurrent()
    }

    @Test fun startRequiresExplicitConsentAndDurableMetadataBeforeNativeStart() = runTest {
        val h = Harness(backgroundScope)
        try {
            assertEquals(RunSessionProblemCode.CONFIRMATION_REQUIRED, rejected(h.start(false)).code)
            assertEquals(0, h.tracker.starts)
            assertNull(h.storage.get(RunSessionCoordinator.STORAGE_KEY))
            applied(h.start())
            assertTrue(h.tracker.subscribedBeforeStart)
            assertEquals(10, h.tracker.requestedInterval)
            assertEquals(metadata, h.coordinator.state.value.metadata)
            assertEquals(StoredRunSessionPhase.RECORDING, RunSessionJournal(h.storage).load().sessions.single().phase)
            assertTrue(h.queue.snapshot().isEmpty())
        } finally { h.close() }
    }

    @Test fun startDiskFailureNeverStartsTrackerOrClaimsAnAcceptedSample() = runTest {
        val store = TestStore().apply { failNextSessionWrite = true }
        val h = Harness(backgroundScope, store)
        try {
            assertEquals(RunSessionProblemCode.STORAGE, rejected(h.start()).code)
            assertEquals(0, h.tracker.starts)
            assertEquals(0, h.coordinator.state.value.acceptedSampleCount)
            assertTrue(h.queue.snapshot().isEmpty())
        } finally { h.close() }
    }

    @Test fun realSamplesDriveDistanceTimeAverageAndOrderedCheckpointState() = runTest {
        val h = Harness(backgroundScope)
        try {
            applied(h.start())
            emit(h, sample(0))
            assertEquals(1, h.coordinator.state.value.nextCheckpointIndex)
            assertEquals(100.0, h.coordinator.state.value.distanceToNextCheckpointMeters!!, 0.001)
            emit(h, sample(1))
            val state = h.coordinator.state.value
            assertEquals(listOf(sample(0), sample(1)), state.acceptedSamples)
            assertEquals(2, state.acceptedSampleCount)
            assertEquals(20.0, state.distanceMeters, 1e-6)
            assertEquals(10_000L, state.elapsedMillis)
            assertEquals(2.0, state.averageSpeedMetersPerSecond, 1e-6)
            assertEquals(80.0, state.distanceToNextCheckpointMeters!!, 0.001)
            assertEquals(state.acceptedSamples, RunSessionJournal(h.storage).load().sessions.single().samples)
        } finally { h.close() }
    }

    @Test fun pauseResumeIncludesPauseGapAndNeverRewritesNativeTimestamps() = runTest {
        val h = Harness(backgroundScope)
        try {
            applied(h.start())
            emit(h, sample(0)); emit(h, sample(1))
            applied(h.coordinator.pause(metadata.userId))
            emit(h, sample(2)) // No active collector while paused.
            assertEquals(2, h.coordinator.state.value.acceptedSampleCount)
            h.now = base + 30_000
            assertEquals(RunSessionProblemCode.CONFIRMATION_REQUIRED, rejected(h.coordinator.resume(metadata.userId, false)).code)
            assertEquals(1, h.tracker.starts)
            applied(h.coordinator.resume(metadata.userId, true))
            emit(h, sample(2), clock = base + 30_000) // Late callback from the earlier capture window.
            assertEquals(RunSessionSampleRejection.BEFORE_RECORDING_WINDOW, h.coordinator.state.value.lastSampleRejection)
            emit(h, sample(4))
            val state = h.coordinator.state.value
            assertEquals(listOf(base, base + 10_000, base + 40_000), state.acceptedSamples.map { it.timestampMillis })
            assertEquals(40_000L, state.elapsedMillis)
            assertEquals(80.0, state.distanceMeters, 0.001)
            assertEquals(2, h.tracker.starts)
        } finally { h.close() }
    }

    @Test fun rejectsAccuracySpeedDuplicateFutureAndTooFrequentSamplesWithoutPersistingThem() = runTest {
        val h = Harness(backgroundScope)
        try {
            applied(h.start()); emit(h, sample(0))
            emit(h, sample(1).copy(accuracyMeters = 75.0))
            assertEquals(LocationRejection.ACCURACY, h.coordinator.state.value.lastFilterRejection)
            emit(h, sample(1).copy(speedMetersPerSecond = 6.0))
            assertEquals(LocationRejection.SPEED, h.coordinator.state.value.lastFilterRejection)
            emit(h, sample(0))
            assertEquals(LocationRejection.NON_INCREASING_TIME, h.coordinator.state.value.lastFilterRejection)
            emit(h, sample(1), clock = base)
            assertEquals(RunSessionSampleRejection.FUTURE_TIME, h.coordinator.state.value.lastSampleRejection)
            emit(h, RunLocationSample(RunGeo.offset(origin, 10.0, 0.0), base + 5_000, 10.0))
            assertEquals(RunSessionSampleRejection.TOO_FREQUENT, h.coordinator.state.value.lastSampleRejection)
            assertEquals(1, h.coordinator.state.value.acceptedSampleCount)
            assertEquals(5, h.coordinator.state.value.rejectedSampleCount)
            assertEquals(listOf(sample(0)), RunSessionJournal(h.storage).load().sessions.single().samples)
        } finally { h.close() }
    }

    @Test fun liveBatchesAreConfirmedOnlyAfterRealSamplesAreDurableAndFinishIsExactlyOnce() = runTest {
        val h = Harness(backgroundScope)
        try {
            applied(h.start())
            for (i in 0..5) emit(h, sample(i))
            assertEquals(1, h.queue.snapshot().size)
            assertEquals("0", h.queue.snapshot().single().batch.payload.finishType)
            assertTrue(h.queue.snapshot().single().confirmed)
            assertEquals(5, h.coordinator.state.value.queuedSampleCount)
            h.now += 100_000 // A finish-button delay is not a fabricated endpoint or runTime.
            val finished = applied(h.coordinator.finish(metadata.userId))
            val blocks = h.queue.snapshot()
            assertEquals(RunSessionPhase.FINISHED, finished.phase)
            assertEquals(6, finished.acceptedSampleCount)
            assertEquals(listOf("0", "1"), blocks.map { it.batch.payload.finishType })
            assertTrue(blocks.all { it.status == RunBatchStatus.READY })
            assertEquals(sample(5).timestampMillis, blocks.last().batch.payload.endTime)
            assertEquals(50_000L, blocks.last().batch.payload.runTime)
            assertEquals(50_000L, finished.elapsedMillis)
            assertEquals(h.now, finished.finishedAtMillis)
            applied(h.coordinator.finish(metadata.userId))
            assertEquals(blocks, h.queue.snapshot())
            emit(h, sample(6), clock = h.now)
            assertEquals(6, h.coordinator.state.value.acceptedSampleCount)
        } finally { h.close() }
    }

    @Test fun emptyAndSinglePointSessionsFinishLocallyWithoutManufacturingCoordinates() = runTest {
        for (count in 0..1) {
            val h = Harness(backgroundScope)
            try {
                applied(h.start())
                if (count == 1) emit(h, sample(0))
                h.now += 60_000
                val state = applied(h.coordinator.finish(metadata.userId))
                assertEquals(RunSessionPhase.FINISHED, state.phase)
                assertEquals(count, state.acceptedSampleCount)
                assertEquals(RunSessionUploadPhase.INSUFFICIENT_SAMPLES, state.uploadPhase)
                assertFalse(state.hasFinishableTrack)
                assertEquals(0L, state.elapsedMillis)
                assertTrue(h.queue.snapshot().isEmpty())
                val raw = RunSessionJournal(h.storage).load().sessions.single().samples
                assertEquals(if (count == 0) emptyList() else listOf(sample(0)), raw)
            } finally { h.close() }
        }
    }

    @Test fun recoveryNeverStartsNativeCaptureOrMaterializesAnUnconfirmedTail() = runTest {
        val first = Harness(backgroundScope)
        applied(first.start())
        for (i in 0..5) emit(first, sample(i))
        val diskAtDeath = first.persistedCopy()
        first.close()
        val restored = Harness(backgroundScope, diskAtDeath)
        try {
            val state = applied(restored.coordinator.restore(metadata.userId))
            assertEquals(RunSessionPhase.RECOVERED, state.phase)
            assertEquals(6, state.acceptedSampleCount)
            assertEquals(0, restored.tracker.starts)
            assertEquals(1, restored.queue.snapshot().size)
            assertEquals(RunBatchStatus.STAGED, restored.queue.snapshot().single().status)
            assertEquals(5, state.queuedSampleCount)
            assertTrue(state.uploadConfirmationRequired)
            var sends = 0
            val worker = RunUploadWorker(restored.queue, RunUploadTransport { _, batch -> sends++; RunSendResult.Accepted(batch.batchId) })
            assertEquals(RunWorkerStop.NEEDS_CONFIRMATION, worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
            assertEquals(0, sends)
            restored.now = sample(5).timestampMillis
            val finished = applied(restored.coordinator.finish(metadata.userId))
            assertEquals(2, restored.queue.snapshot().size)
            assertTrue(restored.queue.snapshot().all { it.status == RunBatchStatus.STAGED })
            assertEquals(0, sends)
            assertEquals(RunSessionProblemCode.INVALID_INPUT, rejected(restored.coordinator.confirmPendingUpload(metadata.userId, listOf("stale"))).code)
            applied(restored.coordinator.confirmPendingUpload(metadata.userId, finished.pendingBatchIds))
            assertEquals(RunWorkerStop.COMPLETE, worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
            assertEquals(2, sends)
        } finally { restored.close() }
    }

    @Test fun recoveryPreservesAckTombstonesAndUnknownAcceptanceWithoutRetryingThem() = runTest {
        val first = Harness(backgroundScope)
        applied(first.start())
        for (i in 0..10) emit(first, sample(i))
        val one = assertIs<RunUploadQueue.Claim.Acquired>(first.queue.claimNext(metadata.trid, metadata.userId, Long.MAX_VALUE))
        first.queue.accepted(one.entry.batch.batchId, one.entry.batch.batchId)
        val two = assertIs<RunUploadQueue.Claim.Acquired>(first.queue.claimNext(metadata.trid, metadata.userId, Long.MAX_VALUE))
        first.queue.failed(two.entry.batch.batchId, "fixture acceptance unknown", uncertain = true, allowFailover = false)
        val persisted = first.persistedCopy()
        first.close()
        val restored = Harness(backgroundScope, persisted)
        try {
            val state = applied(restored.coordinator.restore(metadata.userId))
            assertEquals(RunSessionUploadPhase.UNCERTAIN, state.uploadPhase)
            assertEquals(listOf(RunBatchStatus.ACKNOWLEDGED, RunBatchStatus.UNCERTAIN), restored.queue.snapshot().map { it.status })
            assertEquals(RunSessionProblemCode.QUEUE, rejected(restored.coordinator.confirmPendingUpload(metadata.userId, state.pendingBatchIds)).code)
            restored.now = sample(10).timestampMillis
            applied(restored.coordinator.finish(metadata.userId, uploadConfirmed = true))
            assertEquals(listOf(RunBatchStatus.ACKNOWLEDGED, RunBatchStatus.UNCERTAIN, RunBatchStatus.STAGED), restored.queue.snapshot().map { it.status })
            assertEquals(0, restored.tracker.starts)
        } finally { restored.close() }
    }

    @Test fun interruptedFinalizationFreezesSamplesAndCanBeExplicitlyFinishedWithoutDuplicatingFinalBlock() = runTest {
        val store = TestStore()
        val first = Harness(backgroundScope, store)
        applied(first.start())
        for (i in 0..5) emit(first, sample(i))
        store.failNextQueueWrite = true
        assertEquals(RunSessionProblemCode.QUEUE, rejected(first.coordinator.finish(metadata.userId)).code)
        assertTrue(first.coordinator.state.value.finishRequested)
        assertEquals(RunSessionPhase.FINISHING, first.coordinator.state.value.phase)
        emit(first, sample(6))
        assertEquals(6, first.coordinator.state.value.acceptedSampleCount)
        val disk = first.persistedCopy()
        first.close()
        val restored = Harness(backgroundScope, disk)
        try {
            applied(restored.coordinator.restore(metadata.userId))
            assertEquals(RunSessionProblemCode.INVALID_STATE, rejected(restored.coordinator.resume(metadata.userId, true)).code)
            restored.now = sample(6).timestampMillis
            applied(restored.coordinator.finish(metadata.userId))
            val blocks = restored.queue.snapshot()
            assertEquals(2, blocks.size)
            assertEquals(1, blocks.count { it.batch.payload.finishType == "1" })
            assertTrue(blocks.all { it.status == RunBatchStatus.STAGED })
            applied(restored.coordinator.finish(metadata.userId))
            assertEquals(blocks, restored.queue.snapshot())
        } finally { restored.close() }
    }

    @Test fun finalQueueCommitFollowedByJournalFailureStillRecoversTheSameFinalId() = runTest {
        val store = TestStore()
        val first = Harness(backgroundScope, store)
        applied(first.start())
        for (i in 0..5) emit(first, sample(i))
        store.failFinishedWrite = true
        assertEquals(RunSessionProblemCode.STORAGE, rejected(first.coordinator.finish(metadata.userId)).code)
        val finalId = first.queue.snapshot().last().batch.batchId
        assertEquals(StoredRunSessionPhase.FINISHING, RunSessionJournal(store).load().sessions.single().phase)
        val disk = first.persistedCopy()
        first.close()
        val restored = Harness(backgroundScope, disk)
        try {
            applied(restored.coordinator.restore(metadata.userId))
            restored.now = sample(5).timestampMillis
            applied(restored.coordinator.finish(metadata.userId))
            assertEquals(finalId, restored.queue.snapshot().last().batch.batchId)
            assertEquals(1, restored.queue.snapshot().count { it.batch.payload.finishType == "1" })
        } finally { restored.close() }
    }

    @Test fun sampleStorageFailureIsVisibleAndRetrySavesOnlyTheOriginalNativeSample() = runTest {
        val store = TestStore()
        val h = Harness(backgroundScope, store)
        try {
            applied(h.start()); emit(h, sample(0))
            store.failNextSessionWrite = true
            emit(h, sample(1))
            val failed = h.coordinator.state.value
            assertEquals(RunSessionPhase.PAUSED, failed.phase)
            assertEquals(RunSessionProblemCode.STORAGE, failed.problem!!.code)
            assertEquals(1, failed.acceptedSampleCount)
            assertTrue(failed.hasUnpersistedSample)
            assertEquals(RunSessionProblemCode.STORAGE, rejected(h.coordinator.finish(metadata.userId)).code)
            assertEquals(RunSessionProblemCode.STORAGE, rejected(h.coordinator.cancel(metadata.userId)).code)
            val saved = applied(h.coordinator.retryPersistence(metadata.userId))
            assertEquals(listOf(sample(0), sample(1)), saved.acceptedSamples)
            assertFalse(saved.hasUnpersistedSample)
            assertEquals(1, h.tracker.starts)
        } finally { h.close() }
    }

    @Test fun ownerMismatchNeverExposesOrChangesAnotherUsersRawSamplesOrQueue() = runTest {
        val h = Harness(backgroundScope)
        try {
            applied(h.start())
            for (i in 0..5) emit(h, sample(i))
            val raw = h.storage.get(RunSessionCoordinator.STORAGE_KEY)
            val blocks = h.queue.snapshot()
            for (command in listOf<suspend () -> RunSessionAction>(
                { h.coordinator.pause("other-owner") },
                { h.coordinator.resume("other-owner", true) },
                { h.coordinator.finish("other-owner", true) },
                { h.coordinator.cancel("other-owner") },
                { h.coordinator.restore("other-owner") },
            )) {
                assertEquals(RunSessionProblemCode.OWNER_MISMATCH, rejected(command()).code)
                assertNull(h.coordinator.state.value.metadata)
                assertTrue(h.coordinator.state.value.acceptedSamples.isEmpty())
            }
            assertEquals(raw, h.storage.get(RunSessionCoordinator.STORAGE_KEY))
            assertEquals(blocks, h.queue.snapshot())
            assertTrue(h.coordinator.sessions("other-owner").isEmpty())
            applied(h.coordinator.restore(metadata.userId))
            assertEquals(6, h.coordinator.state.value.acceptedSampleCount)
            assertEquals(1, h.tracker.starts)
        } finally { h.close() }
    }

    @Test fun aNewOwnersStartRequestStopsAndIsolatesTheAlreadyActiveOwner() = runTest {
        val h = Harness(backgroundScope)
        try {
            applied(h.start()); emit(h, sample(0))
            val journalBefore = h.storage.get(RunSessionCoordinator.STORAGE_KEY)
            val foreign = RunSessionStart(RunMetadata("foreign-run", "foreign-plan", "foreign-owner"), batchIdPrefix = "foreign-prefix")
            for (confirmed in listOf(false, true)) assertEquals(RunSessionProblemCode.OWNER_MISMATCH, rejected(h.coordinator.start(foreign, "foreign-owner", confirmed)).code)
            assertNull(h.coordinator.state.value.metadata)
            assertTrue(h.coordinator.state.value.acceptedSamples.isEmpty())
            emit(h, sample(1))
            assertEquals(journalBefore, h.storage.get(RunSessionCoordinator.STORAGE_KEY))
            assertEquals(1, h.tracker.starts)
            assertTrue(h.coordinator.sessions("foreign-owner").isEmpty())
        } finally { h.close() }
    }

    @Test fun cancelHoldsUnsentBlocksKeepsRealSamplesAndDoesNotGenerateFinalOne() = runTest {
        val h = Harness(backgroundScope)
        try {
            applied(h.start())
            for (i in 0..5) emit(h, sample(i))
            val raw = h.coordinator.state.value.acceptedSamples
            val state = applied(h.coordinator.cancel(metadata.userId))
            assertEquals(RunSessionPhase.CANCELLED, state.phase)
            assertEquals(raw, state.acceptedSamples)
            assertTrue(h.queue.snapshot().all { it.status == RunBatchStatus.STAGED && !it.confirmed && it.batch.payload.finishType == "0" })
            assertEquals(RunSessionProblemCode.INVALID_STATE, rejected(h.coordinator.finish(metadata.userId)).code)
            assertEquals(RunSessionProblemCode.INVALID_STATE, rejected(h.coordinator.confirmPendingUpload(metadata.userId, state.pendingBatchIds)).code)
            emit(h, sample(6))
            assertEquals(raw, h.coordinator.state.value.acceptedSamples)
        } finally { h.close() }
    }

    @Test fun permissionFailureAndNativeFailurePauseWithoutAutomaticRetry() = runTest {
        val h = Harness(backgroundScope)
        try {
            h.tracker.startPhase = LocationPhase.PermissionRequired
            assertEquals(RunSessionProblemCode.LOCATION, rejected(h.start()).code)
            assertEquals(RunSessionPhase.PAUSED, h.coordinator.state.value.phase)
            assertEquals(1, h.tracker.starts)
            h.tracker.startPhase = LocationPhase.Tracking
            applied(h.coordinator.resume(metadata.userId, true))
            emit(h, sample(0))
            h.tracker.state.value = LocationTrackingState(LocationPhase.LocationDisabled, "fixture GPS disabled")
            runCurrent()
            assertEquals(RunSessionPhase.PAUSED, h.coordinator.state.value.phase)
            assertEquals(RunSessionProblemCode.LOCATION, h.coordinator.state.value.problem!!.code)
            assertEquals(2, h.tracker.starts)
            assertEquals(listOf(sample(0)), h.coordinator.state.value.acceptedSamples)
        } finally { h.close() }
    }

    @Test fun sampleLimitStopsCaptureAndClockRollbackDoesNotAlterRealTimestamps() = runTest {
        val h = Harness(backgroundScope, limit = 2)
        try {
            applied(h.start()); emit(h, sample(0)); emit(h, sample(1)); emit(h, sample(2))
            assertEquals(2, h.coordinator.state.value.acceptedSampleCount)
            assertEquals(RunSessionProblemCode.SAMPLE_LIMIT, h.coordinator.state.value.problem!!.code)
            assertEquals(RunSessionPhase.PAUSED, h.coordinator.state.value.phase)
            assertEquals(RunSessionProblemCode.SAMPLE_LIMIT, rejected(h.coordinator.resume(metadata.userId, true)).code)
            assertEquals(1, h.tracker.starts)
            h.now = base - 1
            assertEquals(RunSessionProblemCode.CLOCK, rejected(h.coordinator.finish(metadata.userId)).code)
            assertEquals(listOf(base, base + 10_000), h.coordinator.state.value.acceptedSamples.map { it.timestampMillis })
        } finally { h.close() }
    }

    @Test fun corruptJournalIsPreservedAndDoesNotStartLocation() = runTest {
        val storage = MemoryKeyValueStore().apply { put(RunSessionCoordinator.STORAGE_KEY, "{fixture corrupt session") }
        val h = Harness(backgroundScope, storage)
        try {
            assertEquals(RunSessionProblemCode.STORAGE, rejected(h.coordinator.restore(metadata.userId)).code)
            assertEquals("{fixture corrupt session", storage.get(RunSessionCoordinator.STORAGE_KEY))
            assertEquals(0, h.tracker.starts)
        } finally { h.close() }
    }

    @Test fun concurrentFinishAndDuplicateStartKeepOneSessionAndOneFinalBlock() = runTest {
        val h = Harness(backgroundScope)
        try {
            applied(h.start())
            assertEquals(RunSessionProblemCode.ACTIVE_SESSION_EXISTS, rejected(h.start()).code)
            assertEquals(1, h.tracker.starts)
            for (i in 0..5) emit(h, sample(i))
            val finishes = List(3) { async { h.coordinator.finish(metadata.userId) } }
            finishes.forEach { applied(it.await()) }
            assertEquals(1, RunSessionJournal(h.storage).load().sessions.size)
            assertEquals(1, h.queue.snapshot().count { it.batch.payload.finishType == "1" })
            assertEquals(2, h.queue.snapshot().size)
        } finally { h.close() }
    }

    @Test fun queueDiskFailureKeepsAcceptedRawDataAndLaterLocalStagingUsesTheSameId() = runTest {
        val store = TestStore()
        val h = Harness(backgroundScope, store)
        try {
            applied(h.start())
            for (i in 0..4) emit(h, sample(i))
            store.failNextQueueWrite = true
            emit(h, sample(5))
            assertEquals(6, h.coordinator.state.value.acceptedSampleCount)
            assertEquals(RunSessionPhase.RECORDING, h.coordinator.state.value.phase)
            assertEquals(RunSessionProblemCode.QUEUE, h.coordinator.state.value.problem!!.code)
            assertEquals(6, RunSessionJournal(store).load().sessions.single().samples.size)
            assertTrue(h.queue.snapshot().isEmpty())
            emit(h, sample(6))
            val block = h.queue.snapshot().single()
            assertEquals("session-batch-000000", block.batch.batchId)
            assertEquals(RunBatchStatus.READY, block.status)
            assertEquals(7, h.coordinator.state.value.acceptedSampleCount)
        } finally { h.close() }
    }

    @Test fun finishedRawHistoryIsRetainedWhenANewOwnedSessionStarts() = runTest {
        val h = Harness(backgroundScope)
        try {
            applied(h.start()); emit(h, sample(0))
            applied(h.coordinator.finish(metadata.userId))
            val next = RunSessionStart(metadata.copy(trid = "second-session"), batchIdPrefix = "second-batch")
            applied(h.coordinator.start(next, metadata.userId, true))
            val records = RunSessionJournal(h.storage).load().sessions
            assertEquals(2, records.size)
            assertEquals(listOf(sample(0)), records.first().samples)
            assertEquals(StoredRunSessionPhase.FINISHED, records.first().phase)
            assertEquals("second-session", records.last().trid)
            assertEquals(2, h.coordinator.sessions(metadata.userId).size)
        } finally { h.close() }
    }

    @Test fun closingOrCancellingAppScopeReleasesOnlyOwnedJobsAndNativeResources() = runTest {
        val parent = SupervisorJob()
        val isolatedAppScope = CoroutineScope(backgroundScope.coroutineContext + parent)
        val h = Harness(isolatedAppScope)
        applied(h.start()); emit(h, sample(0))
        parent.cancel()
        runCurrent()
        assertEquals(RunSessionPhase.CLOSED, h.coordinator.state.value.phase)
        assertEquals(1, h.tracker.closes)
        assertTrue(backgroundScope.isActive)
        assertEquals(RunSessionProblemCode.CLOSED, rejected(h.coordinator.resume(metadata.userId, true)).code)
        applied(h.coordinator.close())
        assertEquals(1, h.tracker.closes)
    }
}
