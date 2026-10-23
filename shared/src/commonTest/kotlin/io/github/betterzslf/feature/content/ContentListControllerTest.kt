package io.github.betterzslf.feature.content

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

internal fun rows(field: String, ids: List<String>): JsonObject = buildJsonObject {
    put(field, JsonArray(ids.map { id -> buildJsonObject { put("id", id); put("title", "fixture-$id") } }))
}

class ContentListControllerTest {
    @Test
    fun serverPagingUsesRawOffsetDeduplicatesAndStopsAtShortPage() = runTest {
        val offsets = mutableListOf<Int>()
        val source = object : ContentSource {
            override suspend fun articles(columnId: String, offset: Int, limit: Int): ApiResult<JsonElement> {
                assertEquals("column", columnId); assertEquals(2, limit); offsets += offset
                return ApiResult.Success(rows("cmsArticleList", when (offset) { 0 -> listOf("a", "b"); 2 -> listOf("b", "c"); else -> listOf("d") }))
            }
        }
        val controller = ContentListController(source, ContentFeedRequest(ContentFeedKind.ARTICLES, "column"), 2)
        controller.refresh(); controller.loadMore(); controller.loadMore(); controller.loadMore()
        assertEquals(listOf(0, 2, 4), offsets)
        assertEquals(listOf("a", "b", "c", "d"), controller.state.value.items.map { it.id })
        assertEquals(5, controller.state.value.rawOffset)
        assertFalse(controller.state.value.hasMore)
    }

    @Test
    fun failedRefreshRetriesOffsetZeroAndPreservesPreviouslyVisibleData() = runTest {
        var fail = false
        val offsets = mutableListOf<Int>()
        val controller = ContentListController(object : ContentSource {
            override suspend fun history(offset: Int, limit: Int): ApiResult<JsonElement> {
                offsets += offset
                return if (fail) ApiResult.Failure(ApiError.Network()) else ApiResult.Success(rows("list", listOf("a", "b")))
            }
        }, ContentFeedRequest(ContentFeedKind.HISTORY), 2)
        controller.refresh(); fail = true; controller.refresh()
        assertEquals(2, controller.state.value.items.size)
        assertIs<ApiError.Network>(controller.state.value.error)
        fail = false; controller.retry()
        assertEquals(listOf(0, 0, 0), offsets)
        assertNull(controller.state.value.error)
    }

    @Test
    fun staleOlderRefreshCannotOverwriteTheLatestResponse() = runTest {
        val oldStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<Unit>()
        var calls = 0
        val controller = ContentListController(object : ContentSource {
            override suspend fun history(offset: Int, limit: Int): ApiResult<JsonElement> {
                if (++calls == 1) { oldStarted.complete(Unit); oldResponse.await(); return ApiResult.Success(rows("list", listOf("old"))) }
                return ApiResult.Success(rows("list", listOf("new")))
            }
        }, ContentFeedRequest(ContentFeedKind.HISTORY))
        val pending = async { controller.refresh() }; oldStarted.await()
        controller.refresh(); oldResponse.complete(Unit); pending.await()
        assertEquals(listOf("new"), controller.state.value.items.map { it.id })
    }

    @Test
    fun announcementsAreFetchedOnceAndExposedInExplicitLocalWindows() = runTest {
        var calls = 0
        val controller = ContentListController(object : ContentSource {
            override suspend fun announcements(): ApiResult<JsonElement> { calls++; return ApiResult.Success(rows("announcementList", (0 until 1_489).map { it.toString() })) }
        }, ContentFeedRequest(ContentFeedKind.ANNOUNCEMENTS), 20)
        controller.refresh()
        assertEquals(20, controller.state.value.items.size)
        assertEquals(1_489, controller.state.value.total)
        controller.loadMore()
        assertEquals(40, controller.state.value.items.size)
        assertEquals(1, calls)
    }

    @Test
    fun malformedListIsNotAnEmptySuccessAndRepeatedFullPagesStop() = runTest {
        var malformed = true
        val controller = ContentListController(object : ContentSource {
            override suspend fun notices(offset: Int, limit: Int): ApiResult<JsonElement> = ApiResult.Success(
                if (malformed) buildJsonObject { put("wrong", JsonArray(emptyList())) } else rows("noticeList", listOf("a", "b")))
        }, ContentFeedRequest(ContentFeedKind.SYSTEM_MESSAGES), 2)
        controller.refresh(); assertIs<ApiError.Decode>(controller.state.value.error)
        malformed = false; controller.refresh(); controller.loadMore()
        assertEquals(2, controller.state.value.items.size)
        assertFalse(controller.state.value.hasMore)
    }

    @Test
    fun loadMoreDoesNotIssueConcurrentDuplicateRequests() = runTest {
        val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<Unit>(); var calls = 0
        val controller = ContentListController(object : ContentSource {
            override suspend fun articles(columnId: String, offset: Int, limit: Int): ApiResult<JsonElement> {
                calls++; if (offset > 0) { started.complete(Unit); response.await() }
                return ApiResult.Success(rows("cmsArticleList", if (offset == 0) listOf("a", "b") else emptyList()))
            }
        }, ContentFeedRequest(ContentFeedKind.ARTICLES, "column"), 2)
        controller.refresh(); val pending = async { controller.loadMore() }; started.await()
        controller.loadMore(); response.complete(Unit); pending.await()
        assertEquals(2, calls)
    }
}
