package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.feature.run.GeoPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonElement

/** One fixed read capability. Neither construction nor any decode starts tracking or a write. */
class ApiRunReadSource private constructor(
    private val transport: RunReadTransport,
    override val boundOwnerId: String,
) : RunReadSource {
    private val identities = RunTaskIdentityRegistry()
    override fun isCurrentSession(): Boolean = transport.isCurrentSession()

    override suspend fun tasks(ownerId: String, offset: Int, limit: Int, isFinish: String): RunReadOutcome<RunRows<RunTaskItem>> =
        read({ transport.tasks(ownerId, offset, limit, isFinish) }) { raw ->
            val rows = RunDataDecoder.tasks(raw)
            val projected = identities.project(rows.items, ::isCurrentSession) ?: return@read retired()
            RunReadOutcome.Success(RunRows(projected, rows.recognized, rows.warnings, rows.rawSize))
        }

    override suspend fun detail(errandId: String, ownerId: String, errandScheduleId: String): RunReadOutcome<RunReadValue<RunTaskDetail>> =
        read({ transport.detail(errandId, ownerId, errandScheduleId) }) { raw ->
            val decoded = RunDataDecoder.detailValue(raw)
            val value = decoded.value
            val result = if (value == null) null else {
                val task = identities.project(listOf(value.task), ::isCurrentSession)?.single() ?: return@read retired()
                value.toPublic(task)
            }
            RunReadOutcome.Success(RunReadValue(result, decoded.recognized, decoded.warnings))
        }

    override suspend fun records(ownerId: String): RunReadOutcome<RunRows<RunRecordItem>> =
        recordRows { transport.records(ownerId) }

    override suspend fun records(ownerId: String, errandScheduleId: String, offset: Int, limit: Int): RunReadOutcome<RunRows<RunRecordItem>> =
        recordRows { transport.records(ownerId, errandScheduleId, offset, limit) }

    private suspend fun recordRows(load: suspend () -> ApiResult<JsonElement>): RunReadOutcome<RunRows<RunRecordItem>> = read(load) { raw ->
        val rows = RunDataDecoder.records(raw)
        val projected = identities.projectRecords(rows.items, ::isCurrentSession) ?: return@read retired()
        RunReadOutcome.Success(RunRows(projected, rows.recognized, rows.warnings, rows.rawSize))
    }

    override suspend fun record(trid: String): RunReadOutcome<RunReadValue<RunRecordItem>> = read({ transport.record(trid) }) { raw ->
        val decoded = RunDataDecoder.recordValue(raw)
        val value = decoded.value
        val result = if (value == null) null else {
            identities.projectRecords(listOf(value), ::isCurrentSession)?.single() ?: return@read retired()
        }
        RunReadOutcome.Success(RunReadValue(result, decoded.recognized, decoded.warnings))
    }

    override suspend fun track(trid: String): RunReadOutcome<RunRows<GeoPoint>> = read({ transport.track(trid) }) { raw ->
        RunReadOutcome.Success(RunDataDecoder.trackRows(raw))
    }

    private suspend fun <T> read(
        load: suspend () -> ApiResult<JsonElement>,
        decode: suspend (JsonElement) -> RunReadOutcome<T>,
    ): RunReadOutcome<T> {
        currentCoroutineContext().ensureActive()
        if (!isCurrentSession()) return retired()
        val response = try { load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return if (isCurrentSession()) RunReadOutcome.Failure(RunReadIssue.NETWORK) else retired()
        }
        currentCoroutineContext().ensureActive()
        if (!isCurrentSession()) return retired()
        val projected = when (response) {
            is ApiResult.Failure -> RunReadOutcome.Failure(response.error.readIssue())
            is ApiResult.Success -> try { decode(response.data) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { RunReadOutcome.Failure(RunReadIssue.MALFORMED) }
        }
        currentCoroutineContext().ensureActive()
        return if (isCurrentSession()) projected else retired()
    }

    override fun toString(): String = "ApiRunReadSource(redacted)"

    companion object {
        /** Captures exactly once. A later epoch needs an explicitly new source, never an automatic retry. */
        fun capture(api: ApiClient): RunReadSource? {
            val binding = api.captureSessionBinding() ?: return null
            val owner = api.boundOwnerId(binding)?.takeIf { it.isNotBlank() } ?: return null
            if (!api.isBindingCurrent(binding)) return null
            return ApiRunReadSource(RunReadTransport(api, binding), owner)
        }
    }
}

private fun retired(): RunReadOutcome.Failure = RunReadOutcome.Failure(RunReadIssue.AUTHENTICATION)
private fun ApiError.readIssue(): RunReadIssue = when (this) {
    is ApiError.Authentication -> RunReadIssue.AUTHENTICATION
    is ApiError.Business -> RunReadIssue.BUSINESS
    is ApiError.Validation -> RunReadIssue.VALIDATION
    is ApiError.Timeout -> RunReadIssue.TIMEOUT
    is ApiError.Network -> RunReadIssue.NETWORK
    is ApiError.Decode -> RunReadIssue.MALFORMED
    is ApiError.Http -> RunReadIssue.HTTP
    is ApiError.Storage -> RunReadIssue.STORAGE
}
