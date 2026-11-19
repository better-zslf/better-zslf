package io.github.betterzslf.feature.auth

import io.github.betterzslf.core.text.AppText

/**
 * The one finite issue-to-copy mapping for the campus login family. Every branch is
 * an existing AppText constant — no new display text is introduced here — so an
 * unrecognized server message can never surface as copy: the BUSINESS issue maps
 * onto the existing generic-failure constant and carries nothing from the wire.
 */
fun CampusLoginIssue.displayText(): String = when (this) {
    CampusLoginIssue.VALIDATION -> AppText.Common.CHECK_REQUIRED_PARAMETERS
    CampusLoginIssue.AUTHENTICATION -> AppText.Auth.EXPIRED_SESSION_RELOGIN_REQUIRED
    CampusLoginIssue.BUSINESS -> AppText.Common.OPERATION_UNSUCCESSFUL_TRY_AGAIN_LATER
    CampusLoginIssue.SESSION_CHANGED -> AppText.Auth.OPERATION_SESSION_CHANGED_RECONFIRM_BEFORE_CONTINUING
    CampusLoginIssue.TIMEOUT -> AppText.Common.REQUEST_TIMEOUT_TRY_AGAIN_LATER
    CampusLoginIssue.NETWORK -> AppText.Common.NETWORK_CONNECTION_FAILED_CHECK_AND_RETRY
    CampusLoginIssue.MALFORMED -> AppText.Common.INVALID_SERVICE_RESPONSE_FORMAT
    CampusLoginIssue.HTTP -> AppText.Common.OPERATION_UNSUCCESSFUL_TRY_AGAIN_LATER
    CampusLoginIssue.STORAGE -> AppText.Auth.LOCAL_SESSION_STORAGE_FAILED
}

/**
 * The notice-banner copy for a state, or null when the banner folds away.
 * The title choice stays with the root (login-notice title while an issue is
 * shown, local-data title for the storage fallback), matching the legacy
 * `message.ifBlank { storageNotice }` split.
 *
 * Banner semantics frozen for the root binding:
 *  - REJECTED / UNKNOWN / SESSION_CHANGED — the issue's finite copy (a null issue
 *    falls back to the existing generic-failure constant, never to raw text);
 *  - EDITING / SUBMITTING / ACCEPTED / CLOSED — null, the banner is hidden.
 */
fun CampusLoginState.noticeText(): String? = when (phase) {
    CampusLoginPhase.REJECTED,
    CampusLoginPhase.UNKNOWN,
    CampusLoginPhase.SESSION_CHANGED,
    -> issue?.displayText() ?: AppText.Common.OPERATION_UNSUCCESSFUL_TRY_AGAIN_LATER
    CampusLoginPhase.EDITING,
    CampusLoginPhase.SUBMITTING,
    CampusLoginPhase.ACCEPTED,
    CampusLoginPhase.CLOSED,
    -> null
}
