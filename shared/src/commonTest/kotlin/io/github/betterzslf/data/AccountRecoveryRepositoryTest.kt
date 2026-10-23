package io.github.betterzslf.data

import io.github.betterzslf.core.net.*
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class AccountRecoveryRepositoryTest {
    @Test fun threeStagesUseTheSameBoundPostQueryContractAndLogoutAfterAck() = runTest {
        val calls = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request ->
            val path = request.url.encodedPath
            calls += path
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            when (path) {
                "/app/campusNetwork/checkPhone" -> {
                    assertEquals(setOf("phone"), request.url.parameters.names())
                    assertEquals("13800000000", request.url.parameters["phone"])
                }
                "/app/appUser/checkPhone" -> {
                    assertEquals(setOf("phone", "smsCode"), request.url.parameters.names())
                    assertEquals("000000", request.url.parameters["smsCode"])
                }
                "/app/appUser/resetNewPassWord" -> {
                    assertEquals(setOf("userId", "newPassWord"), request.url.parameters.names())
                    assertEquals("verified-fixture-target", request.url.parameters["userId"])
                    assertEquals("Fixture#1", request.url.parameters["newPassWord"])
                }
                else -> fail("Unexpected fixture path")
            }
            respond("""{"code":200,"data":{"user":{"id":"verified-fixture-target"}}}""")
        })
        try {
            val bound = assertNotNull(fixture.repositories.auth.boundToCurrentSession())
            assertIs<ApiResult.Success<*>>(bound.sendResetCode("13800000000"))
            assertIs<ApiResult.Success<*>>(bound.checkPhone("13800000000", "000000"))
            val receipt = assertIs<PasswordResetReceipt.Accepted>(bound.resetPasswordWithReceipt("verified-fixture-target", "Fixture#1"))
            assertEquals(PasswordResetLogout.CLEARED, receipt.logout)
            assertEquals(listOf("/app/campusNetwork/checkPhone", "/app/appUser/checkPhone", "/app/appUser/resetNewPassWord"), calls)
            assertNull(fixture.sessions.session.value)
            assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
            assertTrue(bound.isLoggedOut)
            assertFalse(bound.toString().contains("fixture-token"))
        } finally { fixture.close() }
    }

    @Test fun replacedTokenMakesEveryOldBoundStageLocalAndNeverRebinds() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; error("Stale authority must stay local") })
        try {
            val bound = assertNotNull(fixture.repositories.auth.boundToCurrentSession())
            fixture.sessions.save(fixtureSession("replacement-token"))
            assertFalse(bound.isBoundToCurrentSession)
            assertNull(bound.boundToCurrentSession())
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(bound.sendResetCode("13800000000")).error)
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(bound.checkPhone("13800000000", "000000")).error)
            assertIs<ApiError.Authentication>(assertIs<PasswordResetReceipt.Failed>(bound.resetPasswordWithReceipt("old-target", "Fixture#1")).error)
            bound.logout()
            assertEquals("replacement-token", fixture.sessions.session.value?.token)
            assertEquals(0, calls)
        } finally { fixture.close() }
    }

    @Test fun changedOwnerWithSameTokenAlsoStopsOldRequestsAndLogout() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; error("Old owner must stay local") })
        try {
            val bound = assertNotNull(fixture.repositories.auth.boundToCurrentSession())
            fixture.sessions.save(fixtureSession().copy(userInfo = buildJsonObject { put("id", "other-owner") }))
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(bound.sendResetCode("13800000000")).error)
            assertIs<ApiError.Authentication>(assertIs<PasswordResetReceipt.Failed>(bound.resetPasswordWithReceipt("old-target", "Fixture#1")).error)
            bound.logout()
            assertEquals("other-owner", fixture.sessions.session.value?.userId)
            assertEquals(0, calls)
        } finally { fixture.close() }
    }

    @Test fun guestKeepsAllThreeEndpointsRequiredAndSendsNothing() = runTest {
        for (endpoint in listOf(ApiEndpoint.CAMPUS_CHECK_PHONE, ApiEndpoint.CHECK_PHONE, ApiEndpoint.RESET_PASSWORD))
            assertEquals(Authentication.REQUIRED, endpoint.authentication)
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; error("Guest requests remain disabled") }, authenticated = false)
        try {
            assertNull(fixture.repositories.auth.boundToCurrentSession())
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.repositories.auth.sendResetCode("13800000000")).error)
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.repositories.auth.checkPhone("13800000000", "000000")).error)
            assertIs<ApiError.Authentication>(assertIs<PasswordResetReceipt.Failed>(fixture.repositories.auth.resetPasswordWithReceipt("fixture-target", "Fixture#1")).error)
            assertEquals(0, calls)
        } finally { fixture.close() }
    }

    @Test fun lateAckPreservesANewOwnerEvenWhenTokenStringWasReused() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val fixture = ApiTestFixture(MockEngine {
            calls++; started.complete(Unit); release.await()
            respond("""{"code":200,"data":{}}""")
        })
        try {
            val bound = assertNotNull(fixture.repositories.auth.boundToCurrentSession())
            val changing = async { bound.resetPasswordWithReceipt("verified-target", "Fixture#1") }
            started.await()
            fixture.sessions.save(fixtureSession().copy(userInfo = buildJsonObject { put("id", "new-owner") }))
            release.complete(Unit)
            assertEquals(PasswordResetLogout.NEW_SESSION_PRESERVED, assertIs<PasswordResetReceipt.Accepted>(changing.await()).logout)
            assertEquals("new-owner", fixture.sessions.session.value?.userId)
            assertNotNull(fixture.storage.get(SessionStore.STORAGE_KEY))
            assertEquals(1, calls)
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun localDeletionFailureRetainsTheServerAcknowledgementAndClearsMemory() = runTest {
        val memory = MemoryKeyValueStore()
        val storage = object : KeyValueStore {
            override fun get(key: String) = memory.get(key)
            override fun put(key: String, value: String) = memory.put(key, value)
            override fun remove(key: String) { error("fixture disk removal failure") }
            override fun clear() = memory.clear()
        }
        val sessions = SessionStore(storage).apply { save(fixtureSession()) }
        var calls = 0
        val http = HttpClient(MockEngine { calls++; respond("""{"code":200,"data":{}}""") })
        val api = ApiClient(http, sessions, ApiConfig(appBaseUrl = "https://app.fixture.invalid/app"))
        try {
            val bound = assertNotNull(AuthRepository(api, sessions).boundToCurrentSession())
            val receipt = assertIs<PasswordResetReceipt.Accepted>(bound.resetPasswordWithReceipt("verified-target", "Fixture#1"))
            assertEquals(PasswordResetLogout.CLEAR_FAILED, receipt.logout)
            assertNull(sessions.session.value)
            assertNotNull(storage.get(SessionStore.STORAGE_KEY))
            assertFalse(storage.get(SessionStore.STORAGE_KEY).orEmpty().contains("Fixture#1"))
            assertEquals(1, calls)
        } finally { api.close(); http.close() }
    }

    @Test fun weakPasswordIsLocalAndBusinessFailureDoesNotLogout() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; respond("""{"code":400,"message":"fixture rejected"}""") })
        try {
            val bound = assertNotNull(fixture.repositories.auth.boundToCurrentSession())
            assertIs<ApiError.Validation>(assertIs<PasswordResetReceipt.Failed>(bound.resetPasswordWithReceipt("verified-target", "weak")).error)
            assertEquals(0, calls)
            assertIs<ApiError.Business>(assertIs<PasswordResetReceipt.Failed>(bound.resetPasswordWithReceipt("verified-target", "Fixture#1")).error)
            assertNotNull(fixture.sessions.session.value)
            assertEquals(1, calls)
        } finally { fixture.close() }
    }

    @Test fun unknownResetEnvelopeKeepsSessionAndNeverPretendsAcknowledgement() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; respond("accepted-without-a-known-envelope") })
        try {
            val bound = assertNotNull(fixture.repositories.auth.boundToCurrentSession())
            assertIs<ApiError.Decode>(assertIs<PasswordResetReceipt.Failed>(bound.resetPasswordWithReceipt("verified-target", "Fixture#1")).error)
            assertNotNull(fixture.sessions.session.value)
            assertEquals(1, calls)
        } finally { fixture.close() }
    }

    @Test fun legacyResetRetainsItsPublicArgumentOrderAndApiResultContract() = runTest {
        val fixture = ApiTestFixture(MockEngine { request ->
            assertEquals("fixture-target", request.url.parameters["userId"])
            assertEquals("Fixture#1", request.url.parameters["newPassWord"])
            respond("""{"code":200,"data":"fixture","message":"ack"}""")
        })
        try {
            val response = assertIs<ApiResult.Success<*>>(fixture.repositories.auth.resetPassword("fixture-target", "Fixture#1"))
            assertEquals("ack", response.message)
            assertNull(fixture.sessions.session.value)
        } finally { fixture.close() }
    }
}
