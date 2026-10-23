package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Transport only: tracking, offline batching, and user-triggered retries live in feature/run. */
class RunRepository(private val api: ApiClient) {
    suspend fun tasks(userId: String, offset: Int = 0, limit: Int = 20, isFinish: String = "0"): ApiResult<JsonElement> {
        if (offset < 0 || limit <= 0) return ApiResult.Failure(ApiError.Validation(listOf("offset", "limit"), "分页参数应为非负偏移和正数条数"))
        // Always send all four required query keys; callers select the desired status.
        return api.execute(ApiEndpoint.RUN_TASKS, buildJsonObject {
            put("userId", userId)
            put("offset", offset)
            put("limit", limit)
            put("isFinish", isFinish)
        })
    }

    suspend fun detail(trid: String) = api.execute(ApiEndpoint.RUN_DETAIL, queryParameters("trid" to trid))
    suspend fun records(userId: String) = api.execute(ApiEndpoint.RUN_RECORDS, queryParameters("userId" to userId))
    suspend fun track(trid: String) = api.execute(ApiEndpoint.RUN_TRACK, queryParameters("trid" to trid))
    suspend fun record(trid: String) = api.execute(ApiEndpoint.RUN_RECORD, queryParameters("trid" to trid))
    suspend fun punch(parameters: JsonObject) = api.execute(ApiEndpoint.RUN_PUNCH, parameters)

    /** Body is passed verbatim: coordinate remains the upstream's nested JSON string. */
    suspend fun addTrack(body: JsonObject) = api.execute(ApiEndpoint.RUN_ADD_TRACK, body = body)
    suspend fun syncSportData(body: JsonObject) = api.execute(ApiEndpoint.RUN_SYNC, body = body)
    suspend fun reportLocation(body: JsonObject) = api.execute(ApiEndpoint.RUN_REPORT, body = body)
    suspend fun quickSave(body: JsonObject) = api.execute(ApiEndpoint.RUN_QUICK_SAVE, body = body)
}
