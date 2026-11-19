package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiLoginIssue
import io.github.betterzslf.core.net.ApiLoginOutcome
import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two pure login tests moved into core ApiLoginTest with their original methodnames. The
 * remaining three tests keep their names here; the mixed login half below drives the real typed
 * core LOGIN protocol because AuthRepository no longer owns a login path.
 */
class AuthRepositoryTest {
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
            // The login half now drives the real typed core LOGIN protocol: an old-policy password
            // is never rejected locally, the school's refusal stays a finite BUSINESS issue, and
            // exactly one HTTP request is made.
            val attempt = fixture.api.captureLoginAttempt()
            assertTrue(fixture.api.claimLoginAttempt(attempt))
            val outcome = fixture.api.executeLogin(attempt, "fixture", "sixold")
            assertEquals(ApiLoginIssue.BUSINESS, assertIs<ApiLoginOutcome.Rejected>(outcome).issue)
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
