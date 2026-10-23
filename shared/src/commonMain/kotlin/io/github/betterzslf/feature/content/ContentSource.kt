package io.github.betterzslf.feature.content

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.AppRepositories
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Injectable boundary: controllers/tests never construct clients or contact an implicit host. */
interface ContentSource {
    suspend fun columns(name: String): ApiResult<JsonElement> = unsupported()
    suspend fun articles(columnId: String, offset: Int, limit: Int): ApiResult<JsonElement> = unsupported()
    suspend fun history(offset: Int, limit: Int): ApiResult<JsonElement> = unsupported()
    suspend fun article(id: String): ApiResult<JsonElement> = unsupported()
    suspend fun document(name: String): ApiResult<JsonElement> = unsupported()
    suspend fun increaseHitsOnce(owner: String, id: String): ApiResult<Boolean> = unsupported()
    suspend fun likeCount(id: String): ApiResult<JsonElement> = unsupported()
    suspend fun myLike(id: String): ApiResult<JsonElement> = unsupported()
    suspend fun toggleLike(id: String): ApiResult<JsonElement> = unsupported()
    suspend fun notices(offset: Int, limit: Int): ApiResult<JsonElement> = unsupported()
    suspend fun announcements(): ApiResult<JsonElement> = unsupported()
    suspend fun markRead(id: String, kind: NoticeReadKind): ApiResult<JsonElement> = unsupported()
    suspend fun unread(): ApiResult<JsonElement> = unsupported()
    suspend fun discover(): ApiResult<JsonElement> = unsupported()
    suspend fun dictionary(code: String): ApiResult<JsonElement> = unsupported()
}

private fun unsupported() = ApiResult.Failure(ApiError.Decode(AppText.Content.DATA_SOURCE_NOT_CONFIGURED))
enum class NoticeReadKind { SYSTEM, MUTUAL }

class RepositoryContentSource(private val repositories: AppRepositories) : ContentSource {
    override suspend fun columns(name: String) = repositories.content.columns(name)
    override suspend fun articles(columnId: String, offset: Int, limit: Int) = repositories.content.articles(columnId, offset, limit)
    override suspend fun history(offset: Int, limit: Int) = repositories.content.history(buildJsonObject { put("offset", offset); put("limit", limit) })
    override suspend fun article(id: String) = repositories.content.article(id)
    override suspend fun document(name: String) = repositories.content.document(name)
    override suspend fun increaseHitsOnce(owner: String, id: String) = repositories.content.increaseHitsOnce(owner, id)
    override suspend fun likeCount(id: String) = repositories.content.likeCount(id)
    override suspend fun myLike(id: String) = repositories.content.myLike(id)
    override suspend fun toggleLike(id: String) = repositories.content.toggleLike(id)
    override suspend fun notices(offset: Int, limit: Int) = repositories.notices.list("0", offset, limit)
    override suspend fun announcements() = repositories.notices.announcements()
    override suspend fun markRead(id: String, kind: NoticeReadKind) = when (kind) {
        NoticeReadKind.SYSTEM -> repositories.notices.markRead(id)
        NoticeReadKind.MUTUAL -> repositories.notices.markMutualRead(id)
    }
    override suspend fun unread() = repositories.notices.unread()
    override suspend fun discover() = repositories.discover.menu()
    override suspend fun dictionary(code: String) = repositories.discover.dictionary(code)
}
