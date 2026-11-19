package io.github.betterzslf.feature.auth

/**
 * The opaque, single-use ticket [CampusLoginController.beginSubmission] hands to the
 * root for one explicit submit.
 *
 * Opaque by construction: internal constructor, internal fields, redacted toString,
 * no data-class copy/component exposure. It never holds credentials — the username
 * and password exist only as locals of [CampusLoginController.submit] — and it never
 * exposes its [CampusLoginSource] snapshot, the session, or any token to the caller.
 */
class CampusLoginSubmission internal constructor(
    internal val owner: CampusLoginController,
    internal val source: CampusLoginSource,
) {
    /** One started per ticket: a replayed submit is refused. */
    internal var started: Boolean = false

    /** Set when the controller retires this attempt; its receipt is dropped afterwards. */
    internal var retired: Boolean = false

    /** One consumption per ticket: [CampusLoginController.consumeAcceptedContinuation]. */
    internal var consumed: Boolean = false

    override fun toString(): String = "CampusLoginSubmission(redacted)"
}
