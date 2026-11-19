package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Write-side repository contracts; the original read-side assertions live in run/data tests. */
class RunRepositoryTest {
    private val owner = "fixture-user-id"
    private fun body(userId: JsonElement = JsonPrimitive(owner)) = buildJsonObject {
        put("trid", "fixture-task")
        put("userId", userId)
        put("coordinate", "[{\"latitude\":20.0,\"longitude\":110.0}]")
    }
    private fun operations(): List<suspend RunRepository.() -> ApiResult<JsonElement>> {
        val data = body()
        return listOf(
            { punch(queryParameters("longitude1" to "110", "latitude1" to "20", "longitude2" to "110", "latitude2" to "20")) },
            { addTrack(data) },
            { syncSportData(data) },
            { reportLocation(data) },
            { quickSave(data) },
        )
    }

    @Test
    fun capturedOwnerIsStableAndABoundRepositoryNeverSilentlyRebinds() {
        val fixture = ApiTestFixture(MockEngine { error("No request expected") })
        try {
            assertNull(fixture.repositories.run.boundOwnerId)
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            assertEquals(owner, bound.boundOwnerId)
            assertSame(bound, bound.boundToCurrentSession())
            fixture.sessions.save(fixtureSession("replacement-fixture-token").copy(userInfo = buildJsonObject { put("id", "new-fixture-owner") }))
            assertEquals(owner, bound.boundOwnerId)
            assertNull(bound.boundToCurrentSession())
            val fresh = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            assertEquals("new-fixture-owner", fresh.boundOwnerId)
        } finally { fixture.close() }
    }

    @Test
    fun allFiveBoundWriteMethodsPreventHttpAfterTheSessionChanges() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("Stale repository must stay local") })
        try {
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            fixture.sessions.save(fixtureSession("replacement-fixture-token").copy(userInfo = buildJsonObject { put("id", "new-fixture-owner") }))
            for (operation in operations()) {
                assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(operation.invoke(bound)).error)
            }
            assertEquals(5, operations().size)
            assertEquals(0, requests)
            assertEquals("new-fixture-owner", fixture.sessions.session.value?.userId)
            assertEquals("replacement-fixture-token", fixture.sessions.session.value?.token)
        } finally { fixture.close() }
    }

    @Test
    fun allBoundJsonWritesRejectMissingBlankDifferentAndNonStringOwnersLocally() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("Wrong owner must stay local") })
        try {
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            val writes = listOf(bound::addTrack, bound::syncSportData, bound::reportLocation, bound::quickSave)
            val invalidBodies = listOf(
                JsonObject(body().filterKeys { it != "userId" }),
                body(JsonPrimitive("")), body(JsonPrimitive("different-fixture-owner")),
                body(JsonNull), body(JsonPrimitive(123)), body(buildJsonObject { put("id", owner) }),
            )
            for (invalid in invalidBodies) for (write in writes) {
                val error = assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(write(invalid)).error)
                assertEquals(listOf("userId"), error.fields)
                assertFalse(error.message.contains(owner))
                assertFalse(error.message.contains("fixture-token"))
            }
            assertEquals(0, requests)
            assertEquals(owner, fixture.sessions.session.value?.userId)
        } finally { fixture.close() }
    }

    @Test
    fun explicitPunchOwnerIsCheckedWithoutChangingTheCoordinateOnlyContract() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; respond("{\"code\":200,\"data\":{}}") })
        try {
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(bound.punch(queryParameters("userId" to "different-fixture-owner"))).error)
            assertEquals(0, requests)
            assertIs<ApiResult.Success<*>>(bound.punch(queryParameters("longitude1" to "110", "latitude1" to "20", "longitude2" to "110", "latitude2" to "20")))
            assertEquals(1, requests)
        } finally { fixture.close() }
    }

    @Test
    fun unchangedSessionAllowsEveryBoundWriteAndPreservesNestedCoordinateVerbatim() = runTest {
        val paths = mutableSetOf<String>()
        val expectedBody = body()
        val fixture = ApiTestFixture(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            val requestBody = request.body
            if (requestBody is TextContent) assertEquals(expectedBody.toString(), requestBody.text)
            respond("{\"code\":200,\"data\":{}}")
        })
        try {
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            for (operation in operations()) assertIs<ApiResult.Success<*>>(operation.invoke(bound))
            assertEquals(5, paths.size)
        } finally { fixture.close() }
    }

    @Test
    fun legacyUnboundJsonCallsDoNotGainAUserIdRequirement() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            requests++
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            assertFalse((request.body as TextContent).text.contains("userId"))
            respond("{\"code\":200,\"data\":{}}")
        })
        try {
            assertIs<ApiResult.Success<*>>(fixture.repositories.run.quickSave(buildJsonObject { put("trid", "fixture-task") }))
            assertEquals(1, requests)
        } finally { fixture.close() }
    }
}
