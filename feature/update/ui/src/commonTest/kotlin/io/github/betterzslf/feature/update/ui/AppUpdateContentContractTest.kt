package io.github.betterzslf.feature.update.ui

import io.github.betterzslf.feature.update.AppUpdateEvent
import io.github.betterzslf.feature.update.AppUpdateUiState
import kotlin.test.*

/** Rendering-level intent contract only. No repository, native service or Compose instrumentation. */
class AppUpdateContentContractTest {
    @Test
    fun disabledSnapshotDispatchesOnlyNavigationAndNeverSystemActions() {
        val state = AppUpdateUiState()
        for (event in AppUpdateEvent.entries) {
            assertEquals(if (event in setOf(AppUpdateEvent.Back, AppUpdateEvent.OpenRelease)) event else null, state.eventFor(event))
        }
    }

    @Test
    fun eachConfirmationRequiresBothExplicitDialogAndEnabledLiveAction() {
        val canDownload = AppUpdateUiState(canDownload = true)
        assertEquals(AppUpdateEvent.RequestDownload, canDownload.eventFor(AppUpdateEvent.RequestDownload))
        assertNull(canDownload.eventFor(AppUpdateEvent.ConfirmDownload))
        assertEquals(AppUpdateEvent.ConfirmDownload, canDownload.copy(downloadConfirmation = true).eventFor(AppUpdateEvent.ConfirmDownload))
        assertNull(canDownload.copy(canDownload = false, downloadConfirmation = true).eventFor(AppUpdateEvent.ConfirmDownload))
        val canInstall = AppUpdateUiState(canInstall = true)
        assertEquals(AppUpdateEvent.RequestInstall, canInstall.eventFor(AppUpdateEvent.RequestInstall))
        assertNull(canInstall.eventFor(AppUpdateEvent.ConfirmInstall))
        assertEquals(AppUpdateEvent.ConfirmInstall, canInstall.copy(installConfirmation = true).eventFor(AppUpdateEvent.ConfirmInstall))
        assertNull(canInstall.copy(canInstall = false, installConfirmation = true).eventFor(AppUpdateEvent.ConfirmInstall))
    }

    @Test
    fun checkCancellationAndDialogDismissalHaveTheirOwnIndependentGates() {
        val checking = AppUpdateUiState(checking = true)
        assertEquals(AppUpdateEvent.CancelCheck, checking.eventFor(AppUpdateEvent.CancelCheck))
        assertNull(checking.eventFor(AppUpdateEvent.Check))
        assertEquals(AppUpdateEvent.Check, checking.copy(checking = false, canCheck = true).eventFor(AppUpdateEvent.Check))
        assertNull(checking.eventFor(AppUpdateEvent.CancelDownload))
        assertEquals(AppUpdateEvent.CancelDownload, checking.copy(canCancel = true).eventFor(AppUpdateEvent.CancelDownload))
        assertEquals(AppUpdateEvent.DismissDownload, AppUpdateUiState(downloadConfirmation = true).eventFor(AppUpdateEvent.DismissDownload))
        assertEquals(AppUpdateEvent.DismissInstall, AppUpdateUiState(installConfirmation = true).eventFor(AppUpdateEvent.DismissInstall))
    }
}
