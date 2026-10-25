package io.github.betterzslf.feature.content

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.data.NoticeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NoticeActionState(
    val read: Set<String> = emptySet(),
    val pending: Set<String> = emptySet(),
    val errors: Map<String, ApiError> = emptyMap(),
    val unread: Int? = null,
    val unreadError: ApiError? = null,
)

/** Reads happen only after open(); constructing or fetching a list never marks rows. */
class NoticeReadController(private val source: ContentSource, private val owner: String) {
    private val mutable = MutableStateFlow(NoticeActionState())
    val state: StateFlow<NoticeActionState> = mutable.asStateFlow()
    fun key(item: ContentItem, kind: NoticeReadKind): String = "${kind.name}:${item.id}"
    suspend fun refreshUnread() {
        if (owner.isBlank()) return
        when (val response = source.unread()) {
            is ApiResult.Failure -> mutable.value = mutable.value.copy(unreadError = response.error)
            is ApiResult.Success -> mutable.value = if (response.data.number("unreadNumber") == null || response.data.number("noticeMutualCount") == null)
                mutable.value.copy(unreadError = ApiError.Decode(AppText.Content.INVALID_UNREAD_COUNT_STRUCTURE))
                else mutable.value.copy(unread = NoticeRepository.unreadBadgeCount(response.data), unreadError = null)
        }
    }
    suspend fun open(item: ContentItem, kind: NoticeReadKind) {
        val key = key(item, kind)
        if (owner.isBlank() || item.id.isBlank() || item.isRead == true || key in mutable.value.read || key in mutable.value.pending) return
        mutable.value = mutable.value.copy(pending = mutable.value.pending + key, errors = mutable.value.errors - key)
        try {
            when (val response = source.markRead(item.id, kind)) {
                is ApiResult.Failure -> mutable.value = mutable.value.copy(errors = mutable.value.errors + (key to response.error))
                is ApiResult.Success -> {
                    mutable.value = mutable.value.copy(read = mutable.value.read + key)
                    refreshUnread()
                }
            }
        } finally { mutable.value = mutable.value.copy(pending = mutable.value.pending - key) }
    }
}

data class HistoryVisibilityState(val hidden: Boolean = false, val error: ApiError? = null)

/** Local visibility only. This never deletes server history or calls an undocumented endpoint. */
class HistoryVisibilityStore(private val storage: KeyValueStore, private val owner: String) {
    private val key = "better-zslf.content.history-hidden.v1.$owner"
    private val mutable = MutableStateFlow(try { HistoryVisibilityState(owner.isNotBlank() && storage.get(key) == "1") }
        catch (_: Exception) { HistoryVisibilityState(error = ApiError.Storage()) })
    val state: StateFlow<HistoryVisibilityState> = mutable.asStateFlow()
    fun setHidden(hidden: Boolean) {
        if (owner.isBlank()) { mutable.value = mutable.value.copy(error = ApiError.Authentication()); return }
        try {
            if (hidden) storage.put(key, "1") else storage.remove(key)
            mutable.value = HistoryVisibilityState(hidden)
        } catch (_: Exception) { mutable.value = mutable.value.copy(error = ApiError.Storage()) }
    }
}
