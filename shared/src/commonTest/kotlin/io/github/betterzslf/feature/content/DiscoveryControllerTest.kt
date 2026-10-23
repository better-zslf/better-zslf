package io.github.betterzslf.feature.content

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class DiscoveryControllerTest {
    @Test
    fun discoversTreeWithoutGuessingChildrenPropertyNames() {
        val data = buildJsonObject { put("findList", buildJsonArray {
            add(buildJsonObject { put("id", "root"); put("name", "服务"); put("serverOwnedArrayName", buildJsonArray {
                add(buildJsonObject { put("id", "leaf"); put("name", "校园跑") })
            }) })
        }) }
        val tree = assertIs<ApiResult.Success<ContentTree>>(parseContentTree(data)).data
        assertEquals(2, tree.nodes.size)
        assertEquals(listOf("服务"), tree.visible(emptySet()).map { it.label })
        assertEquals(listOf("服务", "校园跑"), tree.visible(setOf(tree.nodes.first().key)).map { it.label })
        assertTrue(tree.hasChildren(tree.nodes.first().key))
        assertEquals("runs" to "", contentDestination("校园跑"))
        assertNull(contentDestination("javascript:run()")); assertNull(contentDestination("https://other.fixture.invalid"))
    }

    @Test
    fun decodesSingleAndMultipleColumnsAndLargeFlatTrees() {
        val single = buildJsonObject { put("cmsColumn", buildJsonObject { put("id", "single"); put("name", "学校动态") }) }
        assertEquals(1, assertIs<ApiResult.Success<ContentTree>>(parseContentTree(single, true)).data.nodes.size)
        val many = buildJsonObject { put("cmsColumnList", JsonArray((0 until 1_489).map {
            buildJsonObject { put("id", it.toString()); put("name", "栏目$it") }
        })) }
        val tree = assertIs<ApiResult.Success<ContentTree>>(parseContentTree(many, true)).data
        assertEquals(1_489, tree.visible(emptySet()).size)
        assertEquals(1_489, tree.nodes.map { it.key }.toSet().size)
        assertIs<ApiError.Decode>(assertIs<ApiResult.Failure>(parseContentTree(JsonObject(emptyMap()))).error)
    }

    @Test
    fun dictionaryValuesComeFromInfoCodeNotTheDictionaryKeyOrGuessedDefaults() = runTest {
        val requested = mutableListOf<String>()
        val controller = DiscoveryController(object : ContentSource {
            override suspend fun discover(): ApiResult<JsonElement> = ApiResult.Success(buildJsonObject { put("findList", JsonArray(emptyList())) })
            override suspend fun dictionary(code: String): ApiResult<JsonElement> {
                requested += code
                return ApiResult.Success(buildJsonObject { put("info", buildJsonArray { add(buildJsonObject {
                    put("name", if (code == "schoolRun") "isOpen" else "radius")
                    put("code", if (code == "schoolRun") "1" else "40")
                }) }) })
            }
        })
        controller.refresh()
        assertEquals(listOf("schoolRun", "attendanceRadius"), requested)
        assertEquals(true, controller.state.value.runOpen)
        assertEquals(40, controller.state.value.attendanceRadiusMeters)
    }

    @Test
    fun columnsDoNotRequestSchoolDictionariesOrAnyMutation() = runTest {
        var dictionaryCalls = 0
        val controller = DiscoveryController(object : ContentSource {
            override suspend fun columns(name: String): ApiResult<JsonElement> {
                assertEquals("新生攻略", name)
                return ApiResult.Success(buildJsonObject { put("cmsColumnList", JsonArray(emptyList())) })
            }
            override suspend fun dictionary(code: String): ApiResult<JsonElement> { dictionaryCalls++; error("Unexpected dictionary") }
        }, "新生攻略")
        controller.refresh(); assertEquals(0, dictionaryCalls); assertFalse(controller.state.value.loading)
    }
}
