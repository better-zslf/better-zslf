package io.github.betterzslf.feature.content

import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

private fun documentData() = buildJsonObject { put("articleDetails", buildJsonObject {
    put("id", "document"); put("title", "Fixture"); put("content", "<p>fixture</p>"); put("hits", 7)
}) }

/** Protocol documents are the only remaining content route: no likes, hit counters or list state. */
class ArticleControllerTest {
    @Test
    fun documentLoadPopulatesDetailWithoutTouchingLikeOrHitEndpoints() = runTest {
        var reads = 0
        val controller = ArticleController(object : ContentSource {
            override suspend fun document(name: String): ApiResult<JsonElement> { reads++; return ApiResult.Success(documentData()) }
        }, "用户协议")
        controller.load()
        assertEquals(1, reads)
        assertEquals("Fixture", controller.state.value.detail?.title)
        assertEquals("<p>fixture</p>", controller.state.value.detail?.html)
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.error)
        assertNull(controller.state.value.liked)
        assertNull(controller.state.value.hits)
    }

    @Test
    fun documentLoadFailureIsReportedAndLeavesNoDetail() = runTest {
        val controller = ArticleController(object : ContentSource {
            override suspend fun document(name: String) = ApiResult.Failure(io.github.betterzslf.core.result.ApiError.Network())
        }, "隐私政策")
        controller.load()
        assertNull(controller.state.value.detail)
        assertIs<io.github.betterzslf.core.result.ApiError.Network>(controller.state.value.error)
        assertFalse(controller.state.value.loading)
    }

    @Test
    fun malformedDocumentStructureIsADecodeError() = runTest {
        val controller = ArticleController(object : ContentSource {
            override suspend fun document(name: String): ApiResult<JsonElement> = ApiResult.Success(JsonObject(emptyMap()))
        }, "使用帮助")
        controller.load()
        assertNull(controller.state.value.detail)
        assertIs<io.github.betterzslf.core.result.ApiError.Decode>(controller.state.value.error)
    }
}
