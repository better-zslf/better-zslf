package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.text.AppText

/** Finite read-only failures. Backend messages and transport diagnostics never enter UI state. */
fun runReadFailureText(issue: RunReadIssue): String = when (issue) {
    RunReadIssue.AUTHENTICATION -> AppText.Auth.USER_ID_MISSING_LOG_IN_AND_REFRESH
    RunReadIssue.BUSINESS -> AppText.Common.OPERATION_UNSUCCESSFUL_TRY_AGAIN_LATER
    RunReadIssue.VALIDATION -> AppText.Common.CHECK_REQUIRED_PARAMETERS
    RunReadIssue.TIMEOUT -> AppText.Common.REQUEST_TIMEOUT_TRY_AGAIN_LATER
    RunReadIssue.NETWORK -> AppText.Common.NETWORK_CONNECTION_FAILED_CHECK_AND_RETRY
    RunReadIssue.MALFORMED -> AppText.Common.INVALID_SERVICE_RESPONSE_FORMAT
    RunReadIssue.HTTP -> AppText.Common.OPERATION_UNSUCCESSFUL_TRY_AGAIN_LATER
    RunReadIssue.STORAGE -> AppText.Common.LOCAL_DATA_SAVE_FAILED
}
