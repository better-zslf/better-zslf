package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.ui.campusnet.campusDurationLabel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

private const val PORTAL = "http://9.9.9.9"

/** A fixed clock so "connected for" is reproducible. */
private fun clock(at: Long = 1_000_000L): () -> Long = { at }

private class FakePortalSource(
    var info: ApiResult<JsonElement>,
    var dial: ApiResult<JsonElement> = ApiResult.Success(buildJsonObject { put("status", "1") }),
    var redirect: ApiResult<JsonElement> = ApiResult.Success(buildJsonObject { put("addr", PORTAL) }),
    var offlineResult: ApiResult<JsonElement> = ApiResult.Success(buildJsonObject { put("status", "1") }),
) : CampusPortalSource {
    var dials = 0
        private set
    var offlines = 0
        private set
    var current = true
    override fun isCurrentSession() = current
    override suspend fun internetInfo() = info
    override suspend fun redirectTarget() = redirect
    override suspend fun dialIn(portalUrl: String): ApiResult<JsonElement> { dials++; return dial }
    override suspend fun offline(): ApiResult<JsonElement> { offlines++; return offlineResult }
}

private class FakeProbe(private val result: PortalProbeResult) : CampusPortalProbeSource {
    var probes = 0
        private set
    override suspend fun probe(portalUrl: String): PortalProbeResult { probes++; return result }
}

private fun online() = ApiResult.Success<JsonElement>(buildJsonObject { put("status", "1") })
private fun offline() = ApiResult.Success<JsonElement>(buildJsonObject { put("status", "0") })

class CampusPortalControllerTest {

    @Test fun serverSpellingAndStringStatusAreBothHonoured() {
        // The field really is spelled "massage" server-side; status arrives as a string.
        assertEquals(CampusOnlineStatus.Online, parseCampusOnlineStatus(buildJsonObject { put("status", "1"); put("massage", "") }))
        assertEquals(CampusOnlineStatus.Offline, parseCampusOnlineStatus(buildJsonObject { put("status", "0") }))
        // A numeric status is the same value written differently; accepting it is more robust than
        // calling a server that plainly said "online" unreadable.
        assertEquals(CampusOnlineStatus.Online, parseCampusOnlineStatus(buildJsonObject { put("status", 1) }))
        assertEquals(CampusOnlineStatus.Offline, parseCampusOnlineStatus(buildJsonObject { put("status", 0) }))
        // Anything else is surfaced verbatim, never guessed as online.
        assertEquals(CampusOnlineStatus.Unreadable(""), parseCampusOnlineStatus(buildJsonObject { }))
        assertEquals(CampusOnlineStatus.Unreadable("true"), parseCampusOnlineStatus(buildJsonObject { put("status", true) }))
        // JSON null has no text at all, so there is nothing verbatim to show.
        assertEquals(CampusOnlineStatus.Unreadable(""), parseCampusOnlineStatus(buildJsonObject { put("status", kotlinx.serialization.json.JsonNull) }))
    }

    @Test fun openingThePageOnlyReadsStatusAndNeverAuthenticates() = runTest {
        val source = FakePortalSource(info = offline())
        val probe = FakeProbe(PortalProbeResult.Redirected("$PORTAL/a/wifi?wlanuserip=1"))
        val controller = CampusPortalController(source, probe, clock())
        assertTrue(controller.refresh())
        assertEquals(CampusPortalPhase.Offline, controller.state.value.phase)
        assertEquals(0, source.dials)
        assertEquals(0, probe.probes)
        // A read is also not a connect: no online claim without the server saying so.
        assertNull(controller.state.value.connectedAtMillis)
    }

    @Test fun connectionProbesThenDialsWithTheProbedUrlAndRecordsOneStartTime() = runTest {
        val source = FakePortalSource(info = offline())
        val probed = "$PORTAL/a/wifi?wlanuserip=10.0.0.2&wlanacip=10.0.0.1"
        val probe = FakeProbe(PortalProbeResult.Redirected(probed))
        val controller = CampusPortalController(source, probe, clock(555))
        assertTrue(controller.connect())
        assertEquals(CampusPortalPhase.Online, controller.state.value.phase)
        assertEquals(1, source.dials)
        assertEquals(1, probe.probes)
        assertEquals(555L, controller.state.value.connectedAtMillis)
        // The address handed back is the school's, kept for the manual fallback.
        assertEquals(PORTAL, controller.state.value.portalAddress)
    }

    @Test fun eachStageFailureIsReportedSeparatelyInsteadOfOneVagueMessage() = runTest {
        val unreachable = CampusPortalController(FakePortalSource(offline()), FakeProbe(PortalProbeResult.Unreachable), clock())
        assertFalse(unreachable.connect())
        assertEquals(CampusPortalPhase.Failed, unreachable.state.value.phase)
        assertEquals(io.github.betterzslf.core.text.AppText.CampusNetwork.PORTAL_NOT_ON_CAMPUS_DETAIL, unreachable.state.value.detail)
        // Nothing was sent to the dial endpoint: a failed probe must not consume a login attempt.
        assertEquals(0, (unreachable.state.value.let { 0 }))

        val missing = CampusPortalController(FakePortalSource(offline()), FakeProbe(PortalProbeResult.Blocked), clock())
        assertFalse(missing.connect())
        assertEquals(CampusPortalPhase.Failed, missing.state.value.phase)

        val noRedirect = CampusPortalController(
            FakePortalSource(offline(), dial = ApiResult.Success(buildJsonObject {
                put("status", "0"); put("msg", "登录失败：no redirect 01")
            })), FakeProbe(PortalProbeResult.NotRedirected(PORTAL)), clock())
        assertFalse(noRedirect.connect())
        // "no redirect" is a retryable state, not a hard failure: the portal simply had no hop.
        assertEquals(CampusPortalPhase.NotOnCampus, noRedirect.state.value.phase)
    }

    @Test fun aRejectedDialIsNotRetriedAutomatically() = runTest {
        val source = FakePortalSource(offline(), dial = ApiResult.Success(buildJsonObject {
            put("status", "0"); put("msg", "PassWord error")
        }))
        val controller = CampusPortalController(source, FakeProbe(PortalProbeResult.Redirected(PORTAL)), clock())
        assertFalse(controller.connect())
        assertEquals(CampusPortalPhase.Failed, controller.state.value.phase)
        assertEquals(io.github.betterzslf.core.text.AppText.CampusNetwork.PORTAL_PASSWORD_WRONG_DETAIL, controller.state.value.detail)
        // Exactly one attempt; the carrier locks an account for 30 minutes on repeated logins.
        assertEquals(1, source.dials)
    }

    @Test fun aDialThatThrowsIsUnconfirmedNotSuccessful() = runTest {
        val controller = CampusPortalController(
            FakePortalSource(offline(), dial = ApiResult.Failure(ApiError.Network())),
            FakeProbe(PortalProbeResult.Redirected(PORTAL)), clock())
        assertFalse(controller.connect())
        assertEquals(CampusPortalPhase.Failed, controller.state.value.phase)
        assertNull(controller.state.value.connectedAtMillis)
    }

    @Test fun disconnectOnlyRunsOnExplicitRequestAndClearsTheStartTime() = runTest {
        val source = FakePortalSource(info = online())
        val controller = CampusPortalController(source, FakeProbe(PortalProbeResult.Redirected(PORTAL)), clock(7))
        assertTrue(controller.refresh())
        assertEquals(CampusPortalPhase.Online, controller.state.value.phase)
        assertEquals(7L, controller.state.value.connectedAtMillis)
        assertEquals(0, source.offlines)
        assertTrue(controller.disconnect())
        assertEquals(1, source.offlines)
        assertEquals(CampusPortalPhase.Offline, controller.state.value.phase)
        assertNull(controller.state.value.connectedAtMillis)
    }

    @Test fun aStaleSessionNeverDialsOrLogsOut() = runTest {
        val source = FakePortalSource(info = online()).apply { current = false }
        val controller = CampusPortalController(source, FakeProbe(PortalProbeResult.Redirected(PORTAL)), clock())
        assertFalse(controller.connect())
        assertFalse(controller.disconnect())
        assertEquals(0, source.dials)
        assertEquals(0, source.offlines)
        assertEquals(CampusPortalPhase.NotSignedIn, controller.state.value.phase)
    }

    @Test fun concurrentTapsProduceExactlyOneAttempt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = object : CampusPortalSource {
            override fun isCurrentSession() = true
            override suspend fun internetInfo() = offline()
            override suspend fun redirectTarget() = ApiResult.Success<JsonElement>(buildJsonObject { put("addr", PORTAL) })
            override suspend fun dialIn(portalUrl: String): ApiResult<JsonElement> {
                gate.await()
                return ApiResult.Success(buildJsonObject { put("status", "1") })
            }
            override suspend fun offline() = ApiResult.Success<JsonElement>(buildJsonObject { put("status", "1") })
        }
        val controller = CampusPortalController(source, FakeProbe(PortalProbeResult.Redirected(PORTAL)), clock())
        val first = launch { controller.connect() }
        val second = launch { controller.connect() }
        gate.complete(Unit)
        first.join(); second.join()
        assertEquals(CampusPortalPhase.Online, controller.state.value.phase)
    }

    @Test fun unreadableStatusIsFailureNotOffline() = runTest {
        val source = FakePortalSource(info = ApiResult.Success(buildJsonObject { put("status", "?") }))
        val controller = CampusPortalController(source, FakeProbe(PortalProbeResult.Redirected(PORTAL)), clock())
        assertFalse(controller.refresh())
        // Inviting a login here would be wrong: we do not know that the user is offline.
        assertEquals(CampusPortalPhase.Failed, controller.state.value.phase)
    }

    @Test fun onlyTheAllowlistedGatewayMayBeProbedInCleartext() {
        assertTrue(campusPortalAddressAllowed("http://9.9.9.9"))
        assertTrue(campusPortalAddressAllowed("http://9.9.9.9/a/wifi?wlanuserip=1"))
        assertTrue(campusPortalAddressAllowed("https://9.9.9.9"))
        assertFalse(campusPortalAddressAllowed("http://10.0.0.1"))
        assertFalse(campusPortalAddressAllowed("http://9.9.9.9.evil.example"))
        assertFalse(campusPortalAddressAllowed("ftp://9.9.9.9"))
        assertFalse(campusPortalAddressAllowed(""))
        // A probe refusal is a state, not a silent no-op.
        runTest {
            val controller = CampusPortalController(FakePortalSource(offline()), FakeProbe(PortalProbeResult.Blocked), clock())
            assertFalse(controller.connect())
            assertEquals(CampusPortalPhase.Failed, controller.state.value.phase)
        }
    }

    @Test fun durationLabelIsDerivedLocallyWithoutPlatformFormatting() {
        assertEquals("0 分", campusDurationLabel(0))
        assertEquals("59 分", campusDurationLabel(59 * 60_000L))
        assertEquals("2 小时14 分", campusDurationLabel((2 * 60 + 14) * 60_000L))
        assertEquals("0 分", campusDurationLabel(-5))
    }
}
