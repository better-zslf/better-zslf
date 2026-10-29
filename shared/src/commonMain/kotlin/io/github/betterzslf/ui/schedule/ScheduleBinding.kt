package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime
import io.github.betterzslf.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** App owns prompt frequency. Merely displaying or skipping this prompt does not authenticate. */
@Composable
fun ScheduleBindingPrompt(graph: AppGraph, onBind: () -> Unit) {
    val runtime = graph.schedule
    val state by runtime.state.collectAsState()
    OverlayDialog(show = state.showBindingPrompt && state.owner != null && state.boundAccount == null,
        title = AppText.Schedule.INTRO_TITLE, summary = AppText.Schedule.INTRO_BODY,
        onDismissRequest = runtime::dismissBindingPrompt) {
        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
            InfoBanner(AppText.Schedule.SECURITY_HINT, tone = UiTone.Neutral)
            PrimaryAction(AppText.Schedule.BIND, onClick = {
                runtime.dismissBindingPrompt()
                onBind()
            })
            TextButton(AppText.Schedule.SKIP, onClick = runtime::dismissBindingPrompt, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun ScheduleBindingPage(runtime: ScheduleRuntime, updatingPassword: Boolean, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    var draft by remember(runtime, state.owner) {
        mutableStateOf(ScheduleBindingDraft(account = if (updatingPassword) state.boundAccount.orEmpty() else ""))
    }
    var showPassword by remember(runtime, state.owner) { mutableStateOf(false) }
    var saving by remember(runtime, state.owner) { mutableStateOf(false) }
    var testing by remember(runtime, state.owner) { mutableStateOf(false) }
    var notice by remember(runtime, state.owner) { mutableStateOf<String?>(null) }
    DisposableEffect(runtime, state.owner) { onDispose { runtime.cancelVerification() } }
    val back = { runtime.cancelVerification(); onBack() }
    val canConfirm = draft.canConfirm(state.verifiedAccount, state.busy || state.verifying || testing || saving) && !state.storageFailure
    Screen(if (updatingPassword) AppText.Schedule.UPDATE_CREDENTIALS else AppText.Schedule.BIND_TITLE, back) { padding ->
        PageList(padding) {
            item("binding-explanation") { InfoBanner(AppText.Schedule.READ_ONLY_HINT, tone = UiTone.Neutral) }
            item("binding-security") { InfoBanner(AppText.Schedule.SECURITY_HINT, tone = UiTone.Neutral) }
            if (state.owner == null) item("binding-owner") { InfoBanner(AppText.Schedule.OWNER_REQUIRED, tone = UiTone.Warning) }
            if (state.storageFailure) item("binding-storage") { InfoBanner(AppText.Schedule.STORAGE_FAILURE, tone = UiTone.Warning) }
            item("binding-inputs") {
                SchedulePanel {
                    TextField(draft.account, { value ->
                        runtime.cancelVerification()
                        draft = draft.accountChanged(value)
                        notice = AppText.Schedule.INPUT_CHANGED
                    }, label = AppText.Schedule.ACCOUNT_FIELD, singleLine = true, enabled = !saving,
                        modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.FieldRadius)
                    TextField(draft.password, { value ->
                        runtime.cancelVerification()
                        draft = draft.passwordChanged(value)
                        notice = AppText.Schedule.INPUT_CHANGED
                    }, label = AppText.Schedule.PASSWORD_FIELD, singleLine = true, enabled = !saving,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.FieldRadius)
                    TextButton(if (showPassword) AppText.Schedule.HIDE_PASSWORD else AppText.Schedule.SHOW_PASSWORD,
                        enabled = !saving, onClick = { showPassword = !showPassword })
                }
            }
            item("binding-verification") {
                when {
                    testing || state.verifying -> ScheduleProgress(AppText.Schedule.TESTING)
                    canConfirm -> InfoBanner(AppText.Schedule.VERIFY_PASSED,
                        title = AppText.Schedule.accountLabel(maskedAcademicAccount(draft.account.trim())), tone = UiTone.Positive)
                    else -> InfoBanner(notice ?: AppText.Schedule.NOT_VERIFIED, tone = UiTone.Neutral)
                }
            }
            state.message?.takeIf(String::isNotBlank)?.let { message ->
                item("binding-runtime-message") { InfoBanner(message, tone = UiTone.Warning) }
            }
            item("binding-test") {
                Button(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.ButtonRadius,
                    minHeight = AppUiTokens.ButtonHeight,
                    enabled = state.owner != null && draft.account.isNotBlank() && draft.password.isNotEmpty() &&
                        !state.busy && !state.verifying && !testing && !saving && !state.storageFailure,
                    onClick = {
                        if (!testing && !saving) {
                            testing = true
                            val captured = draft
                            draft = draft.invalidate()
                            scope.launch {
                                try {
                                    val passed = runtime.verifyBinding(captured.account.trim(), captured.password)
                                    if (draft.revision == captured.revision) {
                                        draft = if (passed) draft.acceptVerification(captured.revision, runtime.state.value.verifiedAccount) else draft.invalidate()
                                        notice = if (passed && draft.verifiedRevision == draft.revision) null else AppText.Schedule.VERIFY_FAILED
                                    }
                                } finally { testing = false }
                            }
                        }
                    }) { Text(AppText.Schedule.TEST_CONNECTION) }
            }
            item("binding-confirm") {
                PrimaryAction(AppText.Schedule.CONFIRM_BINDING, enabled = canConfirm, onClick = {
                    if (!saving && draft.canConfirm(runtime.state.value.verifiedAccount, runtime.state.value.busy || runtime.state.value.verifying)) {
                        saving = true
                        scope.launch {
                            try {
                                if (runtime.confirmBinding()) {
                                    draft = ScheduleBindingDraft()
                                    onBack()
                                } else {
                                    notice = AppText.Common.LOCAL_DATA_SAVE_FAILED
                                }
                            } finally { saving = false }
                        }
                    }
                })
            }
            if (saving) item("binding-saving") { ScheduleProgress(AppText.Common.SUBMITTING) }
        }
    }
}
