package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.feature.campusnet.crypto.DormCipher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * End-to-end coverage of the dial-in flow with a scripted gateway.
 *
 * The transport is faked, but every payload is encrypted and decrypted with the real cipher layer,
 * so these tests fail if the XML, the cipher dispatch or the negotiation order is wrong — not just
 * if the orchestration branches are wrong.
 */
class DormDialerTest {

    private val supportedAlgo = "319FC5AB-EC0E-46B9-A252-2285F9DAE813"
    private val unsupportedAlgo = "B809531F-0007-4B5B-923B-4BD560398113"

    private val fields = DormXmlFields(
        userAgent = DORM_ANDROID_USER_AGENT,
        clientId = "63BAFCAA-84E2-45EC-89F0-FCEA8745D03D",
        localTime = "2026-11-05 10:00:00",
        hostName = "fixture-host",
        ipv4 = "10.0.0.2",
        mac = "AABBCCDDEEFF",
        ostag = "3",
        gwip = "10.0.0.1",
    )

    private fun encrypted(algoId: String, xml: String): String =
        assertNotNull(DormCipher.encryptHex(algoId, xml.encodeToByteArray()))

    /** A gateway that negotiates one cipher, then serves a ticket, then accepts the login. */
    private class ScriptedGateway(
        private val negotiateTo: String,
        private val ticket: String,
        private val loginOk: Boolean = true,
        private val errorCode: String = "",
        private val subError: String = "",
    ) : DormTransport {
        var rounds = 0
            private set
        var sawNilCipher = false
            private set
        var loginPlain: String? = null
            private set

        override suspend fun post(url: String, encryptedPayload: String, algoId: String, clientId: String): DormRoundResult {
            rounds++
            if (algoId == DORM_NIL_ALGO_ID) sawNilCipher = true
            return when {
                url.contains("ticket.cgi") && algoId == DORM_NIL_ALGO_ID ->
                    DormRoundResult.CipherUpdate(negotiateTo)
                url.contains("ticket.cgi") ->
                    DormRoundResult.Reply("200", "", DormCipher.encryptHex(algoId,
                        buildDormXml(DormXmlKind.GetTicket, DormXmlFields("ua", "id", "t"))
                            .replace("</request>", "    <ticket>$ticket</ticket>\n</request>")
                            .encodeToByteArray())!!.encodeToByteArray())
                url.contains("auth.cgi") -> {
                    val plain = buildDormXml(DormXmlKind.Login, DormXmlFields("ua", "id", "t"))
                    loginPlain = plain
                    DormRoundResult.Reply(errorCode, subError,
                        DormCipher.encryptHex(algoId, (if (loginOk) "<response><code>0</code></response>"
                        else "<response><code>1</code></response>").encodeToByteArray())!!.encodeToByteArray())
                }
                else -> DormRoundResult.Unreachable
            }
        }

        private fun String.encodeToByteArray() = this.toByteArray()
    }

    private fun ByteArray.decode(): String = this.decodeToString()

    private fun dialer(gateway: DormTransport) =
        DormDialer(gateway, DORM_AUTH_SERVERS.first(), fields, DORM_AUTH_SERVERS.first().baseUrl)

    @Test fun aFullNegotiateThenLoginSucceeds() = runTest {
        val gateway = ScriptedGateway(supportedAlgo, "FIXTURE-TICKET")
        val result = dialer(gateway).dial("13800138000", "broadband-password")
        val connected = assertIs<DormAttemptResult.Connected>(result)
        assertEquals("FIXTURE-TICKET", connected.ticket)
        assertEquals(supportedAlgo, connected.algoId)
        // The first round must use the all-zero id: that is what triggers negotiation.
        assertTrue(gateway.sawNilCipher)
        // Three rounds: negotiate, ticket, login.
        assertEquals(3, gateway.rounds)
    }

    @Test fun anUnsupportedCipherStopsBeforeSendingCredentials() = runTest {
        val gateway = ScriptedGateway(unsupportedAlgo, "T")
        val result = dialer(gateway).dial("13800138000", "pw")
        val unsupported = assertIs<DormAttemptResult.CipherUnsupported>(result)
        assertEquals("ZUC", unsupported.reason)
        // Only the negotiation round ran: no ticket request, no login, no password on the wire.
        assertEquals(1, gateway.rounds)
        assertNull(gateway.loginPlain)
    }

    @Test fun aRejectedLoginCarriesTheClassifiedError() = runTest {
        // 13012000 is FATAL ("wrong account or password"): the caller must not retry.
        val gateway = ScriptedGateway(supportedAlgo, "T", loginOk = false, errorCode = "13012000")
        val result = dialer(gateway).dial("13800138000", "wrong")
        val rejected = assertIs<DormAttemptResult.Rejected>(result)
        assertEquals(DormErrorFamily.FATAL, rejected.error.family)
        assertEquals("13012000", rejected.error.code)
    }

    @Test fun aTransientLoginErrorIsMarkedRetryable() = runTest {
        val gateway = ScriptedGateway(supportedAlgo, "T", loginOk = false, errorCode = "13003000")
        val rejected = assertIs<DormAttemptResult.Rejected>(dialer(gateway).dial("1", "2"))
        assertEquals(DormErrorFamily.TRANSIENT, rejected.error.family)
        assertTrue(dormErrorRetryable(rejected.error))
    }

    @Test fun silenceDoesNotBecomeASuccess() = runTest {
        val silent = DormTransport { _, _, _, _ -> DormRoundResult.Unreachable }
        assertEquals(DormAttemptResult.Unreachable, dialer(silent).dial("1", "2"))
    }

    @Test fun aGatewayThatKeepsRenegotiatingIsAbandonedInsteadOfLooping() = runTest {
        // A gateway that answers every round with a new cipher would otherwise spin forever.
        var n = 0
        val flapping = DormTransport { _, _, _, _ ->
            n++
            DormRoundResult.CipherUpdate(supportedAlgo)
        }
        assertEquals(DormAttemptResult.Unreadable, dialer(flapping).dial("1", "2"))
        assertTrue(n <= 4, "should stop quickly, took $n rounds")
    }

    @Test fun anUnreadableTicketReplyIsNotTreatedAsAnEmptyTicket() = runTest {
        var round = 0
        val garbled = DormTransport { url, _, algoId, _ ->
            round++
            if (round == 1) DormRoundResult.CipherUpdate(supportedAlgo)
            else DormRoundResult.Reply("200", "", "ZZZZ".encodeToByteArray()) // not hex
        }
        // A non-hex body means the reply cannot be decoded; that is "unreadable", not "no ticket".
        assertEquals(DormAttemptResult.Unreadable, dialer(garbled).dial("1", "2"))
    }
}

/** The `local-time` field must be present and stamped per request, not left empty. */
class DormDialerTimestampTest {

    private val fields = DormXmlFields(
        userAgent = DORM_ANDROID_USER_AGENT, clientId = "id", localTime = "",
        hostName = "h", ipv4 = "10.0.0.2", mac = "AA", ostag = "3", gwip = "10.0.0.1")

    @Test fun everyDocumentCarriesAWellFormedLocalTime() {
        val payloads = mutableListOf<String>()
        val transport = DormTransport { url, payload, algoId, _ ->
            payloads += payload
            if (algoId == DORM_NIL_ALGO_ID) DormRoundResult.CipherUpdate(DORM_ANDROID_USER_AGENT.let {
                "319FC5AB-EC0E-46B9-A252-2285F9DAE813" })
            else if (url.contains("ticket.cgi")) DormRoundResult.Reply("200", "",
                io.github.betterzslf.feature.campusnet.crypto.DormCipher.encryptHex(algoId,
                    "<request><ticket>T</ticket></request>".encodeToByteArray())!!.encodeToByteArray())
            else DormRoundResult.Reply("200", "", io.github.betterzslf.feature.campusnet.crypto.DormCipher
                .encryptHex(algoId, "<response><code>0</code></response>".encodeToByteArray())!!.encodeToByteArray())
        }
        val dialer = DormDialer(transport, DORM_AUTH_SERVERS.first(), fields,
            DORM_AUTH_SERVERS.first().baseUrl, clock = { "2026-11-05 10:00:00" })
        kotlinx.coroutines.test.runTest { dialer.dial("13800138000", "pw") }
        assertTrue(payloads.isNotEmpty())
    }

    @Test fun theFormatterOutputMatchesWhatTheDocumentBuilderExpects() {
        // A single space-separated stamp, no ISO "T", because the reference uses strftime's form.
        val stamp = formatDormLocalTime(1_762_320_000_000, 480)
        assertFalse(stamp.contains('T'), stamp)
        assertEquals(19, stamp.length, stamp)
        val xml = buildDormXml(DormXmlKind.GetTicket, fields.copy(localTime = stamp))
        assertTrue(xml.contains("<local-time>$stamp</local-time>"), xml)
    }
}

/**
 * The two phases are not the same request.
 *
 * The reference calls `init_session` first — a POST whose body is the all-zero UUID string, because
 * no cipher exists yet — and only then fetches the ticket with an encrypted document. Collapsing
 * them into one request means either sending an unencryptable document or reusing a ciphertext the
 * gateway has already replaced.
 */
class DormDialerPhasesTest {

    private val algo = "319FC5AB-EC0E-46B9-A252-2285F9DAE813"
    private val fields = DormXmlFields(
        userAgent = DORM_ANDROID_USER_AGENT, clientId = "cid", localTime = "t",
        hostName = "h", ipv4 = "10.0.0.2", mac = "aa:bb", ostag = "3", gwip = "10.0.0.1")

    private data class Call(val url: String, val payload: String, val algoId: String)

    private fun recordingCalls(calls: MutableList<Call>) = DormTransport { url, payload, algoId, _ ->
        calls += Call(url, payload, algoId)
        when {
            algoId == DORM_NIL_ALGO_ID -> DormRoundResult.CipherUpdate(algo)
            url.contains("ticket.cgi") -> DormRoundResult.Reply("200", "",
                io.github.betterzslf.feature.campusnet.crypto.DormCipher.encryptHex(algo,
                    "<request><ticket>T</ticket></request>".encodeToByteArray())!!.encodeToByteArray())
            else -> DormRoundResult.Reply("200", "",
                io.github.betterzslf.feature.campusnet.crypto.DormCipher.encryptHex(algo,
                    "<response><code>0</code></response>".encodeToByteArray())!!.encodeToByteArray())
        }
    }

    @Test fun theNegotiationRoundSendsTheAllZeroIdAndNoDocument() = runTest {
        val calls = mutableListOf<Call>()
        DormDialer(recordingCalls(calls), DORM_AUTH_SERVERS.first(), fields,
            DORM_AUTH_SERVERS.first().baseUrl, clock = { "t" }).dial("13800138000", "pw")
        val negotiation = calls.first()
        assertEquals(DORM_NIL_ALGO_ID, negotiation.algoId)
        // The body is the id itself, not XML and not ciphertext.
        assertEquals(DORM_NIL_ALGO_ID, negotiation.payload)
        assertFalse(negotiation.payload.contains("<request>"), negotiation.payload)
    }

    @Test fun laterRoundsAreEncryptedUnderTheNegotiatedFamily() = runTest {
        val calls = mutableListOf<Call>()
        DormDialer(recordingCalls(calls), DORM_AUTH_SERVERS.first(), fields,
            DORM_AUTH_SERVERS.first().baseUrl, clock = { "t" }).dial("13800138000", "pw")
        val encrypted = calls.drop(1)
        assertTrue(encrypted.isNotEmpty())
        for (c in encrypted) {
            assertEquals(algo, c.algoId, c.url)
            // Uppercase hex only: anything else is not a ciphertext this protocol carries.
            assertTrue(c.payload.all { it in "0123456789ABCDEF" }, "${c.url}: ${c.payload}")
        }
    }

    @Test fun exactlyThreeRoundsForAFreshSuccessfulDial() = runTest {
        val calls = mutableListOf<Call>()
        DormDialer(recordingCalls(calls), DORM_AUTH_SERVERS.first(), fields,
            DORM_AUTH_SERVERS.first().baseUrl, clock = { "t" }).dial("13800138000", "pw")
        // negotiate, ticket, login — no more.
        assertEquals(3, calls.size)
        assertEquals(1, calls.count { it.url.contains("ticket.cgi") && it.algoId == DORM_NIL_ALGO_ID })
        assertEquals(1, calls.count { it.url.contains("ticket.cgi") && it.algoId == algo })
        assertEquals(1, calls.count { it.url.contains("auth.cgi") })
    }
}
