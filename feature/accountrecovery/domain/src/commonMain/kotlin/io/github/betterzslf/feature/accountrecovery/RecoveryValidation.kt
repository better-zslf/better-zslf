package io.github.betterzslf.feature.accountrecovery

fun recoveryPhoneIssue(phone: String): RecoveryValidationIssue? =
    if (phone.length == 11 && phone.all { it in '0'..'9' }) null else RecoveryValidationIssue.PHONE_FORMAT

fun recoveryCodeIssue(code: String): RecoveryValidationIssue? =
    if (code.isBlank() || code.any { it.isWhitespace() || it.isISOControl() }) RecoveryValidationIssue.SMS_CODE_REQUIRED else null

/** The existing school new-password policy; not a policy for legacy login passwords. */
fun isValidRecoveryNewPassword(value: String): Boolean = value.length >= 8 &&
    value.any { it in 'a'..'z' || it in 'A'..'Z' } &&
    value.any { it in '0'..'9' } &&
    value.any { !it.isLetterOrDigit() && !it.isWhitespace() } &&
    value.none { it.isWhitespace() }

fun recoveryPasswordIssue(password: String, confirmation: String): RecoveryValidationIssue? = when {
    !isValidRecoveryNewPassword(password) -> RecoveryValidationIssue.PASSWORD_POLICY
    password != confirmation -> RecoveryValidationIssue.PASSWORD_MISMATCH
    else -> null
}
