package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.queryParameters
import kotlinx.serialization.json.JsonObject

class FitnessRepository(private val api: ApiClient) {
    suspend fun tests(parameters: JsonObject = JsonObject(emptyMap())) = api.execute(ApiEndpoint.PHYSICAL_TESTS, parameters)
    // Live reads support empty parameters; preserve optional filters from the declaration.
    suspend fun items(physicalId: String? = null) = api.execute(ApiEndpoint.PHYSICAL_ITEMS, queryParameters("physicalId" to physicalId))
    suspend fun counts(id: String? = null) = api.execute(ApiEndpoint.PHYSICAL_COUNTS, queryParameters("id" to id))
    suspend fun myInfo(physicalUserId: String? = null) = api.execute(ApiEndpoint.PHYSICAL_MY_INFO, queryParameters("physicalUserId" to physicalUserId))

    /** Exact enrolment/cancellation fields vary by test; a nonempty form is mandatory. */
    suspend fun enrol(parameters: JsonObject) = api.execute(ApiEndpoint.PHYSICAL_ADD, parameters)
    suspend fun cancel(parameters: JsonObject) = api.execute(ApiEndpoint.PHYSICAL_CANCEL, parameters)
}
