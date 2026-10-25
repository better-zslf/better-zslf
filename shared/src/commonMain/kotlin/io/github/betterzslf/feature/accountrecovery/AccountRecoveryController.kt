package io.github.betterzslf.feature.accountrecovery

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.PasswordResetLogout
import io.github.betterzslf.data.PasswordResetReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock

/** One route, one bound session, no persistence or automatic SMS/password retry. Use on the UI dispatcher. */
class AccountRecoveryController(
    private val source: AccountRecoverySource?,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onAccepted: (PasswordResetLogout) -> Unit = {},
) {
    private val mutable = MutableStateFlow(if (source == null) AccountRecoveryState(
        phase = AccountRecoveryPhase.GUEST,
        message = "当前三个接口保持有效登录会话前置；匿名找回支持待核实。未登录时发送、核验与重置保持禁用。",
    ) else AccountRecoveryState())
    val state: StateFlow<AccountRecoveryState> = mutable.asStateFlow()
    private val flight = Mutex()
    private var alive = true
    private var generation = 0L
    private var nonce = 0L
    private var cooldownUntil = 0L
    private var sentPhone: String? = null
    private var verifiedPhone: String? = null
    private var verifiedCode: String? = null
    private var verifiedId: String? = null
    private var pending: Attempt? = null
    private var attempted = false
    private class Attempt(val nonce: Long, val id: String, val password: String) {
        override fun toString() = "RecoveryAttempt(redacted)"
    }

    private fun current(): Boolean {
        if (!alive) return false
        if (source == null) return false
        if (source.isCurrentSession()) return true
        invalidateSession()
        return false
    }
    private fun clearIdentity() { verifiedPhone = null; verifiedCode = null; verifiedId = null; pending = null }
    private fun invalidateSession() {
        ++generation; clearIdentity(); sentPhone = null; cooldownUntil = 0
        mutable.value = AccountRecoveryState(phase = AccountRecoveryPhase.SESSION_CHANGED,
            message = "登录会话已变化，已清除输入和旧核验结果；请重新进入", inputRevision = mutable.value.inputRevision + 1)
    }
    fun close() {
        alive = false; ++generation; clearIdentity(); sentPhone = null; cooldownUntil = 0
        mutable.value = AccountRecoveryState(phase = AccountRecoveryPhase.CLOSED, inputRevision = mutable.value.inputRevision + 1)
    }
    private fun changed(phone: Boolean) {
        if (mutable.value.terminal || !current()) return
        ++generation; clearIdentity()
        if (phone) sentPhone = null
        mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.EDITING, verified = false,
            maskedPhone = null, preview = null, codeRequested = sentPhone != null, message = null)
    }
    fun phoneChanged() = changed(true)
    fun codeChanged() = changed(false)
    fun editPreview() {
        if (mutable.value.terminal || flight.isLocked || mutable.value.busy || !current()) return
        pending = null
        mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.EDITING, preview = null, message = null)
    }
    fun tickCooldown() {
        if (!alive || cooldownUntil == 0L) return
        val seconds = ((cooldownUntil - nowMillis() + 999L) / 1000L).coerceIn(0, 60).toInt()
        if (mutable.value.cooldownSeconds != seconds) mutable.value = mutable.value.copy(cooldownSeconds = seconds)
    }

    suspend fun sendCode(phone: String, confirmed: Boolean): Boolean {
        if (!flight.tryLock()) return false
        var dispatched = false
        try {
            if (!confirmed || mutable.value.phase != AccountRecoveryPhase.EDITING || !current()) return false
            tickCooldown()
            if (mutable.value.cooldownSeconds > 0) return false
            recoveryPhoneValidation(phone)?.let { mutable.value = mutable.value.copy(message = it); return false }
            clearIdentity(); sentPhone = null
            val ticket = ++generation
            mutable.value = mutable.value.copy(sending = true, verified = false, maskedPhone = null, preview = null,
                codeRequested = false, message = "正在请求发送短信，不代表已经送达")
            dispatched = true
            val response = call { source!!.sendCode(phone) }
            if (!current()) return false
            when (response) {
                is ApiResult.Success -> {
                    val sameInputs = ticket == generation
                    sentPhone = if (sameInputs) phone else null
                    cooldownUntil = nowMillis() + 60_000L
                    mutable.value = mutable.value.copy(codeRequested = sameInputs, cooldownSeconds = 60,
                        message = if (sameInputs) "服务已接受短信请求；本地 60 秒倒计时不保证送达，也不表示服务端验证码有效期。"
                        else "先前输入的短信请求已被服务接受，但输入已变化，请重新核对；本地 60 秒等待不保证送达。")
                    return sameInputs
                }
                is ApiResult.Failure -> {
                    val unknown = response.error !is ApiError.Business && response.error !is ApiError.Validation
                    mutable.value = mutable.value.copy(phase = if (unknown) AccountRecoveryPhase.UNCERTAIN else AccountRecoveryPhase.EDITING,
                        message = recoveryError(response.error) + if (unknown) "；本页停止发送，请核对实际短信结果，不会自动重发。" else "；本次未启动成功倒计时。")
                    return false
                }
            }
        } catch (cancelled: CancellationException) {
            if (dispatched && alive && current()) mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.UNCERTAIN,
                message = "短信请求过程已中断，是否发送尚未确认；本页停止发送，不会重发")
            throw cancelled
        } finally {
            if (mutable.value.sending) mutable.value = mutable.value.copy(sending = false)
            flight.unlock()
        }
    }

    suspend fun verify(phone: String, code: String): Boolean {
        if (!flight.tryLock()) return false
        try {
            if (mutable.value.phase != AccountRecoveryPhase.EDITING || !current()) return false
            val invalid = recoveryPhoneValidation(phone) ?: if (code.isBlank() || code.any { it.isWhitespace() || it.isISOControl() }) "请输入短信验证码" else null
            if (invalid != null) { mutable.value = mutable.value.copy(message = invalid); return false }
            if (sentPhone != phone) { mutable.value = mutable.value.copy(message = "请先为当前手机号明确请求短信，再核验验证码"); return false }
            clearIdentity()
            val ticket = ++generation
            mutable.value = mutable.value.copy(verifying = true, verified = false, maskedPhone = null, preview = null, message = null)
            val response = call { source!!.verify(phone, code) }
            if (!current() || ticket != generation) return false
            if (response is ApiResult.Failure) { mutable.value = mutable.value.copy(message = recoveryError(response.error)); return false }
            val id = recoveryVerifiedId((response as ApiResult.Success<JsonElement>).data)
            if (id == null) {
                mutable.value = mutable.value.copy(message = "核验响应缺少已证的 user.id；保持未验证，不以当前登录账号或手机号代替")
                return false
            }
            verifiedPhone = phone; verifiedCode = code; verifiedId = id
            mutable.value = mutable.value.copy(verified = true, maskedPhone = recoveryMask(phone),
                message = "短信核验已返回有效目标身份；仅用于本次学校登录密码重置")
            return true
        } finally {
            if (mutable.value.verifying) mutable.value = mutable.value.copy(verifying = false)
            flight.unlock()
        }
    }

    fun prepare(phone: String, code: String, password: String, confirmation: String): Boolean {
        if (mutable.value.phase != AccountRecoveryPhase.EDITING || flight.isLocked || mutable.value.busy || attempted || !current()) return false
        val id = verifiedId
        if (id == null || !mutable.value.verified || phone != verifiedPhone || code != verifiedCode) {
            mutable.value = mutable.value.copy(message = "请先核验当前手机号与验证码；编辑后原身份即作废")
            return false
        }
        recoveryPasswordValidation(password, confirmation)?.let { mutable.value = mutable.value.copy(message = it); return false }
        val receipt = ++nonce
        pending = Attempt(receipt, id, password)
        mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.PREVIEW,
            preview = AccountRecoveryPreview(receipt, recoveryMask(phone)), message = null)
        return true
    }

    suspend fun confirm(previewId: Long, acknowledged: Boolean): Boolean {
        if (!flight.tryLock()) return false
        var dispatched = false
        try {
            if (mutable.value.terminal || !current()) return false
            val attempt = pending
            if (!acknowledged || attempted || attempt == null || attempt.nonce != previewId ||
                mutable.value.preview?.id != previewId || mutable.value.phase != AccountRecoveryPhase.PREVIEW) return false
            attempted = true
            clearIdentity(); sentPhone = null; cooldownUntil = 0
            mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.RESETTING, verified = false,
                codeRequested = false, cooldownSeconds = 0, message = "正在重置学校登录密码；本次仅发送一次")
            if (!current()) return false
            dispatched = true
            val outcome = try { source!!.reset(attempt.id, attempt.password) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PasswordResetReceipt.Failed(ApiError.Network()) }
            if (!alive) return false
            when (outcome) {
                is PasswordResetReceipt.Accepted -> {
                    // Successful reset deliberately makes the old binding stale by logging it out.
                    if (outcome.logout == PasswordResetLogout.NEW_SESSION_PRESERVED || source?.isLoggedOut() != true) {
                        invalidateSession(); return false
                    }
                    mutable.value = mutable.value.copy(
                        phase = if (outcome.logout == PasswordResetLogout.CLEAR_FAILED) AccountRecoveryPhase.CLEANUP_WARNING else AccountRecoveryPhase.COMPLETED,
                        preview = null, maskedPhone = null, inputRevision = mutable.value.inputRevision + 1,
                        message = if (outcome.logout == PasswordResetLogout.CLEAR_FAILED)
                            "服务已确认重置成功，内存会话已退出，但本机持久会话删除失败；请处理本机会话记录。不要再次重置。"
                        else "服务已确认学校登录密码重置成功，当前会话已退出；请用新密码登录。本页不重复提交。")
                    onAccepted(outcome.logout)
                    return true
                }
                is PasswordResetReceipt.Failed -> {
                    if (!current()) return false
                    val explicit = outcome.error is ApiError.Business || outcome.error is ApiError.Validation
                    mutable.value = mutable.value.copy(phase = if (explicit) AccountRecoveryPhase.FAILED else AccountRecoveryPhase.UNCERTAIN,
                        message = recoveryError(outcome.error) + if (explicit) "；本次停止，不自动重置。" else "；服务可能已接收，请先核对实际结果，本页不会再次重置。")
                    return false
                }
            }
        } catch (cancelled: CancellationException) {
            if (dispatched && alive && current()) mutable.value = mutable.value.copy(phase = AccountRecoveryPhase.UNCERTAIN,
                message = "重置过程已中断，是否接受尚未确认；请核对实际结果，本页不再发送")
            throw cancelled
        } finally { flight.unlock() }
    }

    private suspend fun call(block: suspend () -> ApiResult<JsonElement>): ApiResult<JsonElement> = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { ApiResult.Failure(ApiError.Network()) }
    override fun toString() = "AccountRecoveryController(phase=${mutable.value.phase})"
}
