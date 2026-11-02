package io.github.betterzslf.feature.content

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement

data class ArticleState(
    val detail: ContentItem? = null,
    val hits: Int? = null,
    val count: Int? = null,
    val liked: Boolean? = null,
    val loading: Boolean = false,
    val toggling: Boolean = false,
    val error: ApiError? = null,
    val likeError: ApiError? = null,
    val hitError: ApiError? = null,
)

/** Document-only reader; the article list, likes and hit counters have been retired. */
class ArticleController(private val source: ContentSource, val id: String) {
    private val mutable = MutableStateFlow(ArticleState())
    val state: StateFlow<ArticleState> = mutable.asStateFlow()
    private var generation = 0L

    suspend fun load() {
        val ticket = ++generation
        mutable.value = mutable.value.copy(loading = true, error = null)
        val response = source.document(id)
        if (ticket != generation) return
        if (response is ApiResult.Failure) {
            mutable.value = mutable.value.copy(loading = false, error = response.error)
            return
        }
        val data = (response as ApiResult.Success<JsonElement>).data
        val detail = (data as? JsonObject)?.get("articleDetails") as? JsonObject
        if (detail == null) {
            mutable.value = mutable.value.copy(loading = false, error = ApiError.Decode(AppText.Content.INVALID_ARTICLE_DETAIL_STRUCTURE))
            return
        }
        mutable.value = ArticleState(detail = ContentItem.article(detail), loading = false)
    }
}
