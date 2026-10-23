package io.github.betterzslf.core.net

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiClientTest {
    @Test
    fun routesBothBasesAndUsesBareAuthorization() = runTest {
        val requests = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request ->
            requests += request.url.host + request.url.encodedPath
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            assertEquals(HttpMethod.Post, request.method)
            respond("{\"code\":200,\"data\":{},\"message\":\"ok\",\"success\":false}")
        })
        try {
            assertIs<ApiResult.Success<*>>(fixture.repositories.profile.photo())
            assertIs<ApiResult.Success<*>>(fixture.repositories.run.detail("task-fixture"))
            assertEquals(listOf(
                "app.fixture.invalid/app/appUser/getUserPhotoURL",
                "run.fixture.invalid/run/errand/getErrandDetail",
            ), requests)
        } finally { fixture.close() }
    }

    @Test
    fun publicVersionIsGetAndNeverSendsAnExistingToken() = runTest {
        val fixture = ApiTestFixture(MockEngine { request ->
            assertEquals("/app/checkVersion", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("{\"code\":200,\"data\":{\"version\":\"1.0.79\"}}")
        })
        try { assertIs<ApiResult.Success<*>>(fixture.repositories.updates.check()) }
        finally { fixture.close() }
    }

    @Test
    fun missingSessionPreventsProtectedNetworkCalls() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; error("Protected request should stay local") }, authenticated = false)
        try {
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.repositories.profile.userInfo()).error)
            assertEquals(0, calls)
        } finally { fixture.close() }
    }

    @Test
    fun http401ClearsPersistentAndObservableSessionBeforeReturning() = runTest {
        val fixture = ApiTestFixture(MockEngine { respond("", HttpStatusCode.Unauthorized) })
        try {
            val result = fixture.repositories.profile.userInfo()
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(result).error)
            assertNull(fixture.sessions.session.value)
            assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
            assertNull(SessionStore(fixture.storage).session.value)
        } finally { fixture.close() }
    }

    @Test
    fun late401DoesNotDeleteNewerLoginSession() = runTest {
        lateinit var fixture: ApiTestFixture
        fixture = ApiTestFixture(MockEngine {
            fixture.sessions.save(fixtureSession("newer-fixture-token"))
            respond("", HttpStatusCode.Unauthorized)
        })
        try {
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.repositories.profile.userInfo()).error)
            assertEquals("newer-fixture-token", fixture.sessions.session.value?.token)
        } finally { fixture.close() }
    }

    @Test
    fun envelope401AlsoInvalidatesTheMatchingSession() = runTest {
        val fixture = ApiTestFixture(MockEngine { respond("{\"code\":401,\"message\":\"expired\"}") })
        try {
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.repositories.profile.userInfo()).error)
            assertNull(fixture.sessions.session.value)
        } finally { fixture.close() }
    }

    @Test
    fun distinguishesBusinessHttpAndDecodeFailures() = runTest {
        val cases = listOf(
            Triple("{\"code\":400,\"data\":\"\",\"message\":\"fixture error\",\"success\":false}", HttpStatusCode.OK, "business"),
            Triple("<server stack trace>", HttpStatusCode.InternalServerError, "http"),
            Triple("{\"data\":{}}", HttpStatusCode.OK, "decode"),
            Triple("[]", HttpStatusCode.OK, "decode"),
            Triple("malformed", HttpStatusCode.OK, "decode"),
            Triple("", HttpStatusCode.OK, "decode"),
        )
        for ((body, status, kind) in cases) {
            val fixture = ApiTestFixture(MockEngine { respond(body, status) })
            try {
                val error = assertIs<ApiResult.Failure>(fixture.repositories.profile.userInfo()).error
                when (kind) {
                    "business" -> {
                        assertEquals(400, assertIs<ApiError.Business>(error).code)
                        assertEquals("fixture error", error.message)
                    }
                    "http" -> assertEquals(500, assertIs<ApiError.Http>(error).status)
                    else -> assertIs<ApiError.Decode>(error)
                }
                assertNotNull(fixture.sessions.session.value)
            } finally { fixture.close() }
        }
    }

    @Test
    fun emptyIncreaseHitsIsSuccessButOtherEmptyResponsesAreNot() = runTest {
        val fixture = ApiTestFixture(MockEngine { respond("") })
        try {
            assertEquals(JsonNull, assertIs<ApiResult.Success<*>>(fixture.repositories.content.increaseHits("fixture-article")).data)
            assertIs<ApiError.Decode>(assertIs<ApiResult.Failure>(fixture.repositories.profile.photo()).error)
        } finally { fixture.close() }
    }

    @Test
    fun classifiesNetworkAndTimeoutWithoutExceptionDetails() = runTest {
        val failures = listOf(
            IOException("https://fixture.invalid/login?password=fixture-secret"),
            HttpRequestTimeoutException("https://fixture.invalid/login?password=fixture-secret", 8_000),
        )
        failures.forEach { failure ->
            val fixture = ApiTestFixture(MockEngine { throw failure })
            try {
                val result = assertIs<ApiResult.Failure>(fixture.repositories.profile.userInfo())
                if (failure is HttpRequestTimeoutException) assertIs<ApiError.Timeout>(result.error)
                else assertIs<ApiError.Network>(result.error)
                assertTrue(!result.error.message.contains("fixture-secret"))
            } finally { fixture.close() }
        }
    }

    @Test
    fun preservesCallerCancellationInsteadOfTurningItIntoNetworkFailure() = runTest {
        val fixture = ApiTestFixture(MockEngine { throw CancellationException("fixture cancellation") })
        try {
            assertFailsWith<CancellationException> { fixture.repositories.profile.userInfo() }
        } finally { fixture.close() }
    }

    @Test
    fun serializesArraysObjectsAndUnicodeWithoutInjectingExtraParameters() = runTest {
        val objectValue = buildJsonObject { put("tag", "中文") }
        val fixture = ApiTestFixture(MockEngine { request ->
            assertEquals(listOf("one", "two&other=not-a-param"), request.url.parameters.getAll("tags[]"))
            assertEquals(objectValue.toString(), request.url.parameters["filter"])
            assertEquals("a+b !@#$,[] 中文", request.url.parameters["text"])
            assertNull(request.url.parameters["omitted"])
            assertNull(request.url.parameters["other"])
            respond("{\"code\":200,\"data\":{}}")
        })
        try {
            assertIs<ApiResult.Success<*>>(fixture.repositories.content.history(buildJsonObject {
                put("tags", buildJsonArray { add(JsonPrimitive("one")); add(JsonPrimitive("two&other=not-a-param")) })
                put("filter", objectValue)
                put("text", "a+b !@#$,[] 中文")
                put("omitted", JsonNull)
            }))
        } finally { fixture.close() }
    }

    @Test
    fun doesNotFollowRedirectsOrForwardTokens() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine {
            calls++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.fixture.invalid/"))
        })
        try {
            assertEquals(302, assertIs<ApiError.Http>(assertIs<ApiResult.Failure>(fixture.repositories.profile.userInfo()).error).status)
            assertEquals(1, calls)
        } finally { fixture.close() }
    }

    @Test
    fun validatesParameterLocationAndMissingIdsBeforeAnyRequest() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; error("Invalid input should stay local") })
        try {
            val failures = listOf(
                fixture.repositories.venues.selectTime("", ""),
                fixture.repositories.venues.application(" "),
                fixture.repositories.venues.relate("venue", ""),
                fixture.repositories.run.addTrack(JsonObject(emptyMap())),
                fixture.repositories.run.tasks(""),
                fixture.repositories.content.articles("column", -1, 0),
                fixture.repositories.fitness.enrol(JsonObject(emptyMap())),
                fixture.api.execute(ApiEndpoint.RUN_SYNC, query = queryParameters("trid" to "task")),
                fixture.api.execute(ApiEndpoint.RESET_PASSWORD, body = queryParameters("userId" to "fixture")),
                fixture.api.execute(ApiEndpoint.UPLOAD),
            )
            failures.forEach { assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(it).error) }
            assertEquals(0, calls)
        } finally { fixture.close() }
    }

    @Test
    fun fileUrlsOnlyResolveRelativeUserfilesPaths() {
        val fixture = ApiTestFixture(MockEngine { error("URL resolution never makes requests") })
        try {
            assertEquals("https://files.fixture.invalid/userfiles/image/fixture.png", fixture.api.fileUrl("image/fixture.png"))
            listOf("", "../secret", "%2e%2e/secret", "https://other.invalid/image", "image\\file", "image?q=x").forEach {
                assertNull(fixture.api.fileUrl(it))
            }
        } finally { fixture.close() }
    }
}
