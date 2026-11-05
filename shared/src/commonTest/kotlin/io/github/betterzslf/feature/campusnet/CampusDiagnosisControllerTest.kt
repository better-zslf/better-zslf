package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private const val PORTAL = "http://9.9.9.9"
private const val PROBED = "$PORTAL/a/wifi?wlanuserip=10.1.2.3&wlanacip=10.1.2.1"
private const val KNOWN_ALGO = "35101415-A20F-4DFE-B00B-0B4F3B2F8C66"

private fun answered(schoolId: String = "school", domain: String = "dom", area: String = "area") =
    PortalProbeResult.Answered(PROBED, redirected = true, schoolId = schoolId, domain = domain, area = area)

class CampusDiagnosisControllerTest {

    private fun controller(
        portalAddress: suspend () -> String? = { PORTAL },
        probe: CampusDiagnosisProbe? = CampusDiagnosisProbe { answered() },
        handshake: CampusDiagnosisHandshake? = CampusDiagnosisHandshake { _, _, _, _, _ ->
            DormHandshakeResult.Negotiated(KNOWN_ALGO, 128)
        },
    ) = CampusDiagnosisController(portalAddress, probe, handshake)

    @Test fun aSuccessfulRunReportsTheAlgoIdItsFamilyAndTheIdentity() = runTest {
        val c = controller()
        assertTrue(c.run())
        val diagnosis = c.state.value.diagnosis
        assertEquals(KNOWN_ALGO, diagnosis.algoId)
        assertEquals("triple-modified-TEA-CBC", diagnosis.algorithmHint)
        assertEquals("school", diagnosis.schoolId)
        assertEquals("triple-modified-TEA-CBC", campusAlgoFamilyName(KNOWN_ALGO))
        assertTrue(diagnosis.hasAlgoId)
        assertEquals(AppText.CampusNetwork.diagnosisAlgoKnown("triple-modified-TEA-CBC"),
            campusAlgoStatusText(diagnosis))
    }

    @Test fun theIdentityFromThePortalIsEchoedToTheHandshake() = runTest {
        var seen: Triple<String, String, String>? = null
        val c = CampusDiagnosisController(
            portalAddress = { PORTAL },
            probe = CampusDiagnosisProbe { answered("S1", "D1", "A1") },
            handshake = CampusDiagnosisHandshake { _, _, s, d, a ->
                seen = Triple(s, d, a)
                DormHandshakeResult.Negotiated(KNOWN_ALGO, 42)
            },
        )
        assertTrue(c.run())
        assertEquals(Triple("S1", "D1", "A1"), seen)
    }

    @Test fun noPortalAddressIsReportedAsOffCampusNotAsAFailureOfTheHandshake() = runTest {
        val c = controller(portalAddress = { null })
        assertFalse(c.run())
        assertEquals(AppText.CampusNetwork.DIAGNOSIS_OFF_CAMPUS, c.state.value.message)
        // The handshake must never run without an address: there is nothing to dial.
        assertFalse(c.state.value.diagnosis.hasAlgoId)
        // No gateway code exists here, so the status must not claim one.
        assertEquals(AppText.CampusNetwork.DIAGNOSIS_NOT_RUN,
            campusAlgoStatusText(c.state.value.diagnosis))
    }

    @Test fun aPortalThatDoesNotAnswerIsOffCampus() = runTest {
        for (result in listOf(PortalProbeResult.Unreachable, PortalProbeResult.Blocked)) {
            val c = controller(probe = CampusDiagnosisProbe { result })
            assertFalse(c.run())
            assertEquals(AppText.CampusNetwork.DIAGNOSIS_OFF_CAMPUS, c.state.value.message)
        }
    }

    @Test fun aProbeWithoutPortalAddressesCannotReachTheHandshake() = runTest {
        var handshakes = 0
        val c = CampusDiagnosisController(
            portalAddress = { PORTAL },
            probe = CampusDiagnosisProbe {
                PortalProbeResult.Answered("$PORTAL/plain", redirected = false, schoolId = "s")
            },
            handshake = CampusDiagnosisHandshake { _, _, _, _, _ ->
                handshakes++; DormHandshakeResult.Negotiated(KNOWN_ALGO, 1)
            },
        )
        assertFalse(c.run())
        assertEquals(0, handshakes)
        // The portal answered but carried no wlan* parameters: the network is fine, the redirect
        // shape is not the one a dial can use. Reported distinctly from "no answer".
        assertEquals(AppText.CampusNetwork.DIAGNOSIS_NO_ADDRESS, c.state.value.message)
        // The school id was still captured, so a later attempt does not start from zero.
        assertEquals("s", c.state.value.diagnosis.schoolId)
    }

    @Test fun aRefusalKeepsTheGatewaysCodeAndIsShownVerbatim() = runTest {
        val c = controller(handshake = CampusDiagnosisHandshake { _, _, _, _, _ ->
            DormHandshakeResult.Rejected("15", "", 0)
        })
        assertFalse(c.run())
        val diagnosis = c.state.value.diagnosis
        assertEquals("15", diagnosis.errorCode)
        assertFalse(diagnosis.hasAlgoId)
        assertEquals(AppText.CampusNetwork.diagnosisErrorCode("15"), campusAlgoStatusText(diagnosis))
    }

    @Test fun aBlobWithoutAnIdIsNoAnswerRatherThanAFakeOne() = runTest {
        val c = controller(handshake = CampusDiagnosisHandshake { _, _, _, _, _ ->
            DormHandshakeResult.UnreadableBlob("200", 64)
        })
        assertFalse(c.run())
        assertFalse(c.state.value.diagnosis.hasAlgoId)
        assertEquals(AppText.CampusNetwork.DIAGNOSIS_NO_ANSWER, c.state.value.message)
    }

    @Test fun aThrowingProbeOrHandshakeIsContained() = runTest {
        val probeThrows = controller(probe = CampusDiagnosisProbe { error("boom") })
        assertFalse(probeThrows.run())
        assertEquals(AppText.CampusNetwork.DIAGNOSIS_OFF_CAMPUS, probeThrows.state.value.message)

        val handshakeThrows = controller(handshake = CampusDiagnosisHandshake { _, _, _, _, _ -> error("boom") })
        assertFalse(handshakeThrows.run())
        assertEquals(AppText.CampusNetwork.DIAGNOSIS_OFF_CAMPUS, handshakeThrows.state.value.message)
    }

    @Test fun anUnknownButWellFormedIdIsReportedAsUnknownFamily() = runTest {
        val c = controller(handshake = CampusDiagnosisHandshake { _, _, _, _, _ ->
            DormHandshakeResult.Negotiated("11111111-2222-3333-4444-555555555555", 8)
        })
        assertTrue(c.run())
        val diagnosis = c.state.value.diagnosis
        assertTrue(diagnosis.hasAlgoId)
        assertEquals("", diagnosis.algorithmHint)
        assertEquals(AppText.CampusNetwork.DIAGNOSIS_ALGO_UNKNOWN_FAMILY, campusAlgoStatusText(diagnosis))
    }

    @Test fun theReportLineIsPasteSafeAndCarriesEveryValue() = runTest {
        val c = controller()
        c.run()
        val line = c.state.value.diagnosis.reportLine()
        // One line, no spaces: whatever the user pastes stays intact.
        assertFalse(line.contains('\n'))
        assertFalse(line.contains(' '))
        assertTrue(line.contains("algoId=$KNOWN_ALGO"))
        assertTrue(line.contains("portal=$PORTAL"))
        assertTrue(line.contains("schoolId=school"))
    }

    @Test fun runningTwiceDoesNotStackAttempts() = runTest {
        var handshakes = 0
        val c = CampusDiagnosisController(
            portalAddress = { PORTAL },
            probe = CampusDiagnosisProbe { answered() },
            handshake = CampusDiagnosisHandshake { _, _, _, _, _ ->
                handshakes++; DormHandshakeResult.Negotiated(KNOWN_ALGO, 1)
            },
        )
        assertTrue(c.run())
        assertTrue(c.run())
        // A second explicit run is allowed (the user asked), but it is still one at a time.
        assertEquals(2, handshakes)
    }
}

/**
 * The distinction between "the portal did not answer" and "it answered without the parameters".
 *
 * Both block a dial, but they mean different things and lead to different fixes: the first is a
 * network problem, the second means the redirect shape is not the one the dialer expects. Collapsing
 * them sent the user to check their WiFi when the WiFi was fine.
 */
class CampusDiagnosisAddressTest {

    private val portal = "http://9.9.9.9"

    @Test fun anAnswerWithoutTheTwoAddressesSaysSoRatherThanBlamingTheNetwork() = runTest {
        val c = CampusDiagnosisController(
            portalAddress = { portal },
            probe = CampusDiagnosisProbe {
                PortalProbeResult.Answered("$portal/plain", redirected = false,
                    schoolId = "s", domain = "d", area = "a")
            },
            handshake = CampusDiagnosisHandshake { _, _, _, _, _ ->
                error("handshake must not run without an address")
            },
        )
        assertFalse(c.run())
        assertEquals(AppText.CampusNetwork.DIAGNOSIS_NO_ADDRESS, c.state.value.message)
        // The identity was still captured, so the report is useful even when the dial is blocked.
        assertEquals("s", c.state.value.diagnosis.schoolId)
    }

    @Test fun anUnreachablePortalStillReadsAsOffCampus() = runTest {
        val c = CampusDiagnosisController(
            portalAddress = { portal },
            probe = CampusDiagnosisProbe { PortalProbeResult.Unreachable },
            handshake = CampusDiagnosisHandshake { _, _, _, _, _ -> error("must not run") },
        )
        assertFalse(c.run())
        assertEquals(AppText.CampusNetwork.DIAGNOSIS_OFF_CAMPUS, c.state.value.message)
    }
}

/** What the shared holder records, including the case where it can record nothing usable. */
class CampusDiagnosisHolderTest {

    @Test fun aUsableRedirectIsParsedIntoTheTwoAddresses() {
        val h = CampusDiagnosisControllerHolder()
        h.record("http://9.9.9.9/a/wifi?wlanuserip=10.0.0.2&wlanacip=10.0.0.1", "S", "D", "A")
        val address = assertNotNull(h.observed)
        assertEquals("10.0.0.2", address.clientIp)
        assertEquals("10.0.0.1", address.acIp)
        assertTrue(h.probed)
        assertEquals(Triple("S", "D", "A"), h.identity)
    }

    @Test fun anUnusableRedirectLeavesNoAddressButIsStillMarkedProbed() {
        // This is what stops the sign-in button from saying "read the diagnosis first" right after
        // the diagnosis ran successfully.
        val h = CampusDiagnosisControllerHolder()
        h.record("http://9.9.9.9/plain", "S", "D", "A")
        assertNull(h.observed)
        assertTrue(h.probed)
    }

    @Test fun clearResetsEverything() {
        val h = CampusDiagnosisControllerHolder()
        h.record("http://9.9.9.9/a/wifi?wlanuserip=1.1.1.1&wlanacip=2.2.2.2", "S", "D", "A")
        h.clear()
        assertNull(h.observed)
        assertNull(h.identity)
        assertFalse(h.probed)
    }
}
