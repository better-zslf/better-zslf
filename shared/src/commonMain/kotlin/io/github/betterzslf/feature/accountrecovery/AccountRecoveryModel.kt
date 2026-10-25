package io.github.betterzslf.feature.accountrecovery

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.AuthRepository
import io.github.betterzslf.data.PasswordResetReceipt
import kotlinx.serialization.json.*

interface AccountRecoverySource {
    fun isCurrentSession(): Boolean
    fun isLoggedOut(): Boolean
    suspend fun sendCode(phone: String): ApiResult<JsonElement>
    suspend fun verify(phone: String, code: String): ApiResult<JsonElement>
    suspend fun reset(userId: String, password: String): PasswordResetReceipt
}

class RepositoryAccountRecoverySource private constructor(private val repository: AuthRepository) : AccountRecoverySource {
    override fun isCurrentSession() = repository.isBoundToCurrentSession
    override fun isLoggedOut() = repository.isLoggedOut
    override suspend fun sendCode(phone: String) = repository.sendResetCode(phone)
    override suspend fun verify(phone: String, code: String) = repository.checkPhone(phone, code)
    override suspend fun reset(userId: String, password: String) = repository.resetPasswordWithReceipt(userId, password)
    override fun toString() = "RepositoryAccountRecoverySource(redacted)"
    companion object {
        fun capture(repository: AuthRepository): RepositoryAccountRecoverySource? =
            repository.boundToCurrentSession()?.let(::RepositoryAccountRecoverySource)
    }
}

enum class AccountRecoveryPhase { GUEST, EDITING, PREVIEW, RESETTING, COMPLETED, CLEANUP_WARNING, FAILED, UNCERTAIN, SESSION_CHANGED, CLOSED }
data class AccountRecoveryPreview(val id: Long, val maskedPhone: String)
data class AccountRecoveryState(
    val phase: AccountRecoveryPhase = AccountRecoveryPhase.EDITING,
    val sending: Boolean = false,
    val verifying: Boolean = false,
    val codeRequested: Boolean = false,
    val cooldownSeconds: Int = 0,
    val verified: Boolean = false,
    val maskedPhone: String? = null,
    val preview: AccountRecoveryPreview? = null,
    val message: String? = null,
    val inputRevision: Long = 0,
) {
    val busy: Boolean get() = sending || verifying || phase == AccountRecoveryPhase.RESETTING
    val terminal: Boolean get() = phase !in setOf(AccountRecoveryPhase.EDITING, AccountRecoveryPhase.PREVIEW)
}

/** Static consumer contract: data.user.id only; no current-owner, phone or alias fallback. */
internal fun recoveryVerifiedId(data: JsonElement): String? {
    val id = (((data as? JsonObject)?.get("user") as? JsonObject)?.get("id") as? JsonPrimitive) ?: return null
    if (!id.isString && id.doubleOrNull?.isFinite() != true) return null
    return id.contentOrNull?.takeIf { it.isNotBlank() && it == it.trim() && it.none(Char::isISOControl) }
}

fun recoveryPhoneValidation(phone: String): String? =
    if (phone.length == 11 && phone.all { it in '0'..'9' }) null else AppText.Common.MOBILE_NUMBER_MUST_HAVE_ELEVEN_DIGITS

fun recoveryPasswordValidation(password: String, confirmation: String): String? = when {
    !AuthRepository.isValidNewPassword(password) -> AppText.Recovery.NEW_SCHOOL_PASSWORD_COMPLEXITY_AND_LENGTH_REQUIRED
    password != confirmation -> AppText.Recovery.NEW_SCHOOL_PASSWORD_CONFIRMATION_MISMATCH
    else -> null
}

internal fun recoveryMask(phone: String): String = phone.take(3) + "••••" + phone.takeLast(2)
internal fun recoveryError(error: ApiError): String = when (error) {
    is ApiError.Authentication -> AppText.Recovery.CURRENT_LOGIN_SESSION_EXPIRED_REENTER
    is ApiError.Business -> AppText.Recovery.REQUEST_NOT_ACCEPTED_REVIEW_DETAILS
    is ApiError.Validation -> AppText.Recovery.REVIEW_REQUIRED_DETAILS
    is ApiError.Timeout -> AppText.Common.REQUEST_TIMEOUT_RESULT_UNCONFIRMED
    is ApiError.Network -> AppText.Common.CONNECTION_INTERRUPTED_RESULT_UNCONFIRMED
    is ApiError.Http -> AppText.Recovery.SERVICE_FAILED_RESULT_UNCONFIRMED
    is ApiError.Decode -> AppText.Recovery.SERVICE_RESPONSE_STRUCTURE_UNCONFIRMED
    is ApiError.Storage -> AppText.Recovery.LOCAL_STATE_PROCESSING_FAILED
}
