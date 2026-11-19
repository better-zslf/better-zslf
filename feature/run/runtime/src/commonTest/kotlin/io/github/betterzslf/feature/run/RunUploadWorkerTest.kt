package io.github.betterzslf.feature.run

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure transport/storage fixtures: cancellation never authorizes a second network write. */
class RunUploadWorkerTest {
    private val metadata = RunMetadata("worker-fixture-run", "worker-fixture-schedule", "worker-fixture-owner")

    private suspend fun readyQueue(storage: KeyValueStore = MemoryKeyValueStore()): RunUploadQueue {
        val track = RunTrackGenerator.fromSamples((0..10).map { index ->
            RunLocationSample(RunGeo.offset(GeoPoint(10.0, 20.0), index * 20.0, 0.0), 1_000_000L + index * 10_000L, 10.0)
        })
        return RunUploadQueue(storage).also { queue ->
            queue.enqueue(RunBatcher.create(metadata, track, "worker-stable-fixture", finished = true))
            queue.confirmUpload(metadata.trid, metadata.userId, queue.snapshot().map { it.batch.batchId })
        }
    }

    @Test fun swallowedCancellationWithExactAcceptedReceiptCheckpointsOnlyThatBatch() = runTest {
        for (limit in listOf(1, 100)) {
            val storage = MemoryKeyValueStore()
            val queue = readyQueue(storage)
            val entered = CompletableDeferred<Unit>()
            var sends = 0
            val worker = RunUploadWorker(queue, RunUploadTransport { _, batch ->
                sends++
                entered.complete(Unit)
                try { awaitCancellation() }
                catch (_: CancellationException) { RunSendResult.Accepted(batch.batchId) }
            })
            val pending = async { worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }, maximumAttempts = limit) }
            entered.await()
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
            val persisted = queue.snapshot()
            assertEquals(1, sends)
            assertEquals(RunBatchStatus.ACKNOWLEDGED, persisted.first().status)
            assertEquals(1, persisted.first().attempts)
            assertFalse(persisted.first().confirmed)
            assertTrue(persisted.drop(1).all { it.status == RunBatchStatus.READY && it.attempts == 0 })
            assertEquals(persisted, RunUploadQueue(storage).snapshot())
        }
    }

    @Test fun swallowedCancellationWithoutAnExactReceiptRemainsUncertainAndNeverFailsOver() = runTest {
        for (wrongReceipt in listOf(false, true)) {
            val queue = readyQueue()
            val entered = CompletableDeferred<Unit>()
            var sends = 0
            val worker = RunUploadWorker(queue, RunUploadTransport { _, _ ->
                sends++
                entered.complete(Unit)
                try { awaitCancellation() }
                catch (_: CancellationException) {
                    if (wrongReceipt) RunSendResult.Accepted("unrelated-fixture-batch")
                    else RunSendResult.Unknown("fixture cancellation without receipt")
                }
            })
            val pending = async { worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }) }
            entered.await()
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
            val persisted = queue.snapshot()
            assertEquals(1, sends)
            assertEquals(RunBatchStatus.UNCERTAIN, persisted.first().status)
            assertEquals(RunUploadEndpoint.PRIMARY, persisted.first().endpoint)
            assertTrue(persisted.first().failures.single().uncertain)
            assertTrue(persisted.drop(1).all { it.status == RunBatchStatus.READY && it.attempts == 0 })
            var resumedSends = 0
            val resumed = RunUploadWorker(queue, RunUploadTransport { _, batch ->
                resumedSends++; RunSendResult.Accepted(batch.batchId)
            }).run(metadata.trid, metadata.userId, { Long.MAX_VALUE })
            assertEquals(RunWorkerStop.UNCERTAIN, resumed.stop)
            assertEquals(0, resumedSends)
        }
    }

    @Test fun swallowedCancellationWithDefiniteRejectionDoesNotExecuteAnAllowedFailover() = runTest {
        val queue = readyQueue()
        var sends = 0
        val worker = RunUploadWorker(queue, RunUploadTransport { _, _ ->
            sends++
            currentCoroutineContext().cancel()
            RunSendResult.NotAccepted("fixture definite rejection", mayFailover = true)
        })
        val pending = async { worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }) }
        assertFailsWith<CancellationException> { pending.await() }
        val persisted = queue.snapshot()
        assertEquals(1, sends)
        assertEquals(RunBatchStatus.READY, persisted.first().status)
        assertEquals(RunUploadEndpoint.SYNC_SPORT_DATA, persisted.first().endpoint)
        assertEquals(1, persisted.first().attempts)
        assertFalse(persisted.first().failures.single().uncertain)
        assertTrue(persisted.drop(1).all { it.attempts == 0 })
    }

    @Test fun cancellationBeforeWorkerAdmissionNeverClaimsOrSendsABatch() = runTest {
        val queue = readyQueue()
        val before = queue.snapshot()
        var sends = 0
        val worker = RunUploadWorker(queue, RunUploadTransport { _, batch -> sends++; RunSendResult.Accepted(batch.batchId) })
        val pending = async {
            currentCoroutineContext().cancel()
            worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE })
        }
        assertFailsWith<CancellationException> { pending.await() }
        assertEquals(0, sends)
        assertEquals(before, queue.snapshot())
    }

    @Test fun cancellationDuringClaimPersistenceStopsBeforeTransportAndMarksOnlyTheClaimUncertain() = runTest {
        val memory = MemoryKeyValueStore()
        var running: Job? = null
        var cancelOnClaim = false
        val storage = object : KeyValueStore by memory {
            override fun put(key: String, value: String) {
                memory.put(key, value)
                if (cancelOnClaim && key == "run.upload.queue.v1" && value.contains("\"status\":\"IN_FLIGHT\"")) {
                    cancelOnClaim = false
                    running?.cancel()
                }
            }
        }
        val queue = readyQueue(storage)
        var sends = 0
        val worker = RunUploadWorker(queue, RunUploadTransport { _, batch -> sends++; RunSendResult.Accepted(batch.batchId) })
        cancelOnClaim = true
        val pending = async {
            running = currentCoroutineContext()[Job]
            worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE })
        }
        assertFailsWith<CancellationException> { pending.await() }
        val persisted = queue.snapshot()
        assertEquals(0, sends)
        assertEquals(RunBatchStatus.UNCERTAIN, persisted.first().status)
        assertEquals(1, persisted.first().attempts)
        assertTrue(persisted.drop(1).all { it.status == RunBatchStatus.READY && it.attempts == 0 })
    }
}
