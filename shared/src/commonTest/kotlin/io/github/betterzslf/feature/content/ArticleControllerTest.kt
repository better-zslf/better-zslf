package io.github.betterzslf.feature.content

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

private fun articleData() = buildJsonObject { put("articleDetails", buildJsonObject {
    put("id", "article"); put("title", "Fixture"); put("content", "<p>fixture</p>"); put("hits", 7)
}) }

class ArticleControllerTest {
    @Test
    fun initialCountMyLikeAndToggleUseCanonicalServerResult() = runTest {
        var toggles = 0
        val controller = ArticleController(object : ContentSource {
            override suspend fun article(id: String) = ApiResult.Success(articleData())
            override suspend fun increaseHitsOnce(owner: String, id: String) = ApiResult.Success(true)
            override suspend fun likeCount(id: String) = ApiResult.Success(buildJsonObject { put("count", 5) })
            override suspend fun myLike(id: String) = ApiResult.Failure(ApiError.Business(400, "此用户没有点赞"))
            override suspend fun toggleLike(id: String): ApiResult<JsonElement> {
                toggles++; return ApiResult.Success(buildJsonObject { put("result", toggles == 1); put("count", if (toggles == 1) 6 else 5) })
            }
        }, "owner", "article")
        controller.load()
        assertEquals(8, controller.state.value.hits); assertEquals(5, controller.state.value.count); assertEquals(false, controller.state.value.liked)
        controller.toggleLike(); assertEquals(true, controller.state.value.liked); assertEquals(6, controller.state.value.count)
        controller.toggleLike(); assertEquals(false, controller.state.value.liked); assertEquals(5, controller.state.value.count)
    }

    @Test
    fun unexpectedBusinessFailureIsNotTreatedAsNotLiked() = runTest {
        val controller = ArticleController(object : ContentSource {
            override suspend fun article(id: String) = ApiResult.Success(articleData())
            override suspend fun likeCount(id: String) = ApiResult.Success(buildJsonObject { put("count", 2) })
            override suspend fun myLike(id: String) = ApiResult.Failure(ApiError.Business(400, "fixture backend error"))
        }, "owner", "article")
        controller.load(); assertNull(controller.state.value.liked); assertIs<ApiError.Business>(controller.state.value.likeError)
    }

    @Test
    fun documentsAndAnonymousViewsDoNotCountOrToggleOrReadPrivateLikes() = runTest {
        var writes = 0; var likeReads = 0
        val source = object : ContentSource {
            override suspend fun document(name: String) = ApiResult.Success(articleData())
            override suspend fun article(id: String) = ApiResult.Success(articleData())
            override suspend fun increaseHitsOnce(owner: String, id: String): ApiResult<Boolean> { writes++; return ApiResult.Success(true) }
            override suspend fun likeCount(id: String): ApiResult<JsonElement> { likeReads++; return ApiResult.Success(JsonNull) }
            override suspend fun toggleLike(id: String): ApiResult<JsonElement> { writes++; return ApiResult.Success(JsonNull) }
        }
        val document = ArticleController(source, "owner", "用户协议", true)
        document.load(); document.toggleLike(); document.refreshLikes()
        val anonymous = ArticleController(source, "", "article")
        anonymous.load(); anonymous.toggleLike()
        assertEquals(0, writes); assertEquals(0, likeReads)
    }

    @Test
    fun duplicateToggleIsBlockedAndMalformedResultDoesNotInventOptimisticState() = runTest {
        val started = CompletableDeferred<Unit>(); val response = CompletableDeferred<Unit>(); var calls = 0
        val controller = ArticleController(object : ContentSource {
            override suspend fun article(id: String) = ApiResult.Success(articleData())
            override suspend fun likeCount(id: String) = ApiResult.Success(buildJsonObject { put("count", 5) })
            override suspend fun myLike(id: String) = ApiResult.Failure(ApiError.Business(400, "此用户没有点赞"))
            override suspend fun toggleLike(id: String): ApiResult<JsonElement> { calls++; started.complete(Unit); response.await(); return ApiResult.Success(JsonObject(emptyMap())) }
        }, "owner", "article")
        controller.load(); val pending = async { controller.toggleLike() }; started.await()
        controller.toggleLike(); response.complete(Unit); pending.await()
        assertEquals(1, calls); assertEquals(false, controller.state.value.liked); assertEquals(5, controller.state.value.count)
        assertIs<ApiError.Decode>(controller.state.value.likeError); assertFalse(controller.state.value.toggling)
    }
}
