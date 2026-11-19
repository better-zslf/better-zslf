package io.github.betterzslf.feature.accountrecovery

import io.github.betterzslf.core.text.AppText

internal const val RECOVERY_WRITE_BACK_NOTICE = AppText.Recovery.REQUEST_POSSIBLY_ACCEPTED_BACK_DOES_NOT_REVOKE_NOTICE

/** Only SMS dispatch and password reset hold navigation; read-only verification remains cancellable. */
enum class RecoveryBackDecision { ALLOW, WAIT_FOR_RECEIPT }

fun recoveryBackDecision(state: AccountRecoveryState): RecoveryBackDecision =
    if (state.sending || state.phase == AccountRecoveryPhase.RESETTING) RecoveryBackDecision.WAIT_FOR_RECEIPT
    else RecoveryBackDecision.ALLOW

internal fun recoveryWriteInFlight(state: AccountRecoveryState): Boolean =
    recoveryBackDecision(state) == RecoveryBackDecision.WAIT_FOR_RECEIPT

/** Read at arrow/gesture commit, not at gesture start or an earlier Compose frame. Never mutate outcomes. */
internal fun recoveryGuardedBack(
    readState: () -> AccountRecoveryState,
    navigateBack: () -> Unit,
    showPendingNotice: () -> Unit,
) {
    if (recoveryWriteInFlight(readState())) showPendingNotice() else navigateBack()
}
