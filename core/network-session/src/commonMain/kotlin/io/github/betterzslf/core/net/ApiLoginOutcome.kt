package io.github.betterzslf.core.net

/**
 * Precise closed classification of why one typed login did not reach Accepted. No raw server
 * text, business code, URL, credential or payload ever crosses this boundary.
 */
enum class ApiLoginIssue {
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

    /** The response arrived but did not carry a usable session (moved decode rules). */
    MALFORMED,

    /** Non-2xx HTTP status; the upstream decision is unknown. */
    HTTP,

    /** The session was granted but local persistence failed. */
    STORAGE,
}

/**
 * Accepted carries no payload: the established session is only the store's public flow, and the
 * right to continue past it is the separate exactly-once continuation. Rejected is a known
 * refusal; Unknown is honest uncertainty about the upstream or local outcome. Neither outcome
 * is ever retried automatically inside core.
 */
sealed interface ApiLoginOutcome {
    data object Accepted : ApiLoginOutcome
    data class Rejected(val issue: ApiLoginIssue) : ApiLoginOutcome
    data class Unknown(val issue: ApiLoginIssue) : ApiLoginOutcome
}
