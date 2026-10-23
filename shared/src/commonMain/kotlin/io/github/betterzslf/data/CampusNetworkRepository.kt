package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.queryParameters

class CampusNetworkRepository(private val api: ApiClient) {
    suspend fun checkPhone(phone: String) = api.execute(ApiEndpoint.CAMPUS_CHECK_PHONE, queryParameters("phone" to phone))
    /** Returns private account data to the caller; nothing is cached or logged. */
    suspend fun accounts() = api.execute(ApiEndpoint.CAMPUS_ACCOUNTS)

    /** Explicit opt-in action: UI keeps this hidden until expanded and confirmed. */
    suspend fun updatePassword(operatorAccountId: String, newPassword: String) = api.execute(ApiEndpoint.CAMPUS_UPDATE_PASSWORD,
        queryParameters("operatorAccountId" to operatorAccountId, "newOperatorPassword" to newPassword))
}
