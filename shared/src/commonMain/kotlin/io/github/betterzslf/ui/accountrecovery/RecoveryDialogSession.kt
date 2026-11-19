package io.github.betterzslf.ui.accountrecovery

import io.github.betterzslf.feature.accountrecovery.AccountRecoveryPhase
import io.github.betterzslf.feature.accountrecovery.AccountRecoveryPreview
import io.github.betterzslf.feature.accountrecovery.AccountRecoveryState
import io.github.betterzslf.feature.accountrecovery.RecoveryResetTicket
import io.github.betterzslf.feature.accountrecovery.recoveryPhoneValidation

/** Private product-bound dialog authority. No raw value or ticket is sent to reusable Content. */
internal class RecoveryDialogSession(
    private val isCurrent: () -> Boolean,
    private val ticketIsCurrent: (RecoveryResetTicket) -> Boolean,
) {
    private var mounted = true
    private var generation = 0L
    val inputGeneration: Long get() = generation
    fun acceptsEventsFrom(expectedGeneration: Long): Boolean = current() && generation == expectedGeneration
    private var sms: RecoverySmsConfirmation? = null
    private var reset: RecoveryResetConfirmation? = null
    var acknowledged: Boolean = false
        private set

    fun inputsChanged() {
        generation++
        sms = null; reset = null; acknowledged = false
    }

    fun openSms(phone: String, state: AccountRecoveryState): Boolean {
        inputsChanged()
        if (!current() || !canSend(state) || recoveryPhoneValidation(phone) != null) return false
        sms = RecoverySmsConfirmation(this, generation, state.inputRevision, phone)
        return true
    }

    fun smsVisible(state: AccountRecoveryState): Boolean = sms?.let { smsCurrent(it, state) } == true
    fun smsMaskedPhone(state: AccountRecoveryState): String = sms?.takeIf { smsCurrent(it, state) }
        ?.phone?.let { it.take(3) + "••••" + it.takeLast(2) }.orEmpty()
    fun dismissSms() { inputsChanged() }
    fun takeSms(state: AccountRecoveryState): RecoverySmsConfirmation? {
        val captured = sms
        sms = null
        return captured?.takeIf { smsCurrent(it, state) }
    }
    fun smsCurrent(captured: RecoverySmsConfirmation, state: AccountRecoveryState): Boolean =
        captured.owner === this && captured.generation == generation && captured.inputRevision == state.inputRevision &&
            current() && canSend(state)

    fun openReset(
        displayedPreview: AccountRecoveryPreview?,
        readState: () -> AccountRecoveryState,
        captureTicket: () -> RecoveryResetTicket?,
    ): Boolean {
        inputsChanged()
        val state = readState()
        // Equal masked text is not identity. Never authorize an unseen newer private preview.
        if (!current() || displayedPreview == null || state.preview !== displayedPreview ||
            state.phase != AccountRecoveryPhase.PREVIEW || state.busy) return false
        val ticket = captureTicket() ?: return false
        val afterCapture = readState()
        if (!current() || afterCapture.preview !== displayedPreview ||
            afterCapture.phase != AccountRecoveryPhase.PREVIEW || afterCapture.busy || !ticketIsCurrent(ticket)) return false
        reset = RecoveryResetConfirmation(this, generation, afterCapture.inputRevision, ticket, displayedPreview.maskedPhone)
        return true
    }

    fun resetVisible(state: AccountRecoveryState): Boolean = reset?.let { resetCurrent(it, state) } == true
    fun resetMaskedPhone(state: AccountRecoveryState): String = reset?.takeIf { resetCurrent(it, state) }?.maskedPhone.orEmpty()
    fun dismissReset() { inputsChanged() }
    fun toggleAcknowledgement(state: AccountRecoveryState) {
        acknowledged = if (resetVisible(state)) !acknowledged else false
    }
    fun takeReset(state: AccountRecoveryState): RecoveryResetConfirmation? {
        val captured = reset
        val agreed = acknowledged
        reset = null; acknowledged = false
        return captured?.takeIf { agreed && resetCurrent(it, state) }
    }
    fun resetCurrent(captured: RecoveryResetConfirmation, state: AccountRecoveryState): Boolean =
        captured.owner === this && captured.generation == generation && captured.inputRevision == state.inputRevision &&
            current() && state.phase == AccountRecoveryPhase.PREVIEW && !state.busy && state.preview != null &&
            ticketIsCurrent(captured.ticket)

    fun close() { mounted = false; inputsChanged() }
    private fun current(): Boolean = mounted && runCatching(isCurrent).getOrDefault(false)
    private fun canSend(state: AccountRecoveryState): Boolean = state.editable && state.cooldownSeconds == 0
    override fun toString(): String = "RecoveryDialogSession(redacted)"
}

internal class RecoverySmsConfirmation(
    internal val owner: RecoveryDialogSession,
    internal val generation: Long,
    internal val inputRevision: Long,
    internal val phone: String,
) {
    override fun toString(): String = "RecoverySmsConfirmation(redacted)"
}

internal class RecoveryResetConfirmation(
    internal val owner: RecoveryDialogSession,
    internal val generation: Long,
    internal val inputRevision: Long,
    internal val ticket: RecoveryResetTicket,
    internal val maskedPhone: String,
) {
    override fun toString(): String = "RecoveryResetConfirmation(redacted)"
}
