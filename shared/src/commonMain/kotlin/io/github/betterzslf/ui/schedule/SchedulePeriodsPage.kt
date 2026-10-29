package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.validateSchedulePeriods
import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime
import io.github.betterzslf.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*

private data class PeriodPreviewContext(val owner: String?, val account: String?, val source: ScheduleSnapshot?) {
    override fun toString(): String = "PeriodPreviewContext(redacted)"
}

@Composable
internal fun SchedulePeriodsPage(runtime: ScheduleRuntime, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    var editing by remember(state.owner) { mutableStateOf(false) }
    var editor by remember(state.owner) { mutableStateOf(SchedulePeriodEdit(state.periods)) }
    var saving by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var previewContext by remember { mutableStateOf<PeriodPreviewContext?>(null) }
    var notice by remember(state.owner) { mutableStateOf<String?>(null) }
    val context = PeriodPreviewContext(state.owner, state.boundAccount, state.sourceSnapshot)
    val previewValid = editor.canApply(state.periods) && previewContext == context
    val editable = scheduleCanEditPeriods(state)
    val invalidRows = remember(editor.periods) { schedulePeriodIssueRows(editor.periods) }
    LaunchedEffect(state.periods, context) {
        confirm = false
        if (!editing || state.boundAccount == null) {
            editing = false; editor = SchedulePeriodEdit(state.periods); previewContext = null
        }
    }
    Screen(AppText.Schedule.PERIODS_TITLE, onBack) { padding ->
        PageList(padding) {
            item("periods-source") { InfoBanner(AppText.Schedule.PERIODS_SOURCE, tone = UiTone.Neutral) }
            if (state.owner == null) item("periods-owner") { InfoBanner(AppText.Schedule.OWNER_REQUIRED, tone = UiTone.Warning) }
            else if (state.boundAccount == null) item("periods-bind-first") { InfoBanner(AppText.Schedule.PERIODS_BIND_REQUIRED, tone = UiTone.Neutral) }
            if (state.storageFailure) item("periods-storage") { InfoBanner(AppText.Schedule.STORAGE_FAILURE, tone = UiTone.Warning) }
            notice?.let { message -> item("periods-local-message") { InfoBanner(message, tone = UiTone.Warning) } }
            state.message?.takeIf(String::isNotBlank)?.let { message -> item("periods-runtime-message") { InfoBanner(message, tone = UiTone.Warning) } }
            itemsIndexed(if (editing) editor.periods else state.periods, key = { index, _ -> "period:$index" }) { index, period ->
                SchedulePanel {
                    Text(AppText.Schedule.periodLabel(period.number))
                    if (!editing) Text("${period.start}–${period.end}") else {
                        // Stacked fields remain reachable on 320dp / large fonts / an open IME.
                        TextField(period.start, { value ->
                            editor = editor.change(index, start = value); previewContext = null; confirm = false; notice = null
                        }, label = AppText.Schedule.START_FIELD, singleLine = true, enabled = !saving && state.owner != null && state.boundAccount != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.FieldRadius)
                        TextField(period.end, { value ->
                            editor = editor.change(index, end = value); previewContext = null; confirm = false; notice = null
                        }, label = AppText.Schedule.END_FIELD, singleLine = true, enabled = !saving && state.owner != null && state.boundAccount != null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.FieldRadius)
                        if (index in invalidRows) InfoBanner(AppText.Schedule.PERIODS_INVALID, tone = UiTone.Warning)
                    }
                }
            }
            if (!editing) item("periods-edit") { PrimaryAction(AppText.Schedule.EDIT_PERIODS,
                enabled = editable, onClick = {
                    editor = SchedulePeriodEdit(state.periods); editing = true; previewContext = null; notice = null
                }) }
            else {
                if (!validateSchedulePeriods(editor.periods)) item("periods-invalid") { InfoBanner(AppText.Schedule.PERIODS_INVALID, tone = UiTone.Warning) }
                item("periods-preview") { PrimaryAction(AppText.Schedule.PREVIEW_PERIODS,
                    enabled = validateSchedulePeriods(editor.periods) && !saving && editable,
                    onClick = { editor = editor.preview(state.periods); previewContext = context; confirm = false; notice = null }) }
                if (editor.previewRevision != null) {
                    item("periods-impact") { SchedulePanel {
                        val source = state.sourceSnapshot
                        if (source != null && source.complete) Text(AppText.Schedule.affectedCourses(source.courses.size))
                        else InfoBanner(if (source == null) AppText.Schedule.NO_CACHE else AppText.Schedule.PARTIAL_HINT, tone = UiTone.Neutral)
                        Text(AppText.Schedule.CONFIRM_PERIODS_HINT)
                        editor.periods.forEachIndexed { index, period ->
                            val before = editor.baseline?.getOrNull(index)
                            if (before != period) Text(AppText.Schedule.periodLabel(period.number) + " · " +
                                "${before?.start.orEmpty()}–${before?.end.orEmpty()} → ${period.start}–${period.end}")
                        }
                        if (!previewValid) InfoBanner(AppText.Schedule.PERIODS_CHANGED, tone = UiTone.Warning)
                    } }
                    item("periods-apply") { PrimaryAction(AppText.Schedule.SAVE_PERIODS,
                        enabled = previewValid && !saving && editable,
                        onClick = { confirm = true }) }
                }
                item("periods-cancel") { TextButton(AppText.Common.CANCEL, enabled = !saving,
                    modifier = Modifier.fillMaxWidth(), onClick = {
                        editing = false; editor = SchedulePeriodEdit(state.periods); previewContext = null; confirm = false; notice = null
                    }) }
            }
            if (saving) item("periods-saving") { ScheduleProgress(AppText.Common.SUBMITTING) }
        }
        ConfirmDialog(confirm && previewValid, AppText.Schedule.CONFIRM_PERIODS_TITLE, AppText.Schedule.CONFIRM_PERIODS_HINT,
            { confirm = false }) {
            confirm = false
            val latest = runtime.state.value
            if (!saving && scheduleCanEditPeriods(latest) && editor.canApply(latest.periods) && previewContext == PeriodPreviewContext(latest.owner, latest.boundAccount, latest.sourceSnapshot)) {
                val candidate = editor.periods.toList()
                saving = true
                scope.launch { try {
                    if (runtime.updatePeriods(candidate)) {
                        editing = false; editor = SchedulePeriodEdit(runtime.state.value.periods); previewContext = null; notice = null
                    } else notice = AppText.Schedule.PERIODS_SAVE_FAILED
                } finally { saving = false } }
            }
        }
    }
}
