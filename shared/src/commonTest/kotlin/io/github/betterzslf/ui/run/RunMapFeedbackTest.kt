package io.github.betterzslf.ui.run

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunCheckpoint
import io.github.betterzslf.platform.*
import kotlin.test.*

/** Fake events only. No Compose runtime, WebView, bridge, GPS, API or request is executed. */
class RunMapFeedbackTest {
    private val owner = Any()
    private val identity = RunMapFeedbackIdentity(owner, RunMapProvider.AMAP, "public_fixture_key")
    private val document = Any()
    private val a = GeoPoint(104.07, 30.67)
    private val b = GeoPoint(104.08, 30.68)
    private fun data() = RunMapFeedbackData(listOf("fixture" to listOf(a, b)),
        listOf(RunCheckpoint("first", a), RunCheckpoint("second", b)), RunMapCoordinateSystem.WGS84, RunMapRole.IMPORTED)
    private fun initial() = RunMapFeedbackState.initial(identity, document, data())
    private fun ready() = initial().onEvent(identity, document, RunMapEvent.Ready)

    @Test fun dataNeverCreatesReadyBeforeTheSingleRealCallback() {
        var state = initial()
        assertEquals(RunMapDocumentFeedback.Pending, state.status)
        assertNull(state.text())
        state = state.forDocument(identity, document, data().copy(paths = listOf("other" to listOf(b, a))))
        assertEquals(RunMapDocumentFeedback.Pending, state.status)
        assertNull(state.text())
        state = state.onEvent(identity, document, RunMapEvent.Ready)
        assertEquals(RunMapDocumentFeedback.Ready, state.status)
        assertEquals(AppText.Run.MAP_READY_MARKER_VIEW_ONLY_HINT, state.text())
    }

    @Test fun oneReadyCallbackSurvivesRepeatedPathCoordinateAndRoleUpdates() {
        var state = initial()
        var deliveredReady = 0
        state = state.onEvent(identity, document, RunMapEvent.Ready).also { deliveredReady++ }
        val changes = listOf(data().copy(paths = listOf("new" to listOf(b, a))),
            data().copy(coordinateSystem = RunMapCoordinateSystem.GCJ02), data().copy(role = RunMapRole.ACTUAL), data())
        for (next in changes) {
            state = state.forDocument(identity, document, next)
            assertEquals(RunMapDocumentFeedback.Ready, state.status)
            assertEquals(AppText.Run.MAP_READY_MARKER_VIEW_ONLY_HINT, state.text())
        }
        assertEquals(1, deliveredReady)
    }

    @Test fun failedSurvivesDataChangesAndTransientEventsWithoutInventingRecovery() {
        for (reason in RunMapFailure.entries) {
            var state = ready().onEvent(identity, document, RunMapEvent.Failed(reason))
            state = state.forDocument(identity, document, data().copy(paths = listOf("other" to listOf(b))))
            state = state.onEvent(identity, document, RunMapEvent.CheckpointSelected("second"))
            state = state.onEvent(identity, document, RunMapEvent.DrawPoint(a, RunMapCoordinateSystem.WGS84))
            assertEquals(RunMapDocumentFeedback.Failed(reason), state.status)
            assertEquals(AppText.Run.mapFallback(reason.toString()), state.text())
        }
    }

    @Test fun onlyAnActualReadyEventCanRecoverAFailureInTheCurrentDocument() {
        val failed = initial().onEvent(identity, document, RunMapEvent.Failed(RunMapFailure.NETWORK))
        val changed = failed.forDocument(identity, document, data().copy(paths = listOf("new" to listOf(b))))
        assertEquals(RunMapDocumentFeedback.Failed(RunMapFailure.NETWORK), changed.status)
        assertEquals(RunMapDocumentFeedback.Ready, changed.onEvent(identity, document, RunMapEvent.Ready).status)
    }

    @Test fun ownerReplacementClearsReadyAndRejectsOldOwnerEvents() {
        val nextIdentity = RunMapFeedbackIdentity(Any(), RunMapProvider.AMAP, "public_fixture_key")
        val next = ready().forDocument(nextIdentity, document, data())
        assertEquals(RunMapDocumentFeedback.Pending, next.status)
        assertNull(next.text())
        assertSame(next, next.onEvent(identity, document, RunMapEvent.Ready))
        assertSame(next, next.onEvent(identity, document, RunMapEvent.Failed(RunMapFailure.SDK)))
        assertEquals(RunMapDocumentFeedback.Ready, next.onEvent(nextIdentity, document, RunMapEvent.Ready).status)
    }

    @Test fun providerOrKeyChangeClearsFeedbackAndRejectsPreviousConfiguration() {
        val selected = ready().onEvent(identity, document, RunMapEvent.CheckpointSelected("second"))
        for (nextIdentity in listOf(RunMapFeedbackIdentity(owner, RunMapProvider.TIAN_DI_TU, "public_fixture_key"),
            RunMapFeedbackIdentity(owner, RunMapProvider.AMAP, "replacement_key"),
            RunMapFeedbackIdentity(owner, null, null), RunMapFeedbackIdentity(owner, RunMapProvider.AMAP, null))) {
            val next = selected.forDocument(nextIdentity, document, data())
            assertEquals(RunMapDocumentFeedback.Pending, next.status)
            assertNull(next.transient); assertNull(next.text())
            assertSame(next, next.onEvent(identity, document, RunMapEvent.Ready))
            assertSame(next, next.onEvent(identity, document, RunMapEvent.CheckpointSelected("second")))
        }
    }

    @Test fun newMountWithIdenticalConfigurationDoesNotInheritReadyOrAcceptOldCallbacks() {
        val nextDocument = Any()
        val equivalentIdentity = RunMapFeedbackIdentity(owner, RunMapProvider.AMAP, "public_fixture_key")
        assertEquals(identity, equivalentIdentity)
        val next = ready().forDocument(equivalentIdentity, nextDocument, data())
        assertEquals(RunMapDocumentFeedback.Pending, next.status)
        assertSame(next, next.onEvent(identity, document, RunMapEvent.Ready))
        assertSame(next, next.onEvent(identity, document, RunMapEvent.CheckpointSelected("first")))
        assertEquals(RunMapDocumentFeedback.Ready, next.onEvent(equivalentIdentity, nextDocument, RunMapEvent.Ready).status)
    }

    @Test fun emptyDataRetiresMountAndDataReturnNeedsFreshMountAndActualReady() {
        val empty = data().copy(paths = emptyList(), checkpoints = emptyList())
        val retired = ready().forDocument(identity, document, empty)
        assertEquals(RunMapDocumentFeedback.Pending, retired.status)
        assertNull(retired.text())
        assertSame(retired, retired.onEvent(identity, document, RunMapEvent.Ready))
        val oldMountWithData = retired.forDocument(identity, document, data())
        assertSame(oldMountWithData, oldMountWithData.onEvent(identity, document, RunMapEvent.Ready))
        val nextDocument = Any()
        val fresh = oldMountWithData.forDocument(identity, nextDocument, data())
        assertEquals(RunMapDocumentFeedback.Pending, fresh.status)
        assertEquals(RunMapDocumentFeedback.Ready, fresh.onEvent(identity, nextDocument, RunMapEvent.Ready).status)
    }

    @Test fun checkpointOnlyDataStillHasAMapButFullyEmptyDataAcceptsNoEvents() {
        val checkpointOnly = data().copy(paths = emptyList())
        assertTrue(checkpointOnly.hasMapData)
        val checkpointState = RunMapFeedbackState.initial(identity, document, checkpointOnly)
        assertEquals(RunMapDocumentFeedback.Ready, checkpointState.onEvent(identity, document, RunMapEvent.Ready).status)
        val empty = checkpointOnly.copy(checkpoints = emptyList())
        assertFalse(empty.hasMapData)
        val emptyState = RunMapFeedbackState.initial(identity, document, empty)
        assertSame(emptyState, emptyState.onEvent(identity, document, RunMapEvent.Ready))
        assertSame(emptyState, emptyState.onEvent(identity, document, RunMapEvent.Failed(RunMapFailure.SDK)))
    }

    @Test fun dataChangeClearsOldCheckpointOrdinalAndNewSelectionUsesCurrentOrder() {
        val selected = ready().onEvent(identity, document, RunMapEvent.CheckpointSelected("second"))
        assertEquals(AppText.Run.selectedCheckpointNotice("2"), selected.text())
        val reordered = selected.forDocument(identity, document, data().copy(checkpoints = data().checkpoints.reversed()))
        assertNull(reordered.transient)
        assertEquals(AppText.Run.MAP_READY_MARKER_VIEW_ONLY_HINT, reordered.text())
        val newSelection = reordered.onEvent(identity, document, RunMapEvent.CheckpointSelected("second"))
        assertEquals(AppText.Run.selectedCheckpointNotice("1"), newSelection.text())
    }

    @Test fun unknownOrRemovedCheckpointUsesExistingUnknownTextNotPreviousIndex() {
        val selected = ready().onEvent(identity, document, RunMapEvent.CheckpointSelected("second"))
        val changed = selected.forDocument(identity, document, data().copy(checkpoints = listOf(RunCheckpoint("first", a))))
        val unknown = changed.onEvent(identity, document, RunMapEvent.CheckpointSelected("second"))
        assertEquals(RunMapTransientFeedback.Checkpoint(null), unknown.transient)
        assertEquals(AppText.Run.selectedCheckpointNotice(AppText.Common.UNKNOWN), unknown.text())
        assertEquals(RunMapDocumentFeedback.Ready, unknown.status)
        val pendingUnknown = initial().onEvent(identity, document, RunMapEvent.CheckpointSelected("missing"))
        assertEquals(RunMapDocumentFeedback.Pending, pendingUnknown.status)
    }

    @Test fun coordinateOrCheckpointPointChangeClearsOnlyTransientNotice() {
        val selected = ready().onEvent(identity, document, RunMapEvent.CheckpointSelected("first"))
        for (next in listOf(data().copy(coordinateSystem = RunMapCoordinateSystem.GCJ02),
            data().copy(checkpoints = listOf(RunCheckpoint("first", b))), data().copy(role = RunMapRole.RECOMMENDED))) {
            val updated = selected.forDocument(identity, document, next)
            assertNull(updated.transient)
            assertEquals(AppText.Run.MAP_READY_MARKER_VIEW_ONLY_HINT, updated.text())
        }
    }

    @Test fun duplicateReadyIsIdempotentAndDrawNoticeIsNeverARealPositionOrReady() {
        val ready = ready()
        assertSame(ready, ready.onEvent(identity, document, RunMapEvent.Ready))
        val selected = ready.onEvent(identity, document, RunMapEvent.CheckpointSelected("first"))
        assertSame(selected, selected.onEvent(identity, document, RunMapEvent.Ready))
        val draw = initial().onEvent(identity, document, RunMapEvent.DrawPoint(a, RunMapCoordinateSystem.WGS84))
        assertEquals(RunMapDocumentFeedback.Pending, draw.status)
        assertEquals(AppText.Run.MAP_PREVIEW_EVENTS_NOT_REAL_LOCATION_HINT, draw.text())
        val changed = draw.forDocument(identity, document, data().copy(paths = listOf("new" to listOf(b))))
        assertNull(changed.transient); assertNull(changed.text())
    }

    @Test fun sameDataDoesNotResetFeedbackAndDiagnosticsExposeNoKeyCoordinatesOrNotice() {
        val selected = ready().onEvent(identity, document, RunMapEvent.CheckpointSelected("second"))
        assertSame(selected, selected.forDocument(identity, document, data()))
        for (text in listOf(identity.toString(), data().toString(), selected.toString())) {
            assertFalse(text.contains("public_fixture_key"))
            assertFalse(text.contains(a.longitude.toString()))
            assertFalse(text.contains("second"))
        }
    }
}
