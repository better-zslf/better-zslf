package io.github.betterzslf.feature.accountrecovery

internal const val RECOVERY_WRITE_BACK_NOTICE = "请求可能已接收，请等待回执，返回不等于撤销"

/** Only SMS dispatch and password reset hold navigation; read-only verification remains cancellable. */
internal fun recoveryWriteInFlight(state: AccountRecoveryState): Boolean =
    state.sending || state.phase == AccountRecoveryPhase.RESETTING

/** Read at arrow/gesture commit, not at gesture start or an earlier Compose frame. Never mutate outcomes. */
internal fun recoveryGuardedBack(
    readState: () -> AccountRecoveryState,
    navigateBack: () -> Unit,
    showPendingNotice: () -> Unit,
) {
    if (recoveryWriteInFlight(readState())) showPendingNotice() else navigateBack()
}
