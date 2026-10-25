package io.github.betterzslf.feature.accountrecovery

import io.github.betterzslf.core.text.AppText

internal const val RECOVERY_WRITE_BACK_NOTICE = AppText.Recovery.REQUEST_POSSIBLY_ACCEPTED_BACK_DOES_NOT_REVOKE_NOTICE

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
