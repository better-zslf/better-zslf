package io.github.betterzslf.feature.auth

import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adapter finite-mapping and lifecycle tests only: the decode rules, the claim/CAS race
 * semantics and the store-wide nonce live in core's own ApiLoginTest. These tests pin that the
 * feature never sees anything beyond its finite states.
 */
class ApiCampusLoginSourceTest {
    @Test
    fun captureIsPassiveAndHoldsOneFixedCoreAttempt() = runTest {
        var requests = 0
        val fixture = CampusLoginApiFixture(MockEngine { requests++; error("Passive capture must not request") })
        try {
            val source = fixture.source()
            assertFalse(source.isCurrent())
            source.cancel()   // cancelling an unclaimed source is a silent no-op
            assertFalse(source.isCurrent())
            assertTrue(source.claim())
            // A source that re-captured per call would fail here: its own claim must be current.
            assertTrue(source.isCurrent())
            assertEquals(0, requests)
        } finally { fixture.close() }
    }

    @Test
    fun submitMapsCoreAcceptanceAndEstablishesTheStoreSession() = runTest {
        val fixture = CampusLoginApiFixture(MockEngine { request ->
            assertEquals("campus-fixture-account", request.url.parameters["username"])
            assertNull(request.headers[HttpHeaders.Authorization])
            respond(CAMPUS_LOGIN_SUCCESS_BODY)
        })
        try {
            val source = fixture.source()
            assertTrue(source.claim())
            assertIs<CampusLoginOutcome.Accepted>(source.submit("campus-fixture-account", "fixture-password !@#"))
            assertEquals("campus-fixture-token", fixture.sessions.session.value?.token)
            assertEquals("campus-fixture-map", fixture.sessions.session.value?.mapToken)
            assertEquals(fixture.sessions.session.value, SessionStore(fixture.storage).session.value)
        } finally { fixture.close() }
    }

    @Test
    fun submitNeverLeavesTheFiniteIssueEnum() = runTest {
        for ((engine, expected) in listOf(
            MockEngine { respond("{\"code\":400,\"data\":\"\",\"message\":\"wrong password fixture\"}") } to
                (CampusLoginIssue.BUSINESS to true),
            MockEngine { respond("", HttpStatusCode.Unauthorized) } to
                (CampusLoginIssue.AUTHENTICATION to true),
            MockEngine { respond("{\"code\":200,\"data\":{\"accessToken\":\"fixture\"}}") } to
                (CampusLoginIssue.MALFORMED to false),
            MockEngine { throw HttpRequestTimeoutException("https://fixture.invalid/login", 8_000) } to
                (CampusLoginIssue.TIMEOUT to false),
        )) {
            val (issue, rejected) = expected
            val fixture = CampusLoginApiFixture(engine)
            try {
                val source = fixture.source()
                assertTrue(source.claim())
                val outcome = source.submit("fixture-account", "fixture-password")
                if (rejected) assertEquals(issue, assertIs<CampusLoginOutcome.Rejected>(outcome).issue)
                else assertEquals(issue, assertIs<CampusLoginOutcome.Unknown>(outcome).issue)
                assertNull(fixture.sessions.session.value)
            } finally { fixture.close() }
        }
    }

    @Test
    fun anUnclaimedOrCancelledSourceNeverReachesTheNetwork() = runTest {
        var requests = 0
        val fixture = CampusLoginApiFixture(MockEngine { requests++; error("Unadmitted login must stay local") })
        try {
            val unclaimed = fixture.source()
            assertEquals(CampusLoginIssue.SESSION_CHANGED,
                assertIs<CampusLoginOutcome.Rejected>(unclaimed.submit("fixture-account", "fixture-password")).issue)
            assertEquals(0, requests)

            val cancelled = fixture.source()
            assertTrue(cancelled.claim())
            cancelled.cancel()
            assertEquals(CampusLoginIssue.SESSION_CHANGED,
                assertIs<CampusLoginOutcome.Rejected>(cancelled.submit("fixture-account", "fixture-password")).issue)
            assertEquals(0, requests)
            assertNull(fixture.sessions.session.value)
        } finally { fixture.close() }
    }

    @Test
    fun anAcceptedContinuationIsConsumedExactlyOnceThroughTheAdapter() = runTest {
        val fixture = CampusLoginApiFixture(MockEngine { respond(CAMPUS_LOGIN_SUCCESS_BODY) })
        try {
            val source = fixture.source()
            assertTrue(source.claim())
            assertIs<CampusLoginOutcome.Accepted>(source.submit("fixture-account", "fixture-password"))
            assertTrue(source.isCurrent())
            assertTrue(source.consumeAcceptedContinuation())
            assertFalse(source.isCurrent())
            assertFalse(source.consumeAcceptedContinuation())
        } finally { fixture.close() }
    }

    @Test
    fun aNewerClaimRetiresAnOlderSourceContinuation() = runTest {
        val fixture = CampusLoginApiFixture(MockEngine { respond(CAMPUS_LOGIN_SUCCESS_BODY) })
        try {
            val older = fixture.source()
            assertTrue(older.claim())
            assertIs<CampusLoginOutcome.Accepted>(older.submit("fixture-account", "fixture-password"))
            val newer = fixture.source()
            assertTrue(newer.claim())
            assertFalse(older.isCurrent())
            assertFalse(older.consumeAcceptedContinuation())
            assertTrue(newer.isCurrent())
        } finally { fixture.close() }
    }
}
