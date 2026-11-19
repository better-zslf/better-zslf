package io.github.betterzslf.feature.accountrecovery

import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.Authentication
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ApiAccountRecoverySourceTest {
    private val target = assertNotNull(RecoveryTarget.fromVerifiedId("verified-target"))
    private val ack = """{"code":200,"data":{}}"""

    @Test fun captureAndStatusReadsAreLocalAndGuestCannotCaptureAnyAuthority() = runTest {
        var requests = 0
        val fixture = RecoveryApiFixture(MockEngine { requests++; error("Construction must remain local") }, authenticated = false)
        try {
            assertNull(ApiAccountRecoverySource.capture(fixture.api, fixture.sessions))
            for (endpoint in listOf(ApiEndpoint.CAMPUS_CHECK_PHONE, ApiEndpoint.CHECK_PHONE, ApiEndpoint.RESET_PASSWORD))
                assertEquals(Authentication.REQUIRED, endpoint.authentication)
            fixture.sessions.save(recoveryFixtureSession())
            val first = fixture.source()
            val second = fixture.source()
            assertNotSame(first, second)
            assertTrue(first.isCurrentSession())
            assertFalse(first.isLoggedOut())
            assertEquals("ApiAccountRecoverySource(redacted)", first.toString())
            assertFalse(first.toString().contains("fixture-token"))
            assertEquals(0, requests)
        } finally { fixture.close() }
    }

    @Test fun threeStagesUseExactProtectedPostQueryAndOnlyVerifiedTargetIsReset() = runTest {
        val calls = mutableListOf<String>()
        val fixture = RecoveryApiFixture(MockEngine { request ->
            calls += request.url.encodedPath
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath) {
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
                    assertEquals("verified-target", request.url.parameters["userId"])
                    assertEquals("Fixture#1", request.url.parameters["newPassWord"])
                }
                else -> fail("Unexpected endpoint")
            }
            respond("""{"code":200,"data":{"user":{"id":"verified-target"}},"message":"fixture-secret-server-text"}""")
        })
        try {
            val source = fixture.source()
            assertEquals(RecoverySmsOutcome.Accepted, source.sendCode("13800000000"))
            val verified = assertIs<RecoveryVerifyOutcome.Verified>(source.verify("13800000000", "000000"))
            assertEquals(target.id, verified.target.id)
            val result = source.reset(verified.target, "Fixture#1")
            assertEquals(RecoveryResetOutcome.Accepted(RecoveryLogout.CLEARED), result)
            assertEquals(listOf("/app/campusNetwork/checkPhone", "/app/appUser/checkPhone", "/app/appUser/resetNewPassWord"), calls)
            assertFalse(result.toString().contains("fixture-secret-server-text"))
            assertTrue(source.isLoggedOut())
            assertFalse(source.isCurrentSession())
            assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
        } finally { fixture.close() }
    }

    @Test fun everyTransportErrorMapsToOnlyTheFixedIssueAndNeverRawServerText() {
        val errors = listOf(
            ApiError.Authentication("fixture-secret") to RecoveryIssue.AUTHENTICATION,
            ApiError.Business(456, "fixture-secret") to RecoveryIssue.BUSINESS,
            ApiError.Validation(listOf("fixture-secret"), "fixture-secret") to RecoveryIssue.VALIDATION,
            ApiError.Timeout("fixture-secret") to RecoveryIssue.TIMEOUT,
            ApiError.Network("fixture-secret") to RecoveryIssue.NETWORK,
            ApiError.Http(503, "fixture-secret") to RecoveryIssue.HTTP,
            ApiError.Decode("fixture-secret") to RecoveryIssue.DECODE,
            ApiError.Storage("fixture-secret") to RecoveryIssue.STORAGE,
        )
        assertEquals(RecoveryIssue.entries.toSet(), errors.map { it.second }.toSet())
        for ((error, expected) in errors) {
            assertEquals(expected, error.toRecoveryIssue())
            assertFalse(error.toRecoveryIssue().toString().contains("fixture-secret"))
        }
    }

    @Test fun businessRejectionAndUnknownEnvelopeHaveDifferentWriteOutcomesWithoutRetryOrLogout() = runTest {
        for ((body, expectedIssue) in listOf(
            """{"code":400,"message":"fixture-secret"}""" to RecoveryIssue.BUSINESS,
            "not-an-envelope" to RecoveryIssue.DECODE,
        )) {
            var requests = 0
            val fixture = RecoveryApiFixture(MockEngine { requests++; respond(body) })
            try {
                val source = fixture.source()
                val sms = source.sendCode("13800000000")
                val reset = source.reset(target, "Fixture#1")
                if (expectedIssue == RecoveryIssue.BUSINESS) {
                    assertEquals(RecoverySmsOutcome.Rejected(expectedIssue), sms)
                    assertEquals(RecoveryResetOutcome.Rejected(expectedIssue), reset)
                } else {
                    assertEquals(RecoverySmsOutcome.Unknown(expectedIssue), sms)
                    assertEquals(RecoveryResetOutcome.Unknown(expectedIssue), reset)
                }
                assertEquals(2, requests)
                assertNotNull(fixture.sessions.session.value)
                assertFalse((sms.toString() + reset).contains("fixture-secret"))
            } finally { fixture.close() }
        }
    }

    @Test fun httpAndNetworkFailuresStayUnknownAndVerificationGetsOnlyTypedFailure() = runTest {
        for (status in listOf(503, 302)) {
            var requests = 0
            val fixture = RecoveryApiFixture(MockEngine { requests++; respond("fixture-secret", HttpStatusCode.fromValue(status)) })
            try {
                val source = fixture.source()
                assertEquals(RecoverySmsOutcome.Unknown(RecoveryIssue.HTTP), source.sendCode("13800000000"))
                assertEquals(RecoveryVerifyOutcome.Failed(RecoveryIssue.HTTP), source.verify("13800000000", "000000"))
                assertEquals(RecoveryResetOutcome.Unknown(RecoveryIssue.HTTP), source.reset(target, "Fixture#1"))
                assertEquals(3, requests)
                assertNotNull(fixture.sessions.session.value)
            } finally { fixture.close() }
        }
        val fixture = RecoveryApiFixture(MockEngine { error("fixture-secret-credential-query") })
        try {
            val source = fixture.source()
            assertEquals(RecoverySmsOutcome.Unknown(RecoveryIssue.NETWORK), source.sendCode("13800000000"))
            assertEquals(RecoveryResetOutcome.Unknown(RecoveryIssue.NETWORK), source.reset(target, "Fixture#1"))
        } finally { fixture.close() }
    }

    @Test fun everyReplacementEpochStopsAllOldStagesLocallyAndNeverRebinds() = runTest {
        for (mutation in 0..4) {
            var requests = 0
            val fixture = RecoveryApiFixture(MockEngine { requests++; error("Old authority must stay local") })
            try {
                val source = fixture.source()
                val original = assertNotNull(fixture.sessions.session.value)
                when (mutation) {
                    0 -> fixture.sessions.save(original.copy())
                    1 -> { fixture.sessions.clear(); fixture.sessions.save(original) }
                    2 -> fixture.sessions.save(original.copy(mapToken = "replacement-map", mapWebType = "2"))
                    3 -> fixture.sessions.save(recoveryFixtureSession(token = "replacement-token"))
                    else -> fixture.sessions.save(recoveryFixtureSession(owner = "replacement-owner"))
                }
                val current = fixture.sessions.session.value
                val epoch = fixture.sessions.revision.value
                val stored = fixture.storage.get(SessionStore.STORAGE_KEY)
                assertFalse(source.isCurrentSession())
                assertEquals(RecoverySmsOutcome.Unknown(RecoveryIssue.AUTHENTICATION), source.sendCode("13800000000"))
                assertEquals(RecoveryVerifyOutcome.Failed(RecoveryIssue.AUTHENTICATION), source.verify("13800000000", "000000"))
                assertEquals(RecoveryResetOutcome.Unknown(RecoveryIssue.AUTHENTICATION), source.reset(target, "Fixture#1"))
                assertSame(current, fixture.sessions.session.value)
                assertEquals(epoch, fixture.sessions.revision.value)
                assertEquals(stored, fixture.storage.get(SessionStore.STORAGE_KEY))
                assertTrue(fixture.source().isCurrentSession())
                assertFalse(source.isCurrentSession())
                assertEquals(0, requests)
            } finally { fixture.close() }
        }
    }

    @Test fun lateAcceptedResetPreservesEveryNewEpochEvenWithIdenticalCredentials() = runTest {
        for (mutation in 0..4) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var writes = 0
            val fixture = RecoveryApiFixture(MockEngine { writes++; entered.complete(Unit); release.await(); respond(ack) })
            try {
                val source = fixture.source()
                val pending = async { source.reset(target, "Fixture#1") }
                entered.await()
                val original = assertNotNull(fixture.sessions.session.value)
                when (mutation) {
                    0 -> fixture.sessions.save(original.copy())
                    1 -> { fixture.sessions.clear(); fixture.sessions.save(original) }
                    2 -> fixture.sessions.save(original.copy(mapToken = "replacement-map"))
                    3 -> fixture.sessions.save(recoveryFixtureSession(token = "replacement-token"))
                    else -> fixture.sessions.save(recoveryFixtureSession(owner = "replacement-owner"))
                }
                val current = fixture.sessions.session.value
                val epoch = fixture.sessions.revision.value
                val stored = fixture.storage.get(SessionStore.STORAGE_KEY)
                release.complete(Unit)
                assertEquals(RecoveryResetOutcome.Accepted(RecoveryLogout.NEW_SESSION_PRESERVED), pending.await())
                assertSame(current, fixture.sessions.session.value)
                assertEquals(epoch, fixture.sessions.revision.value)
                assertEquals(stored, fixture.storage.get(SessionStore.STORAGE_KEY))
                assertFalse(source.isCurrentSession())
                assertEquals(1, writes)
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test fun acknowledgedResetRetainsAcceptedWhenCleanupThrowsIncludingCancellation() = runTest {
        for (cancelCleanup in listOf(false, true)) {
            val backing = MemoryKeyValueStore()
            val store = object : KeyValueStore {
                override fun get(key: String) = backing.get(key)
                override fun put(key: String, value: String) = backing.put(key, value)
                override fun remove(key: String) {
                    if (cancelCleanup) throw CancellationException("fixture-cleanup-cancel")
                    error("fixture-disk-delete")
                }
                override fun clear() = backing.clear()
            }
            var writes = 0
            val fixture = RecoveryApiFixture(MockEngine { writes++; respond(ack) }, storage = store)
            try {
                val source = fixture.source()
                assertEquals(RecoveryResetOutcome.Accepted(RecoveryLogout.CLEAR_FAILED), source.reset(target, "Fixture#1"))
                assertNull(fixture.sessions.session.value)
                assertTrue(source.isLoggedOut())
                assertFalse(source.isCurrentSession())
                assertNotNull(fixture.storage.get(SessionStore.STORAGE_KEY))
                assertEquals(1, writes)
            } finally { fixture.close() }
        }
    }

    @Test fun inFlightCancellationPropagatesWithoutCleanupOrHiddenRetry() = runTest {
        for (reset in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var requests = 0
            val fixture = RecoveryApiFixture(MockEngine { requests++; entered.complete(Unit); release.await(); respond(ack) })
            try {
                val source = fixture.source()
                var returned = false
                val job = launch {
                    if (reset) source.reset(target, "Fixture#1") else source.sendCode("13800000000")
                    returned = true
                }
                entered.await(); job.cancelAndJoin()
                assertFalse(returned)
                assertTrue(source.isCurrentSession())
                assertNotNull(fixture.sessions.session.value)
                assertEquals(1, requests)
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test fun timeoutRemainsUnknownWhileAuthenticationFailureRetiresOnlyItsOwnSession() = runTest {
        var timedOutCalls = 0
        val timeout = RecoveryApiFixture(MockEngine {
            timedOutCalls++
            throw HttpRequestTimeoutException("https://app.fixture.invalid/app/reset?newPassWord=fixture-secret", 8_000)
        })
        try {
            val source = timeout.source()
            assertEquals(RecoverySmsOutcome.Unknown(RecoveryIssue.TIMEOUT), source.sendCode("13800000000"))
            assertEquals(RecoveryResetOutcome.Unknown(RecoveryIssue.TIMEOUT), source.reset(target, "Fixture#1"))
            assertEquals(2, timedOutCalls)
            assertTrue(source.isCurrentSession())
        } finally { timeout.close() }
        for (sms in listOf(false, true)) {
            var calls = 0
            val fixture = RecoveryApiFixture(MockEngine { calls++; respond("", HttpStatusCode.Unauthorized) })
            try {
                val source = fixture.source()
                if (sms) assertEquals(RecoverySmsOutcome.Unknown(RecoveryIssue.AUTHENTICATION), source.sendCode("13800000000"))
                else assertEquals(RecoveryResetOutcome.Unknown(RecoveryIssue.AUTHENTICATION), source.reset(target, "Fixture#1"))
                assertTrue(source.isLoggedOut())
                assertFalse(source.isCurrentSession())
                assertNull(fixture.sessions.session.value)
                assertEquals(1, calls)
            } finally { fixture.close() }
        }
    }

    @Test fun weakPasswordAndMissingParametersRejectLocallyWithoutAProtectedRequest() = runTest {
        var requests = 0
        val fixture = RecoveryApiFixture(MockEngine { requests++; error("Validation must remain local") })
        try {
            val source = fixture.source()
            assertEquals(RecoveryResetOutcome.Rejected(RecoveryIssue.VALIDATION), source.reset(target, "weak"))
            assertEquals(RecoverySmsOutcome.Rejected(RecoveryIssue.VALIDATION), source.sendCode(""))
            assertEquals(RecoveryVerifyOutcome.Failed(RecoveryIssue.VALIDATION), source.verify("13800000000", ""))
            assertNotNull(fixture.sessions.session.value)
            assertEquals(0, requests)
        } finally { fixture.close() }
    }
}
