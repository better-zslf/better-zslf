package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.ApiSessionBinding
import io.github.betterzslf.core.net.queryParameters

/** Legacy calls remain source-compatible; interactive operations use one opaque bound instance. */
class CampusNetworkRepository private constructor(private val api: ApiClient, private val binding: ApiSessionBinding?) {
    constructor(api: ApiClient) : this(api, null)

    val isBoundToCurrentSession: Boolean get() = binding != null && api.isBindingCurrent(binding)

    /** A stale bound repository never silently captures a newer login. */
    fun boundToCurrentSession(): CampusNetworkRepository? {
        if (binding != null) return if (api.isBindingCurrent(binding)) this else null
        return api.captureSessionBinding()?.let { CampusNetworkRepository(api, it) }
    }

    /** This may cause an SMS to be sent; it is not verification of a supplied SMS code. */
    suspend fun checkPhone(phone: String) = api.execute(ApiEndpoint.CAMPUS_CHECK_PHONE,
        queryParameters("phone" to phone), expectedSession = binding)

    /** Returns current-user account data; no explicit identity parameter is invented. */
    suspend fun accounts() = api.execute(ApiEndpoint.CAMPUS_ACCOUNTS, expectedSession = binding)

    /** Target comes from an authenticated account row, never from a user-entered account/id. */
    suspend fun updatePassword(operatorAccountId: String, newPassword: String) = api.execute(ApiEndpoint.CAMPUS_UPDATE_PASSWORD,
        queryParameters("operatorAccountId" to operatorAccountId, "newOperatorPassword" to newPassword), expectedSession = binding)

    override fun toString(): String = "CampusNetworkRepository(bound=${binding != null})"
}
