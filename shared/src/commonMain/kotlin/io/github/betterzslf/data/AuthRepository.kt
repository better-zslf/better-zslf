package io.github.betterzslf.data

import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class AuthRepository(private val api: ApiClient, private val sessions: SessionStore) {
    /** Credentials are used only in this request; no password is retained or logged. */
    suspend fun login(username: String, password: String): ApiResult<Session> {
        return when (val result = api.execute(ApiEndpoint.LOGIN, queryParameters("username" to username, "password" to password))) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> {
                val data = result.data as? JsonObject
                val token = (data?.get("accessToken") as? JsonPrimitive)?.contentOrNull
                val userInfo = data?.get("userInfo") as? JsonObject
                val deptInfo = data?.get("deptInfo") as? JsonObject
                if (token.isNullOrBlank() || userInfo == null || deptInfo == null) {
                    ApiResult.Failure(ApiError.Decode("登录响应缺少会话信息"))
                } else {
                    val session = Session(
                        token = token,
                        userInfo = userInfo,
                        deptInfo = deptInfo,
                        mapToken = (data["mapToken"] as? JsonPrimitive)?.contentOrNull,
                        mapWebType = (data["mapWebType"] as? JsonPrimitive)?.contentOrNull,
                    )
                    try {
                        sessions.save(session)
                        ApiResult.Success(session, result.message)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ApiResult.Failure(ApiError.Storage())
                    }
                }
            }
        }
    }

    fun logout() { sessions.clear() }

    /** Despite the login-screen placement, docs/13 confirms this endpoint requires a token. */
    suspend fun checkPhone(phone: String, smsCode: String): ApiResult<JsonElement> =
        api.execute(ApiEndpoint.CHECK_PHONE, queryParameters("phone" to phone, "smsCode" to smsCode))

    /** An explicit password-changing action; caller handles its confirmation UI. POST+query only. */
    suspend fun resetPassword(userId: String, newPassword: String): ApiResult<JsonElement> {
        passwordError(newPassword)?.let { return it }
        return changePassword(ApiEndpoint.RESET_PASSWORD, queryParameters("userId" to userId, "newPassWord" to newPassword))
    }

    suspend fun modifyPassword(oldPassword: String, newPassword: String): ApiResult<JsonElement> {
        passwordError(newPassword)?.let { return it }
        return changePassword(ApiEndpoint.MODIFY_PASSWORD, queryParameters("oldPassword" to oldPassword, "newPassWord" to newPassword))
    }

    private suspend fun changePassword(endpoint: ApiEndpoint, parameters: JsonObject): ApiResult<JsonElement> {
        val requestToken = sessions.session.value?.token
        val result = api.execute(endpoint, parameters)
        if (result is ApiResult.Success) {
            try {
                sessions.clearIfTokenMatches(requestToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return ApiResult.Failure(ApiError.Storage())
            }
        }
        return result
    }

    private fun passwordError(password: String): ApiResult.Failure? =
        if (isValidNewPassword(password)) null else ApiResult.Failure(ApiError.Validation(
            fields = listOf("newPassWord"),
            message = "新密码至少 8 位，须同时包含字母、数字和符号，且不含空白字符",
        ))

    companion object {
        fun isValidNewPassword(value: String): Boolean = value.length >= 8 &&
            value.any { it in 'a'..'z' || it in 'A'..'Z' } &&
            value.any { it in '0'..'9' } &&
            value.any { !it.isLetterOrDigit() && !it.isWhitespace() } &&
            value.none { it.isWhitespace() }
    }
}
