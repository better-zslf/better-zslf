package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.queryParameters

class DiscoverRepository(private val api: ApiClient) {
    suspend fun menu() = api.execute(ApiEndpoint.DISCOVER)
    /** Known keys: schoolRun and attendanceRadius; dictionary num is the string "1". */
    suspend fun dictionary(code: String, num: String = "1") =
        api.execute(ApiEndpoint.DICTIONARY, queryParameters("code" to code, "num" to num))
}
