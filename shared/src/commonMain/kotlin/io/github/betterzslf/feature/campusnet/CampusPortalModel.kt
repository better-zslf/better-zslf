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
 * Only these portal hosts may be opened over cleartext HTTP.
 *
 * The address is *server-supplied*, so this is an allowlist rather than a hardcoded URL: whatever
 * `getRedirectUrl` pushes is checked against it before any request leaves the app. A new gateway IP
 * therefore fails closed with a visible reason instead of silently bypassing TLS policy.
 * Must stay in sync with `androidApp/src/main/res/xml/network_security_config.xml`.
 */
val CAMPUS_PORTAL_CLEARTEXT_HOSTS: Set<String> = setOf("9.9.9.9")

/**
 * A portal address is usable only when it is plain HTTP to an allowlisted host.
 * `https` is also accepted: it needs no cleartext exemption.
 */
fun campusPortalAddressAllowed(raw: String): Boolean {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return false
    val host = when {
        trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring(7)
        trimmed.startsWith("https://", ignoreCase = true) -> return true
        else -> return false
    }
    val authority = host.substringBefore('/').substringBefore('?').substringBefore('#')
    val hostOnly = if (authority.startsWith("[")) authority.substringBefore(']') + "]" else authority.substringBefore(':')
    return hostOnly.lowercase() in CAMPUS_PORTAL_CLEARTEXT_HOSTS
}
