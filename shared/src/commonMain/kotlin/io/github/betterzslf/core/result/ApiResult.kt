package io.github.betterzslf.core.result

/** A failure never contains a request URL, password, token, or raw server response. */
sealed interface ApiError {
    val message: String

    data class Authentication(override val message: String = "登录状态已失效，请重新登录") : ApiError
    data class Business(val code: Int, override val message: String) : ApiError
    data class Http(val status: Int, override val message: String = "服务暂时异常（HTTP $status）") : ApiError
    data class Network(override val message: String = "网络连接失败，请检查网络后重试") : ApiError
    data class Timeout(override val message: String = "请求超时，请稍后重试") : ApiError
    data class Decode(override val message: String = "服务响应格式异常") : ApiError
    data class Validation(val fields: List<String>, override val message: String = "请检查必填参数") : ApiError
    data class Storage(override val message: String = "本地会话存储失败") : ApiError
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
