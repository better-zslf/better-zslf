package io.github.betterzslf.core.result

import io.github.betterzslf.core.text.AppText

/** A failure never contains a request URL, password, token, or raw server response. */
sealed interface ApiError {
    val message: String

    data class Authentication(override val message: String = AppText.Auth.EXPIRED_SESSION_RELOGIN_REQUIRED) : ApiError
    data class Business(val code: Int, override val message: String) : ApiError
    data class Http(val status: Int, override val message: String = AppText.Common.httpStatusMessage((status))) : ApiError
    data class Network(override val message: String = AppText.Common.NETWORK_CONNECTION_FAILED_CHECK_AND_RETRY) : ApiError
    data class Timeout(override val message: String = AppText.Common.REQUEST_TIMEOUT_TRY_AGAIN_LATER) : ApiError
    data class Decode(override val message: String = AppText.Common.INVALID_SERVICE_RESPONSE_FORMAT) : ApiError
    data class Validation(val fields: List<String>, override val message: String = AppText.Common.CHECK_REQUIRED_PARAMETERS) : ApiError
    data class Storage(override val message: String = AppText.Auth.LOCAL_SESSION_STORAGE_FAILED) : ApiError
}

sealed interface ApiResult<out T> {
    data class Success<T>(val data: T, val message: String = "") : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

inline fun <T, R> ApiResult<T>.map(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data), message)
    is ApiResult.Failure -> this
}

fun <T> ApiResult<T>.getOrNull(): T? = when (this) {
    is ApiResult.Success -> data
    is ApiResult.Failure -> null
}
