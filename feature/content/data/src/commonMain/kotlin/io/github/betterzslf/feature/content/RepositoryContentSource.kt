package io.github.betterzslf.feature.content

import io.github.betterzslf.data.ContentRepository

class RepositoryContentSource(private val repository: ContentRepository) : ContentSource {
    override suspend fun document(name: String) = repository.document(name)
}
