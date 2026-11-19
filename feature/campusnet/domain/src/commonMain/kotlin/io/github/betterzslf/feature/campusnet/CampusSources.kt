package io.github.betterzslf.feature.campusnet

/** Finite transport classifications, never backend text, URLs, credentials or a networking type. */
enum class CampusIssue { AUTHENTICATION, BUSINESS, VALIDATION, TIMEOUT, NETWORK, MALFORMED, HTTP, STORAGE }

sealed interface CampusOutcome<out T> {
    data class Success<T>(val data: T) : CampusOutcome<T>
    data class Failure(val issue: CampusIssue) : CampusOutcome<Nothing>
}

/** Data-to-controller input only; raw identifiers/accounts never belong in a display state. */
class CampusAccountRecord(val id: String, val account: String, val typeStatus: String) {
    override fun toString(): String = "CampusAccountRecord(redacted)"
}

class CampusRedirectTarget(val address: String) {
    override fun toString(): String = "CampusRedirectTarget(redacted)"
}

/** A single captured authority. A stale source must never silently bind to a replacement login. */
interface CampusNetworkSource {
    fun isCurrentSession(): Boolean
    suspend fun accounts(): CampusOutcome<List<CampusAccountRecord>>
    /** May send an SMS; successful acknowledgement is not verification of a supplied code. */
    suspend fun checkPhone(phone: String): CampusOutcome<Unit>
    suspend fun updatePassword(accountId: String, password: String): CampusOutcome<Unit>
}

interface CampusPortalSource {
    fun isCurrentSession(): Boolean
    suspend fun internetInfo(): CampusOutcome<CampusOnlineStatus>
    suspend fun redirectTarget(): CampusOutcome<CampusRedirectTarget>
    suspend fun dialIn(portalUrl: String): CampusOutcome<CampusDialResult>
    suspend fun offline(): CampusOutcome<Unit>
}

/** Narrow read for the independently owned dorm route; never a carrier authentication action. */
interface CampusProvisionedPhoneSource {
    fun isCurrentSession(): Boolean
    suspend fun readProvisionedPhone(): CampusOutcome<String?>
}

fun interface CampusPortalProbeSource {
    suspend fun probe(portalUrl: String): PortalProbeResult
}
