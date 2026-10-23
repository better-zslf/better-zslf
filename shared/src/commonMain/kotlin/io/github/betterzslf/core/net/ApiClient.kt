package io.github.betterzslf.core.net

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One transport for the /app and /run families. No request/response logging is installed:
 * the upstream protocol puts passwords into query strings, so exception text is discarded.
 * The supplied client stays caller-owned; close this derived client when its scope ends.
 */
class ApiClient(
    httpClient: HttpClient,
    private val sessionStore: SessionStore,
    val config: ApiConfig = ApiConfig(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val client = httpClient.config {
        expectSuccess = false
        // Do not forward a bare token (or credential query) to a redirect destination.
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeoutMillis
            connectTimeoutMillis = config.connectTimeoutMillis
            socketTimeoutMillis = config.socketTimeoutMillis
        }
    }

    suspend fun execute(
        endpoint: ApiEndpoint,
        query: JsonObject = JsonObject(emptyMap()),
        body: JsonObject? = null,
    ): ApiResult<JsonElement> {
        if (endpoint.location == ParameterLocation.MULTIPART) {
            return invalid(listOf("file"), "上传接口请使用 multipart 方法")
        }
        if (endpoint.location == ParameterLocation.QUERY && body != null) {
            return invalid(listOf("body"), "此接口的参数应放在 query 中")
        }
        if (endpoint.location == ParameterLocation.JSON && (body == null || query.isNotEmpty())) {
            return invalid(listOf("body"), "此接口仅接收 JSON body 参数")
        }
        val parameters = if (endpoint.location == ParameterLocation.JSON) body!! else query
        val missing = endpoint.required.filter { parameters[it].isMissingParameter() }
        if (missing.isNotEmpty()) return invalid(missing)
        if (endpoint.requiresParameters && parameters.values.all { it.isMissingParameter() }) {
            return invalid(listOf("parameters"))
        }
        return perform(endpoint) {
            url.parameters.appendQuery(query)
            if (body != null) {
                contentType(ContentType.Application.Json)
                // String body avoids any dependency on an engine's serializer setup.
                setBody(body.toString())
            }
        }
    }

    suspend fun upload(
        file: UploadFile,
        tableId: String,
        childType: String = "feedbackImage",
        tablename: String = "sch_feedback",
    ): ApiResult<JsonElement> {
        val invalidFields = buildList {
            if (file.bytes.isEmpty()) add("file")
            if (file.name.isBlank() || file.name.any { it in "\r\n\u0000\"\\/" }) add("filename")
            if (tableId.isBlank()) add("tableId")
            if (childType.isBlank()) add("childType")
            if (tablename.isBlank()) add("tablename")
            if (file.contentType.any { it == '\r' || it == '\n' }) add("contentType")
        }
        if (invalidFields.isNotEmpty()) return invalid(invalidFields)
        try {
            ContentType.parse(file.contentType)
        } catch (_: IllegalArgumentException) {
            return invalid(listOf("contentType"))
        }
        return perform(ApiEndpoint.UPLOAD) {
            setBody(MultiPartFormDataContent(formData {
                append("childType", childType)
                append("tablename", tablename)
                append("tableId", tableId)
                append("file", file.bytes, Headers.build {
                    append(HttpHeaders.ContentType, file.contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                })
            }))
        }
    }

    /**
     * Verified venue-specific route/fields. Empty 2xx is transport success only; a nonempty
     * body must be a successful envelope. Unknown bodies remain Decode/uncertain, never retried.
     * The caller must subsequently reload fileList to establish attachment linkage.
     */
    suspend fun uploadVenueAttachment(file: UploadFile, applyId: String, userId: String): ApiResult<JsonElement> {
        val invalidFields = buildList {
            if (file.bytes.isEmpty()) add("file")
            if (file.name.isBlank() || file.name.any { it in "\r\n\u0000\"\\/" }) add("filename")
            if (applyId.isBlank()) add("applyId")
            if (userId.isBlank()) add("userId")
            if (file.contentType.any { it == '\r' || it == '\n' }) add("contentType")
        }
        if (invalidFields.isNotEmpty()) return invalid(invalidFields)
        try { ContentType.parse(file.contentType) }
        catch (_: IllegalArgumentException) { return invalid(listOf("contentType")) }
        return perform(ApiEndpoint.SITE_UPLOAD) {
            setBody(MultiPartFormDataContent(formData {
                append("applyId", applyId)
                append("userId", userId)
                append("file", file.bytes, Headers.build {
                    append(HttpHeaders.ContentType, file.contentType)
                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                })
            }))
        }
    }

    /** Only server-relative userfiles paths are accepted, never an arbitrary remote URL. */
    fun fileUrl(relativePath: String): String? {
        val path = relativePath.trim().trimStart('/')
        if (path.isEmpty() || path.contains(":") || path.contains('\\') || path.contains('?') ||
            path.contains('#') || path.contains('%') || path.any { it <= ' ' } ||
            path.split('/').any { it == "." || it == ".." }
        ) return null
        return config.filesBaseUrl.trimEnd('/') + "/" + path
    }

    fun close() { client.close() }

    private suspend fun perform(
        endpoint: ApiEndpoint,
        configure: HttpRequestBuilder.() -> Unit,
    ): ApiResult<JsonElement> {
        val token = if (endpoint.authentication == Authentication.NONE) null else sessionStore.session.value?.token
        if (endpoint.authentication == Authentication.REQUIRED && token.isNullOrBlank()) {
            return ApiResult.Failure(ApiError.Authentication())
        }
        return try {
            val response = client.request(config.url(endpoint)) {
                method = endpoint.method
                timeout {
                    requestTimeoutMillis = endpoint.timeoutMillis ?: config.requestTimeoutMillis
                    connectTimeoutMillis = config.connectTimeoutMillis
                    socketTimeoutMillis = maxOf(config.socketTimeoutMillis, endpoint.timeoutMillis ?: 0)
                }
                header(HttpHeaders.Accept, "application/json")
                if (token != null) header(HttpHeaders.Authorization, token)
                config.platform?.let { header("platform", it) }
                config.appVersion?.let { header("appVersion", it) }
                configure()
            }
            val status = response.status.value
            when {
                status == 401 -> {
                    response.bodyAsChannel().cancel(null)
                    authenticationFailure(token)
                }
                status !in 200..299 -> {
                    response.bodyAsChannel().cancel(null)
                    ApiResult.Failure(ApiError.Http(status))
                }
                else -> parseEnvelope(response.bodyAsText(), endpoint, token)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            ApiResult.Failure(ApiError.Timeout())
        } catch (_: ConnectTimeoutException) {
            ApiResult.Failure(ApiError.Timeout())
        } catch (_: SocketTimeoutException) {
            ApiResult.Failure(ApiError.Timeout())
        } catch (_: Exception) {
            // Do not propagate exception messages: Ktor includes the credential-bearing URL.
            ApiResult.Failure(ApiError.Network())
        }
    }

    private fun parseEnvelope(text: String, endpoint: ApiEndpoint, token: String?): ApiResult<JsonElement> {
        if (text.isBlank()) {
            return if (endpoint.acceptsEmptyResponse) ApiResult.Success(JsonNull)
            else ApiResult.Failure(ApiError.Decode())
        }
        val envelope = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } ?: return ApiResult.Failure(ApiError.Decode())
        val code = (envelope["code"] as? JsonPrimitive)?.intOrNull
            ?: return ApiResult.Failure(ApiError.Decode())
        val message = (envelope["message"] as? JsonPrimitive)?.contentOrNull
            ?: (envelope["msg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when (code) {
            200 -> ApiResult.Success(envelope["data"] ?: JsonNull, message)
            401 -> authenticationFailure(token)
            else -> ApiResult.Failure(ApiError.Business(code, message.ifBlank { "操作未成功，请稍后重试" }))
        }
    }

    private fun authenticationFailure(token: String?): ApiResult.Failure {
        try {
            sessionStore.clearIfTokenMatches(token)
        } catch (_: Exception) {
            // SessionStore has already cleared its StateFlow even if private storage failed.
        }
        return ApiResult.Failure(ApiError.Authentication())
    }

    private fun invalid(fields: List<String>, message: String = "请检查必填参数"): ApiResult.Failure =
        ApiResult.Failure(ApiError.Validation(fields, message))
}
