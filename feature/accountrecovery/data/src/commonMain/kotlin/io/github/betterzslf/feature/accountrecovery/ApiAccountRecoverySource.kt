package io.github.betterzslf.feature.accountrecovery

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
import kotlinx.serialization.json.doubleOrNull

/** Real protocol implementation. Never rebinds an old operation or owns the injected client/store. */
class ApiAccountRecoverySource private constructor(
    private val api: ApiClient,
    private val sessions: SessionStore,
    private val binding: ApiSessionBinding,
) : AccountRecoverySource {
    override fun isCurrentSession(): Boolean = api.isBindingCurrent(binding)
    override fun isLoggedOut(): Boolean = sessions.session.value == null

    override suspend fun sendCode(phone: String): RecoverySmsOutcome = when (
        val result = api.execute(ApiEndpoint.CAMPUS_CHECK_PHONE, queryParameters("phone" to phone), expectedSession = binding)
    ) {
        is ApiResult.Success -> RecoverySmsOutcome.Accepted
        is ApiResult.Failure -> result.error.toRecoveryIssue().let { issue ->
            if (issue.isExplicitRejection()) RecoverySmsOutcome.Rejected(issue) else RecoverySmsOutcome.Unknown(issue)
        }
    }

    override suspend fun verify(phone: String, code: String): RecoveryVerifyOutcome = when (
        val result = api.execute(ApiEndpoint.CHECK_PHONE,
            queryParameters("phone" to phone, "smsCode" to code), expectedSession = binding)
    ) {
        is ApiResult.Failure -> RecoveryVerifyOutcome.Failed(result.error.toRecoveryIssue())
        is ApiResult.Success -> recoveryVerifiedTarget(result.data)?.let { RecoveryVerifyOutcome.Verified(it) }
            ?: RecoveryVerifyOutcome.MissingTarget
    }

    override suspend fun reset(target: RecoveryTarget, password: String): RecoveryResetOutcome {
        if (!isValidRecoveryNewPassword(password)) return RecoveryResetOutcome.Rejected(RecoveryIssue.VALIDATION)
        val response = api.execute(ApiEndpoint.RESET_PASSWORD,
            queryParameters("userId" to target.id, "newPassWord" to password), expectedSession = binding)
        if (response is ApiResult.Failure) return response.error.toRecoveryIssue().let { issue ->
            if (issue.isExplicitRejection()) RecoveryResetOutcome.Rejected(issue) else RecoveryResetOutcome.Unknown(issue)
        }
        // Only this already-acknowledged branch absorbs cleanup cancellation/errors. The core
        // compare-and-clear remains one non-suspending authority + epoch check on the state dispatcher.
        val logout = try {
            if (api.clearCurrentBinding(binding)) RecoveryLogout.CLEARED else RecoveryLogout.NEW_SESSION_PRESERVED
        } catch (_: CancellationException) {
            RecoveryLogout.CLEAR_FAILED
        } catch (_: Exception) {
            RecoveryLogout.CLEAR_FAILED
        }
        return RecoveryResetOutcome.Accepted(logout)
    }

    override fun toString(): String = "ApiAccountRecoverySource(redacted)"

    companion object {
        /**
         * Inject the SAME ApiClient/SessionStore pair owned by the application graph. Captures only
         * in-memory authority, performs no request/storage mutation and returns null for a guest.
         */
        fun capture(api: ApiClient, sessions: SessionStore): ApiAccountRecoverySource? =
            api.captureSessionBinding()?.let { ApiAccountRecoverySource(api, sessions, it) }
    }
}

/** Exact data.user.id only; current owner, phone and aliases must never substitute for verification. */
internal fun recoveryVerifiedTarget(data: JsonElement): RecoveryTarget? {
    val id = (((data as? JsonObject)?.get("user") as? JsonObject)?.get("id") as? JsonPrimitive) ?: return null
    if (!id.isString && id.doubleOrNull?.isFinite() != true) return null
    return id.contentOrNull?.let { RecoveryTarget.fromVerifiedId(it) }
}

internal fun ApiError.toRecoveryIssue(): RecoveryIssue = when (this) {
    is ApiError.Authentication -> RecoveryIssue.AUTHENTICATION
    is ApiError.Business -> RecoveryIssue.BUSINESS
    is ApiError.Validation -> RecoveryIssue.VALIDATION
    is ApiError.Timeout -> RecoveryIssue.TIMEOUT
    is ApiError.Network -> RecoveryIssue.NETWORK
    is ApiError.Http -> RecoveryIssue.HTTP
    is ApiError.Decode -> RecoveryIssue.DECODE
    is ApiError.Storage -> RecoveryIssue.STORAGE
}

private fun RecoveryIssue.isExplicitRejection(): Boolean = this == RecoveryIssue.BUSINESS || this == RecoveryIssue.VALIDATION
