package io.github.betterzslf.data

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ContentRepository(private val api: ApiClient) {
    private val hitMutex = Mutex()
    private val hitAttempts = mutableMapOf<Pair<String, String>, ApiResult<Boolean>>()

    /** At most one attempt per owner/article in this application graph, including ambiguous failures. */
    suspend fun increaseHitsOnce(owner: String, id: String): ApiResult<Boolean> = hitMutex.withLock {
        if (owner.isBlank() || id.isBlank()) return@withLock ApiResult.Failure(ApiError.Validation(listOf("owner", "articleId")))
        val key = owner to id
        when (val previous = hitAttempts[key]) {
            is ApiResult.Success -> return@withLock ApiResult.Success(false)
            is ApiResult.Failure -> return@withLock previous
            null -> Unit
        }
        // Reserve before suspension: cancellation must not automatically replay an uncertain increment.
        hitAttempts[key] = ApiResult.Failure(ApiError.Network(AppText.Content.READ_COUNT_UNCONFIRMED_NO_REPEAT_THIS_PROCESS))
        val result = when (val response = increaseHits(id)) {
            is ApiResult.Success -> ApiResult.Success(true, response.message)
            is ApiResult.Failure -> response
        }
        hitAttempts[key] = result
        result
    }

    /** Known names: 学校动态 (single cmsColumn) and 新生攻略 (cmsColumnList). */
    suspend fun columns(name: String) = api.execute(ApiEndpoint.CMS_COLUMNS, queryParameters("name" to name))

    suspend fun articles(columnId: String, offset: Int = 0, limit: Int = 20): ApiResult<JsonElement> {
        if (offset < 0 || limit <= 0) return ApiResult.Failure(ApiError.Validation(listOf("offset", "limit"), AppText.Common.PAGINATION_REQUIRES_NONNEGATIVE_OFFSET_AND_POSITIVE_LIMIT))
        return api.execute(ApiEndpoint.CMS_ARTICLES, buildJsonObject {
            // This misspelling is part of the server contract.
            put("cmsCloumnId", columnId)
            put("offset", offset)
            put("limit", limit)
        })
    }

    suspend fun article(id: String) = articleRequest(ApiEndpoint.ARTICLE_DETAIL, id)
    suspend fun document(name: String) = api.execute(ApiEndpoint.DOCUMENT, queryParameters("articleName" to name))
    suspend fun increaseHits(id: String) = articleRequest(ApiEndpoint.ARTICLE_HITS, id)
    suspend fun likeCount(id: String) = articleRequest(ApiEndpoint.ARTICLE_LIKE_COUNT, id)
    suspend fun myLike(id: String) = articleRequest(ApiEndpoint.ARTICLE_MY_LIKE, id)
    suspend fun toggleLike(id: String) = articleRequest(ApiEndpoint.ARTICLE_TOGGLE_LIKE, id)

    /** Opaque history parameters preserve the upstream API; no invented clear-history flag. */
    suspend fun history(parameters: JsonObject = JsonObject(emptyMap())) = api.execute(ApiEndpoint.ARTICLE_HISTORY, parameters)

    private suspend fun articleRequest(endpoint: ApiEndpoint, id: String) =
        api.execute(endpoint, queryParameters("articleId" to id))
}
