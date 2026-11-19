package io.github.betterzslf.core.net

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The two login tests below moved here from shared AuthRepositoryTest with their original
 * methodnames and not fewer assertions; the seam adapts to the typed LOGIN protocol because the
 * former public success message was replaced by the finite ApiLoginOutcome (reported as a
 * deliberate non-byte change). Everything else in this file covers the seven claim/CAS race
 * semantics and the finite classification of the moved decode.
 */
class ApiLoginTest {
    @Test
    fun loginUsesPostQueryAndPersistsOnlyTheReturnedSession() = runTest {
        val fixture = CoreApiTestFixture(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("fixture-account", request.url.parameters["username"])
            assertEquals("fixture-password !@#", request.url.parameters["password"])
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("""{"code":200,"data":{"accessToken":"fixture-login-token","userInfo":{"id":"fixture-id"},"deptInfo":{"schoolName":"fixture-school"},"mapToken":"fixture-map","mapWebType":"1"},"message":"ok"}""")
        }, authenticated = false)
        try {
            val attempt = fixture.api.captureLoginAttempt()
            assertTrue(fixture.api.claimLoginAttempt(attempt))
            assertIs<ApiLoginOutcome.Accepted>(fixture.api.executeLogin(attempt, "fixture-account", "fixture-password !@#"))
            assertTrue(fixture.api.isLoginAttemptCurrent(attempt))
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
            val fixture = CoreApiTestFixture(MockEngine { respond(body) }, authenticated = false)
            try {
                val attempt = fixture.api.captureLoginAttempt()
                assertTrue(fixture.api.claimLoginAttempt(attempt))
                val outcome = fixture.api.executeLogin(attempt, "fixture", "fixture")
                assertEquals(ApiLoginIssue.MALFORMED, assertIs<ApiLoginOutcome.Unknown>(outcome).issue)
                assertNull(fixture.sessions.session.value)
                assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
            } finally { fixture.close() }
        }
    }

    @Test
    fun capturingAttemptsIsPassiveAndNeverClaimsRetiresOrStoresAnything() = runTest {
        var requests = 0
        val fixture = CoreApiTestFixture(MockEngine { requests++; error("Passive capture must not request") })
        try {
            val session = fixture.sessions.session.value
            val epoch = fixture.sessions.revision.value
            val generation = fixture.sessions.loginClaimGeneration()
            val persisted = fixture.storage.get(SessionStore.STORAGE_KEY)
            repeat(3) {
                val attempt = fixture.api.captureLoginAttempt()
                assertFalse(fixture.api.isLoginAttemptCurrent(attempt))
                assertFalse(fixture.api.cancelLoginAttempt(attempt))
                assertEquals("ApiLoginAttempt(redacted)", attempt.toString())
            }
            assertSame(session, fixture.sessions.session.value)
            assertEquals(epoch, fixture.sessions.revision.value)
            assertEquals(generation, fixture.sessions.loginClaimGeneration())
            assertEquals(persisted, fixture.storage.get(SessionStore.STORAGE_KEY))
            assertEquals(0, requests)
        } finally { fixture.close() }
    }

    @Test
    fun unclaimedCancelledForeignOrBlankAttemptsNeverReachTheNetwork() = runTest {
        var requests = 0
        val fixture = TwoClientLoginFixture(MockEngine { requests++; error("Unadmitted login must stay local") })
        try {
            val unclaimed = fixture.first.captureLoginAttempt()
            assertEquals(ApiLoginIssue.SESSION_CHANGED, assertIs<ApiLoginOutcome.Rejected>(
                fixture.first.executeLogin(unclaimed, "fixture-account", "fixture-password")).issue)

            val cancelled = fixture.first.captureLoginAttempt()
            assertTrue(fixture.first.claimLoginAttempt(cancelled))
            assertTrue(fixture.first.cancelLoginAttempt(cancelled))
            assertEquals(ApiLoginIssue.SESSION_CHANGED, assertIs<ApiLoginOutcome.Rejected>(
                fixture.first.executeLogin(cancelled, "fixture-account", "fixture-password")).issue)

            val foreign = fixture.second.captureLoginAttempt()
            assertFalse(fixture.first.claimLoginAttempt(foreign))
            assertFalse(fixture.first.cancelLoginAttempt(foreign))
            assertEquals(ApiLoginIssue.SESSION_CHANGED, assertIs<ApiLoginOutcome.Rejected>(
                fixture.first.executeLogin(foreign, "fixture-account", "fixture-password")).issue)
            // The foreign attempt's own client may still claim it: the latest store-wide claim wins.
            assertTrue(fixture.second.claimLoginAttempt(foreign))
            assertTrue(fixture.second.isLoginAttemptCurrent(foreign))

            val blank = fixture.first.captureLoginAttempt()
            assertTrue(fixture.first.claimLoginAttempt(blank))
            assertEquals(ApiLoginIssue.VALIDATION, assertIs<ApiLoginOutcome.Rejected>(
                fixture.first.executeLogin(blank, " ", "")).issue)

            assertEquals(0, requests)
            assertEquals(0L, fixture.sessions.revision.value)
            assertNull(fixture.sessions.session.value)
            assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
        } finally { fixture.close() }
    }

    @Test
    fun staleCancelsCheckOwnershipFirstAndLeaveZeroSideEffects() = runTest {
        for (kind in 0..2) {
            var requests = 0
            val storage = MemoryKeyValueStore()
            val fixture = TwoClientLoginFixture(MockEngine { requests++; error("Stale cancel must stay local") }, storage)
            try {
                val current = fixture.first.captureLoginAttempt()
                assertTrue(fixture.first.claimLoginAttempt(current))
                val stale = when (kind) {
                    0 -> fixture.first.captureLoginAttempt()
                    1 -> {
                        val retired = fixture.first.captureLoginAttempt()
                        assertTrue(fixture.first.claimLoginAttempt(retired))
                        assertTrue(fixture.first.claimLoginAttempt(current))
                        retired
                    }
                    else -> fixture.second.captureLoginAttempt()
                }
                val session = fixture.sessions.session.value
                val epoch = fixture.sessions.revision.value
                val generation = fixture.sessions.loginClaimGeneration()
                val persisted = storage.get(SessionStore.STORAGE_KEY)
                assertFalse(fixture.first.cancelLoginAttempt(stale))
                if (kind == 2) assertFalse(fixture.second.cancelLoginAttempt(stale))
                assertSame(session, fixture.sessions.session.value)
                assertEquals(epoch, fixture.sessions.revision.value)
                assertEquals(generation, fixture.sessions.loginClaimGeneration())
                assertEquals(persisted, storage.get(SessionStore.STORAGE_KEY))
                assertTrue(fixture.first.isLoginAttemptCurrent(current))
                assertEquals(0, requests)
            } finally { fixture.close() }
        }
    }

    @Test
    fun aStoreMutatedAfterClaimNeverReceivesTheLateSession() = runTest {
        val fixture = CoreApiTestFixture(MockEngine { respond(loginSuccessBody()) })
        try {
            val attempt = fixture.api.captureLoginAttempt()
            assertTrue(fixture.api.claimLoginAttempt(attempt))
            // An external invalidation between claim and dispatch retires the claim-time snapshot.
            fixture.sessions.clear()
            assertEquals(ApiLoginIssue.SESSION_CHANGED, assertIs<ApiLoginOutcome.Unknown>(
                fixture.api.executeLogin(attempt, "fixture-account", "fixture-password")).issue)
            assertNull(fixture.sessions.session.value)
            assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
        } finally { fixture.close() }
    }

    @Test
    fun lateLoginResponsesNeverOverwriteAMutatedStore() = runTest {
        for (mutation in 0..3) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = CoreApiTestFixture(MockEngine { request ->
                assertNull(request.headers[HttpHeaders.Authorization])
                entered.complete(Unit)
                release.await()
                respond(loginSuccessBody("late-fixture-token"))
            }, authenticated = false)
            try {
                val attempt = fixture.api.captureLoginAttempt()
                assertTrue(fixture.api.claimLoginAttempt(attempt))
                val pending = async { fixture.api.executeLogin(attempt, "fixture-account", "fixture-password") }
                entered.await()
                when (mutation) {
                    0 -> fixture.sessions.save(fixtureSession())
                    1 -> { fixture.sessions.save(fixtureSession()); fixture.sessions.clear() }
                    2 -> { fixture.sessions.save(fixtureSession()); fixture.sessions.save(fixtureSession()) }
                    else -> fixture.sessions.save(fixtureSession().copy(mapToken = "changed-fixture-map", mapWebType = "2"))
                }
                val current = fixture.sessions.session.value
                val epoch = fixture.sessions.revision.value
                val persisted = fixture.storage.get(SessionStore.STORAGE_KEY)
                release.complete(Unit)
                assertEquals(ApiLoginIssue.SESSION_CHANGED, assertIs<ApiLoginOutcome.Unknown>(pending.await()).issue)
                assertSame(current, fixture.sessions.session.value)
                assertEquals(epoch, fixture.sessions.revision.value)
                assertEquals(persisted, fixture.storage.get(SessionStore.STORAGE_KEY))
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test
    fun anAcceptedContinuationIsConsumedExactlyOnce() = runTest {
        val fixture = CoreApiTestFixture(MockEngine { respond(loginSuccessBody()) }, authenticated = false)
        try {
            val attempt = fixture.api.captureLoginAttempt()
            assertTrue(fixture.api.claimLoginAttempt(attempt))
            assertIs<ApiLoginOutcome.Accepted>(fixture.api.executeLogin(attempt, "fixture-account", "fixture-password"))
            assertTrue(fixture.api.isLoginAttemptCurrent(attempt))
            assertTrue(fixture.api.consumeLoginContinuation(attempt))
            assertFalse(fixture.api.isLoginAttemptCurrent(attempt))
            assertFalse(fixture.api.consumeLoginContinuation(attempt))
            // Cancelling an accepted attempt ends it before consumption; the continuation is lost.
            val cancelled = fixture.api.captureLoginAttempt()
            assertTrue(fixture.api.claimLoginAttempt(cancelled))
            assertIs<ApiLoginOutcome.Accepted>(fixture.api.executeLogin(cancelled, "fixture-account", "fixture-password"))
            assertTrue(fixture.api.cancelLoginAttempt(cancelled))
            assertFalse(fixture.api.consumeLoginContinuation(cancelled))
        } finally { fixture.close() }
    }

    @Test
    fun aNewSubmitRetiresAnOldAcceptedContinuationAcrossClients() = runTest {
        for (crossClient in listOf(false, true)) {
            val fixture = TwoClientLoginFixture(MockEngine { respond(loginSuccessBody()) })
            try {
                val older = fixture.first.captureLoginAttempt()
                assertTrue(fixture.first.claimLoginAttempt(older))
                assertIs<ApiLoginOutcome.Accepted>(fixture.first.executeLogin(older, "fixture-account", "fixture-password"))
                assertTrue(fixture.first.isLoginAttemptCurrent(older))
                val newer = if (crossClient) fixture.second else fixture.first
                val fresh = newer.captureLoginAttempt()
                assertTrue(newer.claimLoginAttempt(fresh))
                assertFalse(fixture.first.isLoginAttemptCurrent(older))
                assertFalse(fixture.first.consumeLoginContinuation(older))
                assertTrue(newer.isLoginAttemptCurrent(fresh))
            } finally { fixture.close() }
        }
    }

    @Test
    fun outcomeClassificationMatchesTheKnownRefusalAndUncertainFamilies() = runTest {
        for ((engine, expected) in listOf(
            MockEngine { respond("{\"code\":400,\"data\":\"\",\"message\":\"fixture error\"}") } to
                (ApiLoginIssue.BUSINESS to true),
            MockEngine { respond("", HttpStatusCode.Unauthorized) } to
                (ApiLoginIssue.AUTHENTICATION to true),
            MockEngine { respond("{\"code\":401}") } to
                (ApiLoginIssue.AUTHENTICATION to true),
            MockEngine { respond("<server stack trace>", HttpStatusCode.InternalServerError) } to
                (ApiLoginIssue.HTTP to false),
            MockEngine { throw HttpRequestTimeoutException("https://fixture.invalid/login?password=fixture-secret", 8_000) } to
                (ApiLoginIssue.TIMEOUT to false),
            MockEngine { throw IOException("https://fixture.invalid/login?password=fixture-secret") } to
                (ApiLoginIssue.NETWORK to false),
            MockEngine { respond("malformed") } to
                (ApiLoginIssue.MALFORMED to false),
        )) {
            val (issue, rejected) = expected
            val fixture = CoreApiTestFixture(engine)
            try {
                val attempt = fixture.api.captureLoginAttempt()
                assertTrue(fixture.api.claimLoginAttempt(attempt))
                val outcome = fixture.api.executeLogin(attempt, "fixture-account", "fixture-password")
                if (rejected) assertEquals(issue, assertIs<ApiLoginOutcome.Rejected>(outcome).issue)
                else assertEquals(issue, assertIs<ApiLoginOutcome.Unknown>(outcome).issue)
                // An anonymous login's refusal never gains authority over an existing session.
                assertNotNull(fixture.sessions.session.value)
            } finally { fixture.close() }
        }

        // A storage write failure over a granted session stays Unknown(STORAGE) and publishes nothing.
        val backing = MemoryKeyValueStore()
        val storage = object : KeyValueStore by backing {
            override fun put(key: String, value: String) { error("fixture storage write failure") }
        }
        val failing = TwoClientLoginFixture(MockEngine { respond(loginSuccessBody()) }, storage)
        try {
            val attempt = failing.first.captureLoginAttempt()
            assertTrue(failing.first.claimLoginAttempt(attempt))
            val outcome = failing.first.executeLogin(attempt, "fixture-account", "fixture-password")
            assertEquals(ApiLoginIssue.STORAGE, assertIs<ApiLoginOutcome.Unknown>(outcome).issue)
            assertNull(failing.sessions.session.value)
            assertEquals(0L, failing.sessions.revision.value)
            assertNull(backing.get(SessionStore.STORAGE_KEY))
        } finally { failing.close() }
    }
}

/** ApiLoginTest-only fixture: two clients over one store, for cross-client claim races. */
private class TwoClientLoginFixture(engine: MockEngine, val storage: KeyValueStore = MemoryKeyValueStore()) {
    val sessions = SessionStore(storage)
    private val http = HttpClient(engine)
    val first = ApiClient(http, sessions, loginTestApiConfig())
    val second = ApiClient(http, sessions, loginTestApiConfig())
    fun close() { first.close(); second.close(); http.close() }
}

private fun loginTestApiConfig() = ApiConfig(
    appBaseUrl = "https://app.fixture.invalid/app",
    runBaseUrl = "https://run.fixture.invalid",
    filesBaseUrl = "https://files.fixture.invalid/userfiles/",
)

private fun loginSuccessBody(token: String = "typed-fixture-token") =
    """{"code":200,"data":{"accessToken":"$token","userInfo":{"id":"typed-fixture-owner"},"deptInfo":{"schoolName":"fixture-school"},"mapToken":"typed-fixture-map","mapWebType":"1"},"message":"ok"}"""
