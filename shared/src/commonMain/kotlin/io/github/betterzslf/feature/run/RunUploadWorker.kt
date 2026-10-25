package io.github.betterzslf.feature.run

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
            return RunSendResult.NotAccepted("本地轨迹载荷预检未通过")
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
                    if (claim.finished) "已确认全部批次接收，请查询成绩确认最终结果" else "现有块已确认接收，等待后续定位或结束块",
                    failures,
                )
                is RunUploadQueue.Claim.NotDue -> return RunWorkerResult(RunWorkerStop.NOT_DUE, acknowledged, "下一批轨迹的计划时间尚未到达", failures, claim.dueMillis)
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
                        withContext(NonCancellable) { queue.failed(batchId, "发送被中断，接收结果待核对", uncertain = true, allowFailover = false) }
                        throw cancelled
                    } catch (_: Exception) {
                        RunSendResult.Unknown("发送发生异常，服务端接收结果待核对")
                    }
                    when (response) {
                        is RunSendResult.Accepted -> {
                            if (response.batchId != batchId) {
                                val message = "应答批次与发送批次不匹配，接收结果待核对"
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
                if (finished) "全部批次已确认接收，请查询成绩" else "已接收当前批次，等待结束块", failures)
        }
        return RunWorkerResult(RunWorkerStop.LIMIT, acknowledged, "本轮上传已达批次上限，可继续处理剩余块", failures)
    }
}
