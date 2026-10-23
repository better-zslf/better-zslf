package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.result.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

class NoticeRepository(private val api: ApiClient) {
    /** Refresh on page visibility; the repository intentionally has no polling timer. */
    suspend fun unread() = api.execute(ApiEndpoint.UNREAD)
    suspend fun announcements() = api.execute(ApiEndpoint.ANNOUNCEMENTS)
    /** Shipped message page uses typeId=0 plus offset/limit, not the old docs' type alias. */
    suspend fun list(type: String, offset: Int = 0, limit: Int = 20): ApiResult<JsonElement> {
        if (offset < 0 || limit <= 0) return ApiResult.Failure(io.github.betterzslf.core.result.ApiError.Validation(listOf("offset", "limit")))
        return api.execute(ApiEndpoint.NOTICES, queryParameters("typeId" to type, "offset" to offset.toString(), "limit" to limit.toString()))
    }
    suspend fun markRead(id: String) = api.execute(ApiEndpoint.NOTICE_READ, queryParameters("noticeId" to id))
    suspend fun markMutualRead(id: String) = api.execute(ApiEndpoint.NOTICE_MUTUAL_READ, queryParameters("noticeId" to id))
    suspend fun unreadCount(): ApiResult<Int> = unread().map { unreadBadgeCount(it) }

    companion object {
        fun unreadBadgeCount(data: JsonElement): Int {
            val obj = data as? JsonObject ?: return 0
            val unread = ((obj["unreadNumber"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
            val mutual = ((obj["noticeMutualCount"] as? JsonPrimitive)?.intOrNull ?: 0).coerceAtLeast(0)
            return (unread.toLong() + mutual).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
    }
}
