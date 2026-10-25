package io.github.betterzslf.feature.run

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.storage.KeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
enum class RunUploadEndpoint { PRIMARY, SYNC_SPORT_DATA, REPORT_LOCATION }
@Serializable
enum class RunBatchStatus { STAGED, READY, IN_FLIGHT, ACKNOWLEDGED, FAILED, UNCERTAIN }
@Serializable
data class RunUploadFailure(val endpoint: RunUploadEndpoint, val attempt: Int, val message: String, val uncertain: Boolean)
@Serializable
data class RunQueuedBatch(
    val batch: RunUploadBatch,
    val status: RunBatchStatus = RunBatchStatus.STAGED,
    val endpoint: RunUploadEndpoint = RunUploadEndpoint.PRIMARY,
    val attempts: Int = 0,
    val confirmed: Boolean = false,
    val failures: List<RunUploadFailure> = emptyList(),
)

class RunQueueException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * One instance per application store/key. put() must be durable when it returns for crash guarantees.
 * The entire journal is replaced in one store operation, and local operations are serialized by a Mutex.
 * ACK tombstones are intentionally retained; batchId is not evidence of server-side idempotency.
 */
class RunUploadQueue(private val storage: KeyValueStore, private val storageKey: String = "run.upload.queue.v1") {
    @Serializable
    private data class Document(val version: Int = 1, val entries: List<RunQueuedBatch> = emptyList())
    private val mutex = Mutex()
    private var initialized = false

    suspend fun snapshot(): List<RunQueuedBatch> = mutex.withLock { load().entries.toList() }

    /** Stages only: enqueue never constitutes user confirmation and never performs I/O to a server. */
    suspend fun enqueue(batches: List<RunUploadBatch>) = mutex.withLock {
        if (batches.isEmpty()) return@withLock
        batches.forEach { RunBatcher.validateBatch(it) }
        require(batches.map { it.batchId }.distinct().size == batches.size) { AppText.Run.INPUT_CONTAINS_DUPLICATE_BATCH_IDS }
        val document = load()
        val entries = document.entries.toMutableList()
        for (batch in batches) {
            val existing = entries.firstOrNull { it.batch.batchId == batch.batchId }
            if (existing != null) {
                require(existing.batch == batch) { AppText.Run.BATCH_ID_CONTENT_MISMATCH_ORIGINAL_RETAINED }
                continue
            }
            require(entries.none { it.batch.trid == batch.trid && it.batch.index == batch.index }) { AppText.Run.TASK_CHUNK_INDEX_ALREADY_HAS_DIFFERENT_BATCH_ID }
            entries += RunQueuedBatch(batch)
        }
        require(entries.size <= 20_000) { AppText.Run.LOCAL_UPLOAD_BATCH_LIMIT_REQUIRES_EXISTING_TASK_REVIEW }
        validateRuns(entries)
        persist(document.copy(entries = entries.sortedWith(compareBy({ it.batch.trid }, { it.batch.index }))))
    }

    /** expectedBatchIds comes from the exact pending preview that the user just confirmed. */
    suspend fun confirmUpload(trid: String, ownerId: String, expectedBatchIds: List<String>) = mutex.withLock {
        val document = load()
        val run = ownedRun(document, trid, ownerId)
        val pending = run.filter { it.status != RunBatchStatus.ACKNOWLEDGED }
        require(pending.isNotEmpty()) { AppText.Run.TASK_HAS_NO_PENDING_UPLOAD_BATCHES }
        require(pending.map { it.batch.batchId } == expectedBatchIds) { AppText.Run.UPLOAD_CONTENT_CHANGED_PREVIEW_AND_CONFIRM_AGAIN }
        require(pending.none { it.status == RunBatchStatus.IN_FLIGHT || it.status == RunBatchStatus.UNCERTAIN }) { AppText.Run.REVIEW_UNCERTAIN_BATCHES_BEFORE_RESUME_CONFIRMATION }
        require(pending.all { it.attempts < 100 }) { AppText.Run.BATCH_RETRY_LIMIT_EXCEEDED_INSPECT_FAILURE }
        persist(document.copy(entries = document.entries.map {
            if (it.batch.trid == trid && it.status != RunBatchStatus.ACKNOWLEDGED) it.copy(status = RunBatchStatus.READY, confirmed = true) else it
        }))
    }

    /**
     * Revokes permission for future unsent work. An already in-flight request is not recalled;
     * its acknowledgment/uncertainty and all ACK tombstones remain untouched.
     */
    suspend fun holdRun(trid: String, ownerId: String) = mutex.withLock {
        val document = load()
        ownedRun(document, trid, ownerId)
        persist(document.copy(entries = document.entries.map {
            if (it.batch.trid == trid && it.status in listOf(RunBatchStatus.STAGED, RunBatchStatus.READY, RunBatchStatus.FAILED))
                it.copy(status = RunBatchStatus.STAGED, confirmed = false) else it
        }))
    }

    /** Only after a record/transport reconciliation, not an assumption that a timeout meant rejection. */
    suspend fun reconcile(batchId: String, wasAccepted: Boolean, evidence: String) = mutex.withLock {
        require(evidence.isNotBlank()) { AppText.Run.RECONCILIATION_EVIDENCE_REQUIRED }
        val document = load()
        val current = document.entries.firstOrNull { it.batch.batchId == batchId } ?: throw RunQueueException(AppText.Run.BATCH_NOT_FOUND)
        require(current.status == RunBatchStatus.UNCERTAIN) { AppText.Run.ONLY_UNCERTAIN_BATCHES_REQUIRE_RECONCILIATION }
        val failure = RunUploadFailure(current.endpoint, current.attempts, AppText.Run.receiptEvidence((evidence)), false)
        replace(document, current.copy(
            status = if (wasAccepted) RunBatchStatus.ACKNOWLEDGED else RunBatchStatus.STAGED,
            confirmed = false,
            failures = current.failures + failure,
        ))
    }

    internal sealed interface Claim {
        data class Acquired(val entry: RunQueuedBatch) : Claim
        data class Blocked(val status: RunBatchStatus, val message: String) : Claim
        data class NotDue(val dueMillis: Long) : Claim
        data class Complete(val finished: Boolean) : Claim
    }

    internal suspend fun claimNext(trid: String, ownerId: String, nowMillis: Long): Claim = mutex.withLock {
        require(nowMillis >= 0)
        val document = load()
        val run = ownedRun(document, trid, ownerId)
        val next = run.firstOrNull { it.status != RunBatchStatus.ACKNOWLEDGED } ?: return@withLock Claim.Complete(run.last().batch.payload.finishType == "1")
        if (next.status != RunBatchStatus.READY || !next.confirmed) return@withLock Claim.Blocked(next.status, when (next.status) {
            RunBatchStatus.STAGED -> AppText.Run.UPLOAD_CONTENT_AWAITING_USER_CONFIRMATION
            RunBatchStatus.FAILED -> next.failures.lastOrNull()?.message ?: AppText.Run.UPLOAD_FAILED_REVIEW_BEFORE_RETRY
            RunBatchStatus.UNCERTAIN -> AppText.Run.SERVER_RECEIPT_UNCONFIRMED_PAUSED_TO_PREVENT_DUPLICATES
            RunBatchStatus.IN_FLIGHT -> AppText.Run.BATCH_IS_SENDING
            else -> AppText.Run.BATCH_NOT_READY
        })
        if (next.batch.payload.endTime > nowMillis) return@withLock Claim.NotDue(next.batch.payload.endTime)
        require(next.attempts < 100) { AppText.Run.BATCH_RETRY_LIMIT_EXCEEDED }
        val acquired = next.copy(status = RunBatchStatus.IN_FLIGHT, attempts = next.attempts + 1)
        replace(document, acquired) // The send starts only after this durable checkpoint succeeds.
        Claim.Acquired(acquired)
    }

    internal suspend fun accepted(batchId: String, acknowledgedBatchId: String) = mutex.withLock {
        require(batchId == acknowledgedBatchId) { AppText.Run.UPLOAD_ACKNOWLEDGEMENT_BATCH_MISMATCH }
        val document = load()
        val current = inFlight(document, batchId)
        replace(document, current.copy(status = RunBatchStatus.ACKNOWLEDGED, confirmed = false))
    }

    internal suspend fun failed(batchId: String, message: String, uncertain: Boolean, allowFailover: Boolean): Boolean = mutex.withLock {
        val document = load()
        val current = inFlight(document, batchId)
        val next = if (allowFailover && !uncertain) RunUploadEndpoint.entries.getOrNull(current.endpoint.ordinal + 1) else null
        replace(document, current.copy(
            status = if (uncertain) RunBatchStatus.UNCERTAIN else if (next != null) RunBatchStatus.READY else RunBatchStatus.FAILED,
            endpoint = next ?: current.endpoint,
            confirmed = next != null,
            failures = current.failures + RunUploadFailure(current.endpoint, current.attempts, message, uncertain),
        ))
        next != null
    }

    private fun inFlight(document: Document, batchId: String): RunQueuedBatch {
        val current = document.entries.firstOrNull { it.batch.batchId == batchId } ?: throw RunQueueException(AppText.Run.IN_FLIGHT_BATCH_NOT_FOUND)
        require(current.status == RunBatchStatus.IN_FLIGHT) { AppText.Run.BATCH_STATUS_CHANGED }
        return current
    }
    private fun ownedRun(document: Document, trid: String, ownerId: String): List<RunQueuedBatch> {
        val run = document.entries.filter { it.batch.trid == trid }.sortedBy { it.batch.index }
        require(run.isNotEmpty()) { AppText.Run.LOCAL_RUN_TASK_NOT_FOUND }
        require(ownerId.isNotBlank() && run.all { it.batch.payload.userId == ownerId }) { AppText.Run.CURRENT_USER_LOCAL_TASK_MISMATCH }
        return run
    }
    private fun replace(document: Document, replacement: RunQueuedBatch) {
        persist(document.copy(entries = document.entries.map { if (it.batch.batchId == replacement.batch.batchId) replacement else it }))
    }
    private fun load(): Document {
        val document = try {
            val stored = storage.get(storageKey)
            if (stored == null) Document() else {
                if (stored.length > 16_000_000) throw RunQueueException(AppText.Run.LOCAL_UPLOAD_QUEUE_READ_LIMIT_EXCEEDED_ORIGINAL_RETAINED)
                RunBatcher.json.decodeFromString<Document>(stored).also {
                    require(it.version == 1 && it.entries.size <= 20_000)
                    require(it.entries.map { entry -> entry.batch.batchId }.distinct().size == it.entries.size)
                    validateRuns(it.entries)
                }
            }
        } catch (error: Exception) {
            if (error is RunQueueException) throw error
            throw RunQueueException(AppText.Run.LOCAL_UPLOAD_QUEUE_READ_FAILED_ORIGINAL_RETAINED, error)
        }
        if (initialized) return document
        val recovered = document.copy(entries = document.entries.map {
            if (it.status == RunBatchStatus.IN_FLIGHT) it.copy(
                status = RunBatchStatus.UNCERTAIN,
                confirmed = false,
                failures = it.failures + RunUploadFailure(it.endpoint, it.attempts, AppText.Run.PREVIOUS_SEND_INTERRUPTED_SERVER_RECEIPT_UNCONFIRMED, true),
            ) else it
        })
        if (recovered != document) persist(recovered)
        initialized = true
        return recovered
    }
    private fun persist(document: Document) {
        try {
            val encoded = RunBatcher.json.encodeToString(document)
            if (encoded.length > 16_000_000) throw RunQueueException(AppText.Run.LOCAL_UPLOAD_QUEUE_STORAGE_LIMIT_EXCEEDED_ORIGINAL_RETAINED)
            storage.put(storageKey, encoded)
        } catch (error: Exception) {
            if (error is RunQueueException) throw error
            throw RunQueueException(AppText.Run.LOCAL_UPLOAD_QUEUE_SAVE_FAILED_KEEP_TRACK_AND_RETRY, error)
        }
    }

    private fun validateRuns(entries: List<RunQueuedBatch>) {
        for (run in entries.groupBy { it.batch.trid }.values) {
            val ordered = run.sortedBy { it.batch.index }
            val first = ordered.first().batch.payload
            var previousCoordinate: RunCoordinate? = null
            var cumulativeDistance = 0.0
            for ((index, entry) in ordered.withIndex()) {
                val payload = entry.batch.payload
                require(entry.batch.index == index) { AppText.Run.TASK_BATCH_INDICES_MUST_BE_CONTIGUOUS_FROM_ZERO }
                require(entry.attempts in 0..100) { AppText.Run.INVALID_BATCH_ATTEMPT_COUNT }
                require(payload.userId == first.userId && payload.scheduleId == first.scheduleId && payload.startTime == first.startTime) { AppText.Run.TASK_BATCH_USER_SCHEDULE_AND_START_MUST_MATCH }
                require(payload.finishType != "1" || index == ordered.lastIndex) { AppText.Run.COMPLETION_MARKER_RESTRICTED_TO_FINAL_BATCH }
                val coordinates = RunBatcher.validateBatch(entry.batch)
                for (coordinate in coordinates) {
                    val previous = previousCoordinate
                    if (previous == null) {
                        require(coordinate.locatetime == first.startTime && coordinate.dis == 0.0 && coordinate.count == 0.0) { AppText.Run.FIRST_POINT_TIME_AND_DISTANCE_MUST_MATCH_RUN_START }
                    } else {
                        require(coordinate.locatetime > previous.locatetime) { AppText.Run.CROSS_BATCH_TRACK_TIMESTAMPS_MUST_STRICTLY_INCREASE }
                        fun point(value: RunCoordinate): GeoPoint { val p = value.location.split(','); return GeoPoint(p[0].toDouble(), p[1].toDouble()) }
                        val distance = RunGeo.distanceMeters(point(previous), point(coordinate))
                        require(kotlin.math.abs(distance - coordinate.dis) <= 0.01) { AppText.Run.CROSS_BATCH_DISTANCE_COORDINATE_MISMATCH }
                        require(distance / ((coordinate.locatetime - previous.locatetime) / 1000.0) <= 5.000001) { AppText.Run.CROSS_BATCH_SPEED_LIMIT_EXCEEDED }
                    }
                    cumulativeDistance += coordinate.dis
                    previousCoordinate = coordinate
                }
                require(kotlin.math.abs(cumulativeDistance - payload.runLength.toDouble()) <= 0.01) { AppText.Run.CUMULATIVE_DISTANCE_BATCH_TRACK_MISMATCH }
            }
        }
    }
}
