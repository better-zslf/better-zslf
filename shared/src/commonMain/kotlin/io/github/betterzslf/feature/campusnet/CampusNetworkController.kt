package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement

/** Route-local, memory-only state. A terminal write attempt is never reset into a retry. */
class CampusNetworkController(private val source: CampusNetworkSource?) {
    private val mutable = MutableStateFlow(CampusNetworkState())
    val state: StateFlow<CampusNetworkState> = mutable.asStateFlow()
    private val flight = Mutex()
    private var alive = true
    private var generation = 0L
    private var nonce = 0L
    private var targets: Map<String, String> = emptyMap()
    private var checkedPhone: String? = null
    private var checkedAccount: String? = null
    private var pending: PasswordAttempt? = null
    private var attempted = false

    private class PasswordAttempt(val previewId: Long, val accountKey: String, val accountId: String, val password: String) {
        override fun toString() = "PasswordAttempt(redacted)"
    }

    private fun current(): Boolean {
        if (alive && source?.isCurrentSession() == true) return true
        invalidateSession()
        return false
    }

    private fun clearSecrets() { checkedPhone = null; checkedAccount = null; pending = null }

    private fun invalidateSession() {
        ++generation
        clearSecrets()
        targets = emptyMap()
        mutable.value = CampusNetworkState(phase = CampusPasswordPhase.SESSION_CHANGED,
            formMessage = AppText.CampusNetwork.LOGIN_CHANGED_INPUT_AND_ACCOUNT_RESULTS_CLEARED_REENTER, inputRevision = mutable.value.inputRevision + 1)
    }

    fun close() { alive = false; invalidateSession() }

    fun setExpanded(value: Boolean) {
        if (!current() || mutable.value.busy || flight.isLocked) return
        if (!value) {
            clearSecrets()
            mutable.value = mutable.value.copy(expanded = false, phoneChecked = false, preview = null,
                phase = if (mutable.value.terminal) mutable.value.phase else CampusPasswordPhase.EDITING,
                inputRevision = mutable.value.inputRevision + 1)
        } else mutable.value = mutable.value.copy(expanded = true)
    }

    fun selectAccount(key: String) {
        if (!current() || mutable.value.busy || flight.isLocked || mutable.value.terminal || !mutable.value.expanded) return
        if (targets[key] == null) { mutable.value = mutable.value.copy(formMessage = AppText.CampusNetwork.ACCOUNT_ID_UNVERIFIABLE_QUERY_ONLY); return }
        if (key == mutable.value.selectedAccountKey) return
        clearSecrets()
        mutable.value = mutable.value.copy(selectedAccountKey = key, phoneChecked = false, preview = null,
            phase = CampusPasswordPhase.EDITING, formMessage = null, inputRevision = mutable.value.inputRevision + 1)
    }

    /** Editing the phone/target invalidates its check and any earlier confirmation receipt. */
    fun forgetPhoneCheck() {
        if (!current() || mutable.value.busy || flight.isLocked || mutable.value.terminal) return
        clearSecrets()
        mutable.value = mutable.value.copy(phoneChecked = false, preview = null, phase = CampusPasswordPhase.EDITING, formMessage = null)
    }

    fun editPreview() {
        if (!current() || mutable.value.busy || flight.isLocked || mutable.value.terminal) return
        pending = null
        mutable.value = mutable.value.copy(preview = null, phase = CampusPasswordPhase.EDITING, formMessage = null)
    }

    suspend fun refresh(): Boolean {
        if (!flight.tryLock()) return false
        try {
            if (!current()) return false
            val ticket = ++generation
            clearSecrets()
            targets = emptyMap()
            mutable.value = mutable.value.copy(loading = true, queryMessage = null, phoneChecked = false,
                selectedAccountKey = if (mutable.value.terminal) mutable.value.selectedAccountKey else null,
                preview = if (mutable.value.terminal) mutable.value.preview else null,
                phase = if (mutable.value.terminal) mutable.value.phase else CampusPasswordPhase.EDITING,
                inputRevision = mutable.value.inputRevision + 1)
            val response = call { source!!.accounts() }
            if (!current() || ticket != generation) return false
            return acceptAccounts(response, ticket)
        } finally {
            if (mutable.value.loading) mutable.value = mutable.value.copy(loading = false)
            flight.unlock()
        }
    }

    private fun acceptAccounts(response: ApiResult<JsonElement>, ticket: Long): Boolean {
        val decoded = when (response) {
            is ApiResult.Failure -> response
            is ApiResult.Success -> parseCampusAccounts(response.data, ticket)
        }
        return when (decoded) {
            is ApiResult.Failure -> {
                targets = emptyMap()
                mutable.value = mutable.value.copy(loading = false, queryMessage = campusFailure(decoded.error))
                false
            }
            is ApiResult.Success -> {
                targets = decoded.data.targets
                mutable.value = mutable.value.copy(accounts = decoded.data.rows, loading = false, queryMessage = null)
                true
            }
        }
    }

    /** code=200 here means a phone check/SMS request succeeded, NOT verification of an SMS code. */
    suspend fun checkPhone(phone: String): Boolean {
        if (!flight.tryLock()) return false
        try {
            if (!current() || mutable.value.terminal || !mutable.value.expanded) return false
            val key = mutable.value.selectedAccountKey
            if (key == null || targets[key] == null) {
                mutable.value = mutable.value.copy(formMessage = AppText.CampusNetwork.SELECT_VALID_ACCOUNT_FROM_CURRENT_QUERY_FIRST)
                return false
            }
            val invalid = campusPhoneValidation(phone)
            if (invalid != null) { mutable.value = mutable.value.copy(formMessage = invalid); return false }
            if (checkedPhone == phone && checkedAccount == key && mutable.value.phoneChecked) return true
            clearSecrets()
            mutable.value = mutable.value.copy(checkingPhone = true, phoneChecked = false, preview = null,
                phase = CampusPasswordPhase.EDITING, formMessage = null)
            val ticket = generation
            val response = call { source!!.checkPhone(phone) }
            if (!current() || ticket != generation) return false
            return when (response) {
                is ApiResult.Failure -> {
                    mutable.value = mutable.value.copy(formMessage = campusFailure(response.error) + AppText.CampusNetwork.PASSWORD_SUBMISSION_DISABLED_NO_AUTOMATIC_RECHECK_SUFFIX)
                    false
                }
                is ApiResult.Success -> {
                    checkedPhone = phone
                    checkedAccount = key
                    mutable.value = mutable.value.copy(phoneChecked = true,
                        formMessage = AppText.CampusNetwork.PHONE_CHECK_PASSED_NOT_SMS_VERIFICATION_HINT)
                    true
                }
            }
        } finally {
            if (mutable.value.checkingPhone) mutable.value = mutable.value.copy(checkingPhone = false)
            flight.unlock()
        }
    }

    fun prepare(phone: String, password: String, confirmation: String): Boolean {
        if (!current() || flight.isLocked || mutable.value.busy || mutable.value.phase != CampusPasswordPhase.EDITING || attempted || !mutable.value.expanded) return false
        val key = mutable.value.selectedAccountKey
        if (key == null) { mutable.value = mutable.value.copy(formMessage = AppText.CampusNetwork.SELECT_VALID_BROADBAND_ACCOUNT_FIRST); return false }
        val target = targets[key]
        if (target == null || key != checkedAccount || phone != checkedPhone || !mutable.value.phoneChecked) {
            mutable.value = mutable.value.copy(formMessage = AppText.CampusNetwork.COMPLETE_CURRENT_PHONE_CHECK_FOR_SELECTED_ACCOUNT_FIRST)
            return false
        }
        val invalid = campusPasswordValidation(password, confirmation)
        if (invalid != null) { mutable.value = mutable.value.copy(formMessage = invalid); return false }
        val account = mutable.value.accounts.firstOrNull { it.key == key && it.canChangePassword } ?: return false
        val id = ++nonce
        pending = PasswordAttempt(id, key, target, password)
        mutable.value = mutable.value.copy(phase = CampusPasswordPhase.PREVIEW,
            preview = CampusPasswordPreview(id, account.maskedAccount, campusMask(phone)), formMessage = null)
        return true
    }

    /** One receipt, one final acknowledgement, one bound request; terminal failures remain terminal. */
    suspend fun confirm(previewId: Long, acknowledged: Boolean): Boolean {
        if (!flight.tryLock()) return false
        var sent = false
        var writeAccepted = false
        try {
            if (!current()) return false
            val attempt = pending
            if (!acknowledged || attempted || mutable.value.phase != CampusPasswordPhase.PREVIEW ||
                attempt == null || attempt.previewId != previewId || mutable.value.preview?.id != previewId ||
                mutable.value.selectedAccountKey != attempt.accountKey || targets[attempt.accountKey] != attempt.accountId) return false
            attempted = true
            pending = null
            checkedPhone = null
            checkedAccount = null
            mutable.value = mutable.value.copy(phase = CampusPasswordPhase.SUBMITTING, phoneChecked = false,
                formMessage = AppText.CampusNetwork.SUBMITTING_BROADBAND_PASSWORD_CHANGE_ONCE)
            // ApiClient also checks this exact opaque binding atomically before choosing its token.
            if (!current()) return false
            sent = true
            val response = call { source!!.updatePassword(attempt.accountId, attempt.password) }
            if (!current()) return false
            when (response) {
                is ApiResult.Failure -> {
                    val phase = if (response.error is ApiError.Business || response.error is ApiError.Validation)
                        CampusPasswordPhase.FAILED else CampusPasswordPhase.UNCERTAIN
                    mutable.value = mutable.value.copy(phase = phase, formMessage = campusFailure(response.error) +
                        if (phase == CampusPasswordPhase.UNCERTAIN) AppText.CampusNetwork.POSSIBLY_ACCEPTED_MANUAL_REVIEW_NO_RESUBMIT_SUFFIX
                        else AppText.CampusNetwork.ATTEMPT_STOPPED_NO_AUTO_RESUBMIT_SUFFIX)
                    return false
                }
                is ApiResult.Success -> {
                    writeAccepted = true
                    mutable.value = mutable.value.copy(phase = CampusPasswordPhase.COMPLETED, loading = true,
                        formMessage = AppText.CampusNetwork.PASSWORD_CHANGE_SUCCEEDED_REQUERYING_ACCOUNT)
                    val ticket = ++generation
                    val readBack = call { source!!.accounts() }
                    if (!current() || ticket != generation) return false
                    val refreshed = acceptAccounts(readBack, ticket)
                    mutable.value = mutable.value.copy(formMessage = if (refreshed)
                        AppText.CampusNetwork.PASSWORD_CHANGE_SUCCEEDED_ACCOUNT_REQUERIED_NO_RESUBMIT
                        else AppText.CampusNetwork.PASSWORD_CHANGE_SUCCEEDED_ACCOUNT_REQUERY_FAILED_REFRESH_ONLY)
                    return true
                }
            }
        } catch (cancelled: CancellationException) {
            if (sent && current()) {
                mutable.value = if (writeAccepted) mutable.value.copy(
                    phase = CampusPasswordPhase.COMPLETED, queryMessage = AppText.CampusNetwork.ACCOUNT_REQUERY_INTERRUPTED,
                    formMessage = AppText.CampusNetwork.PASSWORD_CHANGE_SUCCEEDED_ACCOUNT_REQUERY_INTERRUPTED_REFRESH_ONLY)
                else mutable.value.copy(phase = CampusPasswordPhase.UNCERTAIN,
                    formMessage = AppText.CampusNetwork.SUBMISSION_INTERRUPTED_ACCEPTANCE_UNCONFIRMED_MANUAL_REVIEW)
            }
            throw cancelled
        } finally {
            if (mutable.value.loading) mutable.value = mutable.value.copy(loading = false)
            flight.unlock()
        }
    }

    private suspend fun call(block: suspend () -> ApiResult<JsonElement>): ApiResult<JsonElement> = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { ApiResult.Failure(ApiError.Network()) }

    override fun toString() = "CampusNetworkController(phase=${mutable.value.phase})"
}
