package io.github.betterzslf.feature.content

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ContentActionsTest {
    @Test
    fun explicitOpenUsesCorrectSystemAndMutualWritesAndRefreshesUnreadOnlyAfterSuccess() = runTest {
        val writes = mutableListOf<Pair<String, NoticeReadKind>>()
        var unreadCalls = 0
        val controller = NoticeReadController(object : ContentSource {
            override suspend fun markRead(id: String, kind: NoticeReadKind): ApiResult<JsonElement> { writes += id to kind; return ApiResult.Success(JsonNull) }
            override suspend fun unread(): ApiResult<JsonElement> { unreadCalls++; return ApiResult.Success(buildJsonObject { put("unreadNumber", 2); put("noticeMutualCount", 3) }) }
        }, "owner")
        assertTrue(writes.isEmpty())
        controller.open(ContentItem("system", "System", isRead = false), NoticeReadKind.SYSTEM)
        controller.open(ContentItem("announcement", "Announcement"), NoticeReadKind.MUTUAL)
        controller.open(ContentItem("system", "System"), NoticeReadKind.SYSTEM)
        assertEquals(listOf("system" to NoticeReadKind.SYSTEM, "announcement" to NoticeReadKind.MUTUAL), writes)
        assertEquals(2, unreadCalls); assertEquals(5, controller.state.value.unread)
    }

    @Test
    fun failedWriteDoesNotOptimisticallyMarkReadOrReduceBadge() = runTest {
        var unreadCalls = 0
        val controller = NoticeReadController(object : ContentSource {
            override suspend fun markRead(id: String, kind: NoticeReadKind) = ApiResult.Failure(ApiError.Network())
            override suspend fun unread(): ApiResult<JsonElement> { unreadCalls++; return ApiResult.Success(JsonNull) }
        }, "owner")
        val item = ContentItem("id", "Fixture")
        controller.open(item, NoticeReadKind.SYSTEM)
        assertTrue(controller.state.value.read.isEmpty()); assertTrue(controller.state.value.pending.isEmpty())
        assertIs<ApiError.Network>(controller.state.value.errors[controller.key(item, NoticeReadKind.SYSTEM)])
        assertEquals(0, unreadCalls)
    }

    @Test
    fun duplicatePendingAndAnonymousReadActionsNeverCreateDuplicateWrites() = runTest {
        var calls = 0; val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<Unit>()
        val source = object : ContentSource {
            override suspend fun markRead(id: String, kind: NoticeReadKind): ApiResult<JsonElement> { calls++; started.complete(Unit); response.await(); return ApiResult.Success(JsonNull) }
        }
        val item = ContentItem("id", "Fixture")
        NoticeReadController(source, "").open(item, NoticeReadKind.MUTUAL)
        assertEquals(0, calls)
        val controller = NoticeReadController(source, "owner")
        val pending = async { controller.open(item, NoticeReadKind.MUTUAL) }; started.await()
        controller.open(item, NoticeReadKind.MUTUAL); response.complete(Unit); pending.await()
        assertEquals(1, calls)
    }

    @Test
    fun localHistoryHidingIsOwnerScopedReversibleAndNeverUsesTheNetwork() {
        val storage = MemoryKeyValueStore()
        val first = HistoryVisibilityStore(storage, "owner-a")
        first.setHidden(true)
        assertTrue(first.state.value.hidden)
        assertTrue(HistoryVisibilityStore(storage, "owner-a").state.value.hidden)
        assertFalse(HistoryVisibilityStore(storage, "owner-b").state.value.hidden)
        first.setHidden(false)
        assertFalse(HistoryVisibilityStore(storage, "owner-a").state.value.hidden)
    }

    @Test
    fun failedLocalHistoryWriteDoesNotClaimSuccess() {
        val memory = MemoryKeyValueStore()
        val storage = object : KeyValueStore by memory {
            override fun put(key: String, value: String) { error("fixture disk failure") }
        }
        val visibility = HistoryVisibilityStore(storage, "owner")
        visibility.setHidden(true)
        assertFalse(visibility.state.value.hidden); assertIs<ApiError.Storage>(visibility.state.value.error)
    }
}
