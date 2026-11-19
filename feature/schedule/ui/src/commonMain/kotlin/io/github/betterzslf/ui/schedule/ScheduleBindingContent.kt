package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.ui.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Displaying, skipping, or dismissing this prompt never authenticates. */
@Composable
fun ScheduleBindingPromptContent(visible: Boolean, onEvent: (ScheduleBindingUiEvent) -> Unit) {
    val dispatch: (ScheduleBindingUiEvent) -> Unit = { if (visible) onEvent(it) }
    OverlayDialog(show = visible, title = AppText.Schedule.INTRO_TITLE, summary = AppText.Schedule.INTRO_BODY,
        onDismissRequest = { dispatch(ScheduleBindingUiEvent.PROMPT_DISMISS) }) {
        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
            InfoBanner(AppText.Schedule.SECURITY_HINT, tone = UiTone.Neutral)
            PrimaryAction(AppText.Schedule.BIND, onClick = { dispatch(ScheduleBindingUiEvent.PROMPT_BIND) })
            TextButton(AppText.Schedule.SKIP, onClick = { dispatch(ScheduleBindingUiEvent.PROMPT_SKIP) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Full binding layout. The product owns both raw input slots and every operation capability. */
@Composable
fun ScheduleBindingPageContent(
    display: ScheduleBindingDisplay,
    controls: ScheduleBindingControls,
    onEvent: (ScheduleBindingUiEvent) -> Unit,
    accountField: @Composable () -> Unit,
    passwordField: @Composable () -> Unit,
) {
    val dispatch: (ScheduleBindingUiEvent) -> Unit = { if (controls.allows(it)) onEvent(it) }
    Screen(if (display.updatingPassword) AppText.Schedule.UPDATE_CREDENTIALS else AppText.Schedule.BIND_TITLE,
        onBack = if (controls.canBack) ({ dispatch(ScheduleBindingUiEvent.BACK) }) else null) { padding ->
        PageList(padding) {
            item("binding-explanation") { InfoBanner(AppText.Schedule.READ_ONLY_HINT, tone = UiTone.Neutral) }
            item("binding-security") { InfoBanner(AppText.Schedule.SECURITY_HINT, tone = UiTone.Neutral) }
            if (!display.hasSlot) item("binding-owner") { InfoBanner(AppText.Schedule.OWNER_REQUIRED, tone = UiTone.Warning) }
            if (display.storageFailure) item("binding-storage") { InfoBanner(AppText.Schedule.STORAGE_FAILURE, tone = UiTone.Warning) }
            item("binding-inputs") {
                SchedulePanel {
                    accountField()
                    passwordField()
                }
            }
            item("binding-verification") {
                when {
                    display.testing -> ScheduleProgress(AppText.Schedule.TESTING)
                    controls.canConfirm -> InfoBanner(AppText.Schedule.VERIFY_PASSED,
                        title = AppText.Schedule.accountLabel(display.verifiedLabel), tone = UiTone.Positive)
                    else -> InfoBanner(display.notice.text(), tone = UiTone.Neutral)
                }
            }
            display.runtimeMessage?.let { message ->
                item("binding-runtime-message") { InfoBanner(message.text(), tone = UiTone.Warning) }
            }
            item("binding-test") {
                Button(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.ButtonRadius,
                    minHeight = AppUiTokens.ButtonHeight, enabled = controls.canVerify,
                    onClick = { dispatch(ScheduleBindingUiEvent.VERIFY) }) { Text(AppText.Schedule.TEST_CONNECTION) }
            }
            item("binding-confirm") {
                PrimaryAction(AppText.Schedule.CONFIRM_BINDING, enabled = controls.canConfirm,
                    onClick = { dispatch(ScheduleBindingUiEvent.CONFIRM) })
            }
            if (display.saving) item("binding-saving") { ScheduleProgress(AppText.Common.SUBMITTING) }
        }
    }
}
