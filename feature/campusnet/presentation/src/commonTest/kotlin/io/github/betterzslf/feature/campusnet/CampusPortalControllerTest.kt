package io.github.betterzslf.feature.campusnet

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private const val PORTAL = "http://9.9.9.9"
private fun clock(at: Long = 1_000_000L): () -> Long = { at }

private class FakePortalSource(
    var info: CampusOutcome<CampusOnlineStatus>,
    var dial: CampusOutcome<CampusDialResult> = CampusOutcome.Success(CampusDialResult.Accepted),
    var redirect: CampusOutcome<CampusRedirectTarget> = CampusOutcome.Success(CampusRedirectTarget(PORTAL)),
    var offlineResult: CampusOutcome<Unit> = CampusOutcome.Success(Unit),
) : CampusPortalSource {
    var dials = 0
        private set
    var offlines = 0
        private set
    var current = true
    override fun isCurrentSession() = current
    override suspend fun internetInfo() = info
    override suspend fun redirectTarget() = redirect
    override suspend fun dialIn(portalUrl: String): CampusOutcome<CampusDialResult> { dials++; return dial }
    override suspend fun offline(): CampusOutcome<Unit> { offlines++; return offlineResult }
}

private class FakeProbe(private val result: PortalProbeResult) : CampusPortalProbeSource {
    var probes = 0
        private set
    override suspend fun probe(portalUrl: String): PortalProbeResult { probes++; return result }
}

private fun online() = CampusOutcome.Success(CampusOnlineStatus.Online)
private fun offline() = CampusOutcome.Success(CampusOnlineStatus.Offline)
private fun answeredRedirect(url: String) = PortalProbeResult.Answered(url, redirected = true)
private fun answeredDirect(url: String) = PortalProbeResult.Answered(url, redirected = false)

class CampusPortalControllerTest {
    @Test fun openingThePageOnlyReadsStatusAndNeverAuthenticates() = runTest {
        val source = FakePortalSource(info = offline())
        val probe = FakeProbe(answeredRedirect("$PORTAL/a/wifi?wlanuserip=1"))
        val controller = CampusPortalController(source, probe, clock())
        assertTrue(controller.refresh())
        assertEquals(CampusPortalPhase.Offline, controller.state.value.phase)
        assertEquals(0, source.dials); assertEquals(0, probe.probes)
        assertNull(controller.state.value.connectedAtMillis)
    }

    @Test fun connectionProbesThenDialsWithTheProbedUrlAndRecordsOneStartTime() = runTest {
        val source = FakePortalSource(info = offline())
        val probed = "$PORTAL/a/wifi?wlanuserip=10.0.0.2&wlanacip=10.0.0.1"
        val probe = FakeProbe(answeredRedirect(probed))
        val controller = CampusPortalController(source, probe, clock(555))
        assertTrue(controller.connect())
        assertEquals(CampusPortalPhase.Online, controller.state.value.phase)
        assertEquals(1, source.dials); assertEquals(1, probe.probes)
        assertEquals(555L, controller.state.value.connectedAtMillis)
        assertEquals(PORTAL, controller.state.value.portalAddress)
    }

    @Test fun eachStageFailureIsReportedSeparatelyInsteadOfOneVagueMessage() = runTest {
        val unreachableSource = FakePortalSource(offline())
        val unreachable = CampusPortalController(unreachableSource, FakeProbe(PortalProbeResult.Unreachable), clock())
        assertFalse(unreachable.connect())
        assertEquals(CampusPortalPhase.Failed, unreachable.state.value.phase)
        assertEquals(io.github.betterzslf.core.text.AppText.CampusNetwork.PORTAL_NOT_ON_CAMPUS_DETAIL, unreachable.state.value.detail)
        assertEquals(0, unreachableSource.dials)
        val missing = CampusPortalController(FakePortalSource(offline()), FakeProbe(PortalProbeResult.Blocked), clock())
        assertFalse(missing.connect()); assertEquals(CampusPortalPhase.Failed, missing.state.value.phase)
        val noRedirect = CampusPortalController(
            FakePortalSource(offline(), dial = CampusOutcome.Success(CampusDialResult.Rejected(CampusDialRejection.NoRedirect, "登录失败：no redirect 01"))),
            FakeProbe(answeredDirect(PORTAL)), clock())
        assertFalse(noRedirect.connect()); assertEquals(CampusPortalPhase.NotOnCampus, noRedirect.state.value.phase)
    }

    @Test fun aRejectedDialIsNotRetriedAutomatically() = runTest {
        val source = FakePortalSource(offline(), dial = CampusOutcome.Success(CampusDialResult.Rejected(CampusDialRejection.PasswordWrong, "PassWord error")))
        val controller = CampusPortalController(source, FakeProbe(answeredRedirect(PORTAL)), clock())
        assertFalse(controller.connect()); assertEquals(CampusPortalPhase.Failed, controller.state.value.phase)
        assertEquals(io.github.betterzslf.core.text.AppText.CampusNetwork.PORTAL_PASSWORD_WRONG_DETAIL, controller.state.value.detail)
        assertEquals(1, source.dials)
    }

    @Test fun aDialThatThrowsIsUnconfirmedNotSuccessful() = runTest {
        val controller = CampusPortalController(FakePortalSource(offline(), dial = CampusOutcome.Failure(CampusIssue.NETWORK)),
            FakeProbe(answeredRedirect(PORTAL)), clock())
        assertFalse(controller.connect()); assertEquals(CampusPortalPhase.Failed, controller.state.value.phase)
        assertNull(controller.state.value.connectedAtMillis)
    }

    @Test fun disconnectOnlyRunsOnExplicitRequestAndClearsTheStartTime() = runTest {
        val source = FakePortalSource(info = online())
        val controller = CampusPortalController(source, FakeProbe(answeredRedirect(PORTAL)), clock(7))
        assertTrue(controller.refresh()); assertEquals(CampusPortalPhase.Online, controller.state.value.phase)
        assertEquals(7L, controller.state.value.connectedAtMillis); assertEquals(0, source.offlines)
        assertTrue(controller.disconnect()); assertEquals(1, source.offlines)
        assertEquals(CampusPortalPhase.Offline, controller.state.value.phase); assertNull(controller.state.value.connectedAtMillis)
    }

    @Test fun aStaleSessionNeverDialsOrLogsOut() = runTest {
        val source = FakePortalSource(info = online()).apply { current = false }
        val controller = CampusPortalController(source, FakeProbe(answeredRedirect(PORTAL)), clock())
        assertFalse(controller.connect()); assertFalse(controller.disconnect())
        assertEquals(0, source.dials); assertEquals(0, source.offlines)
        assertEquals(CampusPortalPhase.NotSignedIn, controller.state.value.phase)
    }

    @Test fun concurrentTapsProduceExactlyOneAttempt() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = object : CampusPortalSource {
            override fun isCurrentSession() = true
            override suspend fun internetInfo(): CampusOutcome<CampusOnlineStatus> = CampusOutcome.Success(CampusOnlineStatus.Offline)
            override suspend fun redirectTarget() = CampusOutcome.Success(CampusRedirectTarget(PORTAL))
            override suspend fun dialIn(portalUrl: String): CampusOutcome<CampusDialResult> {
                gate.await(); return CampusOutcome.Success(CampusDialResult.Accepted)
            }
            override suspend fun offline(): CampusOutcome<Unit> = CampusOutcome.Success(Unit)
        }
        val controller = CampusPortalController(source, FakeProbe(answeredRedirect(PORTAL)), clock())
        val first = launch { controller.connect() }; val second = launch { controller.connect() }
        gate.complete(Unit); first.join(); second.join()
        assertEquals(CampusPortalPhase.Online, controller.state.value.phase)
    }

    @Test fun unreadableStatusIsFailureNotOffline() = runTest {
        val source = FakePortalSource(info = CampusOutcome.Success(CampusOnlineStatus.Unreadable("?")))
        val controller = CampusPortalController(source, FakeProbe(answeredRedirect(PORTAL)), clock())
        assertFalse(controller.refresh()); assertEquals(CampusPortalPhase.Failed, controller.state.value.phase)
    }

    @Test fun onlyTheAllowlistedGatewayMayBeProbedInCleartext() {
        assertTrue(campusPortalAddressAllowed("http://9.9.9.9"))
        assertTrue(campusPortalAddressAllowed("http://9.9.9.9/a/wifi?wlanuserip=1"))
        assertTrue(campusPortalAddressAllowed("https://9.9.9.9"))
        assertFalse(campusPortalAddressAllowed("http://10.0.0.1"))
        assertFalse(campusPortalAddressAllowed("http://9.9.9.9.evil.example"))
        assertFalse(campusPortalAddressAllowed("ftp://9.9.9.9")); assertFalse(campusPortalAddressAllowed(""))
        runTest {
            val controller = CampusPortalController(FakePortalSource(offline()), FakeProbe(PortalProbeResult.Blocked), clock())
            assertFalse(controller.connect()); assertEquals(CampusPortalPhase.Failed, controller.state.value.phase)
        }
    }

    @Test fun closeDuringRedirectDoesNotStartAProbeOrPublishTheLateAddress() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var probes = 0; var dials = 0
        val source = object : CampusPortalSource {
            override fun isCurrentSession() = true
            override suspend fun internetInfo(): CampusOutcome<CampusOnlineStatus> = online()
            override suspend fun redirectTarget(): CampusOutcome<CampusRedirectTarget> {
                started.complete(Unit); release.await(); return CampusOutcome.Success(CampusRedirectTarget(PORTAL))
            }
            override suspend fun dialIn(portalUrl: String): CampusOutcome<CampusDialResult> { dials++; return CampusOutcome.Success(CampusDialResult.Accepted) }
            override suspend fun offline(): CampusOutcome<Unit> = CampusOutcome.Success(Unit)
        }
        val controller = CampusPortalController(source, CampusPortalProbeSource { probes++; answeredDirect(it) }, clock())
        val request = launch { assertFalse(controller.connect()) }
        started.await(); controller.close(); val retired = controller.state.value
        release.complete(Unit); request.join()
        assertEquals(0, probes); assertEquals(0, dials); assertEquals(retired, controller.state.value)
    }

    @Test fun closeDuringProbeDoesNotDialOrPublishTheLateObservation() = runTest {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val source = FakePortalSource(offline())
        val controller = CampusPortalController(source, CampusPortalProbeSource {
            started.complete(Unit); release.await(); answeredDirect(it)
        }, clock())
        val request = launch { assertFalse(controller.connect()) }
        started.await(); controller.close(); val retired = controller.state.value
        release.complete(Unit); request.join()
        assertEquals(0, source.dials); assertEquals(retired, controller.state.value)
    }
}
