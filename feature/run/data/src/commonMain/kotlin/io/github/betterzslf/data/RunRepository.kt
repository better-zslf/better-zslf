package io.github.betterzslf.data

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.ApiSessionBinding
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
            ) return ApiResult.Failure(ApiError.Validation(listOf("userId"), AppText.Auth.REQUEST_ACCOUNT_OWNERSHIP_MISMATCH))
        }
        return api.execute(endpoint, query, body, expectedSession = binding)
    }

    override fun toString(): String = "RunRepository(bound=${binding != null})"
}
