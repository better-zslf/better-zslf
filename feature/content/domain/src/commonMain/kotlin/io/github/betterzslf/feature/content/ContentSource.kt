package io.github.betterzslf.feature.content

import io.github.betterzslf.core.result.ApiResult
import kotlinx.serialization.json.JsonElement

/** Injectable boundary: controllers/tests never construct clients or contact an implicit host. */
interface ContentSource {
    suspend fun document(name: String): ApiResult<JsonElement>
}
