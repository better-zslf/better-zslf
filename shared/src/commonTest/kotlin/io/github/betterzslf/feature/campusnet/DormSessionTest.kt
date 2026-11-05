package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.feature.campusnet.crypto.DormCipher
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Heartbeat and sign-out.
 *
 * These were previously untested, and both had defects: the heartbeat read `<keep-retry>` from its
 * reply while the protocol puts the next interval in `<interval>`, so the keep-alive never updated
 * and would eventually drift out of step with the gateway.
 */
class DormSessionTest {

    private val algo = "319FC5AB-EC0E-46B9-A252-2285F9DAE813"
    private val fields = DormXmlFields(
        userAgent = DORM_ANDROID_USER_AGENT, clientId = "cid", localTime = "t",
        hostName = "h", ipv4 = "10.0.0.2", mac = "aa", ostag = "3", gwip = "10.0.0.1")

    private fun session(
        transport: DormTransport,
        keepRetrySeconds: Int = 300,
    ) = DormSession(transport, DORM_AUTH_SERVERS.first(), "cid", "TK", algo, fields, keepRetrySeconds)

    private fun reply(algoId: String, xml: String, errorCode: String = "200") =
        DormRoundResult.Reply(errorCode, "",
            DormCipher.encryptHex(algoId, xml.encodeToByteArray())!!.encodeToByteArray())

    private class Recorder(private val answer: (String) -> DormRoundResult) : DormTransport {
        val urls = mutableListOf<String>()
        override suspend fun post(url: String, encryptedPayload: String, algoId: String, clientId: String): DormRoundResult {
            urls += url
            return answer(url)
        }
    }

    @Test fun aHeartbeatUsesIntervalNotKeepRetry() = runTest {
        // The two elements are different, and reading the wrong one leaves the interval stale.
        val recorder = Recorder { reply(algo, "<response><interval>600</interval></response>") }
        val s = session(recorder)
        assertTrue(s.heartbeat())
        // 600 advertised, minus the safety margin.
        assertEquals(570, s.state.value.keepRetrySeconds)
        assertEquals(DormSessionPhase.Alive, s.state.value.phase)
        assertTrue(recorder.urls.single().contains("keep.cgi"))
    }

    @Test fun aHeartbeatWithoutIntervalIsAProtocolFailure() = runTest {
        val recorder = Recorder { reply(algo, "<response><keep-retry>600</keep-retry></response>") }
        val s = session(recorder)
        assertFalse(s.heartbeat())
        assertEquals(DormSessionPhase.Lost, s.state.value.phase)
    }

    @Test fun aShortIntervalIsClampedSoTheHeartbeatCannotBecomeATightLoop() = runTest {
        val recorder = Recorder { reply(algo, "<response><interval>10</interval></response>") }
        val s = session(recorder)
        assertTrue(s.heartbeat())
        assertTrue(s.state.value.keepRetrySeconds >= 60, "interval ${s.state.value.keepRetrySeconds}")
    }

    @Test fun anUnreachableHeartbeatMarksTheSessionLost() = runTest {
        val s = session(DormTransport { _, _, _, _ -> DormRoundResult.Unreachable })
        assertFalse(s.heartbeat())
        assertEquals(DormSessionPhase.Lost, s.state.value.phase)
    }

    @Test fun anUndecodableHeartbeatReplyIsUnknownNotHealthy() = runTest {
        val s = session(DormTransport { _, _, _, _ ->
            DormRoundResult.Reply("200", "", "not-hex".encodeToByteArray())
        })
        assertFalse(s.heartbeat())
        assertEquals(DormSessionPhase.Unknown, s.state.value.phase)
    }

    @Test fun signOutUsesTermAndReportsTheOutcome() = runTest {
        val recorder = Recorder { reply(algo, "<response></response>") }
        val s = session(recorder)
        assertTrue(s.logout())
        assertTrue(recorder.urls.single().contains("term.cgi"))
        assertEquals(DormSessionPhase.SignedOut, s.state.value.phase)
    }

    @Test fun signOutOnAnUnreachableGatewayDoesNotClaimSuccess() = runTest {
        val s = session(DormTransport { _, _, _, _ -> DormRoundResult.Unreachable })
        assertFalse(s.logout())
        assertEquals(DormSessionPhase.Lost, s.state.value.phase)
    }

    @Test fun aClosedSessionDoesNothingAtAll() = runTest {
        var posts = 0
        val s = session(DormTransport { _, _, _, _ -> posts++; DormRoundResult.Unreachable })
        s.close()
        assertFalse(s.heartbeat())
        assertFalse(s.logout())
        assertEquals(0, posts)
    }

    @Test fun theHeartbeatCountsAsAliveEvenWithANon200HeaderBecauseIntervalDecides() = runTest {
        // The reference decides from the decrypted body, not the header, once a reply arrives.
        val recorder = Recorder { reply(algo, "<response><interval>300</interval></response>", "200") }
        val s = session(recorder)
        assertTrue(s.heartbeat())
        assertEquals(DormSessionPhase.Alive, s.state.value.phase)
    }
}
