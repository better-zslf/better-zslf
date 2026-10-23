package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryTest {
    @Test
    fun loginUsesPostQueryAndPersistsOnlyTheReturnedSession() = runTest {
        val fixture = ApiTestFixture(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("fixture-account", request.url.parameters["username"])
            assertEquals("fixture-password !@#", request.url.parameters["password"])
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("""{"code":200,"data":{"accessToken":"fixture-login-token","userInfo":{"id":"fixture-id"},"deptInfo":{"schoolName":"fixture-school"},"mapToken":"fixture-map","mapWebType":"1"},"message":"ok"}""")
        }, authenticated = false)
        try {
            val result = assertIs<ApiResult.Success<*>>(fixture.repositories.auth.login("fixture-account", "fixture-password !@#"))
            assertEquals("ok", result.message)
            assertEquals("fixture-login-token", fixture.sessions.session.value?.token)
            assertEquals("fixture-map", fixture.sessions.session.value?.mapToken)
            assertEquals("1", fixture.sessions.session.value?.mapWebType)
            val persisted = assertNotNull(fixture.storage.get(SessionStore.STORAGE_KEY))
            assertFalse(persisted.contains("fixture-account"))
            assertFalse(persisted.contains("fixture-password"))
            assertEquals(fixture.sessions.session.value, SessionStore(fixture.storage).session.value)
        } finally { fixture.close() }
    }

    @Test
    fun malformedLoginDoesNotCreateASession() = runTest {
        for (body in listOf(
            "{\"code\":200,\"data\":{\"accessToken\":\"fixture\"}}",
            "{\"code\":200,\"data\":\"\"}",
            "{\"code\":200,\"data\":{\"accessToken\":\"\",\"userInfo\":{},\"deptInfo\":{}}}",
        )) {
            val fixture = ApiTestFixture(MockEngine { respond(body) }, authenticated = false)
            try {
                assertIs<ApiError.Decode>(assertIs<ApiResult.Failure>(fixture.repositories.auth.login("fixture", "fixture")).error)
                assertNull(fixture.sessions.session.value)
                assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
            } finally { fixture.close() }
        }
    }

    @Test
    fun successfulPasswordChangeLogsOutAndFailedChangeRetainsSession() = runTest {
        for (code in listOf(200, 400)) {
            val fixture = ApiTestFixture(MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("Fixture#1", request.url.parameters["newPassWord"])
                assertEquals("fixture-old", request.url.parameters["oldPassword"])
                respond("{\"code\":$code,\"data\":\"\",\"message\":\"fixture\"}")
            })
            try {
                val result = fixture.repositories.auth.modifyPassword("fixture-old", "Fixture#1")
                if (code == 200) {
                    assertIs<ApiResult.Success<*>>(result)
                    assertNull(fixture.sessions.session.value)
                    assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
                } else {
                    assertIs<ApiError.Business>(assertIs<ApiResult.Failure>(result).error)
                    assertNotNull(fixture.sessions.session.value)
                }
            } finally { fixture.close() }
        }
    }

    @Test
    fun newPasswordPolicyIsLocalButOldLoginPasswordsAreNotRejected() = runTest {
        assertTrue(AuthRepository.isValidNewPassword("Fixture#1"))
        listOf("short", "onlyletters", "123456789!", "Letters123", "Ab1! abcdef").forEach {
            assertFalse(AuthRepository.isValidNewPassword(it))
        }
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; respond("{\"code\":400,\"message\":\"fixture\"}") })
        try {
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(fixture.repositories.auth.resetPassword("fixture-id", "weak")).error)
            assertEquals(0, calls)
            assertIs<ApiError.Business>(assertIs<ApiResult.Failure>(fixture.repositories.auth.login("fixture", "sixold")).error)
            assertEquals(1, calls)
        } finally { fixture.close() }
    }

    @Test
    fun loggedOutPasswordResetDoesNotAttemptAProtectedRequest() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; error("Protected reset should stay local") }, authenticated = false)
        try {
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.repositories.auth.resetPassword("fixture-id", "Fixture#1")).error)
            assertEquals(0, calls)
        } finally { fixture.close() }
    }
}
