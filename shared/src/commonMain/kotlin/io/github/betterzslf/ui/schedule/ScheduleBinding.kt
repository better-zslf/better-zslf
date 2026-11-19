package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.betterzslf.AppGraph
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime
import io.github.betterzslf.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*

/** App owns prompt frequency. Merely displaying or skipping this prompt does not authenticate. */
@Composable
fun ScheduleBindingPrompt(graph: AppGraph, onBind: () -> Unit) {
    val runtime = graph.schedule
    val state by runtime.state.collectAsState()
    val visible = state.showBindingPrompt && state.owner != null && state.boundAccount == null
    val prompt = remember(runtime, visible) { ScheduleBindingPromptSession.open(runtime, visible) }
    DisposableEffect(prompt) { onDispose { prompt.close() } }
    ScheduleBindingPromptContent(visible, onEvent = { event -> prompt.dispatch(event, onBind) })
}

/**
 * The academic credential form. Reached from the timetable itself and, since the timetable no
 * longer depends on a campus login, also directly from the login screen's 教务系统 entry.
 */
@Composable
fun ScheduleBindingPage(
    runtime: ScheduleRuntime,
    updatingPassword: Boolean,
    onBack: () -> Unit,
    onBound: (() -> Unit)? = null,
) {
    // Observe runtime state for recomposition, but never export it or use a restored revision as authority.
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val page = remember(runtime, updatingPassword) { ScheduleBindingSession.open(runtime, updatingPassword) }
    DisposableEffect(page) { onDispose { page.close() } }
    val generation = page.inputGeneration
    val display = page.display(state)
    val controls = page.controls(state)
    ScheduleBindingPageContent(display, controls,
        onEvent = { event -> when (event) {
            ScheduleBindingUiEvent.BACK -> page.back(generation, onBack)
            ScheduleBindingUiEvent.VERIFY -> page.beginVerification(generation)?.let { request ->
                scope.launch { page.verify(request) }
            }
            ScheduleBindingUiEvent.CONFIRM -> page.beginConfirmation(generation)?.let { request ->
                scope.launch { page.confirm(request) { (onBound ?: onBack)() } }
            }
            else -> Unit
        } },
        accountField = {
            TextField(page.draft.account, { value -> page.accountChanged(generation, value) },
                label = AppText.Schedule.ACCOUNT_FIELD, singleLine = true, enabled = page.inputsEnabled,
                modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.FieldRadius)
        },
        passwordField = {
            TextField(page.draft.password, { value -> page.passwordChanged(generation, value) },
                label = AppText.Schedule.PASSWORD_FIELD, singleLine = true, enabled = page.inputsEnabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (page.showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                // S01: reveal control in the field's own trailing slot, not a separate row.
                trailingIcon = { PasswordVisibilityIcon(page.showPassword, { page.togglePassword(generation) }) },
                modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.FieldRadius)
        })
}
