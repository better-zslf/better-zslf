package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiConfig
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.github.betterzslf.data.RunRepository
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.platform.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RunRuntimeTest {
    private val base = 1_700_000_000_000L
    private val origin = GeoPoint(113.4, 22.5)
    private val owner = "fixture-owner-a"
    private val clientTrid = "fixture-account-a$base"
    private fun session(user: String = owner, token: String = "fixture-token-a") = Session(token, buildJsonObject { put("id", user); put("account", "fixture-account-a"); put("name", "Fixture Runner") }, JsonObject(emptyMap()))
    private fun detail(trid: String = "fixture-trid", schedule: String? = "fixture-schedule") = RunTaskDetail(
        RunTaskItem(trid, schedule, "Fixture task", null, null, "0", trid, buildJsonObject { put("trid", trid); put("errandId", "$trid-errand"); if (schedule != null) put("scheduleId", schedule) }, errandId = "$trid-errand"),
        listOf(RunCheckpoint("checkpoint-0", RunMapCoordinates.wgs84ToGcj02(origin))), listOf(listOf(origin, RunGeo.offset(origin, 100.0, 0.0)).map(RunMapCoordinates::wgs84ToGcj02)), 80.0, emptyList())
    private fun sample(index: Int, interval: Long = 10_000L) = RunLocationSample(RunGeo.offset(origin, index * 10.0, 0.0), base + index * interval, 8.0, 1.0)

    private class Memory(val values: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
        var failNextKey: String? = null
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) {
            if (failNextKey == key) { failNextKey = null; error("Fixture disk failure") }
            values[key] = value
        }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
        fun cloneStore() = Memory(values.toMutableMap())
    }
    private class FakeTracker : NativeLocationTracker {
        override val state = MutableStateFlow(LocationTrackingState())
        val events = MutableSharedFlow<RunLocationSample>(extraBufferCapacity = 16)
        override val samples = events
        var starts = 0
        var stops = 0
        var closes = 0
        var interval: Int? = null
        var startPhase = LocationPhase.Tracking
        override fun start(samplingSeconds: Int) {
            check(events.subscriptionCount.value > 0) { "Subscribe before native start" }
            starts++; interval = samplingSeconds
            state.value = LocationTrackingState(startPhase)
        }
        override fun stop() { stops++; state.value = LocationTrackingState() }
        override fun close() { closes++; stop() }
    }
    private inner class Harness(
        scope: CoroutineScope,
        val storage: Memory = Memory(),
        factory: ((RunRepository) -> RunUploadTransport)? = null,
    ) {
        var now = base
        var requests = 0
        val headers = mutableListOf<String?>()
        val sessions = SessionStore(storage).also { if (it.session.value == null) it.save(session()) }
        val native = FakeTracker()
        val http = HttpClient(MockEngine { request ->
            requests++; headers += request.headers[HttpHeaders.Authorization]
            respond("{\"code\":200,\"data\":{}}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val api = ApiClient(http, sessions, ApiConfig("https://fixture.invalid/app", "https://fixture.invalid", "https://fixture.invalid/files/"))
        val repository = RunRepository(api)
        val runtime = RunRuntime(scope, storage, repository, native, { now }, sessions.session, factory)
        suspend fun start(sampling: Int = 10, confirmed: Boolean = true) = runtime.start(detail(), confirmed, sampling)
        suspend fun close() { runtime.close(); api.close(); http.close() }
    }
    private suspend fun TestScope.emit(h: Harness, point: RunLocationSample) { h.now = point.timestampMillis; h.native.events.emit(point); runCurrent() }
    private suspend fun TestScope.finish(h: Harness, count: Int = 7) {
        assertTrue(h.start().accepted)
        repeat(count) { emit(h, sample(it)) }
        assertTrue(h.runtime.finish(true).accepted)
        runCurrent()
    }
    private fun pending(h: Harness) = h.runtime.state.value.queue.filter { it.status != RunBatchStatus.ACKNOWLEDGED }.map { it.batch.batchId }

    @Test fun onlyExplicitStartGeneratesOneDurableClientIdAndNeverReusesOptionalServerTrid() = runTest {
        val h = Harness(backgroundScope)
        try {
            assertNull(h.storage.get(RunTaskLinks.STORAGE_KEY))
            val task = detail().let { it.copy(task = it.task.copy(trid = null)) }
            assertTrue(task.canStart)
            assertFalse(h.runtime.start(task, false, 10).accepted)
            assertNull(h.storage.get(RunTaskLinks.STORAGE_KEY))
            assertTrue(h.runtime.start(task, true, 10).accepted)
            assertEquals(clientTrid, h.runtime.state.value.session.metadata?.trid)
            assertEquals(RunTaskSelection("fixture-trid-errand", "fixture-schedule"), h.runtime.state.value.taskSelection)
            h.now += 1000
            assertFalse(h.runtime.start(task, true, 10).accepted)
            assertEquals(clientTrid, h.runtime.state.value.session.metadata?.trid)
            val links = Json.parseToJsonElement(h.storage.get(RunTaskLinks.STORAGE_KEY)!!).jsonObject.getValue("links").jsonArray
            assertEquals(1, links.size)
            assertEquals(1, h.native.starts)
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun missingAuthenticatedAccountIsNotReplacedByUserIdOrATemplate() = runTest {
        val h = Harness(backgroundScope)
        try {
            h.sessions.save(session().copy(userInfo = buildJsonObject { put("id", owner) }))
            assertFalse(h.start().accepted)
            assertEquals(0, h.native.starts)
            assertNull(h.storage.get(RunTaskLinks.STORAGE_KEY))
            assertNull(h.storage.get(RunSessionCoordinator.STORAGE_KEY))
        } finally { h.close() }
    }

    @Test fun preparedIdSurvivesJournalFailureAndProcessRestoreWithoutStartingSensors() = runTest {
        val old = Harness(backgroundScope)
        var next: Harness? = null
        try {
            old.storage.failNextKey = RunSessionCoordinator.STORAGE_KEY
            assertFalse(old.start().accepted)
            assertEquals(0, old.native.starts)
            assertNotNull(old.storage.get(RunTaskLinks.STORAGE_KEY))
            next = Harness(backgroundScope, old.storage.cloneStore())
            next.now = base + 2000
            assertTrue(next.runtime.loadLocalSessions().accepted)
            assertTrue(next.runtime.state.value.localSessions.isEmpty())
            assertEquals(0, next.native.starts)
            assertTrue(next.start().accepted)
            assertEquals(clientTrid, next.runtime.state.value.session.metadata?.trid)
            assertEquals(1, next.native.starts)
        } finally { next?.close(); old.close() }
    }

    @Test fun nativeCheckpointMathUsesAWgsCopyWhileOriginalServerAndSampleStayUnchanged() = runTest {
        val h = Harness(backgroundScope)
        try {
            val server = detail()
            val original = server.checkpoints.toList()
            h.runtime.start(server, true, 10)
            emit(h, sample(0))
            assertEquals(1, h.runtime.state.value.session.nextCheckpointIndex)
            assertEquals(listOf(sample(0)), h.runtime.state.value.session.acceptedSamples)
            assertEquals(original, server.checkpoints)
            assertTrue(RunGeo.distanceMeters(origin, server.checkpoints.single().point) > 100)
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun legacyJournalWithoutTaskLinkKeepsRawSamplesButDoesNotGuessTaskOrCheckpointFrame() = runTest {
        val old = Harness(backgroundScope)
        var next: Harness? = null
        try {
            finish(old)
            val store = old.storage.cloneStore().also { it.remove(RunTaskLinks.STORAGE_KEY) }
            next = Harness(backgroundScope, store)
            assertTrue(next.runtime.restore(clientTrid).accepted)
            assertNull(next.runtime.state.value.taskSelection)
            assertNull(next.runtime.state.value.session.distanceToNextCheckpointMeters)
            assertEquals((0..6).map { sample(it) }, next.runtime.state.value.session.acceptedSamples)
            assertEquals(0, next.native.starts)
            assertEquals(0, next.requests)
        } finally { next?.close(); old.close() }
    }

    @Test fun concurrentExternalCloseCallsBothWaitForWorkerAndNoWriterSurvivesStorageClear() = runTest {
        val sending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val h = Harness(backgroundScope, factory = { RunUploadTransport { _, _ ->
            sending.complete(Unit)
            withContext(NonCancellable) { release.await() }
            RunSendResult.Unknown("Fixture interrupted response")
        } })
        try {
            finish(h)
            val upload = async { h.runtime.upload(pending(h), true) }
            sending.await()
            val first = async { h.runtime.close() }
            runCurrent()
            val second = async { h.runtime.close() }
            runCurrent()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            release.complete(Unit)
            first.await(); second.await()
            assertFailsWith<CancellationException> { upload.await() }
            assertEquals(1, h.native.closes)
            h.storage.clear()
            h.native.events.emit(sample(7))
            h.native.state.value = LocationTrackingState(LocationPhase.Failed)
            runCurrent()
            assertTrue(h.storage.values.isEmpty())
            assertTrue(h.runtime.state.value.closed)
        } finally { release.complete(Unit); h.close() }
    }

    @Test fun rejectedJournalCloseStillShutsAllNativeCaptureBeforeStorageClear() = runTest {
        val h = Harness(backgroundScope)
        try {
            h.start(); emit(h, sample(0))
            h.storage.failNextKey = RunSessionCoordinator.STORAGE_KEY
            h.runtime.close()
            assertEquals(1, h.native.closes)
            h.storage.clear()
            h.native.events.emit(sample(1)); runCurrent()
            assertTrue(h.storage.values.isEmpty())
            assertTrue(h.runtime.state.value.closed)
        } finally { h.close() }
    }

    @Test fun acknowledgedResponseWithFailedLocalAckPersistenceRemainsUnknownAndNeverAutoRetries() = runTest {
        val store = Memory()
        var sends = 0
        val h = Harness(backgroundScope, store, factory = { RunUploadTransport { _, batch ->
            sends++; store.failNextKey = "run.upload.queue.v1"
            RunSendResult.Accepted(batch.batchId)
        } })
        try {
            finish(h)
            assertFalse(h.runtime.upload(pending(h), true).accepted)
            assertEquals(RunUploadUiPhase.UNCERTAIN, h.runtime.state.value.upload)
            assertTrue(h.runtime.state.value.queue.any { it.status == RunBatchStatus.UNCERTAIN })
            assertFalse(h.runtime.upload(pending(h), true).accepted)
            assertEquals(1, sends)
        } finally { h.close() }
    }

    @Test fun constructionHistoryReadAndCloseDoNotStartSensorsOrNetwork() = runTest {
        val h = Harness(backgroundScope)
        try {
            runCurrent()
            assertEquals(0, h.native.starts)
            assertTrue(h.runtime.loadLocalSessions().accepted)
            assertTrue(h.runtime.state.value.localSessions.isEmpty())
            assertEquals(0, h.requests)
        } finally { h.close() }
        assertEquals(1, h.native.closes)
    }

    @Test fun startRequiresRealIdentifiersSamplingBoundsAndExplicitConsent() = runTest {
        val h = Harness(backgroundScope)
        try {
            assertFalse(h.start(confirmed = false).accepted)
            assertFalse(h.start(sampling = 9).accepted)
            assertFalse(h.start(sampling = 61).accepted)
            assertFalse(h.runtime.start(detail(schedule = null), true, 10).accepted)
            assertEquals(0, h.native.starts)
            assertNull(h.storage.get(RunSessionCoordinator.STORAGE_KEY))
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun concurrentStartTapsHaveOneDurableSessionAndOneTrackerStart() = runTest {
        val h = Harness(backgroundScope)
        try {
            val a = async { h.start() }; val b = async { h.start() }
            assertEquals(1, listOf(a.await(), b.await()).count { it.accepted })
            assertEquals(1, h.native.starts)
            assertNotNull(h.storage.get(RunSessionCoordinator.STORAGE_KEY))
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun nativeSamplesRetainTheirExactWgsCoordinatesAndTimeAndCadence() = runTest {
        val h = Harness(backgroundScope)
        try {
            assertTrue(h.start(15).accepted)
            assertEquals(15, h.native.interval)
            emit(h, sample(0)); emit(h, sample(1)); emit(h, sample(2))
            val state = h.runtime.state.value.session
            assertEquals(listOf(sample(0), sample(2)), state.acceptedSamples)
            assertEquals(20_000L, state.elapsedMillis)
            assertEquals(20.0, state.distanceMeters, 0.01)
            assertEquals(1.0, state.averageSpeedMetersPerSecond, 0.001)
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun liveStagingFinishAndDuplicateFinishNeverSendOrMakeTwoFinalBlocks() = runTest {
        val h = Harness(backgroundScope)
        try {
            finish(h)
            val before = h.runtime.state.value.queue
            assertEquals(2, before.size)
            assertEquals(1, before.count { it.batch.payload.finishType == "1" })
            assertTrue(h.runtime.finish(true).accepted)
            assertEquals(before.map { it.batch }, h.runtime.state.value.queue.map { it.batch })
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun finishAndCancelRequireTheirOwnExplicitConfirmation() = runTest {
        val h = Harness(backgroundScope)
        try {
            h.start(); emit(h, sample(0))
            assertFalse(h.runtime.finish(false).accepted)
            assertFalse(h.runtime.cancel(false).accepted)
            assertEquals(RunSessionPhase.RECORDING, h.runtime.state.value.session.phase)
            assertTrue(h.runtime.cancel(true).accepted)
            assertEquals(RunSessionPhase.CANCELLED, h.runtime.state.value.session.phase)
            assertEquals(listOf(sample(0)), h.runtime.state.value.session.acceptedSamples)
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun zeroAndOneRealPointAreRetainedWithoutAnyFabricatedFinalBlock() = runTest {
        for (count in 0..1) {
            val h = Harness(backgroundScope)
            try {
                finish(h, count)
                assertEquals(count, h.runtime.state.value.session.acceptedSampleCount)
                assertTrue(h.runtime.state.value.queue.isEmpty())
                assertFalse(h.runtime.upload(emptyList(), true).accepted)
                assertEquals(0, h.requests)
            } finally { h.close() }
        }
    }

    @Test fun defaultTransportIsDisabledEvenAfterExactFinalConfirmation() = runTest {
        val h = Harness(backgroundScope)
        try {
            finish(h)
            assertFalse(h.runtime.state.value.transportAvailable)
            assertFalse(h.runtime.upload(pending(h), true).accepted)
            assertEquals(RunUploadUiPhase.UNAVAILABLE, h.runtime.state.value.upload)
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun aSuppliedFactoryStillNeedsFinishedSessionExactBatchPreviewAndFinalConsent() = runTest {
        var factories = 0
        val h = Harness(backgroundScope, factory = { factories++; RunRepositoryTransport(it) })
        try {
            h.start(); repeat(7) { emit(h, sample(it)) }
            assertFalse(h.runtime.upload(pending(h), true).accepted)
            h.runtime.finish(true)
            assertFalse(h.runtime.upload(pending(h), false).accepted)
            assertFalse(h.runtime.upload(listOf("stale-fixture-batch"), true).accepted)
            assertEquals(0, factories)
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun explicitUploadUsesTheBoundOwnersTokenAndOnlyAcknowledgesBatchesNotGrades() = runTest {
        var boundOwner: String? = null
        val h = Harness(backgroundScope, factory = { boundOwner = it.boundOwnerId; RunRepositoryTransport(it) })
        try {
            finish(h)
            assertTrue(h.runtime.upload(pending(h), true).accepted)
            assertEquals(owner, boundOwner)
            assertEquals(2, h.requests)
            assertTrue(h.headers.all { it == "fixture-token-a" })
            assertTrue(h.runtime.state.value.queue.all { it.status == RunBatchStatus.ACKNOWLEDGED })
            assertEquals(RunUploadUiPhase.ACKNOWLEDGED, h.runtime.state.value.upload)
            assertEquals(RunSessionPhase.FINISHED, h.runtime.state.value.session.phase)
        } finally { h.close() }
    }

    @Test fun unknownSendOutcomeIsPreservedAndDoesNotAutomaticallyRetry() = runTest {
        var sends = 0
        val h = Harness(backgroundScope, factory = { RunUploadTransport { _, _ -> sends++; RunSendResult.Unknown("Fixture unknown") } })
        try {
            finish(h)
            assertFalse(h.runtime.upload(pending(h), true).accepted)
            assertEquals(1, sends)
            assertTrue(h.runtime.state.value.queue.any { it.status == RunBatchStatus.UNCERTAIN })
            assertFalse(h.runtime.upload(pending(h), true).accepted)
            assertEquals(1, sends)
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun processRestoreShowsRealSamplesWithoutNativeStartOrUnconfirmedTailMaterialization() = runTest {
        val old = Harness(backgroundScope)
        var recovered: Harness? = null
        try {
            old.start(); repeat(7) { emit(old, sample(it)) }
            val store = old.storage.cloneStore()
            recovered = Harness(backgroundScope, store)
            recovered.now = sample(6).timestampMillis
            assertTrue(recovered.runtime.restore().accepted)
            assertEquals(7, recovered.runtime.state.value.session.acceptedSampleCount)
            assertEquals(RunSessionPhase.RECOVERED, recovered.runtime.state.value.session.phase)
            assertEquals(1, recovered.runtime.state.value.queue.size)
            assertEquals(RunBatchStatus.STAGED, recovered.runtime.state.value.queue.single().status)
            assertEquals(0, recovered.native.starts)
            assertEquals(0, recovered.requests)
            assertFalse(recovered.runtime.resume(false).accepted)
            assertTrue(recovered.runtime.finish(true).accepted)
            assertEquals(2, recovered.runtime.state.value.queue.size)
            assertEquals(0, recovered.requests)
        } finally { recovered?.close(); old.close() }
    }

    @Test fun ownerChangeStopsCaptureAndHidesEveryOldOwnerSampleAndSummary() = runTest {
        val h = Harness(backgroundScope)
        try {
            h.start(); repeat(7) { emit(h, sample(it)) }
            h.sessions.save(session("fixture-owner-b", "fixture-token-b")); runCurrent()
            assertEquals("fixture-owner-b", h.runtime.state.value.ownerId)
            assertNull(h.runtime.state.value.session.metadata)
            assertTrue(h.runtime.state.value.session.acceptedSamples.isEmpty())
            assertTrue(h.runtime.state.value.queue.isEmpty())
            assertTrue(h.native.stops > 0)
            emit(h, sample(7))
            assertTrue(h.runtime.loadLocalSessions().accepted)
            assertTrue(h.runtime.state.value.localSessions.isEmpty())
            assertFalse(h.runtime.restore(clientTrid).accepted)
            assertFalse(h.runtime.upload(emptyList(), true).accepted)
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun sameOwnerTokenChangeAlsoStopsCaptureAndRequiresRenewedResumeConsent() = runTest {
        val h = Harness(backgroundScope)
        try {
            h.start(); repeat(7) { emit(h, sample(it)) }
            h.sessions.save(session(token = "fixture-refreshed-token")); runCurrent()
            assertNull(h.runtime.state.value.session.metadata)
            assertTrue(h.runtime.state.value.requiresRestore)
            assertTrue(h.runtime.restore(clientTrid).accepted)
            assertEquals(1, h.native.starts)
            assertTrue(h.runtime.state.value.queue.all { it.status == RunBatchStatus.STAGED })
            assertFalse(h.runtime.resume(false).accepted)
            assertTrue(h.runtime.resume(true).accepted)
            assertEquals(2, h.native.starts)
        } finally { h.close() }
    }

    @Test fun sessionChangeBeforeTheFlowCollectorRunsCannotUseTheNewTokenForOldPayload() = runTest {
        var factories = 0
        val h = Harness(backgroundScope, factory = { factories++; RunRepositoryTransport(it) })
        try {
            finish(h)
            val ids = pending(h)
            h.sessions.save(session("fixture-owner-b", "fixture-token-b"))
            assertFalse(h.runtime.upload(ids, true).accepted)
            assertEquals(0, factories)
            assertEquals(0, h.requests)
            assertFalse(h.runtime.isCurrentSession() && h.runtime.state.value.ownerId == owner)
        } finally { h.close() }
    }

    @Test fun changingAccountDuringSuspendedSendCancelsItAndRetainsOldOwnerUncertainty() = runTest {
        val sending = CompletableDeferred<Unit>()
        val h = Harness(backgroundScope, factory = { RunUploadTransport { _, _ -> sending.complete(Unit); awaitCancellation() } })
        try {
            finish(h)
            val upload = async { h.runtime.upload(pending(h), true) }
            sending.await()
            h.sessions.save(session("fixture-owner-b", "fixture-token-b")); runCurrent()
            assertFailsWith<CancellationException> { upload.await() }
            assertNull(h.runtime.state.value.session.metadata)
            assertTrue(h.runtime.state.value.queue.isEmpty())
            h.sessions.save(session()); runCurrent()
            assertTrue(h.runtime.restore(clientTrid).accepted)
            assertTrue(h.runtime.state.value.queue.any { it.status == RunBatchStatus.UNCERTAIN })
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun doubleUploadTapsCoalesceAndSelectingAnotherSessionWaitsForCurrentWorker() = runTest {
        val sending = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sends = 0
        val h = Harness(backgroundScope, factory = { RunUploadTransport { _, batch -> sends++; sending.complete(Unit); release.await(); RunSendResult.Accepted(batch.batchId) } })
        try {
            finish(h)
            val ids = pending(h)
            val first = async { h.runtime.upload(ids, true) }
            sending.await()
            assertFalse(h.runtime.upload(ids, true).accepted)
            assertFalse(h.runtime.restore(clientTrid).accepted)
            assertFalse(h.runtime.start(detail("other-fixture-trid"), true, 10).accepted)
            assertEquals(1, sends)
            release.complete(Unit)
            assertTrue(first.await().accepted)
            assertEquals(2, sends)
        } finally { h.close() }
    }

    @Test fun nativePermissionFailurePausesWithoutAutomaticRestart() = runTest {
        val h = Harness(backgroundScope)
        try {
            h.native.startPhase = LocationPhase.PermissionRequired
            assertFalse(h.start().accepted)
            runCurrent()
            assertEquals(RunSessionPhase.PAUSED, h.runtime.state.value.session.phase)
            assertEquals(1, h.native.starts)
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun journalDiskFailureBeforeStartDoesNotLaunchNativeTracker() = runTest {
        val h = Harness(backgroundScope)
        try {
            h.storage.failNextKey = RunSessionCoordinator.STORAGE_KEY
            assertFalse(h.start().accepted)
            assertEquals(0, h.native.starts)
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun unpersistedRealPointCanOnlyBeRetriedLocallyWithoutRetimeOrNetwork() = runTest {
        val h = Harness(backgroundScope)
        try {
            h.start()
            h.storage.failNextKey = RunSessionCoordinator.STORAGE_KEY
            emit(h, sample(0))
            assertTrue(h.runtime.state.value.session.hasUnpersistedSample)
            assertEquals(0, h.runtime.state.value.session.acceptedSampleCount)
            assertFalse(h.runtime.finish(true).accepted)
            assertTrue(h.runtime.retryPersistence().accepted)
            assertEquals(listOf(sample(0)), h.runtime.state.value.session.acceptedSamples)
            assertEquals(0, h.requests)
        } finally { h.close() }
    }

    @Test fun cancellingParentScopeCleansNativeResourcesButNotTheExternalBackgroundScope() = runTest {
        val parent = Job(backgroundScope.coroutineContext[Job])
        val owned = CoroutineScope(backgroundScope.coroutineContext + parent)
        val h = Harness(owned)
        try {
            h.start(); emit(h, sample(0))
            parent.cancel(); runCurrent()
            assertEquals(1, h.native.closes)
            assertTrue(h.runtime.state.value.closed)
            assertTrue(backgroundScope.coroutineContext[Job]!!.isActive)
        } finally { h.close() }
    }
}
