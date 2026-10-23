package io.github.betterzslf.feature.run

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.RunRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface RunSendResult {
    /** This is an explicit acknowledgment of this request, not a guessed success after a timeout. */
    data class Accepted(val batchId: String) : RunSendResult
    /** Use mayFailover only when the endpoint definitively did not accept/commit the request. */
    data class NotAccepted(val message: String, val mayFailover: Boolean = false) : RunSendResult
    data class Unknown(val message: String) : RunSendResult
}

fun interface RunUploadTransport {
    suspend fun send(endpoint: RunUploadEndpoint, batch: RunUploadBatch): RunSendResult
}

/**
 * Keeps the supplied repository (including its captured owner/token binding); never rebinds globally.
 * The repository itself does not retry; uncertain outcomes never fan out to another write endpoint.
 */
class RunRepositoryTransport(private val repository: RunRepository) : RunUploadTransport {
    override suspend fun send(endpoint: RunUploadEndpoint, batch: RunUploadBatch): RunSendResult {
        val body = try {
            batch.toGcj02WireBody()
        } catch (_: IllegalArgumentException) {
            return RunSendResult.NotAccepted(AppText.Run.LOCAL_TRACK_PAYLOAD_PREFLIGHT_FAILED)
        }
        val result = when (endpoint) {
            RunUploadEndpoint.PRIMARY -> repository.addTrack(body)
            RunUploadEndpoint.SYNC_SPORT_DATA -> repository.syncSportData(body)
            RunUploadEndpoint.REPORT_LOCATION -> repository.reportLocation(body)
        }
        return when (result) {
            // code=200 maps to this local batch marker, not a remote batch receipt or run score.
            is ApiResult.Success -> RunSendResult.Accepted(batch.batchId)
            is ApiResult.Failure -> when (val error = result.error) {
                is ApiError.Authentication, is ApiError.Validation, is ApiError.Business -> RunSendResult.NotAccepted(error.message)
                is ApiError.Http -> if (error.status == 404 || error.status == 405) RunSendResult.NotAccepted(error.message, mayFailover = true) else RunSendResult.Unknown(error.message)
                is ApiError.Network, is ApiError.Timeout, is ApiError.Decode, is ApiError.Storage -> RunSendResult.Unknown(error.message)
            }
        }
    }
}

enum class RunWorkerStop { COMPLETE, DRAINED, NEEDS_CONFIRMATION, IN_PROGRESS, FAILED, UNCERTAIN, NOT_DUE, LIMIT }
data class RunWorkerResult(
    val stop: RunWorkerStop,
    val acknowledgedBatches: Int,
    val message: String,
    val failures: List<RunUploadFailure> = emptyList(),
    val nextDueMillis: Long? = null,
)

/** Explicitly invoked worker; neither constructing it nor enqueuing a track transmits anything. */
class RunUploadWorker(private val queue: RunUploadQueue, private val transport: RunUploadTransport) {
    suspend fun run(trid: String, ownerId: String, nowMillis: () -> Long, maximumAttempts: Int = 100): RunWorkerResult {
        require(maximumAttempts in 1..1000)
        var acknowledged = 0
        val failures = mutableListOf<RunUploadFailure>()
        repeat(maximumAttempts) {
            when (val claim = queue.claimNext(trid, ownerId, nowMillis())) {
                is RunUploadQueue.Claim.Complete -> return RunWorkerResult(
                    if (claim.finished) RunWorkerStop.COMPLETE else RunWorkerStop.DRAINED,
                    acknowledged,
                    if (claim.finished) AppText.Run.ALL_BATCH_RECEIPTS_CONFIRMED_QUERY_SCORE_FOR_FINAL_RESULT else AppText.Run.EXISTING_BATCHES_RECEIVED_WAITING_FOR_LOCATION_OR_FINAL_BATCH,
                    failures,
                )
                is RunUploadQueue.Claim.NotDue -> return RunWorkerResult(RunWorkerStop.NOT_DUE, acknowledged, AppText.Run.NEXT_BATCH_PLANNED_TIME_NOT_REACHED, failures, claim.dueMillis)
                is RunUploadQueue.Claim.Blocked -> return RunWorkerResult(when (claim.status) {
                    RunBatchStatus.UNCERTAIN -> RunWorkerStop.UNCERTAIN
                    RunBatchStatus.IN_FLIGHT -> RunWorkerStop.IN_PROGRESS
                    RunBatchStatus.FAILED -> RunWorkerStop.FAILED
                    else -> RunWorkerStop.NEEDS_CONFIRMATION
                }, acknowledged, claim.message, failures)
                is RunUploadQueue.Claim.Acquired -> {
                    val entry = claim.entry
                    val batchId = entry.batch.batchId
                    val response = try {
                        transport.send(entry.endpoint, entry.batch)
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable) { queue.failed(batchId, AppText.Run.SEND_CANCELLED_RECEIPT_UNCONFIRMED, uncertain = true, allowFailover = false) }
                        throw cancelled
                    } catch (_: Exception) {
                        RunSendResult.Unknown(AppText.Run.SEND_EXCEPTION_SERVER_RECEIPT_UNCONFIRMED)
                    }
                    when (response) {
                        is RunSendResult.Accepted -> {
                            if (response.batchId != batchId) {
                                val message = AppText.Run.RESPONSE_BATCH_MISMATCH_RECEIPT_UNCONFIRMED
                                queue.failed(batchId, message, uncertain = true, allowFailover = false)
                                failures += RunUploadFailure(entry.endpoint, entry.attempts, message, true)
                                return RunWorkerResult(RunWorkerStop.UNCERTAIN, acknowledged, message, failures)
                            }
                            // Persistence errors propagate; no further batch is sent before this checkpoint is durable.
                            queue.accepted(batchId, response.batchId)
                            acknowledged++
                        }
                        is RunSendResult.NotAccepted -> {
                            val failover = queue.failed(batchId, response.message, uncertain = false, allowFailover = response.mayFailover)
                            failures += RunUploadFailure(entry.endpoint, entry.attempts, response.message, false)
                            if (!failover) return RunWorkerResult(RunWorkerStop.FAILED, acknowledged, response.message, failures)
                        }
                        is RunSendResult.Unknown -> {
                            queue.failed(batchId, response.message, uncertain = true, allowFailover = false)
                            failures += RunUploadFailure(entry.endpoint, entry.attempts, response.message, true)
                            return RunWorkerResult(RunWorkerStop.UNCERTAIN, acknowledged, response.message, failures)
                        }
                    }
                }
            }
        }
        // A bounded UI invocation may have just acknowledged the final batch: report that accurately.
        val run = queue.snapshot().filter { it.batch.trid == trid }
        if (run.all { it.status == RunBatchStatus.ACKNOWLEDGED }) {
            val finished = run.lastOrNull()?.batch?.payload?.finishType == "1"
            return RunWorkerResult(if (finished) RunWorkerStop.COMPLETE else RunWorkerStop.DRAINED, acknowledged,
                if (finished) AppText.Run.ALL_BATCHES_RECEIVED_QUERY_SCORE else AppText.Run.CURRENT_BATCH_RECEIVED_WAITING_FOR_FINAL_BATCH, failures)
        }
        return RunWorkerResult(RunWorkerStop.LIMIT, acknowledged, AppText.Run.UPLOAD_ROUND_BATCH_LIMIT_REACHED_REMAINING_CAN_CONTINUE, failures)
    }
}
