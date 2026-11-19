package io.github.betterzslf.feature.accountrecovery

import io.github.betterzslf.core.text.AppText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlin.time.Clock

/** One route, one bound session, no persistence or automatic SMS/password retry. Use on the UI dispatcher. */
class AccountRecoveryController(
    private val source: AccountRecoverySource?,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onAccepted: (RecoveryLogout) -> Unit = {},
) {
    private val mutable = MutableStateFlow(if (source == null) AccountRecoveryState(
        phase = AccountRecoveryPhase.GUEST,
        message = AppText.Recovery.AUTHENTICATED_SESSION_REQUIRED_GUEST_ACTIONS_DISABLED_HINT,
    ) else AccountRecoveryState())
    val state: StateFlow<AccountRecoveryState> = mutable.asStateFlow()
    private val flight = Mutex()
    private var alive = true
    private var generation = 0L
    private var nonce = 0L
    private var cooldownUntil = 0L
    private var sentPhone: String? = null
    private var verifiedPhone: String? = null
    private var verifiedCode: String? = null
    private var verifiedTarget: RecoveryTarget? = null
    private var pending: Attempt? = null
    private var attempted = false
    private class Attempt(val nonce: Long, val target: RecoveryTarget, val password: String) {
        override fun toString(): String = "RecoveryAttempt(redacted)"
    }

    private fun current(): Boolean {
        if (!alive || source == null) return false
        if (source.isCurrentSession()) return true
        invalidateSession()
        return false
    }
    private fun clearIdentity() { verifiedPhone = null; verifiedCode = null; verifiedTarget = null; pending = null }
    private fun invalidateSession() {
        ++generation; clearIdentity(); sentPhone = null; cooldownUntil = 0
        mutable.value = AccountRecoveryState(phase = AccountRecoveryPhase.SESSION_CHANGED,
            message = AppText.Recovery.LOGIN_CHANGED_INPUT_AND_VERIFICATION_CLEARED_REENTER, inputRevision = mutable.value.inputRevision + 1)
    }
    fun close() {
        alive = false; ++generation; clearIdentity(); sentPhone = null; cooldownUntil = 0
        mutable.value = AccountRecoveryState(phase = AccountRecoveryPhase.CLOSED, inputRevision = mutable.value.inputRevision + 1)
    }
    private fun changed(phone: Boolean) {
        if (mutable.value.terminal || !current()) return
        ++generation; clearIdentity()
        if (phone) sentPhone = null
        mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.EDITING, verified = false,
            maskedPhone = null, preview = null, codeRequested = sentPhone != null, message = null)
    }
    fun phoneChanged() = changed(true)
    fun codeChanged() = changed(false)
    fun editPreview() {
        if (mutable.value.terminal || flight.isLocked || mutable.value.busy || !current()) return
        pending = null
        mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.EDITING, preview = null, message = null)
    }
    fun tickCooldown() {
        if (!alive || cooldownUntil == 0L) return
        val seconds = ((cooldownUntil - nowMillis() + 999L) / 1000L).coerceIn(0, 60).toInt()
        if (mutable.value.cooldownSeconds != seconds) mutable.value = mutable.value.copy(cooldownSeconds = seconds)
    }

    /** Capture once when the TRUSTED binder opens the final dialog. Never send this ticket to Content. */
    fun captureResetTicket(): RecoveryResetTicket? {
        if (flight.isLocked || mutable.value.busy || attempted || mutable.value.phase != AccountRecoveryPhase.PREVIEW || !current()) return null
        return pending?.let { RecoveryResetTicket(this, it.nonce) }
    }

    /** Rechecks the fixed ticket against this owner and current private preview, not an earlier UI frame. */
    fun isResetTicketCurrent(ticket: RecoveryResetTicket): Boolean {
        if (flight.isLocked || mutable.value.busy || attempted || mutable.value.phase != AccountRecoveryPhase.PREVIEW || !current()) return false
        return pending?.let { ticket.matches(this, it.nonce) } == true && mutable.value.preview != null
    }

    suspend fun sendCode(phone: String, confirmed: Boolean): Boolean {
        if (!flight.tryLock()) return false
        var dispatched = false
        try {
            if (!confirmed || mutable.value.phase != AccountRecoveryPhase.EDITING || !current()) return false
            tickCooldown()
            if (mutable.value.cooldownSeconds > 0) return false
            recoveryPhoneValidation(phone)?.let { mutable.value = mutable.value.copy(message = it); return false }
            clearIdentity(); sentPhone = null
            val ticket = ++generation
            mutable.value = mutable.value.copy(sending = true, verified = false, maskedPhone = null, preview = null,
                codeRequested = false, message = AppText.Recovery.REQUESTING_SMS_DELIVERY_UNCONFIRMED)
            dispatched = true
            val response = try { source!!.sendCode(phone) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { RecoverySmsOutcome.Unknown(RecoveryIssue.NETWORK) }
            if (!current()) return false
            when (response) {
                RecoverySmsOutcome.Accepted -> {
                    val sameInputs = ticket == generation
                    sentPhone = if (sameInputs) phone else null
                    cooldownUntil = nowMillis() + 60_000L
                    mutable.value = mutable.value.copy(codeRequested = sameInputs, cooldownSeconds = 60,
                        message = if (sameInputs) AppText.Recovery.SMS_REQUEST_ACCEPTED_LOCAL_COUNTDOWN_NOT_DELIVERY_OR_EXPIRY
                        else AppText.Recovery.PREVIOUS_SMS_REQUEST_ACCEPTED_INPUT_CHANGED_REVIEW_AGAIN)
                    return sameInputs
                }
                is RecoverySmsOutcome.Rejected -> {
                    mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.EDITING,
                        message = recoveryError(response.issue) + AppText.Recovery.SUCCESS_COUNTDOWN_NOT_STARTED_SUFFIX)
                    return false
                }
                is RecoverySmsOutcome.Unknown -> {
                    mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.UNCERTAIN,
                        message = recoveryError(response.issue) + AppText.Recovery.SMS_SENDING_STOPPED_VERIFY_DELIVERY_NO_AUTO_RESEND_SUFFIX)
                    return false
                }
            }
        } catch (cancelled: CancellationException) {
            if (dispatched && alive && current()) mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.UNCERTAIN,
                message = AppText.Recovery.SMS_REQUEST_INTERRUPTED_DELIVERY_UNCONFIRMED_NO_RESEND)
            throw cancelled
        } finally {
            if (mutable.value.sending) mutable.value = mutable.value.copy(sending = false)
            flight.unlock()
        }
    }

    suspend fun verify(phone: String, code: String): Boolean {
        if (!flight.tryLock()) return false
        try {
            if (mutable.value.phase != AccountRecoveryPhase.EDITING || !current()) return false
            val invalid = recoveryPhoneValidation(phone) ?: recoveryCodeIssue(code)?.let(::recoveryValidationMessage)
            if (invalid != null) { mutable.value = mutable.value.copy(message = invalid); return false }
            if (sentPhone != phone) { mutable.value = mutable.value.copy(message = AppText.Recovery.EXPLICIT_SMS_REQUEST_FOR_CURRENT_PHONE_REQUIRED_BEFORE_VERIFICATION); return false }
            clearIdentity()
            val ticket = ++generation
            mutable.value = mutable.value.copy(verifying = true, verified = false, maskedPhone = null, preview = null, message = null)
            val response = try { source!!.verify(phone, code) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { RecoveryVerifyOutcome.Failed(RecoveryIssue.NETWORK) }
            if (!current() || ticket != generation) return false
            val target = when (response) {
                is RecoveryVerifyOutcome.Failed -> { mutable.value = mutable.value.copy(message = recoveryError(response.issue)); return false }
                RecoveryVerifyOutcome.MissingTarget -> {
                    mutable.value = mutable.value.copy(message = AppText.Recovery.VERIFIED_USER_ID_MISSING_NO_IDENTITY_SUBSTITUTION)
                    return false
                }
                is RecoveryVerifyOutcome.Verified -> response.target
            }
            verifiedPhone = phone; verifiedCode = code; verifiedTarget = target
            mutable.value = mutable.value.copy(verified = true, maskedPhone = recoveryMask(phone),
                message = AppText.Recovery.SMS_VERIFIED_TARGET_IDENTITY_FOR_CURRENT_PASSWORD_RESET_ONLY)
            return true
        } finally {
            if (mutable.value.verifying) mutable.value = mutable.value.copy(verifying = false)
            flight.unlock()
        }
    }

    fun prepare(phone: String, code: String, password: String, confirmation: String): Boolean {
        if (mutable.value.phase != AccountRecoveryPhase.EDITING || flight.isLocked || mutable.value.busy || attempted || !current()) return false
        val target = verifiedTarget
        if (target == null || !mutable.value.verified || phone != verifiedPhone || code != verifiedCode) {
            mutable.value = mutable.value.copy(message = AppText.Recovery.VERIFY_CURRENT_PHONE_AND_CODE_EDIT_INVALIDATES_IDENTITY)
            return false
        }
        recoveryPasswordValidation(password, confirmation)?.let { mutable.value = mutable.value.copy(message = it); return false }
        pending = Attempt(++nonce, target, password)
        mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.PREVIEW,
            preview = AccountRecoveryPreview(recoveryMask(phone)), message = null)
        return true
    }

    /** A single public confirmation path: fixed owner-fenced ticket plus explicit acknowledgement. */
    suspend fun confirm(ticket: RecoveryResetTicket, acknowledged: Boolean): Boolean {
        if (!flight.tryLock()) return false
        var dispatched = false
        try {
            if (mutable.value.terminal || !current()) return false
            val attempt = pending
            if (!acknowledged || attempted || attempt == null || !ticket.matches(this, attempt.nonce) ||
                mutable.value.preview == null || mutable.value.phase != AccountRecoveryPhase.PREVIEW) return false
            attempted = true
            clearIdentity(); sentPhone = null; cooldownUntil = 0
            mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.RESETTING, verified = false,
                codeRequested = false, cooldownSeconds = 0, message = AppText.Recovery.RESETTING_SCHOOL_PASSWORD_SINGLE_REQUEST)
            if (!current()) return false
            dispatched = true
            val outcome = try { source!!.reset(attempt.target, attempt.password) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { RecoveryResetOutcome.Unknown(RecoveryIssue.NETWORK) }
            if (!alive) return false
            when (outcome) {
                is RecoveryResetOutcome.Accepted -> {
                    // Successful reset deliberately makes the old binding stale by logging it out.
                    if (outcome.logout == RecoveryLogout.NEW_SESSION_PRESERVED || source?.isLoggedOut() != true) {
                        invalidateSession(); return false
                    }
                    mutable.value = mutable.value.copy(
                        phase = if (outcome.logout == RecoveryLogout.CLEAR_FAILED) AccountRecoveryPhase.CLEANUP_WARNING else AccountRecoveryPhase.COMPLETED,
                        preview = null, maskedPhone = null, inputRevision = mutable.value.inputRevision + 1,
                        message = if (outcome.logout == RecoveryLogout.CLEAR_FAILED)
                            AppText.Recovery.RESET_CONFIRMED_SESSION_EXITED_PERSISTED_SESSION_DELETE_FAILED
                        else AppText.Recovery.SCHOOL_PASSWORD_RESET_CONFIRMED_LOG_IN_AGAIN_NO_RESUBMIT)
                    onAccepted(outcome.logout)
                    return true
                }
                is RecoveryResetOutcome.Rejected -> {
                    if (!current()) return false
                    mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.FAILED,
                        message = recoveryError(outcome.issue) + AppText.Recovery.ATTEMPT_STOPPED_NO_AUTOMATIC_RESET_SUFFIX)
                    return false
                }
                is RecoveryResetOutcome.Unknown -> {
                    if (!current()) return false
                    mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.UNCERTAIN,
                        message = recoveryError(outcome.issue) + AppText.Recovery.POSSIBLY_ACCEPTED_VERIFY_RESULT_NO_REPEAT_RESET_SUFFIX)
                    return false
                }
            }
        } catch (cancelled: CancellationException) {
            if (dispatched && alive && current()) mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.UNCERTAIN,
                message = AppText.Recovery.RESET_INTERRUPTED_ACCEPTANCE_UNCONFIRMED_NO_RESEND)
            throw cancelled
        } finally { flight.unlock() }
    }

    override fun toString(): String = "AccountRecoveryController(phase=${mutable.value.phase})"
}
