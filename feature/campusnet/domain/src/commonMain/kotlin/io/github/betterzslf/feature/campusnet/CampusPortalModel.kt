package io.github.betterzslf.feature.campusnet

/** What the school backend reports; unknown values never mean offline or online. */
sealed interface CampusOnlineStatus {
    data object Online : CampusOnlineStatus
    data object Offline : CampusOnlineStatus
    data class Unreadable(val raw: String) : CampusOnlineStatus
}

sealed interface CampusDialResult {
    data object Accepted : CampusDialResult
    data class Rejected(val reason: CampusDialRejection, val raw: String) : CampusDialResult
}

enum class CampusDialRejection { PasswordWrong, Unpaid, NoRedirect, Other }

/** Protocol literals, in the reference client's original precedence. */
fun classifyCampusDialRejection(raw: String): CampusDialRejection = when {
    raw.contains("PassWord error") -> CampusDialRejection.PasswordWrong
    raw.contains("Product not ordered") -> CampusDialRejection.Unpaid
    raw.contains("no redirect 01") || raw.contains("no redirect 02") -> CampusDialRejection.NoRedirect
    else -> CampusDialRejection.Other
}

/** Single-hop portal observation, also consumed by the independently owned dorm diagnosis. */
sealed interface PortalProbeResult {
    data class Answered(
        val url: String,
        val redirected: Boolean,
        val schoolId: String = "",
        val domain: String = "",
        val area: String = "",
    ) : PortalProbeResult
    data object Blocked : PortalProbeResult
    data object Unreachable : PortalProbeResult
    data object MissingLocation : PortalProbeResult
}

val CAMPUS_PORTAL_CLEARTEXT_HOSTS: Set<String> = setOf("9.9.9.9")
val DORM_AUTH_CLEARTEXT_HOSTS: Set<String> = setOf("14.146.227.141", "121.8.177.212")

/** Keep the exact shared portal/carrier allowlist policy, including HTTPS handling. */
fun campusPortalAddressAllowed(raw: String): Boolean = cleartextHostOf(raw)?.let {
    it in CAMPUS_PORTAL_CLEARTEXT_HOSTS || it in DORM_AUTH_CLEARTEXT_HOSTS
} ?: raw.trim().startsWith("https://", ignoreCase = true)

internal fun cleartextHostOf(raw: String): String? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true)) return null
    val authority = trimmed.substring(7).substringBefore('/').substringBefore('?').substringBefore('#')
    return (if (authority.startsWith("[")) authority.substringBefore(']') + "]"
    else authority.substringBefore(':')).lowercase()
}

fun dormAuthAddressAllowed(raw: String): Boolean =
    raw.trim().startsWith("https://", ignoreCase = true) ||
        (cleartextHostOf(raw)?.let { it in DORM_AUTH_CLEARTEXT_HOSTS } == true)

/** The only provisioned-phone prefix algorithm. No credentials are retained or normalised. */
fun campusProvisionedPhonePrefix(account: String): String? {
    val prefix = account.substringBefore('@').trim()
    return prefix.takeIf { it.length == 11 && it.all { c -> c in '0'..'9' } }
}
