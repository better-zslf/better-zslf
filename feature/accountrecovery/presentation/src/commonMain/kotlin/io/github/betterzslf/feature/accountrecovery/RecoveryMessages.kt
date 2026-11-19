package io.github.betterzslf.feature.accountrecovery

import io.github.betterzslf.core.text.AppText

fun recoveryPhoneValidation(phone: String): String? = recoveryPhoneIssue(phone)?.let(::recoveryValidationMessage)
fun recoveryPasswordValidation(password: String, confirmation: String): String? =
    recoveryPasswordIssue(password, confirmation)?.let(::recoveryValidationMessage)

internal fun recoveryMask(phone: String): String = phone.take(3) + "••••" + phone.takeLast(2)

internal fun recoveryValidationMessage(issue: RecoveryValidationIssue): String = when (issue) {
    RecoveryValidationIssue.PHONE_FORMAT -> AppText.Common.MOBILE_NUMBER_MUST_HAVE_ELEVEN_DIGITS
    RecoveryValidationIssue.SMS_CODE_REQUIRED -> AppText.Recovery.SMS_CODE_REQUIRED
    RecoveryValidationIssue.PASSWORD_POLICY -> AppText.Recovery.NEW_SCHOOL_PASSWORD_COMPLEXITY_AND_LENGTH_REQUIRED
    RecoveryValidationIssue.PASSWORD_MISMATCH -> AppText.Recovery.NEW_SCHOOL_PASSWORD_CONFIRMATION_MISMATCH
}

internal fun recoveryError(issue: RecoveryIssue): String = when (issue) {
    RecoveryIssue.AUTHENTICATION -> AppText.Recovery.CURRENT_LOGIN_SESSION_EXPIRED_REENTER
    RecoveryIssue.BUSINESS -> AppText.Recovery.REQUEST_NOT_ACCEPTED_REVIEW_DETAILS
    RecoveryIssue.VALIDATION -> AppText.Recovery.REVIEW_REQUIRED_DETAILS
    RecoveryIssue.TIMEOUT -> AppText.Common.REQUEST_TIMEOUT_RESULT_UNCONFIRMED
    RecoveryIssue.NETWORK -> AppText.Common.CONNECTION_INTERRUPTED_RESULT_UNCONFIRMED
    RecoveryIssue.HTTP -> AppText.Recovery.SERVICE_FAILED_RESULT_UNCONFIRMED
    RecoveryIssue.DECODE -> AppText.Recovery.SERVICE_RESPONSE_STRUCTURE_UNCONFIRMED
    RecoveryIssue.STORAGE -> AppText.Recovery.LOCAL_STATE_PROCESSING_FAILED
}
