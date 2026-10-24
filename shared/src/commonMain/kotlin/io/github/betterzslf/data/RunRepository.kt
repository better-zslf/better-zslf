package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.ApiSessionBinding
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Transport only: tracking, offline batching, and user-triggered retries live in feature/run. */
class RunRepository private constructor(private val api: ApiClient, private val binding: ApiSessionBinding?) {
    constructor(api: ApiClient) : this(api, null)

    /** Stable captured owner for an explicitly bound operation; null for the legacy live repository. */
    val boundOwnerId: String? get() = binding?.let(api::boundOwnerId)

    /**
     * Capture owner and token from one session value. Bound instances never silently retarget a
     * different login: rebinding a stale bound instance returns null, not a fresh capability.
     */
    fun boundToCurrentSession(): RunRepository? {
        if (binding != null) return if (api.isBindingCurrent(binding)) this else null
        return api.captureSessionBinding()?.let { RunRepository(api, it) }
    }

    suspend fun tasks(userId: String, offset: Int = 0, limit: Int = 20, isFinish: String = "0"): ApiResult<JsonElement> {
        if (offset < 0 || limit <= 0) return ApiResult.Failure(ApiError.Validation(listOf("offset", "limit"), "分页参数应为非负偏移和正数条数"))
        // Always send all four required query keys; callers select the desired status.
        return execute(ApiEndpoint.RUN_TASKS, buildJsonObject {
            put("userId", userId)
            put("offset", offset)
            put("limit", limit)
            put("isFinish", isFinish)
        })
    }

    /** Task identity and schedule identity are separate; trid identifies a client run attempt. */
    suspend fun detail(errandId: String, userId: String, errandScheduleId: String) = execute(ApiEndpoint.RUN_DETAIL,
        queryParameters("errandId" to errandId, "userId" to userId, "errandScheduleId" to errandScheduleId))

    /** Kept source-compatible only; the former trid-only detail query was not the shipped protocol. */
    @Deprecated("任务详情请提供errandId、userId、errandScheduleId；请从任务列表重新进入")
    @Suppress("UNUSED_PARAMETER")
    suspend fun detail(trid: String): ApiResult<JsonElement> = ApiResult.Failure(ApiError.Validation(
        listOf("errandId", "userId", "errandScheduleId"), "任务详情缺少任务与排期信息，请从任务列表重新进入"))

    /** Existing successful user-wide query remains supported without an invented filter. */
    suspend fun records(userId: String) = execute(ApiEndpoint.RUN_RECORDS, queryParameters("userId" to userId))

    /** Observed task-detail record call: schedule filter and true offset/limit paging. */
    suspend fun records(userId: String, errandScheduleId: String, offset: Int = 0, limit: Int = 10): ApiResult<JsonElement> {
        if (errandScheduleId.isBlank()) return ApiResult.Failure(ApiError.Validation(listOf("errandScheduleId")))
        if (offset < 0 || limit <= 0) return ApiResult.Failure(ApiError.Validation(listOf("offset", "limit")))
        return execute(ApiEndpoint.RUN_RECORDS, buildJsonObject {
            put("errandScheduleId", errandScheduleId)
            put("userId", userId)
            put("offset", offset)
            put("limit", limit)
        })
    }
    suspend fun track(trid: String) = execute(ApiEndpoint.RUN_TRACK, queryParameters("trid" to trid))
    suspend fun record(trid: String) = execute(ApiEndpoint.RUN_RECORD, queryParameters("trid" to trid))
    suspend fun punch(parameters: JsonObject) = execute(ApiEndpoint.RUN_PUNCH, parameters)

    /** Body is passed verbatim: coordinate remains the upstream's nested JSON string. */
    suspend fun addTrack(body: JsonObject) = execute(ApiEndpoint.RUN_ADD_TRACK, body = body)
    suspend fun syncSportData(body: JsonObject) = execute(ApiEndpoint.RUN_SYNC, body = body)
    suspend fun reportLocation(body: JsonObject) = execute(ApiEndpoint.RUN_REPORT, body = body)
    suspend fun quickSave(body: JsonObject) = execute(ApiEndpoint.RUN_QUICK_SAVE, body = body)

    private suspend fun execute(
        endpoint: ApiEndpoint,
        query: JsonObject = JsonObject(emptyMap()),
        body: JsonObject? = null,
    ): ApiResult<JsonElement> {
        if (binding != null) {
            val owner = boundOwnerId
            val userId = (body ?: query)["userId"]
            // Bound JSON uploads always name their owner. Query methods without a userId
            // parameter (track/record/punch) still authenticate with the exact captured binding.
            if ((body != null || userId != null) &&
                (owner == null || userId !is JsonPrimitive || !userId.isString || userId.content != owner)
            ) return ApiResult.Failure(ApiError.Validation(listOf("userId"), "请求归属与本次操作的登录账号不一致"))
        }
        return api.execute(endpoint, query, body, expectedSession = binding)
    }

    override fun toString(): String = "RunRepository(bound=${binding != null})"
}
