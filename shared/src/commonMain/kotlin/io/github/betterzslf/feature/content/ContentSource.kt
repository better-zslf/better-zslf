package io.github.betterzslf.feature.content

import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.AppRepositories
import kotlinx.serialization.json.JsonElement

/** Injectable boundary: controllers/tests never construct clients or contact an implicit host. */
interface ContentSource {
    suspend fun document(name: String): ApiResult<JsonElement>
}

class RepositoryContentSource(private val repositories: AppRepositories) : ContentSource {
    override suspend fun document(name: String) = repositories.content.document(name)
}
