package io.github.betterzslf.feature.auth

/**
 * The closed phase set of the campus-login controller. Seven states only:
 * the whole presentation job is this machine plus the [CampusLoginIssue] whitelist.
 */
enum class CampusLoginPhase {
    /** The form is idle and editable; a submit may begin. */
    EDITING,

    /** One login attempt is in flight; the fields and the button are locked. */
    SUBMITTING,

    /** The attempt was accepted; one continuation is pending for the root to consume. */
    ACCEPTED,

    /** The attempt was rejected with a finite issue; a manual resubmit is allowed. */
    REJECTED,

    /**
     * The attempt ended with an unconfirmed outcome (the request was already on the
     * wire, so repeating it blind could sign in twice). No automatic retry, and no
     * resubmit until [CampusLoginController.inputsChanged] retires the receipt.
     */
    UNKNOWN,

    /** The fixed-page/current gate moved on; the attempt (if any) was retired. */
    SESSION_CHANGED,

    /** [CampusLoginController.close] ran; terminal, no further submissions. */
    CLOSED,
}
