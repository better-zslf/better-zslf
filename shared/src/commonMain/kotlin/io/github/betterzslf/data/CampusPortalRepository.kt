package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.ApiSessionBinding
import io.github.betterzslf.core.net.queryParameters

/**
 * On-campus / dorm dial-in mediated entirely by the school backend (`/verification/…`).
 *
 * The phone is unauthenticated on the campus network at this point, so every step rides the
 * existing HTTPS API and the campus session token — no cleartext goes to the portal from here.
 * Only the redirect probe touches the portal itself; see `KtorPortalProbe`.
 *
 * Third-party captive-portal sign-in (the carrier's `:7001` dialer) is a different protocol and
 * is deliberately absent: it needs an `algo_id` cipher that must be captured on campus first.
 */
class CampusPortalRepository private constructor(private val api: ApiClient, private val binding: ApiSessionBinding?) {
    constructor(api: ApiClient) : this(api, null)

    val isBoundToCurrentSession: Boolean get() = binding != null && api.isBindingCurrent(binding)

    fun boundToCurrentSession(): CampusPortalRepository? {
        if (binding != null) return if (api.isBindingCurrent(binding)) this else null
        return api.captureSessionBinding()?.let { CampusPortalRepository(api, it) }
    }

    /** Current campus-network online state. Takes no parameters: identity comes from the session. */
    suspend fun internetInfo() = api.execute(ApiEndpoint.CAMPUS_INTERNET_INFO, expectedSession = binding)

    /** The portal address and check keyword the school currently wants probed. */
    suspend fun redirectTarget() = api.execute(ApiEndpoint.CAMPUS_REDIRECT_TARGET, expectedSession = binding)

    /** `urls` and `resData` carry the same probed portal URL; the original client sent both. */
    suspend fun dialIn(portalUrl: String) = api.execute(ApiEndpoint.CAMPUS_DIAL_IN,
        queryParameters("urls" to portalUrl, "resData" to portalUrl), expectedSession = binding)

    suspend fun offline() = api.execute(ApiEndpoint.CAMPUS_OFFLINE, expectedSession = binding)

    override fun toString(): String = "CampusPortalRepository(bound=${binding != null})"
}
