package io.github.betterzslf.ui.schedule

/** Display intents only. Credential edits and binding authority stay in the product's slots/binder. */
enum class ScheduleBindingUiEvent { PROMPT_BIND, PROMPT_SKIP, PROMPT_DISMISS, BACK, VERIFY, CONFIRM }

data class ScheduleBindingControls(
    val canVerify: Boolean = false,
    val canConfirm: Boolean = false,
    val canBack: Boolean = true,
) {
    fun allows(event: ScheduleBindingUiEvent): Boolean = when (event) {
        ScheduleBindingUiEvent.VERIFY -> canVerify
        ScheduleBindingUiEvent.CONFIRM -> canConfirm
        ScheduleBindingUiEvent.BACK -> canBack
        ScheduleBindingUiEvent.PROMPT_BIND, ScheduleBindingUiEvent.PROMPT_SKIP, ScheduleBindingUiEvent.PROMPT_DISMISS -> false
    }
}
