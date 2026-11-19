package io.github.betterzslf.feature.auth

/**
 * Precise closed classification, isomorphic to the core ApiLoginIssue. No raw server text,
 * business code, request diagnostics, credential or payload crosses this boundary, so the
 * presentation layer can never render an unclassified upstream message.
 */
enum class CampusLoginIssue {
    /** Local admission or parameter validation refused the attempt before any request. */
    VALIDATION,

    /** The anonymous login itself received an authentication refusal (HTTP or envelope 401). */
    AUTHENTICATION,

    /** Explicit upstream business rejection (envelope code != 200), including wrong credentials. */
    BUSINESS,

    /** The attempt no longer owns the store-wide latest claim, or the store moved under it. */
    SESSION_CHANGED,

    /** The request timed out; the upstream decision is unknown. */
    TIMEOUT,

    /** Transport failed; the upstream decision is unknown. */
    NETWORK,

    /** The response arrived but did not carry a usable session. */
    MALFORMED,

    /** Non-2xx HTTP status; the upstream decision is unknown. */
    HTTP,

    /** The session was granted but local persistence failed. */
    STORAGE,
}

/**
 * Accepted carries no session and no message: the established session is only the store's
 * public flow, and the right to continue past it is the separate exactly-once continuation.
 * Rejected is a known refusal; Unknown is honest uncertainty about the upstream or local
 * outcome, and neither is retried automatically anywhere.
 */
sealed interface CampusLoginOutcome {
    data object Accepted : CampusLoginOutcome
    data class Rejected(val issue: CampusLoginIssue) : CampusLoginOutcome
    data class Unknown(val issue: CampusLoginIssue) : CampusLoginOutcome
}

/**
 * One login screen's finite view of the typed LOGIN protocol. The implementing data adapter is
 * captured passively and holds one fixed core attempt; it never exposes the core capability,
 * transport, session store or raw JSON to presentation.
 */
interface CampusLoginSource {
    /** Explicit SUBMIT only: claims the fixed attempt as the store-wide latest login claim. */
    fun claim(): Boolean

    /** True while the fixed attempt still owns the store-wide latest claim. */
    fun isCurrent(): Boolean

    /** Credentials appear only in this call stack; they are never retained by the source. */
    suspend fun submit(username: String, password: String): CampusLoginOutcome

    /** Withdraws the fixed attempt when it still owns the current claim; zero side effects otherwise. */
    fun cancel()

    /** Consumes this source's own accepted continuation exactly once. */
    fun consumeAcceptedContinuation(): Boolean
}
