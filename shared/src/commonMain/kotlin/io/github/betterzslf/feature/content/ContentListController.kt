package io.github.betterzslf.feature.content

import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Explicit refresh/more actions; there is no timer, infinite prefetch, or write on list load. */
class ContentListController(
    private val source: ContentSource,
    val request: ContentFeedRequest,
    val pageSize: Int = 20,
) {
    init { require(pageSize in 1..100) }
    private val mutable = MutableStateFlow(ContentListState())
    val state: StateFlow<ContentListState> = mutable.asStateFlow()
    private var generation = 0L
    private var allAnnouncements: List<ContentItem>? = null
    private var failedRefresh = true

    suspend fun retry() { if (failedRefresh) refresh() else loadMore() }

    suspend fun refresh() {
        val ticket = ++generation
        mutable.value = mutable.value.copy(loading = true, loadingMore = false, error = null)
        load(ticket, 0, refresh = true)
    }

    suspend fun loadMore() {
        val previous = mutable.value
        if (previous.loading || previous.loadingMore || !previous.hasMore) return
        val cached = allAnnouncements
        if (request.kind == ContentFeedKind.ANNOUNCEMENTS && cached != null) {
            val next = (previous.rawOffset + pageSize).coerceAtMost(cached.size)
            mutable.value = previous.copy(items = cached.take(next), rawOffset = next, hasMore = next < cached.size)
            return
        }
        mutable.value = previous.copy(loadingMore = true, error = null)
        load(generation, previous.rawOffset, refresh = false)
    }

    private suspend fun load(ticket: Long, offset: Int, refresh: Boolean) {
        val response = when (request.kind) {
            ContentFeedKind.ARTICLES -> source.articles(request.id, offset, pageSize)
            ContentFeedKind.HISTORY -> source.history(offset, pageSize)
            ContentFeedKind.SYSTEM_MESSAGES -> source.notices(offset, pageSize)
            ContentFeedKind.ANNOUNCEMENTS -> source.announcements()
        }
        if (ticket != generation) return
        val decoded = when (response) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> parseContentList(response.data, when (request.kind) {
                ContentFeedKind.ARTICLES -> "cmsArticleList"
                ContentFeedKind.HISTORY -> "list"
                ContentFeedKind.SYSTEM_MESSAGES -> "noticeList"
                ContentFeedKind.ANNOUNCEMENTS -> "announcementList"
            }, request.kind == ContentFeedKind.SYSTEM_MESSAGES || request.kind == ContentFeedKind.ANNOUNCEMENTS)
        }
        when (decoded) {
            is ApiResult.Failure -> {
                failedRefresh = refresh
                mutable.value = mutable.value.copy(loading = false, loadingMore = false, error = decoded.error)
            }
            is ApiResult.Success -> {
                val rows = decoded.data
                if (request.kind == ContentFeedKind.ANNOUNCEMENTS) {
                    val unique = rows.distinctBy(::identity)
                    allAnnouncements = unique
                    mutable.value = ContentListState(unique.take(pageSize), hasMore = unique.size > pageSize,
                        rawOffset = minOf(pageSize, unique.size), total = unique.size)
                } else {
                    val previous = if (refresh) emptyList() else mutable.value.items
                    val combined = (previous + rows).distinctBy(::identity)
                    mutable.value = ContentListState(combined, hasMore = rows.size >= pageSize && combined.size > previous.size,
                        rawOffset = offset + rows.size)
                }
            }
        }
    }

    private fun identity(item: ContentItem): String = item.id.ifBlank { "${item.title}\u0000${item.created}\u0000${item.description}\u0000${item.html}" }
}
