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

class ArticleController(private val source: ContentSource, private val owner: String, val id: String, val document: Boolean = false) {
    private val mutable = MutableStateFlow(ArticleState())
    val state: StateFlow<ArticleState> = mutable.asStateFlow()
    private var generation = 0L
    private var likesGeneration = 0L

    suspend fun load() {
        if (mutable.value.toggling) return
        val ticket = ++generation
        mutable.value = mutable.value.copy(loading = true, error = null)
        val response = if (document) source.document(id) else source.article(id)
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
        mutable.value = ArticleState(detail = ContentItem.article(detail), hits = detail.number("hits"), loading = false)
        if (document || owner.isBlank()) return
        refreshLikes(ticket)
        if (ticket != generation) return
        when (val counted = source.increaseHitsOnce(owner, id)) {
            is ApiResult.Failure -> if (ticket == generation) mutable.value = mutable.value.copy(hitError = counted.error)
            is ApiResult.Success -> if (ticket == generation && counted.data) {
                mutable.value = mutable.value.copy(hits = mutable.value.hits?.let { (it.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() })
            }
        }
    }

    suspend fun refreshLikes() { if (!mutable.value.toggling) refreshLikes(generation) }
    private suspend fun refreshLikes(ticket: Long) {
        if (document || owner.isBlank() || ticket != generation) return
        val likeTicket = ++likesGeneration
        val count = source.likeCount(id)
        val liked = source.myLike(id)
        if (ticket != generation || likeTicket != likesGeneration) return
        var error: ApiError? = null
        val total = when (count) {
            is ApiResult.Failure -> { error = count.error; null }
            is ApiResult.Success -> count.data.number("count")?.takeIf { it >= 0 }
                ?: run { error = ApiError.Decode(AppText.Content.INVALID_LIKE_COUNT_STRUCTURE); null }
        }
        val mine = when (liked) {
            is ApiResult.Success -> true // Shipped page uses code=200 as its liked signal.
            is ApiResult.Failure -> if (liked.error is ApiError.Business && liked.error.code == 400 && liked.error.message.startsWith("此用户没有点赞")) false
                else { error = liked.error; null }
        }
        mutable.value = mutable.value.copy(count = total, liked = mine, likeError = error)
    }

    /** Never retries a possibly accepted toggle; the user can refresh its state instead. */
    suspend fun toggleLike() {
        val before = mutable.value
        if (document || owner.isBlank() || before.loading || before.toggling || before.detail == null) return
        val ticket = generation
        ++likesGeneration // An older in-flight read must not overwrite this toggle's result.
        mutable.value = before.copy(toggling = true, likeError = null)
        try {
            when (val response = source.toggleLike(id)) {
                is ApiResult.Failure -> if (ticket == generation) mutable.value = mutable.value.copy(likeError = response.error)
                is ApiResult.Success -> if (ticket == generation) {
                    val result = response.data.flag("result")
                    val count = response.data.number("count")?.takeIf { it >= 0 }
                    mutable.value = if (result == null || count == null) mutable.value.copy(likeError = ApiError.Decode(AppText.Content.LIKE_RESULT_REQUIRES_REFRESH_CONFIRMATION))
                        else mutable.value.copy(liked = result, count = count)
                }
            }
        } finally {
            if (ticket == generation) mutable.value = mutable.value.copy(toggling = false)
        }
    }
}
