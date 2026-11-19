package io.github.betterzslf.core.net

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.SessionSnapshot
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
    private val bindingAuthority = Any()

    /** Owner, token and epoch come from one authoritative record, never two observer-flow reads. */
    fun captureSessionBinding(): ApiSessionBinding? {
        val snapshot = sessionStore.captureSnapshot()
        if (snapshot.owner.isNullOrBlank() || snapshot.token.isNullOrBlank()) return null
        return ApiSessionBinding(bindingAuthority, snapshot).takeIf(::isBindingCurrent)
    }

    /**
     * Stable owner captured by this client's binding, even after its epoch retires; null for a
     * different client's capability. This is query provenance, not proof of current authorization.
     */
    fun boundOwnerId(binding: ApiSessionBinding): String? = binding.ownerFor(bindingAuthority)

    /** Checks this client's authority and one current epoch/owner/token record without exposing it. */
    fun isBindingCurrent(binding: ApiSessionBinding): Boolean = binding.matches(bindingAuthority, sessionStore.captureSnapshot())

    /**
     * Non-suspending compare-and-clear on the application's state dispatcher, as required by
     * SessionStore. A foreign or retired binding returns false without mutating storage. Deletion
     * failures propagate only after the in-memory session and epoch have been invalidated.
     * No snapshot, credential or epoch is returned, and this operation never rebinds a capability.
     */
    fun clearCurrentBinding(binding: ApiSessionBinding): Boolean {
        val snapshot = sessionStore.captureSnapshot()
        return binding.matches(bindingAuthority, snapshot) && sessionStore.clearIfCurrent(snapshot)
    }

    // ---- Typed LOGIN protocol ----------------------------------------------------------
    // The store-wide claim registry lives in SessionStore; this client only contributes its own
    // authority. Capture is passive, claim is the only explicit SUBMIT, and the established
    // session is only ever the store's public flow — no payload crosses these boundaries.

    private val loginAuthority = Any()

    /**
     * Passive capture of one login capability for this client: nothing is claimed, retired or
     * mutated, and the store is not read. Capture never preempts the current claim.
     */
    fun captureLoginAttempt(): ApiLoginAttempt = ApiLoginAttempt(loginAuthority)

    /**
     * Explicit SUBMIT only: synchronously installs this attempt as the store-wide latest claim,
     * retiring any earlier claim and any accepted continuation — including another client's over
     * the same store. A foreign attempt is refused with zero side effects.
     */
    fun claimLoginAttempt(attempt: ApiLoginAttempt): Boolean {
        if (!attempt.ownedBy(loginAuthority)) return false
        sessionStore.claimLogin(loginAuthority, attempt)
        return true
    }

    /** True while this attempt still owns the store-wide latest claim (claimed or accepted). */
    fun isLoginAttemptCurrent(attempt: ApiLoginAttempt): Boolean =
        attempt.ownedBy(loginAuthority) && sessionStore.isLoginClaimCurrent(loginAuthority, attempt)

    /**
     * Cancels only when this attempt still owns the current claim; every stale or foreign cancel
     * checks ownership first and is a zero-side-effect false.
     */
    fun cancelLoginAttempt(attempt: ApiLoginAttempt): Boolean {
        if (!attempt.ownedBy(loginAuthority)) return false
        return sessionStore.cancelLoginClaim(loginAuthority, attempt)
    }

    /** Exactly-once consumption of this client's own accepted continuation; a retired or already
     * consumed attempt is a false without side effects. */
    fun consumeLoginContinuation(attempt: ApiLoginAttempt): Boolean {
        if (!attempt.ownedBy(loginAuthority)) return false
        return sessionStore.consumeLoginContinuation(loginAuthority, attempt)
    }

    /**
     * The typed LOGIN path: admission, the moved decode rules and the internal compare-and-save
     * all live in core. Credentials appear only in this request's query. Admission is ensured
     * before dispatch; the claim-time snapshot is carried to the commit and the latest store
     * state is never re-captured after the await to renew a stale attempt.
     */
    suspend fun executeLogin(attempt: ApiLoginAttempt, username: String, password: String): ApiLoginOutcome {
        // Admission before dispatch: a stale, foreign, cancelled or already-concluded attempt
        // never reaches the network.
        if (!attempt.ownedBy(loginAuthority)) return ApiLoginOutcome.Rejected(ApiLoginIssue.SESSION_CHANGED)
        val expected = sessionStore.loginClaimSnapshot(loginAuthority, attempt)
            ?: return ApiLoginOutcome.Rejected(ApiLoginIssue.SESSION_CHANGED)
        val response = executeWithOptionalLoginGate(
            ApiEndpoint.LOGIN, queryParameters("username" to username, "password" to password),
            body = null, expectedSession = null, loginAttempt = attempt,
        )
        return when (response) {
            is ApiResult.Failure -> response.error.toLoginOutcome()
            is ApiResult.Success -> {
                // Post-await and pre-commit are one synchronous state-dispatcher section; the
                // claim and the claim-time snapshot decide, never a freshly captured state.
                if (!sessionStore.isLoginClaimCurrent(loginAuthority, attempt)) {
                    return ApiLoginOutcome.Unknown(ApiLoginIssue.SESSION_CHANGED)
                }
                val session = ApiLoginDecoder.decodeSession(response.data)
                    ?: return ApiLoginOutcome.Unknown(ApiLoginIssue.MALFORMED)
                try {
                    if (sessionStore.saveLoginIfCurrent(loginAuthority, attempt, expected, session)) {
                        ApiLoginOutcome.Accepted
                    } else {
                        // The request was already sent and cannot be withdrawn: report the
                        // supersession honestly and never write over a store that moved.
                        ApiLoginOutcome.Unknown(ApiLoginIssue.SESSION_CHANGED)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // The write's outcome is unknown; it is never retried automatically here.
                    ApiLoginOutcome.Unknown(ApiLoginIssue.STORAGE)
                }
            }
        }
    }

    /** Known rejections stay Rejected; every uncertain upstream or local outcome stays Unknown. */
    private fun ApiError.toLoginOutcome(): ApiLoginOutcome = when (this) {
        is ApiError.Validation -> ApiLoginOutcome.Rejected(ApiLoginIssue.VALIDATION)
        is ApiError.Authentication -> ApiLoginOutcome.Rejected(ApiLoginIssue.AUTHENTICATION)
        is ApiError.Business -> ApiLoginOutcome.Rejected(ApiLoginIssue.BUSINESS)
        is ApiError.Timeout -> ApiLoginOutcome.Unknown(ApiLoginIssue.TIMEOUT)
        is ApiError.Network -> ApiLoginOutcome.Unknown(ApiLoginIssue.NETWORK)
        is ApiError.Decode -> ApiLoginOutcome.Unknown(ApiLoginIssue.MALFORMED)
        is ApiError.Http -> ApiLoginOutcome.Unknown(ApiLoginIssue.HTTP)
        is ApiError.Storage -> ApiLoginOutcome.Unknown(ApiLoginIssue.STORAGE)
    }

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
        expectedSession: ApiSessionBinding? = null,
    ): ApiResult<JsonElement> = executeWithOptionalLoginGate(endpoint, query, body, expectedSession, loginAttempt = null)

    /**
     * The legacy generic entry keeps its public signature; only the specialized typed LOGIN path
     * passes the optional admission gate down to perform. No other endpoint, and no 401
     * handling, changes behavior here.
     */
    private suspend fun executeWithOptionalLoginGate(
        endpoint: ApiEndpoint,
        query: JsonObject,
        body: JsonObject?,
        expectedSession: ApiSessionBinding?,
        loginAttempt: ApiLoginAttempt?,
    ): ApiResult<JsonElement> {
        if (endpoint.location == ParameterLocation.MULTIPART) {
            return invalid(listOf("file"), AppText.Common.UPLOAD_ENDPOINT_REQUIRES_MULTIPART_METHOD)
        }
        if (endpoint.location == ParameterLocation.QUERY && body != null) {
            return invalid(listOf("body"), AppText.Common.ENDPOINT_PARAMETERS_REQUIRE_QUERY)
        }
        if (endpoint.location == ParameterLocation.JSON && (body == null || query.isNotEmpty())) {
            return invalid(listOf("body"), AppText.Common.ENDPOINT_ACCEPTS_JSON_BODY_ONLY)
        }
        val parameters = if (endpoint.location == ParameterLocation.JSON) body!! else query
        val missing = endpoint.required.filter { parameters[it].isMissingParameter() }
        if (missing.isNotEmpty()) return invalid(missing)
        if (endpoint.requiresParameters && parameters.values.all { it.isMissingParameter() }) {
            return invalid(listOf("parameters"))
        }
        return perform(endpoint, expectedSession, loginAttempt) {
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
        expectedSession: ApiSessionBinding? = null,
        loginAttempt: ApiLoginAttempt? = null,
        configure: HttpRequestBuilder.() -> Unit,
    ): ApiResult<JsonElement> {
        // Bind even legacy/unbound requests to one mutation epoch before the HTTP pipeline suspends.
        val snapshot = sessionStore.captureSnapshot()
        if (expectedSession != null && !expectedSession.matches(bindingAuthority, snapshot)) {
            // This is a local stale-operation result, not a server 401; leave the current login alone.
            return ApiResult.Failure(ApiError.Authentication(AppText.Auth.OPERATION_SESSION_CHANGED_RECONFIRM_BEFORE_CONTINUING))
        }
        if (loginAttempt != null && sessionStore.loginClaimSnapshot(loginAuthority, loginAttempt) == null) {
            // Optional admission gate, specialized typed LOGIN only: dispatch while this attempt
            // still owns the store-wide latest claim; a stale attempt stays local, zero requests.
            return ApiResult.Failure(ApiError.Authentication(AppText.Auth.OPERATION_SESSION_CHANGED_RECONFIRM_BEFORE_CONTINUING))
        }
        val token = when {
            endpoint.authentication == Authentication.NONE -> null
            expectedSession != null -> expectedSession.tokenFor(bindingAuthority)
            else -> snapshot.token
        }
        if (endpoint.authentication == Authentication.REQUIRED && token.isNullOrBlank()) {
            return ApiResult.Failure(ApiError.Authentication())
        }
        // Anonymous responses have no authority over any login, including a login made in flight.
        val requestSession = snapshot.takeIf { token != null }
        return try {
            val startedAt = kotlin.time.Clock.System.now().toEpochMilliseconds()
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
            // Local debug log only: method, path without query, status and timing. No body, no token.
            io.github.betterzslf.core.log.AppLog.http(
                endpoint.method.value, endpoint.path, status,
                kotlin.time.Clock.System.now().toEpochMilliseconds() - startedAt,
            )
            when {
                status == 401 -> {
                    response.bodyAsChannel().cancel(null)
                    authenticationFailure(requestSession)
                }
                status !in 200..299 -> {
                    response.bodyAsChannel().cancel(null)
                    ApiResult.Failure(ApiError.Http(status))
                }
                else -> parseEnvelope(response.bodyAsText(), endpoint, requestSession)
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
            io.github.betterzslf.core.log.AppLog.warn("http", "${endpoint.method.value} ${endpoint.path} -> network error")
            ApiResult.Failure(ApiError.Network())
        }
    }

    private fun parseEnvelope(text: String, endpoint: ApiEndpoint, requestSession: SessionSnapshot?): ApiResult<JsonElement> {
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
            401 -> authenticationFailure(requestSession)
            else -> ApiResult.Failure(ApiError.Business(code, message.ifBlank { AppText.Common.OPERATION_UNSUCCESSFUL_TRY_AGAIN_LATER }))
        }
    }

    private fun authenticationFailure(requestSession: SessionSnapshot?): ApiResult.Failure {
        try {
            // The store compares epoch + owner + token and clears without suspension on the state
            // dispatcher. Same-value saves/clear-save ABA cannot revive any old request authority.
            if (requestSession != null) sessionStore.clearIfCurrent(requestSession)
        } catch (_: Exception) {
            // SessionStore has already cleared its StateFlow even if private storage failed.
        }
        return ApiResult.Failure(ApiError.Authentication())
    }

    private fun invalid(fields: List<String>, message: String = AppText.Common.CHECK_REQUIRED_PARAMETERS): ApiResult.Failure =
        ApiResult.Failure(ApiError.Validation(fields, message))
}
