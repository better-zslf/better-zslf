package io.github.betterzslf.ui.run

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.runui.RunManualDraftError
import io.github.betterzslf.platform.RunMapCoordinateSystem
import io.github.betterzslf.platform.RunMapEvent
import io.github.betterzslf.platform.RunMapFailure
import io.github.betterzslf.platform.RunMapProvider
import kotlin.test.*

/** Pure session policy only: no SDK, HTTP, location, repository or native host is exercised. */
class RunManualDraftSessionTest {
    private class Fixture(provider: RunMapProvider? = RunMapProvider.AMAP, key: String? = "fixture-map-key") {
        val owner = Any()
        val controller = Any()
        val identity = RunManualDraftIdentity(owner, controller, provider, key)
        var identityCurrent = true
        var admissionCurrent = true
        val session = RunManualDraftSession(identity, { identityCurrent }, { admissionCurrent })
        val state get() = session.state.value
        fun event(event: RunMapEvent): Boolean = session.onEvent(identity, session.mount, event)
        fun ready() { assertTrue(event(RunMapEvent.Ready)) }
        fun draw(longitude: Double = 113.4, frame: RunMapCoordinateSystem = identity.frame): Boolean =
            event(RunMapEvent.DrawPoint(GeoPoint(longitude, 22.5), frame))
        fun twoPoints() { ready(); assertTrue(draw()); assertTrue(draw(113.401)) }
    }

    @Test fun publicationGuardRequiresTheExactPendingCommitAndLiveIdentity() {
        val f = Fixture()
        f.twoPoints()
        val commit = assertNotNull(f.session.takeCommit())
        val other = Fixture().apply { twoPoints() }
        val foreign = assertNotNull(other.session.takeCommit())
        assertTrue(f.session.canPublishCommit(commit))
        assertFalse(f.session.canPublishCommit(foreign))
        f.identityCurrent = false
        assertFalse(f.session.canPublishCommit(commit))
        assertEquals(RunManualDraftPhase.SAVING, f.state.phase) // Pure predicate, no delayed SideEffect required.
    }

    @Test fun closingBeforePublicationImmediatelyInvalidatesThePendingCommit() {
        val f = Fixture()
        f.twoPoints()
        val commit = assertNotNull(f.session.takeCommit())
        f.session.close()
        assertFalse(f.session.canPublishCommit(commit))
        assertEquals(RunManualDraftPhase.CLOSED, f.state.phase)
        assertEquals(0, f.state.pointCount)
    }

    @Test fun publicationGuardIgnoresOnlyItsOwnAdmissionBusyTransition() {
        val f = Fixture()
        f.twoPoints()
        val commit = assertNotNull(f.session.takeCommit())
        f.admissionCurrent = false
        assertTrue(f.session.canPublishCommit(commit))
        assertTrue(f.session.finishCommit(commit, accepted = true))
        assertFalse(f.session.canPublishCommit(commit))
        assertEquals(RunManualDraftPhase.SAVED, f.state.phase)
    }

    @Test fun configuredEmptySessionStartsPendingAndHasNoSyntheticPosition() {
        val f = Fixture()
        assertEquals(RunManualMapStatus.PENDING, f.state.mapStatus)
        assertEquals(0, f.state.pointCount)
        assertFalse(f.state.canSave)
        assertFalse(f.state.canUndo)
        val model = f.session.mapModel(dark = false)
        assertNull(model.currentLocation)
        assertTrue(model.actualPath.allSegments().isEmpty())
        assertTrue(model.recommendedPath.allSegments().isEmpty())
        assertTrue(model.importedPath.allSegments().isEmpty())
        assertTrue(model.checkpoints.isEmpty())
        assertTrue(model.editMode)
    }

    @Test fun missingProviderOrBlankKeyHasConfigurationFailureAndNoSave() {
        listOf(Fixture(provider = null), Fixture(key = null), Fixture(key = "  ")).forEach { f ->
            assertFalse(f.identity.configured)
            assertEquals(RunMapFailure.CONFIGURATION, f.state.failure)
            assertFalse(f.event(RunMapEvent.Ready))
            assertFalse(f.draw())
            assertNull(f.session.takeCommit())
            assertFalse(f.state.canSave)
        }
    }

    @Test fun pendingDrawAndSaveDoNotManufactureReadyOrChangeDraft() {
        val f = Fixture()
        val before = f.state
        assertFalse(f.draw())
        assertNull(f.session.takeCommit())
        assertSame(before, f.state)
    }

    @Test fun genuinelyReadyEmptyMapCanStillReceiveItsFirstPoint() {
        val f = Fixture()
        f.ready()
        assertEquals(RunManualMapStatus.READY, f.state.mapStatus)
        assertFalse(f.state.canSave)
        assertTrue(f.draw())
        assertEquals(1, f.state.pointCount)
        assertTrue(f.state.canUndo)
        assertFalse(f.state.canSave)
    }

    @Test fun twoActualDrawEventsPermitACommitInTheirOriginalFrame() {
        val f = Fixture()
        f.twoPoints()
        assertTrue(f.state.canSave)
        val route = assertNotNull(assertNotNull(f.session.takeCommit()).route)
        assertEquals(RunMapProvider.AMAP, route.provider)
        assertEquals(RunMapCoordinateSystem.GCJ02, route.coordinateSystem)
        assertEquals(listOf(GeoPoint(113.4, 22.5), GeoPoint(113.401, 22.5)), route.points)
    }

    @Test fun tianDiTuEventsRemainWgs84WithoutCoordinateRelabelling() {
        val f = Fixture(RunMapProvider.TIAN_DI_TU)
        f.twoPoints()
        assertEquals(RunMapCoordinateSystem.WGS84, f.session.mapModel(false).importedPath.coordinateSystem)
        assertEquals(RunMapCoordinateSystem.WGS84, assertNotNull(assertNotNull(f.session.takeCommit()).route).coordinateSystem)
    }

    @Test fun onePointCommitOnlyAddsAnExplicitLocalValidationError() {
        val f = Fixture()
        f.ready(); f.draw()
        assertNull(f.session.takeCommit())
        assertEquals(RunManualDraftError.TOO_FEW_POINTS, f.state.editError)
        assertEquals(RunManualDraftPhase.EDITING, f.state.phase)
        assertEquals(1, f.state.pointCount)
    }

    @Test fun mismatchedFrameIsNotConvertedIntoTheCurrentDraft() {
        val f = Fixture()
        f.ready()
        assertFalse(f.draw(frame = RunMapCoordinateSystem.WGS84))
        assertEquals(RunManualDraftError.FRAME_MISMATCH, f.state.editError)
        assertEquals(0, f.state.pointCount)
    }

    @Test fun unknownFrameIsRejectedWithoutChangingSdkReadiness() {
        val f = Fixture()
        f.ready()
        assertFalse(f.draw(frame = RunMapCoordinateSystem.UNKNOWN))
        assertEquals(RunManualDraftError.UNKNOWN_FRAME, f.state.editError)
        assertEquals(RunManualMapStatus.READY, f.state.mapStatus)
    }

    @Test fun duplicateFeedbackDoesNotCountAsASecondDistinctPoint() {
        val f = Fixture()
        f.ready(); f.draw()
        assertFalse(f.draw())
        assertEquals(RunManualDraftError.ADJACENT_DUPLICATE, f.state.editError)
        assertFalse(f.state.canSave)
        assertTrue(f.draw(113.401))
        assertNull(f.state.editError)
        assertTrue(f.state.canSave)
    }

    @Test fun undoOnAnEmptyDraftIsBoundedAndDoesNotMarkMapReady() {
        val f = Fixture()
        assertFalse(f.session.undo())
        assertEquals(RunManualDraftError.EMPTY_DRAFT, f.state.editError)
        assertEquals(RunManualMapStatus.PENDING, f.state.mapStatus)
    }

    @Test fun undoToOnePointKeepsRealReadyButDisablesSave() {
        val f = Fixture()
        f.twoPoints()
        assertTrue(f.session.undo())
        assertEquals(1, f.state.pointCount)
        assertEquals(RunManualMapStatus.READY, f.state.mapStatus)
        assertFalse(f.state.canSave)
    }

    @Test fun clearingReadyDraftKeepsTheSameLiveEmptyMount() {
        val f = Fixture()
        f.twoPoints()
        val mount = f.session.mount
        assertTrue(f.session.clear())
        assertEquals(0, f.state.pointCount)
        assertSame(mount, f.session.mount)
        assertEquals(RunManualMapStatus.READY, f.state.mapStatus)
        assertTrue(f.draw())
    }

    @Test fun clearingPendingDraftDoesNotManufactureSdkSuccess() {
        val f = Fixture()
        assertTrue(f.session.clear())
        assertEquals(RunManualMapStatus.PENDING, f.state.mapStatus)
        assertFalse(f.state.canSave)
    }

    @Test fun failedSdkStopsDrawAndSaveEvenWithPreviouslyValidPoints() {
        val f = Fixture()
        f.twoPoints()
        assertTrue(f.event(RunMapEvent.Failed(RunMapFailure.NETWORK)))
        assertFalse(f.draw(113.402))
        assertNull(f.session.takeCommit())
        assertEquals(2, f.state.pointCount)
        assertEquals(RunMapFailure.NETWORK, f.state.failure)
    }

    @Test fun undoAndClearNeverClearAnSdkFailure() {
        val f = Fixture()
        f.twoPoints(); f.event(RunMapEvent.Failed(RunMapFailure.SDK))
        assertTrue(f.session.undo())
        assertTrue(f.session.clear())
        assertEquals(0, f.state.pointCount)
        assertEquals(RunManualMapStatus.FAILED, f.state.mapStatus)
        assertEquals(RunMapFailure.SDK, f.state.failure)
        assertFalse(f.state.canSave)
    }

    @Test fun lateReadyCannotHealAFailedMount() {
        val f = Fixture()
        f.event(RunMapEvent.Failed(RunMapFailure.SDK))
        val before = f.state
        assertFalse(f.event(RunMapEvent.Ready))
        assertSame(before, f.state)
    }

    @Test fun checkpointSelectionHasNoDrawingOrSdkSideEffect() {
        val f = Fixture()
        f.ready()
        val before = f.state
        assertFalse(f.event(RunMapEvent.CheckpointSelected("fixture-checkpoint")))
        assertSame(before, f.state)
    }

    @Test fun expiredLiveOwnerIsRetiredBeforeTheNextEventIsConsumed() {
        val f = Fixture()
        f.twoPoints(); f.identityCurrent = false
        assertFalse(f.draw(113.402))
        assertEquals(RunManualDraftPhase.STALE, f.state.phase)
        assertEquals(0, f.state.pointCount)
        assertNull(f.session.takeCommit())
    }

    @Test fun changedParentAdmissionIsCheckedSynchronouslyAtSave() {
        val f = Fixture()
        f.twoPoints(); f.admissionCurrent = false
        assertNull(f.session.takeCommit())
        assertEquals(RunManualDraftPhase.STALE, f.state.phase)
        assertEquals(0, f.state.pointCount)
    }

    @Test fun staleAdmissionAlsoStopsUndoBeforeMutatingTheNewParentSource() {
        val f = Fixture()
        f.twoPoints(); f.admissionCurrent = false
        assertFalse(f.session.undo())
        assertFalse(f.session.clear())
        assertEquals(RunManualDraftPhase.STALE, f.state.phase)
    }

    @Test fun oldDocumentReadyDrawAndFailedDoNotTouchTheCurrentMount() {
        val f = Fixture()
        val before = f.state
        val oldDocument = Any()
        listOf(RunMapEvent.Ready, RunMapEvent.Failed(RunMapFailure.SDK),
            RunMapEvent.DrawPoint(GeoPoint(113.4, 22.5), RunMapCoordinateSystem.GCJ02)).forEach {
            assertFalse(f.session.onEvent(f.identity, oldDocument, it))
            assertSame(before, f.state)
        }
    }

    @Test fun oldOwnerControllerProviderAndKeyEventsAreAllRejected() {
        val f = Fixture()
        val before = f.state
        val foreign = listOf(
            RunManualDraftIdentity(Any(), f.controller, RunMapProvider.AMAP, "fixture-map-key"),
            RunManualDraftIdentity(f.owner, Any(), RunMapProvider.AMAP, "fixture-map-key"),
            RunManualDraftIdentity(f.owner, f.controller, RunMapProvider.TIAN_DI_TU, "fixture-map-key"),
            RunManualDraftIdentity(f.owner, f.controller, RunMapProvider.AMAP, "replacement-key"),
        )
        foreign.forEach { identity ->
            assertFalse(f.session.onEvent(identity, f.session.mount, RunMapEvent.Ready))
            assertSame(before, f.state)
        }
    }

    @Test fun changedProviderNeedsAnEmptyPendingSessionAndNewMount() {
        val f = Fixture()
        f.twoPoints(); f.session.close()
        val nextIdentity = RunManualDraftIdentity(f.owner, f.controller, RunMapProvider.TIAN_DI_TU, "replacement-key")
        assertTrue(f.identity.sharesOwner(nextIdentity))
        val next = RunManualDraftSession(nextIdentity, { true }, { true })
        assertNotSame(f.session.mount, next.mount)
        assertEquals(0, next.state.value.pointCount)
        assertEquals(RunMapCoordinateSystem.WGS84, nextIdentity.frame)
        assertEquals(RunManualMapStatus.PENDING, next.state.value.mapStatus)
        assertFalse(next.onEvent(f.identity, f.session.mount, RunMapEvent.Ready))
    }

    @Test fun reopeningTheSameConfigurationStillDoesNotReuseTheOldDocument() {
        val f = Fixture()
        val next = RunManualDraftSession(f.identity, { true }, { true })
        assertNotSame(f.session.mount, next.mount)
        assertFalse(next.onEvent(f.identity, f.session.mount, RunMapEvent.Ready))
        assertEquals(RunManualMapStatus.PENDING, next.state.value.mapStatus)
    }

    @Test fun takeCommitClosesAllEditAdmissionBeforeAnyAsyncWork() {
        val f = Fixture()
        f.twoPoints()
        assertNotNull(f.session.takeCommit())
        assertEquals(RunManualDraftPhase.SAVING, f.state.phase)
        assertFalse(f.state.canSave)
        assertFalse(f.draw(113.402))
        assertFalse(f.session.undo())
        assertFalse(f.session.clear())
        assertNull(f.session.takeCommit())
        assertFalse(f.session.mapModel(false).editMode)
    }

    @Test fun ownSavingAndReplacementMayInvalidateAdmissionWithoutLosingAcceptedResult() {
        val f = Fixture()
        f.twoPoints()
        val commit = assertNotNull(f.session.takeCommit())
        f.admissionCurrent = false // parent busy/new state caused by this very operation
        assertTrue(f.session.refresh())
        assertTrue(f.session.finishCommit(commit, accepted = true))
        assertEquals(RunManualDraftPhase.SAVED, f.state.phase)
        assertEquals(0, f.state.pointCount)
        assertFalse(f.session.finishCommit(commit, accepted = true))
        assertNull(f.session.takeCommit())
    }

    @Test fun failedCommitIsTerminalAndNeverReusesItsOldAdmission() {
        val f = Fixture()
        f.twoPoints()
        val commit = assertNotNull(f.session.takeCommit())
        assertTrue(f.session.finishCommit(commit, accepted = false))
        assertEquals(RunManualDraftPhase.SAVE_FAILED, f.state.phase)
        assertNull(f.session.takeCommit())
        assertFalse(f.draw(113.402))
        assertFalse(f.session.finishCommit(commit, accepted = true))
    }

    @Test fun anUnrelatedCommitObjectCannotCompleteThisSession() {
        val f = Fixture(); f.twoPoints()
        val ownCommit = assertNotNull(f.session.takeCommit())
        val other = Fixture(); other.twoPoints()
        val otherCommit = assertNotNull(other.session.takeCommit())
        assertFalse(f.session.finishCommit(otherCommit, true))
        assertEquals(RunManualDraftPhase.SAVING, f.state.phase)
        assertTrue(f.session.finishCommit(ownCommit, true))
    }

    @Test fun cancelDuringSaveRetiresBeforeALateAcceptedCallback() {
        val f = Fixture(); f.twoPoints()
        val commit = assertNotNull(f.session.takeCommit())
        f.session.close()
        assertEquals(RunManualDraftPhase.CLOSED, f.state.phase)
        assertEquals(0, f.state.pointCount)
        assertFalse(f.session.finishCommit(commit, true))
        assertFalse(f.draw())
    }

    @Test fun ownerChangeDuringSaveStillRejectsItsLateResult() {
        val f = Fixture(); f.twoPoints()
        val commit = assertNotNull(f.session.takeCommit())
        f.identityCurrent = false
        assertFalse(f.session.finishCommit(commit, true))
        assertEquals(RunManualDraftPhase.STALE, f.state.phase)
    }

    @Test fun closedMountIgnoresReadyDrawAndFailureImmediately() {
        val f = Fixture(); f.twoPoints(); f.session.close()
        val before = f.state
        assertFalse(f.event(RunMapEvent.Ready))
        assertFalse(f.draw())
        assertFalse(f.event(RunMapEvent.Failed(RunMapFailure.SDK)))
        assertSame(before, f.state)
        assertFalse(f.session.mapModel(false).editMode)
    }

    @Test fun diagnosticStringsContainNoKeyOwnerOrCoordinateValues() {
        val f = Fixture(); f.twoPoints()
        val owner = object { override fun toString() = "private-owner-value" }
        val identity = RunManualDraftIdentity(owner, f.controller, RunMapProvider.AMAP, "private-key-value")
        val text = listOf(identity.toString(), f.session.toString(), f.state.toString(), f.session.mapModel(false).toString()).joinToString()
        listOf("private-key-value", "private-owner-value", "fixture-map-key", "113.4", "22.5").forEach { assertFalse(text.contains(it)) }
    }

    @Test fun sessionSurfacesTheCoreBudgetWithoutRenderingAnExtraPoint() {
        val f = Fixture(); f.ready()
        repeat(2000) { index -> assertTrue(f.draw(113.4 + index * 0.000001)) }
        assertFalse(f.draw(113.5))
        assertEquals(2000, f.state.pointCount)
        assertEquals(RunManualDraftError.POINT_LIMIT_REACHED, f.state.editError)
        assertEquals(2000, f.session.mapModel(false).importedPath.points.size)
        assertTrue(f.state.canSave)
    }

    @Test fun emptyDraftAndFailureMessagesNeverClaimNativeGpsOrSaveSuccess() {
        val f = Fixture()
        assertEquals("正在等待地图服务就绪；尚未接收绘制点。", manualDraftMessage(f.state))
        f.ready()
        assertEquals("地图已就绪，请点选路线；这些点不是 GPS 采样。", manualDraftMessage(f.state))
        f.twoPointsAfterReady()
        val commit = assertNotNull(f.session.takeCommit())
        assertEquals("正在更新本机预览，请等待结果。", manualDraftMessage(f.state))
        f.session.finishCommit(commit, false)
        assertEquals("本次替换未完成，请关闭后重新打开绘制；原有预览保持不变。", manualDraftMessage(f.state))
    }

    private fun Fixture.twoPointsAfterReady() { assertTrue(draw()); assertTrue(draw(113.401)) }
}
