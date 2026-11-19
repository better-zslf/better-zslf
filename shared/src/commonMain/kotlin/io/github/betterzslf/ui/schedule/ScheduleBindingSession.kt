package io.github.betterzslf.ui.schedule

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.runtime.ScheduleBindingLease
import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime
import io.github.betterzslf.feature.schedule.runtime.ScheduleState
import io.github.betterzslf.feature.schedule.runtime.ScheduleVerificationAttempt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Root-only arbitration. Merely mounting another page never takes an existing attempt. */
private object ScheduleBindingOwners {
    private class Entry(val runtime: ScheduleRuntime, val page: ScheduleBindingSession, val attempt: ScheduleVerificationAttempt)
    private val entries = mutableListOf<Entry>()
    private class Page(val runtime: ScheduleRuntime, val page: ScheduleBindingSession)
    private val pages = mutableListOf<Page>()
    fun mount(runtime: ScheduleRuntime, page: ScheduleBindingSession) {
        pages.removeAll { it.runtime === runtime }
        pages += Page(runtime, page)
    }
    fun currentPage(runtime: ScheduleRuntime, page: ScheduleBindingSession): Boolean =
        pages.any { it.runtime === runtime && it.page === page }
    fun unmount(runtime: ScheduleRuntime, page: ScheduleBindingSession) {
        pages.removeAll { it.runtime === runtime && it.page === page }
    }
    fun claim(runtime: ScheduleRuntime, page: ScheduleBindingSession, attempt: ScheduleVerificationAttempt) {
        entries.removeAll { it.runtime === runtime }
        entries += Entry(runtime, page, attempt)
    }
    fun owns(runtime: ScheduleRuntime, page: ScheduleBindingSession, attempt: ScheduleVerificationAttempt): Boolean =
        entries.any { it.runtime === runtime && it.page === page && it.attempt === attempt }
    fun release(runtime: ScheduleRuntime, page: ScheduleBindingSession, attempt: ScheduleVerificationAttempt) {
        entries.removeAll { it.runtime === runtime && it.page === page && it.attempt === attempt }
    }
}

/** Production page lifecycle, input revision and callback gates; runtime alone authorizes the operation. */
internal class ScheduleBindingSession private constructor(
    private val runtime: ScheduleRuntime,
    private val updatingPassword: Boolean,
    private val lease: ScheduleBindingLease?,
) {
    var draft by mutableStateOf(ScheduleBindingDraft(account = if (updatingPassword) runtime.state.value.boundAccount.orEmpty() else ""))
        private set
    var showPassword by mutableStateOf(false)
        private set
    private var testing by mutableStateOf(false)
    private var saving by mutableStateOf(false)
    private var notice by mutableStateOf(ScheduleBindingNotice.NOT_VERIFIED)
    private var mounted = true
    private var callbackConsumed = false
    private var attempt: ScheduleVerificationAttempt? = null
    private var verification: Verification? = null
    private var confirmation: Confirmation? = null

    val inputGeneration: Long get() = draft.revision
    val inputsEnabled: Boolean get() = current() && !saving
    private fun activePage(): Boolean = mounted && ScheduleBindingOwners.currentPage(runtime, this)
    private fun current(): Boolean = activePage() && lease?.let(runtime::isBindingLeaseCurrent) == true
    private fun pageCurrent(generation: Long): Boolean = activePage() && draft.revision == generation && !callbackConsumed
    private fun owns(value: ScheduleVerificationAttempt): Boolean = ScheduleBindingOwners.owns(runtime, this, value)

    fun controls(state: ScheduleState = runtime.state.value): ScheduleBindingControls {
        val idle = current() && !state.busy && !state.verifying && !testing && !saving && !state.storageFailure
        return ScheduleBindingControls(
            canVerify = idle && draft.account.isNotBlank() && draft.password.isNotEmpty(),
            canConfirm = idle && attempt?.let(::owns) == true && draft.canConfirm(state.verifiedAccount, false),
            canBack = activePage(),
        )
    }

    fun display(state: ScheduleState = runtime.state.value): ScheduleBindingDisplay {
        return ScheduleBindingDisplay(updatingPassword, state.owner != null, state.storageFailure,
            testing || state.verifying, saving, notice,
            if (controls().canConfirm) maskedAcademicAccount(draft.account.trim()) else "",
            safeScheduleBindingMessage(state.message))
    }

    fun accountChanged(generation: Long, value: String) {
        if (!pageCurrent(generation) || !inputsEnabled) return
        cancelOwnAttempt()
        draft = draft.accountChanged(value)
        notice = ScheduleBindingNotice.INPUT_CHANGED
    }

    fun passwordChanged(generation: Long, value: String) {
        if (!pageCurrent(generation) || !inputsEnabled) return
        cancelOwnAttempt()
        draft = draft.passwordChanged(value)
        notice = ScheduleBindingNotice.INPUT_CHANGED
    }

    fun togglePassword(generation: Long) {
        if (pageCurrent(generation) && inputsEnabled) showPassword = !showPassword
    }

    /** Claim synchronously, before launch, so dispose can cancel authentication even before it starts. */
    fun beginVerification(generation: Long): Verification? {
        if (!pageCurrent(generation) || !controls().canVerify) return null
        val owned = lease?.let(runtime::beginBindingVerification) ?: return null
        val request = Verification(this, generation, owned, draft)
        ScheduleBindingOwners.claim(runtime, this, owned)
        attempt = owned
        verification = request
        draft = draft.invalidate()
        testing = true
        notice = ScheduleBindingNotice.NOT_VERIFIED
        return request
    }

    suspend fun verify(request: Verification): Boolean {
        if (request.page !== this || request.started) return false
        request.started = true
        var accepted = false
        try {
            currentCoroutineContext().ensureActive()
            if (!verificationCurrent(request)) return false
            val passed = runtime.verifyBinding(request.attempt, request.input.account.trim(), request.input.password)
            currentCoroutineContext().ensureActive()
            if (!verificationCurrent(request)) return false
            draft = if (passed) draft.acceptVerification(request.generation, runtime.state.value.verifiedAccount) else draft.invalidate()
            accepted = passed && draft.verifiedRevision == draft.revision
            notice = if (accepted) ScheduleBindingNotice.NOT_VERIFIED else ScheduleBindingNotice.VERIFY_FAILED
            return accepted
        } finally {
            if (!accepted) cancel(request.attempt)
            if (verification === request) { verification = null; testing = false }
        }
    }

    private fun verificationCurrent(request: Verification): Boolean = request.page === this &&
        verification === request && attempt === request.attempt && pageCurrent(request.generation) && current() && owns(request.attempt)

    /** Taking a confirmation is page-local single use; no credential value enters reusable Content. */
    fun beginConfirmation(generation: Long): Confirmation? {
        if (!pageCurrent(generation) || !controls().canConfirm) return null
        val owned = attempt ?: return null
        return Confirmation(this, generation, owned).also { confirmation = it; saving = true }
    }

    suspend fun confirm(request: Confirmation, onBound: () -> Unit): Boolean {
        if (request.page !== this || request.started) return false
        request.started = true
        try {
            currentCoroutineContext().ensureActive()
            if (!confirmationCurrent(request) || !current()) return false
            val commit = runtime.confirmBinding(request.attempt)
            currentCoroutineContext().ensureActive()
            // Own commit advances runtime authority. Validate its fixed receipt, not the old lease,
            // and never capture a new lease from whatever binding happened to become current.
            if (!confirmationCurrent(request)) return false
            if (commit != null && runtime.consumeBindingContinuation(commit)) {
                callbackConsumed = true
                draft = ScheduleBindingDraft()
                ScheduleBindingOwners.release(runtime, this, request.attempt)
                attempt = null
                onBound() // No suspension between runtime consume and the one page continuation.
                return true
            }
            notice = ScheduleBindingNotice.SAVE_FAILED
            return false
        } finally {
            if (confirmation === request) { confirmation = null; saving = false }
        }
    }

    private fun confirmationCurrent(request: Confirmation): Boolean = request.page === this &&
        confirmation === request && attempt === request.attempt && pageCurrent(request.generation) && owns(request.attempt)

    fun back(generation: Long, onBack: () -> Unit): Boolean {
        if (!pageCurrent(generation)) return false
        close()
        onBack()
        return true
    }

    fun close() {
        if (!mounted) return
        mounted = false
        ScheduleBindingOwners.unmount(runtime, this)
        cancelOwnAttempt()
        draft = ScheduleBindingDraft()
        showPassword = false
    }

    private fun cancelOwnAttempt() {
        attempt?.let(::cancel)
        attempt = null
        verification = null
        confirmation = null
        testing = false
        // Saving only reflects this page; runtime's app-owned refresh/native resources survive.
        saving = false
    }

    private fun cancel(value: ScheduleVerificationAttempt) {
        if (!owns(value)) return
        runtime.cancelBindingVerification(value)
        ScheduleBindingOwners.release(runtime, this, value)
    }

    internal class Verification internal constructor(
        internal val page: ScheduleBindingSession, internal val generation: Long,
        internal val attempt: ScheduleVerificationAttempt, internal val input: ScheduleBindingDraft,
    ) {
        internal var started = false
        override fun toString(): String = "ScheduleBindingVerification(redacted)"
    }
    internal class Confirmation internal constructor(
        internal val page: ScheduleBindingSession, internal val generation: Long, internal val attempt: ScheduleVerificationAttempt,
    ) {
        internal var started = false
        override fun toString(): String = "ScheduleBindingConfirmation(redacted)"
    }

    override fun toString(): String = "ScheduleBindingSession(redacted)"

    companion object {
        /** Reload precedes passive capture; opening a form never cancels another form's verification. */
        fun open(runtime: ScheduleRuntime, updatingPassword: Boolean): ScheduleBindingSession {
            runtime.reloadFromStorage()
            return ScheduleBindingSession(runtime, updatingPassword, runtime.captureBindingLease()).also {
                ScheduleBindingOwners.mount(runtime, it)
            }
        }
    }
}

/** A prompt owns only this mount and a fixed passive lease; dismissing never claims verification. */
internal class ScheduleBindingPromptSession private constructor(
    private val runtime: ScheduleRuntime,
    private val visible: Boolean,
    private val lease: ScheduleBindingLease?,
) {
    private var mounted = true
    private var consumed = false
    fun dispatch(event: ScheduleBindingUiEvent, onBind: () -> Unit): Boolean {
        if (!mounted || consumed || !visible || lease?.let(runtime::isBindingLeaseCurrent) != true) return false
        val state = runtime.state.value
        if (!state.showBindingPrompt || state.owner == null || state.boundAccount != null) return false
        if (event != ScheduleBindingUiEvent.PROMPT_BIND && event != ScheduleBindingUiEvent.PROMPT_SKIP &&
            event != ScheduleBindingUiEvent.PROMPT_DISMISS) return false
        consumed = true
        runtime.dismissBindingPrompt()
        if (event == ScheduleBindingUiEvent.PROMPT_BIND) onBind()
        return true
    }
    fun close() { mounted = false }
    override fun toString(): String = "ScheduleBindingPromptSession(redacted)"
    companion object {
        fun open(runtime: ScheduleRuntime, visible: Boolean): ScheduleBindingPromptSession =
            ScheduleBindingPromptSession(runtime, visible, runtime.captureBindingLease())
    }
}

/** Exhaustive allow-list of application copy. Unknown text is never forwarded to public UI props. */
internal fun safeScheduleBindingMessage(message: String?): ScheduleBindingMessage? = when (message) {
    null, "" -> null
    AppText.Schedule.STORAGE_FAILURE -> ScheduleBindingMessage.STORAGE_FAILURE
    AppText.Schedule.VERIFY_FAILED -> ScheduleBindingMessage.VERIFY_FAILED
    AppText.Schedule.CREDENTIALS_REQUIRED -> ScheduleBindingMessage.CREDENTIALS_REQUIRED
    AppText.Schedule.CACHE_RETAINED_HINT -> ScheduleBindingMessage.CACHE_RETAINED
    AppText.Schedule.NO_CACHE -> ScheduleBindingMessage.NO_CACHE
    AppText.Schedule.PERIODS_INVALID -> ScheduleBindingMessage.PERIODS_INVALID
    AppText.Schedule.UNBIND_FAILED -> ScheduleBindingMessage.UNBIND_FAILED
    AppText.Schedule.NATIVE_UPDATE_FAILED -> ScheduleBindingMessage.NATIVE_UPDATE_FAILED
    AppText.Schedule.PARTIAL_PARSE_RETAINED -> ScheduleBindingMessage.PARTIAL_PARSE_RETAINED
    else -> ScheduleBindingMessage.READ_FAILED
}
