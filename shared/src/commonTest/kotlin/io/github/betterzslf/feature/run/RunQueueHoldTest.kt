package io.github.betterzslf.feature.run

import io.github.betterzslf.core.storage.MemoryKeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class RunQueueHoldTest {
    private val metadata = RunMetadata("hold-run", "hold-schedule", "hold-owner")
    private fun batches() = RunBatcher.create(metadata, RunTrackGenerator.fromSamples((0..10).map {
        RunLocationSample(RunGeo.offset(GeoPoint(10.0, 20.0), it * 20.0, 0.0), it * 10_000L, 10.0)
    }), "hold-prefix")
    private suspend fun ready(queue: RunUploadQueue) {
        val blocks = batches()
        queue.enqueue(blocks)
        queue.confirmUpload(metadata.trid, metadata.userId, blocks.map { it.batchId })
    }

    @Test fun holdIsOwnerCheckedAndRevokesOnlyUnsentPermission() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        ready(queue)
        val original = queue.snapshot()
        assertFailsWith<IllegalArgumentException> { queue.holdRun(metadata.trid, "different-owner") }
        assertEquals(original, queue.snapshot())
        queue.holdRun(metadata.trid, metadata.userId)
        assertTrue(queue.snapshot().all { it.status == RunBatchStatus.STAGED && !it.confirmed })
        var sends = 0
        val result = RunUploadWorker(queue, RunUploadTransport { _, batch -> sends++; RunSendResult.Accepted(batch.batchId) })
            .run(metadata.trid, metadata.userId, { Long.MAX_VALUE })
        assertEquals(RunWorkerStop.NEEDS_CONFIRMATION, result.stop)
        assertEquals(0, sends)
    }

    @Test fun holdRetainsAcknowledgedUncertainAndFailureEvidence() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        ready(queue)
        val first = assertIs<RunUploadQueue.Claim.Acquired>(queue.claimNext(metadata.trid, metadata.userId, Long.MAX_VALUE))
        queue.accepted(first.entry.batch.batchId, first.entry.batch.batchId)
        val second = assertIs<RunUploadQueue.Claim.Acquired>(queue.claimNext(metadata.trid, metadata.userId, Long.MAX_VALUE))
        queue.failed(second.entry.batch.batchId, "fixture unknown acceptance", uncertain = true, allowFailover = false)
        val before = queue.snapshot()
        queue.holdRun(metadata.trid, metadata.userId)
        val after = queue.snapshot()
        assertEquals(before[0], after[0])
        assertEquals(before[1], after[1])
        assertEquals(RunBatchStatus.UNCERTAIN, after[1].status)
        assertEquals(RunBatchStatus.STAGED, after[2].status)
        assertEquals(before.map { it.batch }, after.map { it.batch })
    }

    @Test fun holdingDuringASendDoesNotCorruptItsAcknowledgmentOrStartTheNextBlock() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        ready(queue)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var sends = 0
        val worker = RunUploadWorker(queue, RunUploadTransport { _, batch ->
            sends++
            started.complete(Unit)
            release.await()
            RunSendResult.Accepted(batch.batchId)
        })
        val running = async { worker.run(metadata.trid, metadata.userId, { Long.MAX_VALUE }) }
        started.await()
        val inFlight = queue.snapshot().first()
        queue.holdRun(metadata.trid, metadata.userId)
        assertEquals(inFlight, queue.snapshot().first())
        assertTrue(queue.snapshot().drop(1).all { it.status == RunBatchStatus.STAGED && !it.confirmed })
        release.complete(Unit)
        assertEquals(RunWorkerStop.NEEDS_CONFIRMATION, running.await().stop)
        assertEquals(1, sends)
        assertEquals(RunBatchStatus.ACKNOWLEDGED, queue.snapshot().first().status)
    }

    @Test fun holdingAFailedBlockPreservesItsAttemptsEndpointAndMessage() = runTest {
        val queue = RunUploadQueue(MemoryKeyValueStore())
        ready(queue)
        val claim = assertIs<RunUploadQueue.Claim.Acquired>(queue.claimNext(metadata.trid, metadata.userId, Long.MAX_VALUE))
        queue.failed(claim.entry.batch.batchId, "fixture definitive rejection", uncertain = false, allowFailover = false)
        val failed = queue.snapshot().first()
        queue.holdRun(metadata.trid, metadata.userId)
        val held = queue.snapshot().first()
        assertEquals(RunBatchStatus.STAGED, held.status)
        assertFalse(held.confirmed)
        assertEquals(failed.batch, held.batch)
        assertEquals(failed.attempts, held.attempts)
        assertEquals(failed.endpoint, held.endpoint)
        assertEquals(failed.failures, held.failures)
    }
}
