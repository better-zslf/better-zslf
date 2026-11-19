package io.github.betterzslf.feature.accountrecovery.ui

import androidx.compose.runtime.Composable
import io.github.betterzslf.feature.accountrecovery.AccountRecoveryPhase
import io.github.betterzslf.feature.accountrecovery.AccountRecoveryPreview
import io.github.betterzslf.feature.accountrecovery.AccountRecoveryState
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text

class RecoveryContentControlsTest {
    @Test fun idleControlsCannotDispatchWritesOrVerification() {
        val controls = RecoveryContentControls()
        assertFalse(controls.allows(RecoveryUiEvent.CONFIRM_SMS))
        assertFalse(controls.allows(RecoveryUiEvent.CONFIRM_RESET))
        assertFalse(controls.allows(RecoveryUiEvent.VERIFY))
        assertFalse(controls.allows(RecoveryUiEvent.PREPARE_RESET))
        assertTrue(controls.allows(RecoveryUiEvent.BACK))
    }

    @Test fun smsConfirmationRequiresAnOpenedDialogAndCurrentAdmission() {
        assertFalse(RecoveryContentControls(canSend = true).allows(RecoveryUiEvent.CONFIRM_SMS))
        assertFalse(RecoveryContentControls(smsDialogOpen = true).allows(RecoveryUiEvent.CONFIRM_SMS))
        assertTrue(RecoveryContentControls(smsDialogOpen = true, canSend = true).allows(RecoveryUiEvent.CONFIRM_SMS))
        assertTrue(RecoveryContentControls(smsDialogOpen = true).allows(RecoveryUiEvent.DISMISS_SMS_CONFIRMATION))
    }

    @Test fun resetConfirmationNeedsAcknowledgementOnAnOpenDialog() {
        assertFalse(RecoveryContentControls(acknowledged = true).allows(RecoveryUiEvent.CONFIRM_RESET))
        assertFalse(RecoveryContentControls(resetDialogOpen = true).allows(RecoveryUiEvent.CONFIRM_RESET))
        val ready = RecoveryContentControls(resetDialogOpen = true, acknowledged = true)
        assertTrue(ready.allows(RecoveryUiEvent.CONFIRM_RESET))
        assertTrue(ready.allows(RecoveryUiEvent.TOGGLE_ACKNOWLEDGEMENT))
        assertFalse(ready.allows(RecoveryUiEvent.OPEN_RESET_CONFIRMATION))
    }

    @Test fun verificationAndPreparationAreIndependentExplicitIntents() {
        val controls = RecoveryContentControls(canVerify = true)
        assertTrue(controls.allows(RecoveryUiEvent.VERIFY))
        assertFalse(controls.allows(RecoveryUiEvent.PREPARE_RESET))
        assertFalse(controls.allows(RecoveryUiEvent.OPEN_SMS_CONFIRMATION))
        assertFalse(controls.allows(RecoveryUiEvent.CONFIRM_RESET))
    }

    @Test fun stoppedDisplaySnapshotNeedsNoControllerOrCredentialPayload() {
        val state = AccountRecoveryState(phase = AccountRecoveryPhase.UNCERTAIN, message = "Fixture stopped notice")
        val controls = RecoveryContentControls(backNotice = true)
        assertTrue(state.terminal)
        assertFalse(state.editable)
        assertTrue(controls.allows(RecoveryUiEvent.BACK))
        assertTrue(controls.allows(RecoveryUiEvent.DISMISS_BACK_NOTICE))
        assertEquals(12, RecoveryUiEvent.entries.size)
    }
}

/** Compile fixture: all content including receipt can be authored from fake display snapshots only. */
@Composable
internal fun RecoveryFakeContentFixture() {
    AccountRecoveryContent(
        state = AccountRecoveryState(phase = AccountRecoveryPhase.PREVIEW,
            preview = AccountRecoveryPreview("138••••00")),
        controls = RecoveryContentControls(resetDialogOpen = true, resetMaskedPhone = "138••••00"),
        onEvent = {},
        phoneField = { enabled -> Text("Fake phone slot enabled=$enabled") },
        codeField = { enabled -> Text("Fake code slot enabled=$enabled") },
        passwordFields = { enabled -> Text("Fake password slot enabled=$enabled") },
    )
    RecoveryReceiptContent(cleanupWarning = false, onBack = {})
}
