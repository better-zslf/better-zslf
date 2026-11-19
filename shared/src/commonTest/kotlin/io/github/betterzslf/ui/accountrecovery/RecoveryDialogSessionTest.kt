package io.github.betterzslf.ui.accountrecovery

import io.github.betterzslf.feature.accountrecovery.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private class DialogRecoverySource : AccountRecoverySource {
    var current = true
    var loggedOut = false
    var smsPhones = mutableListOf<String>()
    var resets = 0
    override fun isCurrentSession() = current
    override fun isLoggedOut() = loggedOut
    override suspend fun sendCode(phone: String): RecoverySmsOutcome {
        smsPhones += phone
        return RecoverySmsOutcome.Accepted
    }
    override suspend fun verify(phone: String, code: String): RecoveryVerifyOutcome =
        RecoveryVerifyOutcome.Verified(RecoveryTarget.fromVerifiedId("fixture-verified-id")!!)
    override suspend fun reset(target: RecoveryTarget, password: String): RecoveryResetOutcome {
        resets++
        current = false; loggedOut = true
        return RecoveryResetOutcome.Accepted(RecoveryLogout.CLEARED)
    }
}

class RecoveryDialogSessionTest {
    private val phone = "13800138000"
    private val nextPhone = "13900139000"
    private val editing = AccountRecoveryState()
    private suspend fun prepared(source: DialogRecoverySource = DialogRecoverySource()): AccountRecoveryController =
        AccountRecoveryController(source).also {
            assertTrue(it.sendCode(phone, confirmed = true))
            assertTrue(it.verify(phone, "fixture-code"))
            assertTrue(it.prepare(phone, "fixture-code", "Fixture9!", "Fixture9!"))
        }

    @Test fun smsUsesThePhoneCapturedAtDialogOpenNotTheLatestField() {
        val dialogs = RecoveryDialogSession({ true }, { false })
        var field = phone
        assertTrue(dialogs.openSms(field, editing))
        assertEquals("138••••00", dialogs.smsMaskedPhone(editing))
        field = nextPhone // The capture itself never reads the current field at confirmation.
        val captured = assertNotNull(dialogs.takeSms(editing))
        assertEquals(phone, captured.phone)
        assertNotEquals(field, captured.phone)
        assertNull(dialogs.takeSms(editing))
        assertFalse(captured.toString().contains(phone))
    }

    @Test fun fieldChangeImmediatelyRetiresSmsBeforeAnyComposeEffect() {
        val dialogs = RecoveryDialogSession({ true }, { false })
        dialogs.openSms(phone, editing)
        val captured = assertNotNull(dialogs.takeSms(editing))
        dialogs.inputsChanged()
        assertFalse(dialogs.smsCurrent(captured, editing))
        assertFalse(dialogs.smsVisible(editing))
    }

    @Test fun newerSmsDialogCannotAuthorizeAnOlderCapturedPhone() {
        val dialogs = RecoveryDialogSession({ true }, { false })
        dialogs.openSms(phone, editing)
        val old = assertNotNull(dialogs.takeSms(editing))
        dialogs.openSms(nextPhone, editing)
        assertFalse(dialogs.smsCurrent(old, editing))
        assertEquals(nextPhone, assertNotNull(dialogs.takeSms(editing)).phone)
    }

    @Test fun smsConfirmationRechecksLiveCooldownBusyAndInputRevision() {
        val dialogs = RecoveryDialogSession({ true }, { false })
        dialogs.openSms(phone, editing)
        val captured = assertNotNull(dialogs.takeSms(editing))
        assertFalse(dialogs.smsCurrent(captured, editing.copy(cooldownSeconds = 1)))
        assertFalse(dialogs.smsCurrent(captured, editing.copy(sending = true)))
        assertFalse(dialogs.smsCurrent(captured, editing.copy(inputRevision = 1)))
        assertFalse(dialogs.smsCurrent(captured, editing.copy(phase = AccountRecoveryPhase.SESSION_CHANGED)))
    }

    @Test fun invalidPhoneAndRetiredSessionCannotOpenSmsConfirmation() {
        var current = true
        val dialogs = RecoveryDialogSession({ current }, { false })
        assertFalse(dialogs.openSms("not-a-phone", editing))
        current = false
        assertFalse(dialogs.openSms(phone, editing))
        assertNull(dialogs.takeSms(editing))
    }

    @Test fun sessionEpochChangeImmediatelyRetiresAllTakenDialogAuthority() = runTest {
        val controller = prepared()
        var current = true
        val dialogs = RecoveryDialogSession({ current }, controller::isResetTicketCurrent)
        assertTrue(dialogs.openReset(controller.state.value.preview, { controller.state.value }, controller::captureResetTicket))
        dialogs.toggleAcknowledgement(controller.state.value)
        val captured = assertNotNull(dialogs.takeReset(controller.state.value))
        current = false
        assertFalse(dialogs.resetCurrent(captured, controller.state.value))
        assertFalse(dialogs.resetVisible(controller.state.value))
    }

    @Test fun resetNeedsAcknowledgementForThisExactOpenedDialog() = runTest {
        val controller = prepared()
        val dialogs = RecoveryDialogSession({ true }, controller::isResetTicketCurrent)
        val ticket = assertNotNull(controller.captureResetTicket())
        assertTrue(dialogs.openReset(controller.state.value.preview, { controller.state.value }, { ticket }))
        assertNull(dialogs.takeReset(controller.state.value))
        assertTrue(dialogs.openReset(controller.state.value.preview, { controller.state.value }, { ticket }))
        dialogs.toggleAcknowledgement(controller.state.value)
        assertTrue(dialogs.acknowledged)
        dialogs.dismissReset()
        assertFalse(dialogs.acknowledged)
        assertTrue(dialogs.openReset(controller.state.value.preview, { controller.state.value }, { ticket }))
        assertFalse(dialogs.acknowledged)
        assertNull(dialogs.takeReset(controller.state.value))
    }

    @Test fun changingPreviewDoesNotSubstituteANewerTicketForTheOldDialog() = runTest {
        val source = DialogRecoverySource()
        val controller = prepared(source)
        val dialogs = RecoveryDialogSession({ true }, controller::isResetTicketCurrent)
        val oldTicket = assertNotNull(controller.captureResetTicket())
        assertTrue(dialogs.openReset(controller.state.value.preview, { controller.state.value }, { oldTicket }))
        dialogs.toggleAcknowledgement(controller.state.value)
        val captured = assertNotNull(dialogs.takeReset(controller.state.value))
        controller.editPreview()
        assertTrue(controller.prepare(phone, "fixture-code", "Other9!x", "Other9!x"))
        assertNotNull(controller.captureResetTicket())
        assertFalse(dialogs.resetCurrent(captured, controller.state.value))
        assertFalse(controller.confirm(captured.ticket, acknowledged = true))
        assertEquals(0, source.resets)
    }

    @Test fun oldRenderedOpenIntentRejectsANewerEqualMaskedPreviewBeforeCapturingItsTicket() = runTest {
        val controller = prepared()
        val dialogs = RecoveryDialogSession({ true }, controller::isResetTicketCurrent)
        val displayedPreview = assertNotNull(controller.state.value.preview)
        controller.editPreview()
        assertTrue(controller.prepare(phone, "fixture-code", "Other9!x", "Other9!x"))
        val newerPreview = assertNotNull(controller.state.value.preview)
        assertEquals(displayedPreview, newerPreview)
        assertNotSame(displayedPreview, newerPreview)
        var captures = 0
        assertFalse(dialogs.openReset(displayedPreview, { controller.state.value }) {
            captures++
            controller.captureResetTicket()
        })
        assertEquals(0, captures)
        assertFalse(dialogs.resetVisible(controller.state.value))
        assertTrue(dialogs.openReset(newerPreview, { controller.state.value }, controller::captureResetTicket))
        assertFalse(dialogs.acknowledged)
    }

    @Test fun previewReplacementDuringTicketCaptureCannotOpenAConfirmation() = runTest {
        val controller = prepared()
        val dialogs = RecoveryDialogSession({ true }, controller::isResetTicketCurrent)
        val displayedPreview = controller.state.value.preview
        assertFalse(dialogs.openReset(displayedPreview, { controller.state.value }) {
            controller.editPreview()
            assertTrue(controller.prepare(phone, "fixture-code", "Other9!x", "Other9!x"))
            controller.captureResetTicket()
        })
        assertFalse(dialogs.resetVisible(controller.state.value))
        assertFalse(dialogs.acknowledged)
    }

    @Test fun fixedResetTicketDispatchesOnlyOnceAndDoesNotLeakThroughDiagnostics() = runTest {
        val source = DialogRecoverySource()
        val controller = prepared(source)
        val dialogs = RecoveryDialogSession({ true }, controller::isResetTicketCurrent)
        assertTrue(dialogs.openReset(controller.state.value.preview, { controller.state.value }, controller::captureResetTicket))
        assertEquals("138••••00", dialogs.resetMaskedPhone(controller.state.value))
        dialogs.toggleAcknowledgement(controller.state.value)
        val captured = assertNotNull(dialogs.takeReset(controller.state.value))
        assertTrue(dialogs.resetCurrent(captured, controller.state.value))
        assertTrue(controller.confirm(captured.ticket, acknowledged = true))
        assertFalse(controller.confirm(captured.ticket, acknowledged = true))
        assertEquals(1, source.resets)
        assertFalse(dialogs.toString().contains(phone))
        assertFalse(captured.toString().contains("fixture-verified-id"))
        assertNull(dialogs.takeReset(controller.state.value))
    }

    @Test fun dialogCannotBorrowAnotherControllerTicketWithTheSamePrivateNonce() = runTest {
        val first = prepared()
        val second = prepared()
        val dialogs = RecoveryDialogSession({ true }, first::isResetTicketCurrent)
        assertFalse(dialogs.openReset(first.state.value.preview, { first.state.value }, second::captureResetTicket))
        assertNull(dialogs.takeReset(first.state.value))
    }

    @Test fun inputChangeAndCloseRetireAlreadyTakenResetAuthoritySynchronously() = runTest {
        val controller = prepared()
        val dialogs = RecoveryDialogSession({ true }, controller::isResetTicketCurrent)
        dialogs.openReset(controller.state.value.preview, { controller.state.value }, controller::captureResetTicket)
        dialogs.toggleAcknowledgement(controller.state.value)
        val captured = assertNotNull(dialogs.takeReset(controller.state.value))
        dialogs.inputsChanged()
        assertFalse(dialogs.resetCurrent(captured, controller.state.value))
        dialogs.close(); dialogs.close()
        assertFalse(dialogs.openReset(controller.state.value.preview, { controller.state.value }, controller::captureResetTicket))
    }

    @Test fun staleFrameIntentCannotPickUpANewerDialogAfterSynchronousEdit() {
        val dialogs = RecoveryDialogSession({ true }, { false })
        assertTrue(dialogs.openSms(phone, editing))
        val renderedGeneration = dialogs.inputGeneration
        assertTrue(dialogs.acceptsEventsFrom(renderedGeneration))
        dialogs.inputsChanged()
        assertTrue(dialogs.openSms(nextPhone, editing))
        assertFalse(dialogs.acceptsEventsFrom(renderedGeneration))
        assertTrue(dialogs.acceptsEventsFrom(dialogs.inputGeneration))
        assertEquals(nextPhone, assertNotNull(dialogs.takeSms(editing)).phone)
    }

    @Test fun separateDialogOwnersCannotBorrowSmsCapturesOrReviveDismissedOnes() {
        val first = RecoveryDialogSession({ true }, { false })
        val second = RecoveryDialogSession({ true }, { false })
        first.openSms(phone, editing)
        val captured = assertNotNull(first.takeSms(editing))
        assertFalse(second.smsCurrent(captured, editing))
        first.dismissSms()
        assertFalse(first.smsCurrent(captured, editing))
    }
}
