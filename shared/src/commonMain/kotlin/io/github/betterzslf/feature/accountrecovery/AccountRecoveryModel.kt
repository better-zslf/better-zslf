package io.github.betterzslf.feature.accountrecovery

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
    if (phone.length == 11 && phone.all { it in '0'..'9' }) null else "请输入 11 位数字手机号"

fun recoveryPasswordValidation(password: String, confirmation: String): String? = when {
    !AuthRepository.isValidNewPassword(password) -> "新学校登录密码至少 8 位，含字母、数字和符号，且不含空白"
    password != confirmation -> "两次输入的新学校登录密码不一致"
    else -> null
}

internal fun recoveryMask(phone: String): String = phone.take(3) + "••••" + phone.takeLast(2)
internal fun recoveryError(error: ApiError): String = when (error) {
    is ApiError.Authentication -> "本次登录会话已失效，请重新进入"
    is ApiError.Business -> "服务未接受本次请求，请核对资料"
    is ApiError.Validation -> "请核对必填资料"
    is ApiError.Timeout -> "请求超时，结果尚未确认"
    is ApiError.Network -> "连接中断，结果尚未确认"
    is ApiError.Http -> "服务异常，结果尚未确认"
    is ApiError.Decode -> "服务返回结构尚未确认"
    is ApiError.Storage -> "本机状态处理失败"
}
