package io.github.betterzslf.feature.auth

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiLoginAttempt
import io.github.betterzslf.core.net.ApiLoginIssue
import io.github.betterzslf.core.net.ApiLoginOutcome

/**
 * Honest finite adapter over the core typed LOGIN protocol. The request, the moved decode rules
 * and the internal compare-and-save all live in core (ApiClient.executeLogin); nothing was
 * decoded here and this type owns no transport state of its own. It only maps core's finite
 * outcome one-to-one onto the feature's finite states — a single-owner mapping with no raw
 * server text, code, request diagnostics, credentials or session payload crossing the line.
 */
class ApiCampusLoginSource private constructor(
    private val api: ApiClient,
    private val attempt: ApiLoginAttempt,
) : CampusLoginSource {
    override fun claim(): Boolean = api.claimLoginAttempt(attempt)

    override fun isCurrent(): Boolean = api.isLoginAttemptCurrent(attempt)

    override suspend fun submit(username: String, password: String): CampusLoginOutcome =
        api.executeLogin(attempt, username, password).toCampusLoginOutcome()

    override fun cancel() { api.cancelLoginAttempt(attempt) }

    override fun consumeAcceptedContinuation(): Boolean = api.consumeLoginContinuation(attempt)

    override fun toString(): String = "ApiCampusLoginSource(redacted)"

    companion object {
        /**
         * Passive capture over the application's own ApiClient: one fixed core attempt is
         * captured once and never re-captured, no request is made, nothing is claimed and no
         * store state is read or mutated. Capture cannot fail and never returns null; only a
         * later explicit claim turns the fixed attempt into the store-wide latest SUBMIT.
         */
        fun capture(api: ApiClient): CampusLoginSource = ApiCampusLoginSource(api, api.captureLoginAttempt())
    }
}

/** Single-owner finite mapping; the two issue enums are isomorphic by construction. */
internal fun ApiLoginOutcome.toCampusLoginOutcome(): CampusLoginOutcome = when (this) {
    ApiLoginOutcome.Accepted -> CampusLoginOutcome.Accepted
    is ApiLoginOutcome.Rejected -> CampusLoginOutcome.Rejected(issue.toCampusLoginIssue())
    is ApiLoginOutcome.Unknown -> CampusLoginOutcome.Unknown(issue.toCampusLoginIssue())
}

internal fun ApiLoginIssue.toCampusLoginIssue(): CampusLoginIssue = when (this) {
    ApiLoginIssue.VALIDATION -> CampusLoginIssue.VALIDATION
    ApiLoginIssue.AUTHENTICATION -> CampusLoginIssue.AUTHENTICATION
    ApiLoginIssue.BUSINESS -> CampusLoginIssue.BUSINESS
    ApiLoginIssue.SESSION_CHANGED -> CampusLoginIssue.SESSION_CHANGED
    ApiLoginIssue.TIMEOUT -> CampusLoginIssue.TIMEOUT
    ApiLoginIssue.NETWORK -> CampusLoginIssue.NETWORK
    ApiLoginIssue.MALFORMED -> CampusLoginIssue.MALFORMED
    ApiLoginIssue.HTTP -> CampusLoginIssue.HTTP
    ApiLoginIssue.STORAGE -> CampusLoginIssue.STORAGE
}
