package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.queryParameters

/**
 * Protocol documents only (用户协议 / 隐私政策 / 使用帮助). The column, article, history,
 * like and hit-count endpoints were retired along with their screens.
 */
class ContentRepository(private val api: ApiClient) {
    suspend fun document(name: String) = api.execute(ApiEndpoint.DOCUMENT, queryParameters("articleName" to name))
}
