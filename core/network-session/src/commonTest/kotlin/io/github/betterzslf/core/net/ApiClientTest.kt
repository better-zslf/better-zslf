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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApiClientTest {
    @Test
    fun routesBothBasesAndUsesBareAuthorization() = runTest {
        val requests = mutableListOf<String>()
        val fixture = CoreApiTestFixture(MockEngine { request ->
            requests += request.url.host + request.url.encodedPath
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            assertEquals(HttpMethod.Post, request.method)
            respond("{\"code\":200,\"data\":{},\"message\":\"ok\",\"success\":false}")
        })
        try {
            assertIs<ApiResult.Success<*>>(fixture.api.execute(ApiEndpoint.USER_PHOTO))
            assertIs<ApiResult.Success<*>>(fixture.api.execute(ApiEndpoint.RUN_DETAIL, queryParameters("errandId" to "fixture-errand", "userId" to "fixture-user-id", "errandScheduleId" to "fixture-schedule")))
            assertEquals(listOf(
                "app.fixture.invalid/app/appUser/getUserPhotoURL",
                "run.fixture.invalid/run/errand/getErrandDetail",
            ), requests)
        } finally { fixture.close() }
    }

    @Test
    fun publicVersionIsGetAndNeverSendsAnExistingToken() = runTest {
        val fixture = CoreApiTestFixture(MockEngine { request ->
            assertEquals("/app/checkVersion", request.url.encodedPath)
            assertEquals(HttpMethod.Get, request.method)
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("{\"code\":200,\"data\":{\"version\":\"1.0.79\"}}")
        })
        try { assertIs<ApiResult.Success<*>>(fixture.api.execute(ApiEndpoint.CHECK_VERSION)) }
        finally { fixture.close() }
    }

    @Test
    fun missingSessionPreventsProtectedNetworkCalls() = runTest {
        var calls = 0
        val fixture = CoreApiTestFixture(MockEngine { calls++; error("Protected request should stay local") }, authenticated = false)
        try {
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.api.execute(ApiEndpoint.USER_INFO)).error)
            assertEquals(0, calls)
        } finally { fixture.close() }
    }

    @Test
    fun http401ClearsPersistentAndObservableSessionBeforeReturning() = runTest {
        val fixture = CoreApiTestFixture(MockEngine { respond("", HttpStatusCode.Unauthorized) })
        try {
            val result = fixture.api.execute(ApiEndpoint.USER_INFO)
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(result).error)
            assertNull(fixture.sessions.session.value)
            assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
            assertNull(SessionStore(fixture.storage).session.value)
        } finally { fixture.close() }
    }

    @Test
    fun late401DoesNotDeleteNewerLoginSession() = runTest {
        lateinit var fixture: CoreApiTestFixture
        fixture = CoreApiTestFixture(MockEngine {
            fixture.sessions.save(fixtureSession("newer-fixture-token"))
            respond("", HttpStatusCode.Unauthorized)
        })
        try {
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.api.execute(ApiEndpoint.USER_INFO)).error)
            assertEquals("newer-fixture-token", fixture.sessions.session.value?.token)
        } finally { fixture.close() }
    }

    @Test
    fun envelope401AlsoInvalidatesTheMatchingSession() = runTest {
        val fixture = CoreApiTestFixture(MockEngine { respond("{\"code\":401,\"message\":\"expired\"}") })
        try {
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.api.execute(ApiEndpoint.USER_INFO)).error)
            assertNull(fixture.sessions.session.value)
        } finally { fixture.close() }
    }

    @Test
    fun delayedUnbound401CannotClearAnyReplacementEpochWithTheSameToken() = runTest {
        for (envelope in listOf(false, true)) for (mutation in 0..3) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = CoreApiTestFixture(MockEngine { request ->
                assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
                entered.complete(Unit)
                release.await()
                if (envelope) respond("""{"code":401,"message":"fixture expired"}""")
                else respond("", HttpStatusCode.Unauthorized)
            })
            try {
                val pending = async { fixture.api.execute(ApiEndpoint.USER_INFO) }
                entered.await()
                val original = assertNotNull(fixture.sessions.session.value)
                when (mutation) {
                    0 -> fixture.sessions.save(original.copy())
                    1 -> { fixture.sessions.clear(); fixture.sessions.save(original) }
                    2 -> fixture.sessions.save(original.copy(mapToken = "changed-fixture-map", mapWebType = "2"))
                    else -> fixture.sessions.save(original.copy(userInfo = buildJsonObject { put("id", "changed-fixture-owner") }))
                }
                val current = fixture.sessions.session.value
                val epoch = fixture.sessions.revision.value
                val persisted = fixture.storage.get(SessionStore.STORAGE_KEY)
                release.complete(Unit)
                assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(pending.await()).error)
                assertSame(current, fixture.sessions.session.value)
                assertEquals(epoch, fixture.sessions.revision.value)
                assertEquals(persisted, fixture.storage.get(SessionStore.STORAGE_KEY))
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test
    fun anonymousHttpAndEnvelope401NeverGainAuthorityOverAnExistingOrNewLogin() = runTest {
        for (envelope in listOf(false, true)) for (authenticated in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = CoreApiTestFixture(MockEngine { request ->
                assertNull(request.headers[HttpHeaders.Authorization])
                entered.complete(Unit)
                release.await()
                if (envelope) respond("""{"code":401}""") else respond("", HttpStatusCode.Unauthorized)
            }, authenticated = authenticated)
            try {
                val pending = async { fixture.api.execute(ApiEndpoint.CHECK_VERSION) }
                entered.await()
                if (!authenticated) fixture.sessions.save(fixtureSession())
                val current = fixture.sessions.session.value
                val epoch = fixture.sessions.revision.value
                val persisted = fixture.storage.get(SessionStore.STORAGE_KEY)
                release.complete(Unit)
                assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(pending.await()).error)
                assertSame(current, fixture.sessions.session.value)
                assertEquals(epoch, fixture.sessions.revision.value)
                assertEquals(persisted, fixture.storage.get(SessionStore.STORAGE_KEY))
            } finally { release.complete(Unit); fixture.close() }
        }
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
            val fixture = CoreApiTestFixture(MockEngine { respond(body, status) })
            try {
                val error = assertIs<ApiResult.Failure>(fixture.api.execute(ApiEndpoint.USER_INFO)).error
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
    fun emptyResponseBodyIsADecodeFailureForRetainedEndpoints() = runTest {
        val fixture = CoreApiTestFixture(MockEngine { respond("") })
        try {
            assertIs<ApiError.Decode>(assertIs<ApiResult.Failure>(fixture.api.execute(ApiEndpoint.USER_PHOTO)).error)
        } finally { fixture.close() }
    }

    @Test
    fun classifiesNetworkAndTimeoutWithoutExceptionDetails() = runTest {
        val failures = listOf(
            IOException("https://fixture.invalid/login?password=fixture-secret"),
            HttpRequestTimeoutException("https://fixture.invalid/login?password=fixture-secret", 8_000),
        )
        failures.forEach { failure ->
            val fixture = CoreApiTestFixture(MockEngine { throw failure })
            try {
                val result = assertIs<ApiResult.Failure>(fixture.api.execute(ApiEndpoint.USER_INFO))
                if (failure is HttpRequestTimeoutException) assertIs<ApiError.Timeout>(result.error)
                else assertIs<ApiError.Network>(result.error)
                assertTrue(!result.error.message.contains("fixture-secret"))
            } finally { fixture.close() }
        }
    }

    @Test
    fun preservesCallerCancellationInsteadOfTurningItIntoNetworkFailure() = runTest {
        val fixture = CoreApiTestFixture(MockEngine { throw CancellationException("fixture cancellation") })
        try {
            assertFailsWith<CancellationException> { fixture.api.execute(ApiEndpoint.USER_INFO) }
        } finally { fixture.close() }
    }

    @Test
    fun serializesArraysObjectsAndUnicodeWithoutInjectingExtraParameters() = runTest {
        val objectValue = buildJsonObject { put("tag", "中文") }
        val fixture = CoreApiTestFixture(MockEngine { request ->
            assertEquals(listOf("one", "two&other=not-a-param"), request.url.parameters.getAll("tags[]"))
            assertEquals(objectValue.toString(), request.url.parameters["filter"])
            assertEquals("a+b !@#$,[] 中文", request.url.parameters["text"])
            assertNull(request.url.parameters["omitted"])
            assertNull(request.url.parameters["other"])
            respond("{\"code\":200,\"data\":{}}")
        })
        try {
            assertIs<ApiResult.Success<*>>(fixture.api.execute(ApiEndpoint.DOCUMENT, query = buildJsonObject {
                put("articleName", "用户协议")
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
        val fixture = CoreApiTestFixture(MockEngine {
            calls++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.fixture.invalid/"))
        })
        try {
            assertEquals(302, assertIs<ApiError.Http>(assertIs<ApiResult.Failure>(fixture.api.execute(ApiEndpoint.USER_INFO)).error).status)
            assertEquals(1, calls)
        } finally { fixture.close() }
    }

    @Test
    fun validatesParameterLocationAndMissingIdsBeforeAnyRequest() = runTest {
        var calls = 0
        val fixture = CoreApiTestFixture(MockEngine { calls++; error("Invalid input should stay local") })
        try {
            val failures = listOf(
                fixture.api.execute(ApiEndpoint.RUN_ADD_TRACK, body = JsonObject(emptyMap())),
                fixture.api.execute(ApiEndpoint.RUN_TASKS, queryParameters("userId" to "", "offset" to "0", "limit" to "20", "isFinish" to "0")),
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
        val fixture = CoreApiTestFixture(MockEngine { error("URL resolution never makes requests") })
        try {
            assertEquals("https://files.fixture.invalid/userfiles/image/fixture.png", fixture.api.fileUrl("image/fixture.png"))
            listOf("", "../secret", "%2e%2e/secret", "https://other.invalid/image", "image\\file", "image?q=x").forEach {
                assertNull(fixture.api.fileUrl(it))
            }
        } finally { fixture.close() }
    }
}
