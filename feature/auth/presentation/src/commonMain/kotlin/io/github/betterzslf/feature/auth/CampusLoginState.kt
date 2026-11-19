package io.github.betterzslf.feature.auth

/**
 * The finite public snapshot of the campus-login controller: phase, whitelisted
 * issue, the edit generation, the double-tap lock, and the submit clause. No
 * message text and no arbitrary server string is ever carried — display copy is
 * derived from existing core text by the mapping in CampusLoginText.kt.
 *
 * Invariants the controller maintains:
 *  - [submitAllowed] is exactly `phase == EDITING || phase == REJECTED`;
 *  - [busy] is exactly `phase == SUBMITTING`;
 *  - [issue] is non-null exactly on REJECTED / UNKNOWN / SESSION_CHANGED;
 *  - [inputRevision] never decreases.
 */
data class CampusLoginState(
    val phase: CampusLoginPhase,
    val issue: CampusLoginIssue?,
    val inputRevision: Long,
    val busy: Boolean,
    val submitAllowed: Boolean,
) {
    companion object {
        fun initial(): CampusLoginState = CampusLoginState(
            phase = CampusLoginPhase.EDITING,
            issue = null,
            inputRevision = 0L,
            busy = false,
            submitAllowed = true,
        )
    }
}
