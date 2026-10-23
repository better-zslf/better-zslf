package io.github.betterzslf.core.net

/** Bases are configurable so every test can be entirely offline. TLS uses engine defaults. */
data class ApiConfig(
    val appBaseUrl: String = "https://zslf.zsc.edu.cn/app",
    val runBaseUrl: String = "https://zslf.zsc.edu.cn",
    val filesBaseUrl: String = "https://zslf.zsc.edu.cn/userfiles/",
    val requestTimeoutMillis: Long = 8_000,
    val connectTimeoutMillis: Long = 8_000,
    val socketTimeoutMillis: Long = 8_000,
    val platform: String? = null,
    val appVersion: String? = null,
) {
    init {
        require(requestTimeoutMillis > 0 && connectTimeoutMillis > 0 && socketTimeoutMillis > 0)
        require(appBaseUrl.startsWith("https://") || appBaseUrl.startsWith("http://"))
        require(runBaseUrl.startsWith("https://") || runBaseUrl.startsWith("http://"))
    }

    fun url(endpoint: ApiEndpoint): String = if (endpoint.path.startsWith("/run/")) {
        runBaseUrl.trimEnd('/') + endpoint.path
    } else {
        appBaseUrl.trimEnd('/') + endpoint.path.removePrefix("/app")
    }
}
