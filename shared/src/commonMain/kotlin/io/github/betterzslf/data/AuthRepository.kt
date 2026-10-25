package io.github.betterzslf.data

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.ApiSessionBinding
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class PasswordResetLogout { CLEARED, CLEAR_FAILED, NEW_SESSION_PRESERVED }

/** Acknowledgement is retained even when local session cleanup fails; never repeat the reset. */
sealed interface PasswordResetReceipt {
    data class Accepted(val logout: PasswordResetLogout) : PasswordResetReceipt
    data class Failed(val error: ApiError) : PasswordResetReceipt
}

class AuthRepository private constructor(
    private val api: ApiClient,
    private val sessions: SessionStore,
    private val binding: ApiSessionBinding?,
) {
    constructor(api: ApiClient, sessions: SessionStore) : this(api, sessions, null)

    val isBoundToCurrentSession: Boolean get() = binding != null && api.isBindingCurrent(binding)
    val isLoggedOut: Boolean get() = sessions.session.value == null

    fun boundToCurrentSession(): AuthRepository? {
        if (binding != null) return if (api.isBindingCurrent(binding)) this else null
        return api.captureSessionBinding()?.let { AuthRepository(api, sessions, it) }
    }

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
                    ApiResult.Failure(ApiError.Decode(AppText.Auth.LOGIN_RESPONSE_SESSION_INFORMATION_MISSING))
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

    fun logout() { if (binding == null || api.isBindingCurrent(binding)) sessions.clear() }

    /** Observed reset-page upstream: phone-only SMS request, not verification or proof of delivery. */
    suspend fun sendResetCode(phone: String): ApiResult<JsonElement> = api.execute(ApiEndpoint.CAMPUS_CHECK_PHONE,
        queryParameters("phone" to phone), expectedSession = binding)

    /** Keep current REQUIRED authentication; anonymous behavior is not proven endpoint-by-endpoint. */
    suspend fun checkPhone(phone: String, smsCode: String): ApiResult<JsonElement> =
        api.execute(ApiEndpoint.CHECK_PHONE, queryParameters("phone" to phone, "smsCode" to smsCode), expectedSession = binding)

    /** An explicit password-changing action; caller handles its confirmation UI. POST+query only. */
    suspend fun resetPassword(userId: String, newPassword: String): ApiResult<JsonElement> {
        passwordError(newPassword)?.let { return it }
        return changePassword(ApiEndpoint.RESET_PASSWORD, queryParameters("userId" to userId, "newPassWord" to newPassword))
    }

    suspend fun modifyPassword(oldPassword: String, newPassword: String): ApiResult<JsonElement> {
        passwordError(newPassword)?.let { return it }
        return changePassword(ApiEndpoint.MODIFY_PASSWORD, queryParameters("oldPassword" to oldPassword, "newPassWord" to newPassword))
    }

    /** Same public parameter order as resetPassword(userId, newPassword), with explicit acknowledgement. */
    suspend fun resetPasswordWithReceipt(userId: String, newPassword: String): PasswordResetReceipt {
        passwordError(newPassword)?.let { return PasswordResetReceipt.Failed(it.error) }
        val changed = requestPasswordChange(ApiEndpoint.RESET_PASSWORD,
            queryParameters("userId" to userId, "newPassWord" to newPassword))
        return when (val response = changed.response) {
            is ApiResult.Failure -> PasswordResetReceipt.Failed(response.error)
            is ApiResult.Success -> PasswordResetReceipt.Accepted(changed.logout ?: PasswordResetLogout.NEW_SESSION_PRESERVED)
        }
    }

    private class ChangeResult(val response: ApiResult<JsonElement>, val logout: PasswordResetLogout? = null)

    private suspend fun changePassword(endpoint: ApiEndpoint, parameters: JsonObject): ApiResult<JsonElement> {
        val result = requestPasswordChange(endpoint, parameters)
        // Preserve the legacy ApiResult contract; interactive recovery consumes the typed receipt instead.
        return if (result.logout == PasswordResetLogout.CLEAR_FAILED) ApiResult.Failure(ApiError.Storage()) else result.response
    }

    private suspend fun requestPasswordChange(endpoint: ApiEndpoint, parameters: JsonObject): ChangeResult {
        val expected = binding ?: api.captureSessionBinding()
        val snapshot = sessions.session.value
        val response = api.execute(endpoint, parameters, expectedSession = expected)
        if (response is ApiResult.Failure) return ChangeResult(response)
        if (snapshot == null || (expected != null && !api.isBindingCurrent(expected)) ||
            sessions.session.value?.userId != snapshot.userId || sessions.session.value?.token != snapshot.token) {
            return ChangeResult(response, PasswordResetLogout.NEW_SESSION_PRESERVED)
        }
        return try {
            sessions.clearIfTokenMatches(snapshot.token)
            ChangeResult(response, PasswordResetLogout.CLEARED)
        } catch (cancelled: CancellationException) {
            // SessionStore clears its in-memory value in finally, even if backing storage fails.
            ChangeResult(response, PasswordResetLogout.CLEAR_FAILED)
        } catch (_: Exception) {
            ChangeResult(response, PasswordResetLogout.CLEAR_FAILED)
        }
    }

    override fun toString(): String = "AuthRepository(bound=${binding != null})"

    private fun passwordError(password: String): ApiResult.Failure? =
        if (isValidNewPassword(password)) null else ApiResult.Failure(ApiError.Validation(
            fields = listOf("newPassWord"),
            message = AppText.Auth.NEW_PASSWORD_LENGTH_AND_COMPLEXITY_REQUIRED,
        ))

    companion object {
        fun isValidNewPassword(value: String): Boolean = value.length >= 8 &&
            value.any { it in 'a'..'z' || it in 'A'..'Z' } &&
            value.any { it in '0'..'9' } &&
            value.any { !it.isLetterOrDigit() && !it.isWhitespace() } &&
            value.none { it.isWhitespace() }
    }
}
