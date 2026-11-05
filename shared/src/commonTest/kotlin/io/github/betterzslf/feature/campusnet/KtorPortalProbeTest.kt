package io.github.betterzslf.feature.campusnet

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private const val PORTAL_HOST = "http://9.9.9.9"

class KtorPortalProbeTest {

    private fun probe(handler: MockEngine) = KtorPortalProbe(HttpClient(handler))

    @Test fun aThreeOhTwoYieldsTheLocationAndNeverTheBody() = runTest {
        var requestedUserAgent: String? = null
        val client = probe(MockEngine { request ->
            requestedUserAgent = request.headers[HttpHeaders.UserAgent]
            respond("this body must never be read", HttpStatusCode.Found,
                headersOf(HttpHeaders.Location, "$PORTAL_HOST/a/wifi?wlanuserip=10.0.0.2&wlanacip=10.0.0.1"))
        })
        try {
            val result = client.probe(PORTAL_HOST)
            val answered = assertIs<PortalProbeResult.Answered>(result)
            assertTrue(answered.redirected)
            assertEquals("$PORTAL_HOST/a/wifi?wlanuserip=10.0.0.2&wlanacip=10.0.0.1", answered.url)
            // The reference plugin sends a browser UA; portals may not redirect otherwise.
            assertEquals(PORTAL_PROBE_USER_AGENT, requestedUserAgent)
        } finally { client.close() }
    }

    @Test fun aDirectAnswerKeepsTheAddressInsteadOfFailing() = runTest {
        // Non-3xx is not an error: the portal simply answered without bouncing us.
        val client = probe(MockEngine { respond("ok", HttpStatusCode.OK) })
        try {
            val answered = assertIs<PortalProbeResult.Answered>(client.probe(PORTAL_HOST))
            assertFalse(answered.redirected)
            assertEquals(PORTAL_HOST, answered.url)
        } finally { client.close() }
    }

    @Test fun portalIdentityHeadersAreCapturedForTheCarrierDialer() = runTest {
        // The live gateway rejects ticket.cgi unless these are echoed back; they come from here.
        val client = probe(MockEngine {
            respond("", HttpStatusCode.OK, headersOf(
                "schoolid" to listOf("fixture-school"), "domain" to listOf("fixture.domain"),
                "area" to listOf("fixture-area")))
        })
        try {
            val answered = assertIs<PortalProbeResult.Answered>(client.probe(PORTAL_HOST))
            assertEquals("fixture-school", answered.schoolId)
            assertEquals("fixture.domain", answered.domain)
            assertEquals("fixture-area", answered.area)
        } finally { client.close() }
    }

    @Test fun aPortalWithoutIdentityHeadersReportsEmptyRatherThanGuessing() = runTest {
        val client = probe(MockEngine { respond("", HttpStatusCode.OK) })
        try {
            val answered = assertIs<PortalProbeResult.Answered>(client.probe(PORTAL_HOST))
            assertEquals("", answered.schoolId)
            assertEquals("", answered.domain)
            assertEquals("", answered.area)
        } finally { client.close() }
    }

    @Test fun aRedirectWithoutLocationIsReportedRatherThanTreatedAsSuccess() = runTest {
        val client = probe(MockEngine { respond("", HttpStatusCode.MovedPermanently) })
        try {
            assertEquals(PortalProbeResult.MissingLocation, client.probe(PORTAL_HOST))
            assertEquals(PortalProbeResult.MissingLocation, client.probe(PORTAL_HOST))
        } finally { client.close() }
    }

    @Test fun aBlockedAddressIsRefusedBeforeAnyRequestLeaves() = runTest {
        var calls = 0
        val client = probe(MockEngine { calls++; respond("ok", HttpStatusCode.OK) })
        try {
            assertEquals(PortalProbeResult.Blocked, client.probe("http://10.0.0.1"))
            assertEquals(PortalProbeResult.Blocked, client.probe("http://9.9.9.9.evil.example"))
            assertEquals(PortalProbeResult.Blocked, client.probe("ftp://9.9.9.9"))
            // The allowlist is enforced before the request, so nothing was sent.
            assertEquals(0, calls)
        } finally { client.close() }
    }

    @Test fun aThrowingEngineIsUnreachableNotAnException() = runTest {
        val client = probe(MockEngine { throw kotlinx.io.IOException("boom") })
        try {
            assertEquals(PortalProbeResult.Unreachable, client.probe(PORTAL_HOST))
        } finally { client.close() }
    }

    @Test fun afterCloseNothingIsProbed() = runTest {
        var calls = 0
        val client = probe(MockEngine { calls++; respond("ok", HttpStatusCode.OK) })
        client.close()
        client.close() // idempotent
        assertEquals(PortalProbeResult.Unreachable, client.probe(PORTAL_HOST))
        assertEquals(0, calls)
    }
}
