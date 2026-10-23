package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint

class ProfileRepository(private val api: ApiClient) {
    suspend fun userInfo() = api.execute(ApiEndpoint.USER_INFO)
    suspend fun photo() = api.execute(ApiEndpoint.USER_PHOTO)
    suspend fun statistics() = api.execute(ApiEndpoint.USER_STATISTICS)
}
