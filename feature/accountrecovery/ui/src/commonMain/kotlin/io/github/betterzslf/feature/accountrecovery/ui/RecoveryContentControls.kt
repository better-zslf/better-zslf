package io.github.betterzslf.feature.accountrecovery.ui

/** Safe render controls only. Raw inputs and opaque confirmation capabilities stay in the binder. */
data class RecoveryContentControls(
    val editable: Boolean = false,
    val canSend: Boolean = false,
    val canVerify: Boolean = false,
    val canPrepare: Boolean = false,
    val smsDialogOpen: Boolean = false,
    val smsMaskedPhone: String = "",
    val resetDialogOpen: Boolean = false,
    val resetMaskedPhone: String = "",
    val acknowledged: Boolean = false,
    val backNotice: Boolean = false,
) {
    fun allows(event: RecoveryUiEvent): Boolean = when (event) {
        RecoveryUiEvent.BACK, RecoveryUiEvent.DISMISS_BACK_NOTICE -> true
        RecoveryUiEvent.OPEN_SMS_CONFIRMATION -> canSend
        RecoveryUiEvent.DISMISS_SMS_CONFIRMATION -> smsDialogOpen
        RecoveryUiEvent.CONFIRM_SMS -> smsDialogOpen && canSend
        RecoveryUiEvent.VERIFY -> canVerify
        RecoveryUiEvent.PREPARE_RESET -> canPrepare
        RecoveryUiEvent.EDIT_PREVIEW, RecoveryUiEvent.OPEN_RESET_CONFIRMATION -> !resetDialogOpen
        RecoveryUiEvent.DISMISS_RESET_CONFIRMATION, RecoveryUiEvent.TOGGLE_ACKNOWLEDGEMENT -> resetDialogOpen
        RecoveryUiEvent.CONFIRM_RESET -> resetDialogOpen && acknowledged
    }
}

/** Every event is a zero-payload intent. No field text, identity, nonce or ticket crosses this API. */
enum class RecoveryUiEvent {
    BACK, DISMISS_BACK_NOTICE,
    OPEN_SMS_CONFIRMATION, DISMISS_SMS_CONFIRMATION, CONFIRM_SMS, VERIFY,
    PREPARE_RESET, EDIT_PREVIEW, OPEN_RESET_CONFIRMATION, DISMISS_RESET_CONFIRMATION,
    TOGGLE_ACKNOWLEDGEMENT, CONFIRM_RESET,
}
