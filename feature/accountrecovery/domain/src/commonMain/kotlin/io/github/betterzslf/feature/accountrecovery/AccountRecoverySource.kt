package io.github.betterzslf.feature.accountrecovery

/** Fixed classifications only. No server text, request diagnostics, credentials or retry command. */
enum class RecoveryIssue { AUTHENTICATION, BUSINESS, VALIDATION, TIMEOUT, NETWORK, HTTP, DECODE, STORAGE }
enum class RecoveryLogout { CLEARED, CLEAR_FAILED, NEW_SESSION_PRESERVED }
enum class RecoveryValidationIssue { PHONE_FORMAT, SMS_CODE_REQUIRED, PASSWORD_POLICY, PASSWORD_MISMATCH }

/**
 * A nominal verified-response value, NOT an authorization capability. The data source's captured
 * session binding and the controller's private verification/confirmation chain provide authority.
 * Raw identifiers never belong in UI state, navigation, diagnostics or persisted form state.
 */
class RecoveryTarget private constructor(val id: String) {
    override fun toString(): String = "RecoveryTarget(redacted)"

    companion object {
        fun fromVerifiedId(raw: String): RecoveryTarget? = raw
            .takeIf { it.isNotBlank() && it == it.trim() && it.none(Char::isISOControl) }
            ?.let(::RecoveryTarget)
    }
}

/** Bound once to one session epoch. Construction and status reads perform no request or rebinding. */
interface AccountRecoverySource {
    fun isCurrentSession(): Boolean
    fun isLoggedOut(): Boolean
    suspend fun sendCode(phone: String): RecoverySmsOutcome
    suspend fun verify(phone: String, code: String): RecoveryVerifyOutcome
    suspend fun reset(target: RecoveryTarget, password: String): RecoveryResetOutcome
}

/** Accepted is only an SMS request acknowledgement, never delivery or code expiry. */
sealed interface RecoverySmsOutcome {
    data object Accepted : RecoverySmsOutcome
    data class Rejected(val issue: RecoveryIssue) : RecoverySmsOutcome
    data class Unknown(val issue: RecoveryIssue) : RecoverySmsOutcome
}

sealed interface RecoveryVerifyOutcome {
    data class Verified(val target: RecoveryTarget) : RecoveryVerifyOutcome
    data class Failed(val issue: RecoveryIssue) : RecoveryVerifyOutcome
    data object MissingTarget : RecoveryVerifyOutcome
}

/** Local cleanup failure cannot erase the server's acknowledgement or authorize another reset. */
sealed interface RecoveryResetOutcome {
    data class Accepted(val logout: RecoveryLogout) : RecoveryResetOutcome
    data class Rejected(val issue: RecoveryIssue) : RecoveryResetOutcome
    data class Unknown(val issue: RecoveryIssue) : RecoveryResetOutcome
}
