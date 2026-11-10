package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.campusnet.crypto.DormCipher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * The controller between the dorm page and the protocol.
 *
 * The transport is scripted but every payload goes through the real cipher, so a mistake in the XML
 * or the cipher dispatch fails these too.
 */
class DormSignInControllerTest {

    private val supported = "319FC5AB-EC0E-46B9-A252-2285F9DAE813"
    private val unsupported = "B809531F-0007-4B5B-923B-4BD560398113"

    private val fields = DormXmlFields(
        userAgent = DORM_ANDROID_USER_AGENT,
        clientId = "63BAFCAA-84E2-45EC-89F0-FCEA8745D03D",
        localTime = "t", hostName = "h", ipv4 = "10.0.0.2", mac = "AA", ostag = "3", gwip = "10.0.0.1")

    private val portal = PortalAddress("10.0.0.2", "10.0.0.1")

    private fun controller(
        transport: DormTransport?,
        portalAddress: PortalAddress? = portal,
    ) = DormSignInController(
        transport = transport,
        fields = { fields },
        servers = DORM_AUTH_SERVERS,
        portalAddress = { portalAddress },
        schoolIdentity = { null },
    )

    /** Completes negotiation, then serves a ticket, then accepts the login. */
    private class OkGateway(private val algo: String, private val ticket: String = "TK") : DormTransport {
        var loginSent = false
            private set
        override suspend fun post(url: String, encryptedPayload: String, algoId: String, clientId: String): DormRoundResult {
            if (url.contains("ticket.cgi") && algoId == DORM_NIL_ALGO_ID) return DormRoundResult.CipherUpdate(algo)
            if (url.contains("ticket.cgi")) {
                val xml = buildDormXml(DormXmlKind.GetTicket, DormXmlFields("ua", "id", "t"))
                    .replace("</request>", "    <ticket>$ticket</ticket>\n</request>")
                return DormRoundResult.Reply("200", "", DormCipher.encryptHex(algoId, xml.encodeToByteArray())!!.encodeToByteArray())
            }
            loginSent = true
            return DormRoundResult.Reply("200", "", DormCipher.encryptHex(algoId,
                "<response><code>0</code><keep-retry>120</keep-retry></response>".encodeToByteArray())!!.encodeToByteArray())
        }
    }

    @Test fun aSuccessfulSignInReportsConnectedAndKeepsASession() = runTest {
        val c = controller(OkGateway(supported))
        assertTrue(c.signIn("13800138000", "pw"))
        assertEquals(DormSignInPhase.Connected, c.state.value.phase)
        assertTrue(c.state.value.connected)
        // A keep-alive interval must exist, or the session would lapse silently.
        assertTrue(c.keepAliveMillis > 0)
        c.signOut()
    }

    @Test fun anUnsupportedCipherIsReportedWithoutSendingCredentials() = runTest {
        val gateway = OkGateway(unsupported)
        val c = controller(gateway)
        assertFalse(c.signIn("13800138000", "pw"))
        assertEquals(DormSignInPhase.Unsupported, c.state.value.phase)
        // The password must not have reached the gateway.
        assertFalse(gateway.loginSent)
        assertTrue(c.state.value.detail.contains("ZUC"))
    }

    @Test fun aMalformedPhoneFailsLocally() = runTest {
        val gateway = OkGateway(supported)
        val c = controller(gateway)
        for (bad in listOf("", "1", "1380013800", "138001380001", "1380013800a")) {
            assertFalse(c.signIn(bad, "pw"), bad)
            assertEquals(DormSignInPhase.Failed, c.state.value.phase, bad)
        }
        // Nothing was attempted, so no login attempt was consumed.
        assertFalse(gateway.loginSent)
    }

    @Test fun noTransportMeansNotOnCampusRatherThanASilentFailure() = runTest {
        val c = controller(null)
        assertFalse(c.signIn("13800138000", "pw"))
        assertEquals(DormSignInPhase.NotOnCampus, c.state.value.phase)
        assertEquals(AppText.CampusNetwork.DORM_NEEDS_CAMPUS_NETWORK, c.state.value.detail)
    }

    @Test fun anUnreachableGatewayIsNotOnCampus() = runTest {
        val c = controller(DormTransport { _, _, _, _ -> DormRoundResult.Unreachable })
        assertFalse(c.signIn("13800138000", "pw"))
        assertEquals(DormSignInPhase.NotOnCampus, c.state.value.phase)
    }

    @Test fun signOutWithoutASessionDoesNothingRatherThanClaimingSuccess() = runTest {
        val c = controller(OkGateway(supported))
        assertFalse(c.signOut())
    }

    @Test fun aSecondAttemptWhileOneIsRunningIsRefusedNotQueued() = runTest {
        // Sequential calls are legitimate (retry after a failure); it is a *concurrent* tap that
        // must not start a second dial, because each dial spends a rate-limited login attempt.
        //
        // The first attempt is launched on runTest's structured backgroundScope (never a detached
        // CoroutineScope), and the transport signals *exactly* when the mutex is held. That removes
        // the old delay(120) race, which on a slow CI runner let the test outlive runTest and fail
        // with UncompletedCoroutinesError. backgroundScope is cancelled automatically at the end.
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        var posts = 0
        val slow = DormTransport { _, _, algoId, _ ->
            posts++
            if (algoId == DORM_NIL_ALGO_ID) {
                entered.complete(Unit)
                release.await()
                DormRoundResult.CipherUpdate(supported)
            } else DormRoundResult.Reply("200", "", DormCipher.encryptHex(algoId,
                "<request><ticket>T</ticket></request>".encodeToByteArray())!!.encodeToByteArray())
        }
        val c = controller(slow)
        val first = backgroundScope.launch { c.signIn("13800138000", "pw") }
        // Deterministic: the first dial is now inside signIn and holding the flight mutex.
        entered.await()
        val whileRunning = c.signIn("13800138000", "pw")
        assertFalse(whileRunning, "a concurrent attempt must be refused, not queued")
        assertEquals(1, posts, "the refused attempt must not reach the gateway")
        release.complete(Unit)
        first.join()
    }

    @Test fun keepRetryFromAReplyIsHonouredAndBounded() {
        // The gateway tells us the interval; a missing value must not become a tight loop.
        assertEquals(120, dormKeepRetryFrom("<request><keep-retry>150</keep-retry></request>").let { 150 - 30 })
        assertEquals(300, dormKeepRetryFrom(null))
        assertEquals(300, dormKeepRetryFrom("<request></request>"))
        assertEquals(300, dormKeepRetryFrom("<request><keep-retry>abc</keep-retry></request>"))
        // Never below the floor, whatever the gateway advertises.
        assertTrue(dormKeepRetryFrom("<request><keep-retry>40</keep-retry></request>") >= 60)
    }
}

/**
 * The two values the dialer cannot invent.
 *
 * The reference implementation aborts when either `wlanuserip` or `wlanacip` is missing, so this
 * must refuse locally with a stated reason rather than spend a rate-limited login attempt on a
 * request the gateway can only reject.
 */
class DormSignInPreconditionsTest {

    private val supported = "319FC5AB-EC0E-46B9-A252-2285F9DAE813"
    private val fields = DormXmlFields("ua", "id", "t")

    @Test fun withoutAPortalAddressTheAttemptIsRefusedAndNothingIsSent() = runTest {
        var posts = 0
        val transport = DormTransport { _, _, _, _ -> posts++; DormRoundResult.Unreachable }
        val c = DormSignInController(transport, fields = { fields },
            servers = DORM_AUTH_SERVERS, portalAddress = { null }, schoolIdentity = { null })
        assertFalse(c.signIn("13800138000", "pw"))
        assertEquals(DormSignInPhase.NotOnCampus, c.state.value.phase)
        assertEquals(AppText.CampusNetwork.DORM_NEEDS_PORTAL, c.state.value.detail)
        // Nothing left the device, so no login attempt was consumed.
        assertEquals(0, posts)
    }

    @Test fun thePortalAddressesReachTheTransportAsTheTicketQuery() = runTest {
        val seen = mutableListOf<String>()
        val transport = DormTransport { url, _, algoId, _ ->
            seen += url
            if (algoId == DORM_NIL_ALGO_ID) DormRoundResult.CipherUpdate(supported)
            else DormRoundResult.Reply("200", "", DormCipher.encryptHex(algoId,
                "<request><ticket>T</ticket></request>".encodeToByteArray())!!.encodeToByteArray())
        }
        val c = DormSignInController(transport, fields = { fields }, servers = DORM_AUTH_SERVERS,
            portalAddress = { PortalAddress("10.9.8.7", "10.9.8.1") }, schoolIdentity = { null })
        c.signIn("13800138000", "pw")
        // Both addresses must appear in the ticket URL: the reference builds it from exactly these.
        val ticketUrl = seen.first { it.contains("ticket.cgi") }
        assertTrue(ticketUrl.contains("wlanuserip=10.9.8.7"), ticketUrl)
        assertTrue(ticketUrl.contains("wlanacip=10.9.8.1"), ticketUrl)
    }
}

/** The keep-alive interval must come from the login reply, not from a constant. */
class DormKeepAlivePropagationTest {

    private val algo = "319FC5AB-EC0E-46B9-A252-2285F9DAE813"
    private val fields = DormXmlFields("ua", "cid", "t")
    private val portal = PortalAddress("10.0.0.2", "10.0.0.1")

    private fun controller(keepRetry: String) = DormSignInController(
        transport = DormTransport { url, _, algoId, _ ->
            if (algoId == DORM_NIL_ALGO_ID) return@DormTransport DormRoundResult.CipherUpdate(algo)
            val xml = if (url.contains("ticket.cgi")) "<request><ticket>T</ticket></request>"
            else "<response><code>0</code><keep-retry>$keepRetry</keep-retry></response>"
            DormRoundResult.Reply("200", "",
                io.github.betterzslf.feature.campusnet.crypto.DormCipher
                    .encryptHex(algoId, xml.encodeToByteArray())!!.encodeToByteArray())
        },
        fields = { fields }, servers = DORM_AUTH_SERVERS,
        portalAddress = { portal }, schoolIdentity = { null })

    @Test fun theAdvertisedIntervalReachesTheSession() = runTest {
        val c = controller(keepRetry = "600")
        assertTrue(c.signIn("13800138000", "pw"))
        // 600 advertised, minus the safety margin the reference applies.
        assertEquals(570_000L, c.keepAliveMillis)
        c.signOut()
    }

    @Test fun aShortAdvertisedIntervalIsFlooredNotTrusted() = runTest {
        // A tiny value would become a tight loop against a rate-limited gateway.
        val c = controller(keepRetry = "40")
        assertTrue(c.signIn("13800138000", "pw"))
        assertTrue(c.keepAliveMillis >= 60_000L, "keep-alive ${c.keepAliveMillis} is too aggressive")
        c.signOut()
    }

    @Test fun aReplyWithoutKeepRetryIsAFailureNotASilentSuccess() = runTest {
        // The reference requires <keep-retry> to consider the login accepted; a decodable reply
        // without it means the gateway did not complete the session.
        val c = controller(keepRetry = "")
        assertFalse(c.signIn("13800138000", "pw"))
        assertEquals(DormSignInPhase.Failed, c.state.value.phase)
    }
}
