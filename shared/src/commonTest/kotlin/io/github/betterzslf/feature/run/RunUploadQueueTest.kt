package io.github.betterzslf.feature.run

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class RunUploadQueueTest {
    private val metadata = RunMetadata("synthetic-run", "synthetic-schedule", "synthetic-user")
    private fun track(count: Int = 11): GeneratedRunTrack = RunTrackGenerator.fromSamples((0 until count).map {
        RunLocationSample(RunGeo.offset(GeoPoint(10.0, 20.0), it * 20.0, 0.0), 1_000_000L + it * 10_000L, 10.0)
    })
    private fun batches(count: Int = 11, finished: Boolean = true) = RunBatcher.create(metadata, track(count), "local-stable-prefix", finished)
    private suspend fun confirm(queue: RunUploadQueue) {
        queue.confirmUpload(metadata.trid, metadata.userId, queue.snapshot().filter { it.status != RunBatchStatus.ACKNOWLEDGED }.map { it.batch.batchId })
    }
    private fun accepting(calls: MutableList<Pair<RunUploadEndpoint, String>>) = RunUploadTransport { endpoint, batch ->
        calls += endpoint to batch.batchId
        RunSendResult.Accepted(batch.batchId)
    }

    @Test fun fivePointsPerBlockLastBlockOnlyFinishedAndCoordinateRemainsAString() {
        val blocks = batches()
        assertEquals(listOf(5, 5, 1), blocks.map { Json.parseToJsonElement(it.payload.coordinate).jsonArray.size })
        assertEquals(listOf("0", "0", "1"), blocks.map { it.payload.finishType })
        assertEquals(3, blocks.map { it.batchId }.distinct().size)
        assertEquals(blocks, batches())
        val wire = blocks.last().payload.toJsonObject()
        assertEquals(blocks.last().payload.coordinate, wire["coordinate"]!!.jsonPrimitive.content)
        assertEquals(100_000L, blocks.last().payload.runTime)
        assertEquals(blocks.last().payload.endTime - blocks.last().payload.startTime, blocks.last().payload.runTime)
        assertEquals(track().averageSpeedMetersPerSecond, blocks.last().payload.speed.toDouble(), 1e-8)
        val secondFirst = Json.parseToJsonElement(blocks[1].payload.coordinate).jsonArray.first().jsonObject
        assertEquals(50.0, secondFirst["count"]!!.jsonPrimitive.content.toDouble())
        assertEquals(20.0, secondFirst["dis"]!!.jsonPrimitive.content.toDouble(), 1e-6)
    }

    @Test fun exactMultipleOfFiveAndLiveTailNeverRequireRewritingAnOldBatch() {
        assertEquals(listOf("0", "1"), batches(10).map { it.payload.finishType })
        val live = batches(10, finished = false)
        assertEquals(1, live.size)
        assertEquals(live.first(), batches(11, finished = true).first())
        assertTrue(batches(5, finished = false).isEmpty())
        assertEquals("1", batches(5, finished = true).single().payload.finishType)
    }

    @Test fun enqueueIsIdempotentConflictsPreserveExistingDataAndIndexesMustBeContinuous() = runTest {
        val store = MemoryKeyValueStore()
        val queue = RunUploadQueue(store)
        val blocks = batches()
        queue.enqueue(blocks)
        queue.enqueue(blocks)
        assertEquals(3, queue.snapshot().size)
        assertFailsWith<IllegalArgumentException> { queue.enqueue(listOf(blocks.first().copy(payload = blocks.first().payload.copy(userName = "different")))) }
        assertEquals(blocks, queue.snapshot().map { it.batch })
        assertFailsWith<IllegalArgumentException> { RunUploadQueue(MemoryKeyValueStore()).enqueue(blocks.drop(1)) }
        assertFailsWith<IllegalArgumentException> { RunUploadQueue(MemoryKeyValueStore()).enqueue(listOf(blocks.first().copy(payload = blocks.first().payload.copy(finishType = "1"))) + blocks.drop(1)) }
    }

    @Test fun explicitConfirmationExactPreviewAndOwnerAreRequiredBeforeAnySend() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        queue.enqueue(batches())
        val calls = mutableListOf<Pair<RunUploadEndpoint, String>>()
        val worker = RunUploadWorker(queue, accepting(calls))
        assertEquals(RunWorkerStop.NEEDS_CONFIRMATION, worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        assertTrue(calls.isEmpty())
        assertFailsWith<IllegalArgumentException> { queue.confirmUpload(metadata.trid, metadata.userId, listOf("stale-preview")) }
        assertFailsWith<IllegalArgumentException> { queue.confirmUpload(metadata.trid, "another-user", batches().map { it.batchId }) }
        assertFailsWith<IllegalArgumentException> { worker.run(metadata.trid, "another-user", { Long.MAX_VALUE }) }
        confirm(queue)
        assertEquals(RunWorkerStop.COMPLETE, worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        assertEquals(3, calls.size)
    }

    @Test fun futureTrajectoryIsNotSentBeforeItsPlannedTime() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        val blocks = batches()
        queue.enqueue(blocks)
        confirm(queue)
        val calls = mutableListOf<Pair<RunUploadEndpoint, String>>()
        val result = RunUploadWorker(queue, accepting(calls)).run(metadata.trid, metadata.userId, { blocks.first().payload.endTime - 1 })
        assertEquals(RunWorkerStop.NOT_DUE, result.stop)
        assertEquals(blocks.first().payload.endTime, result.nextDueMillis)
        assertTrue(calls.isEmpty())
    }

    @Test fun acknowledgedChunksSurviveProcessRestartWithoutBeingSentAgain() = runTest {
        val store = MemoryKeyValueStore()
        val queue = RunUploadQueue(store)
        queue.enqueue(batches())
        confirm(queue)
        val calls = mutableListOf<Pair<RunUploadEndpoint, String>>()
        assertEquals(RunWorkerStop.LIMIT, RunUploadWorker(queue, accepting(calls)).run(metadata.trid, metadata.userId, { Long.MAX_VALUE }, maximumAttempts = 1).stop)
        assertEquals(RunBatchStatus.ACKNOWLEDGED, queue.snapshot().first().status)
        val reopened = RunUploadQueue(store)
        assertEquals(RunWorkerStop.COMPLETE, RunUploadWorker(reopened, accepting(calls)).run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        assertEquals(batches().map { it.batchId }, calls.map { it.second })
        reopened.enqueue(batches()) // ACK tombstones also prevent re-enqueue from resending.
        assertEquals(RunWorkerStop.COMPLETE, RunUploadWorker(reopened, accepting(calls)).run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        assertEquals(3, calls.size)
    }

    @Test fun confirmedDefinitiveFailuresUseThreeEndpointsAndRemainVisible() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        queue.enqueue(batches(6))
        confirm(queue)
        val calls = mutableListOf<RunUploadEndpoint>()
        val firstId = batches(6).first().batchId
        val transport = RunUploadTransport { endpoint, batch ->
            calls += endpoint
            if (batch.batchId == firstId && endpoint != RunUploadEndpoint.REPORT_LOCATION) RunSendResult.NotAccepted("fixture endpoint unavailable", mayFailover = true)
            else RunSendResult.Accepted(batch.batchId)
        }
        val result = RunUploadWorker(queue, transport).run(metadata.trid, metadata.userId, { Long.MAX_VALUE })
        assertEquals(RunWorkerStop.COMPLETE, result.stop)
        assertEquals(listOf(RunUploadEndpoint.PRIMARY, RunUploadEndpoint.SYNC_SPORT_DATA, RunUploadEndpoint.REPORT_LOCATION), calls.take(3))
        assertEquals(2, result.failures.size)
        assertEquals(2, queue.snapshot().first().failures.size)
        assertEquals(3, queue.snapshot().first().attempts)
    }

    @Test fun allThreeDefinitiveFailuresStopAndAreNotReportedAsSuccess() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        queue.enqueue(batches())
        confirm(queue)
        var calls = 0
        val result = RunUploadWorker(queue, RunUploadTransport { _, _ -> calls++; RunSendResult.NotAccepted("fixture failure $calls", true) })
            .run(metadata.trid, metadata.userId, { Long.MAX_VALUE })
        assertEquals(3, calls)
        assertEquals(RunWorkerStop.FAILED, result.stop)
        assertEquals(3, result.failures.size)
        assertEquals(RunBatchStatus.FAILED, queue.snapshot().first().status)
        assertEquals(0, result.acknowledgedBatches)
    }

    @Test fun authenticationOrBusinessRejectionDoesNotCascade() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        queue.enqueue(batches())
        confirm(queue)
        var calls = 0
        val result = RunUploadWorker(queue, RunUploadTransport { _, _ -> calls++; RunSendResult.NotAccepted("fixture needs login") }).run(metadata.trid, metadata.userId, { Long.MAX_VALUE })
        assertEquals(RunWorkerStop.FAILED, result.stop)
        assertEquals(1, calls)
        assertEquals("fixture needs login", queue.snapshot().first().failures.single().message)
    }

    @Test fun timeoutIsUncertainNeverAutomaticallyResentAndRequiresReconciliation() = runTest {
        val store = MemoryKeyValueStore()
        val queue = RunUploadQueue(store)
        queue.enqueue(batches())
        confirm(queue)
        var calls = 0
        val uncertain = RunUploadWorker(queue, RunUploadTransport { _, _ -> calls++; RunSendResult.Unknown("fixture timeout") })
        assertEquals(RunWorkerStop.UNCERTAIN, uncertain.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        val reopened = RunUploadQueue(store)
        assertEquals(RunBatchStatus.UNCERTAIN, reopened.snapshot().first().status)
        val sent = mutableListOf<Pair<RunUploadEndpoint, String>>()
        assertEquals(RunWorkerStop.UNCERTAIN, RunUploadWorker(reopened, accepting(sent)).run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        assertTrue(sent.isEmpty())
        assertEquals(1, calls)
        assertFailsWith<IllegalArgumentException> { confirm(reopened) }
        reopened.reconcile(batches().first().batchId, wasAccepted = true, evidence = "fixture server ledger contains batch")
        assertEquals(RunWorkerStop.COMPLETE, RunUploadWorker(reopened, accepting(sent)).run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        assertEquals(batches().drop(1).map { it.batchId }, sent.map { it.second })
    }

    @Test fun provenNotAcceptedCanBeExplicitlyReconfirmedUsingTheIdenticalBatchId() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        queue.enqueue(batches())
        confirm(queue)
        RunUploadWorker(queue, RunUploadTransport { _, _ -> RunSendResult.Unknown("fixture connection lost") }).run(metadata.trid, metadata.userId, { Long.MAX_VALUE })
        val original = queue.snapshot().first().batch
        queue.reconcile(original.batchId, false, "fixture server confirms request was never accepted")
        assertEquals(RunBatchStatus.STAGED, queue.snapshot().first().status)
        confirm(queue)
        val sent = mutableListOf<Pair<RunUploadEndpoint, String>>()
        RunUploadWorker(queue, accepting(sent)).run(metadata.trid, metadata.userId, { Long.MAX_VALUE })
        assertEquals(original.batchId, sent.first().second)
        assertEquals(original, queue.snapshot().first().batch)
    }

    @Test fun crashedInflightBatchBecomesUncertainBeforeAnyNewSend() = runTest {
        val store = MemoryKeyValueStore()
        val queue = RunUploadQueue(store)
        queue.enqueue(batches())
        confirm(queue)
        assertIs<RunUploadQueue.Claim.Acquired>(queue.claimNext(metadata.trid, metadata.userId, Long.MAX_VALUE))
        val reopened = RunUploadQueue(store)
        assertEquals(RunBatchStatus.UNCERTAIN, reopened.snapshot().first().status)
        val calls = mutableListOf<Pair<RunUploadEndpoint, String>>()
        assertEquals(RunWorkerStop.UNCERTAIN, RunUploadWorker(reopened, accepting(calls)).run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        assertTrue(calls.isEmpty())
    }

    @Test fun cancellationPersistsAnUncertainOutcomeAndConcurrentWorkersDoNotDuplicate() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        queue.enqueue(batches())
        confirm(queue)
        val started = CompletableDeferred<Unit>()
        val first = launch {
            RunUploadWorker(queue, RunUploadTransport { _, _ -> started.complete(Unit); awaitCancellation() }).run(metadata.trid, metadata.userId, { Long.MAX_VALUE })
        }
        started.await()
        val calls = mutableListOf<Pair<RunUploadEndpoint, String>>()
        assertEquals(RunWorkerStop.IN_PROGRESS, RunUploadWorker(queue, accepting(calls)).run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        assertTrue(calls.isEmpty())
        first.cancelAndJoin()
        assertEquals(RunBatchStatus.UNCERTAIN, queue.snapshot().first().status)
    }

    @Test fun mismatchedAcknowledgmentAndThrownTransportExceptionsAreNeverSuccess() = runTest {
        for (transport in listOf(
            RunUploadTransport { _, _ -> RunSendResult.Accepted("different-batch") },
            RunUploadTransport { _, _ -> throw IllegalStateException("fixture internal exception") },
        )) {
            val queue = RunUploadQueue(MemoryKeyValueStore())
            queue.enqueue(batches())
            confirm(queue)
            val result = RunUploadWorker(queue, transport).run(metadata.trid, metadata.userId, { Long.MAX_VALUE })
            assertEquals(RunWorkerStop.UNCERTAIN, result.stop)
            assertEquals(0, result.acknowledgedBatches)
            assertEquals(RunBatchStatus.UNCERTAIN, queue.snapshot().first().status)
        }
    }

    @Test fun ackPersistenceFailurePropagatesAndRecoveryDoesNotResend() = runTest {
        val store = FailingStore(failOnPut = 4) // enqueue, confirm, in-flight, then ACK checkpoint fails.
        val queue = RunUploadQueue(store)
        queue.enqueue(batches())
        confirm(queue)
        val calls = mutableListOf<Pair<RunUploadEndpoint, String>>()
        assertFailsWith<RunQueueException> { RunUploadWorker(queue, accepting(calls)).run(metadata.trid, metadata.userId, { Long.MAX_VALUE }) }
        assertEquals(1, calls.size)
        val reopened = RunUploadQueue(store)
        assertEquals(RunBatchStatus.UNCERTAIN, reopened.snapshot().first().status)
        assertEquals(RunWorkerStop.UNCERTAIN, RunUploadWorker(reopened, accepting(calls)).run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        assertEquals(1, calls.size)
    }

    @Test fun corruptOrUnreadableStorageIsSurfacedAndNeverOverwritten() = runTest {
        val store = MemoryKeyValueStore()
        store.put("run.upload.queue.v1", "{corrupt fixture")
        val queue = RunUploadQueue(store)
        assertFailsWith<RunQueueException> { queue.snapshot() }
        assertFailsWith<RunQueueException> { queue.enqueue(batches()) }
        assertEquals("{corrupt fixture", store.get("run.upload.queue.v1"))
        val unreadable = object : KeyValueStore {
            override fun get(key: String): String? = throw IllegalStateException("fixture disk unavailable")
            override fun put(key: String, value: String) = error("should never overwrite")
            override fun remove(key: String) = error("should never remove")
            override fun clear() = error("should never clear")
        }
        assertFailsWith<RunQueueException> { RunUploadQueue(unreadable).snapshot() }
    }

    @Test fun liveChunksCanBeAppendedWithoutRewritingAcknowledgments() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        queue.enqueue(batches(10, finished = false))
        confirm(queue)
        val calls = mutableListOf<Pair<RunUploadEndpoint, String>>()
        val worker = RunUploadWorker(queue, accepting(calls))
        assertEquals(RunWorkerStop.DRAINED, worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        queue.enqueue(batches(10, finished = true))
        assertEquals(RunBatchStatus.ACKNOWLEDGED, queue.snapshot().first().status)
        assertEquals(RunWorkerStop.NEEDS_CONFIRMATION, worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        confirm(queue)
        assertEquals(RunWorkerStop.COMPLETE, worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }).stop)
        assertEquals(2, calls.size)
        assertEquals(2, calls.map { it.second }.distinct().size)
    }

    private class FailingStore(private val failOnPut: Int) : KeyValueStore {
        private val backing = MemoryKeyValueStore()
        private var puts = 0
        override fun get(key: String) = backing.get(key)
        override fun put(key: String, value: String) {
            puts++
            if (puts == failOnPut) error("fixture write failure")
            backing.put(key, value)
        }
        override fun remove(key: String) = backing.remove(key)
        override fun clear() = backing.clear()
    }
}
