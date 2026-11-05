package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Campus-network dial-in state as the status card shows it.
 *
 * The six states are the design contract (task book §7). `NotSignedIn` and `Offline` are
 * deliberately distinct: one means there is no campus session to dial with, the other means there
 * is one but the network is not up. Collapsing them told the user nothing actionable.
 */
enum class CampusPortalPhase { NotSignedIn, Offline, Connecting, Online, Failed, NotOnCampus }

/** What the school backend says about the current connection. */
sealed interface CampusOnlineStatus {
    data object Online : CampusOnlineStatus
    data object Offline : CampusOnlineStatus
    /** The field was missing or not a recognised verbatim value; never guessed as online. */
    data class Unreadable(val raw: String) : CampusOnlineStatus
}

/**
 * `/verification/getInternetInfo` returns `{status, massage}`.
 *
 * Two things worth keeping: the server spells the field `massage`, and `status` is a *string*
 * (`"0"` / `"1"`), so a JSON number must not silently pass. Any other value is surfaced verbatim
 * rather than mapped to a guess.
 */
fun parseCampusOnlineStatus(data: JsonElement): CampusOnlineStatus {
    val row = data as? JsonObject ?: return CampusOnlineStatus.Unreadable("")
    val raw = (row["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return when (raw) {
        "1" -> CampusOnlineStatus.Online
        "0" -> CampusOnlineStatus.Offline
        else -> CampusOnlineStatus.Unreadable(raw.take(32))
    }
}

/** Result of `POST /verification/appDialInLogin`, which reuses `getInternetInfo`'s shape. */
sealed interface CampusDialResult {
    data object Accepted : CampusDialResult

    /** The server rejected the attempt with free text; the client matches on a few literals. */
    data class Rejected(val reason: CampusDialRejection, val raw: String) : CampusDialResult
}

/**
 * The reference client branches on `indexOf` substrings of the server message
 * (`no redirect 01/02`, `PassWord error`, `Product not ordered`). Those are protocol literals, not
 * display copy, so they stay here; the user-facing sentence comes from `AppText`.
 */
enum class CampusDialRejection { PasswordWrong, Unpaid, NoRedirect, Other }

fun classifyCampusDialRejection(raw: String): CampusDialRejection = when {
    raw.contains("PassWord error") -> CampusDialRejection.PasswordWrong
    raw.contains("Product not ordered") -> CampusDialRejection.Unpaid
    raw.contains("no redirect 01") || raw.contains("no redirect 02") -> CampusDialRejection.NoRedirect
    else -> CampusDialRejection.Other
}

fun parseCampusDialResult(data: JsonElement): CampusDialResult {
    val row = data as? JsonObject ?: return CampusDialResult.Rejected(CampusDialRejection.Other, "")
    val status = (row["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (status == "1") return CampusDialResult.Accepted
    // The reference client reads `msg`; the envelope may also carry it as `message`.
    val message = (row["msg"] as? JsonPrimitive)?.contentOrNull
        ?: (row["message"] as? JsonPrimitive)?.contentOrNull
        ?: ""
    return CampusDialResult.Rejected(classifyCampusDialRejection(message), message.take(120))
}

/** Detail sentence for a rejection, chosen from the protocol literal, never from raw server text. */
fun campusDialRejectionDetail(rejection: CampusDialRejection, raw: String): String = when (rejection) {
    CampusDialRejection.PasswordWrong -> AppText.CampusNetwork.PORTAL_PASSWORD_WRONG_DETAIL
    CampusDialRejection.Unpaid -> AppText.CampusNetwork.PORTAL_UNPAID_DETAIL
    CampusDialRejection.NoRedirect -> AppText.CampusNetwork.PORTAL_NO_REDIRECT_DETAIL
    CampusDialRejection.Other ->
        if (raw.isBlank()) AppText.CampusNetwork.PORTAL_DIAL_REJECTED_DETAIL
        else AppText.CampusNetwork.portalErrorDetail(raw)
}

/**
 * Only these hosts may be reached over cleartext HTTP.
 *
 * Both lists are server- or protocol-fixed rather than user-supplied, so an allowlist works:
 *  - the portal gateway the school backend hands us (`getRedirectUrl`), and
 *  - the two carrier authentication servers on port 7001, which speak plain HTTP by protocol.
 *
 * A new host therefore fails closed with a visible reason instead of the app silently relaxing TLS.
 * Must stay in sync with `androidApp/src/main/res/xml/network_security_config.xml`.
 */
val CAMPUS_PORTAL_CLEARTEXT_HOSTS: Set<String> = setOf("9.9.9.9")

/** The carrier dialer's two fixed authentication hosts (see `DORM_AUTH_SERVERS`). */
val DORM_AUTH_CLEARTEXT_HOSTS: Set<String> = setOf("14.146.227.141", "121.8.177.212")

/**
 * A portal address is usable only when it is plain HTTP to an allowlisted host.
 * `https` is also accepted: it needs no cleartext exemption.
 */
fun campusPortalAddressAllowed(raw: String): Boolean = cleartextHostOf(raw)?.let {
    it in CAMPUS_PORTAL_CLEARTEXT_HOSTS || it in DORM_AUTH_CLEARTEXT_HOSTS
} ?: raw.trim().startsWith("https://", ignoreCase = true)

/** The lowercased host of a plain-HTTP URL, or `null` when the URL is not `http://`. */
internal fun cleartextHostOf(raw: String): String? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true)) return null
    val authority = trimmed.substring(7).substringBefore('/').substringBefore('?').substringBefore('#')
    return (if (authority.startsWith("[")) authority.substringBefore(']') + "]"
    else authority.substringBefore(':')).lowercase()
}

/** Whether the carrier dialer may talk to [raw] at all. Same rule as the probe, separate intent. */
fun dormAuthAddressAllowed(raw: String): Boolean =
    raw.trim().startsWith("https://", ignoreCase = true) ||
        (cleartextHostOf(raw)?.let { it in DORM_AUTH_CLEARTEXT_HOSTS } == true)
