package io.github.betterzslf.ui.schedule

import androidx.compose.runtime.Composable
import io.github.betterzslf.core.text.AppText
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text

class ScheduleBindingContentTest {
    @Test fun idleControlsCannotAdmitVerificationOrConfirmation() {
        val controls = ScheduleBindingControls()
        assertFalse(controls.allows(ScheduleBindingUiEvent.VERIFY))
        assertFalse(controls.allows(ScheduleBindingUiEvent.CONFIRM))
        assertTrue(controls.allows(ScheduleBindingUiEvent.BACK))
    }

    @Test fun verificationAndConfirmationHaveIndependentFiniteAdmission() {
        val verify = ScheduleBindingControls(canVerify = true)
        assertTrue(verify.allows(ScheduleBindingUiEvent.VERIFY))
        assertFalse(verify.allows(ScheduleBindingUiEvent.CONFIRM))
        val confirm = ScheduleBindingControls(canConfirm = true, canBack = false)
        assertTrue(confirm.allows(ScheduleBindingUiEvent.CONFIRM))
        assertFalse(confirm.allows(ScheduleBindingUiEvent.VERIFY))
        assertFalse(confirm.allows(ScheduleBindingUiEvent.BACK))
    }

    @Test fun pageControlsNeverAuthorizePromptIntents() {
        val controls = ScheduleBindingControls(canVerify = true, canConfirm = true)
        listOf(ScheduleBindingUiEvent.PROMPT_BIND, ScheduleBindingUiEvent.PROMPT_SKIP,
            ScheduleBindingUiEvent.PROMPT_DISMISS).forEach { assertFalse(controls.allows(it)) }
        assertEquals(6, ScheduleBindingUiEvent.entries.size)
    }

    @Test fun displayHasOnlyFlagsMaskedLabelAndFiniteDiagnosticCopy() {
        val display = ScheduleBindingDisplay(updatingPassword = true, hasSlot = true, testing = true,
            saving = true, verifiedLabel = "ab••••yz", notice = ScheduleBindingNotice.INPUT_CHANGED,
            runtimeMessage = ScheduleBindingMessage.STORAGE_FAILURE)
        assertTrue(display.updatingPassword); assertTrue(display.hasSlot)
        assertTrue(display.testing); assertTrue(display.saving)
        assertEquals("ab••••yz", display.verifiedLabel)
        assertEquals("ScheduleBindingDisplay(redacted)", display.toString())
        assertFalse(display.toString().contains(display.verifiedLabel))
    }

    @Test fun noticesPreserveTheOriginalLocallyAuthoredCopy() {
        assertEquals(AppText.Schedule.NOT_VERIFIED, ScheduleBindingNotice.NOT_VERIFIED.text())
        assertEquals(AppText.Schedule.INPUT_CHANGED, ScheduleBindingNotice.INPUT_CHANGED.text())
        assertEquals(AppText.Schedule.VERIFY_FAILED, ScheduleBindingNotice.VERIFY_FAILED.text())
        assertEquals(AppText.Common.LOCAL_DATA_SAVE_FAILED, ScheduleBindingNotice.SAVE_FAILED.text())
    }

    @Test fun runtimeMessagesAreFiniteRatherThanAcceptingArbitraryServiceText() {
        assertEquals(10, ScheduleBindingMessage.entries.size)
        assertEquals(AppText.Schedule.STORAGE_FAILURE, ScheduleBindingMessage.STORAGE_FAILURE.text())
        assertEquals(AppText.Schedule.CREDENTIALS_REQUIRED, ScheduleBindingMessage.CREDENTIALS_REQUIRED.text())
        assertEquals(AppText.Common.READ_FAILED, ScheduleBindingMessage.READ_FAILED.text())
        assertTrue(ScheduleBindingMessage.entries.all { it.text().isNotBlank() })
    }
}

/** Compile-only fixture: both full Contents accept fake input slots without any product capability. */
@Composable
internal fun ScheduleBindingFakeContents() {
    ScheduleBindingPromptContent(visible = true, onEvent = {})
    ScheduleBindingPageContent(
        display = ScheduleBindingDisplay(hasSlot = true, verifiedLabel = "ab••••yz"),
        controls = ScheduleBindingControls(canVerify = true), onEvent = {},
        accountField = { Text("Fake account field") },
        passwordField = { Text("Fake password field") },
    )
}
