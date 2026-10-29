package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime
import io.github.betterzslf.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun ScheduleTermWeekPage(runtime: ScheduleRuntime, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val weeks = remember(state.snapshot, state.selectedWeek) { scheduleUiWeeks(state.snapshot, state.selectedWeek) }
    Screen(AppText.Schedule.SELECT_TERM_WEEK, onBack) { padding -> PageList(padding) {
        item("selector-help") { InfoBanner(AppText.Schedule.HISTORY_HINT + "\n" + AppText.Schedule.WEEK_ZERO_HINT, tone = UiTone.Neutral) }
        if (state.owner == null) item("selector-owner") { InfoBanner(AppText.Schedule.OWNER_REQUIRED, tone = UiTone.Warning) }
        if (state.busy) item("selector-loading") { ScheduleProgress(AppText.Common.LOADING) }
        state.message?.takeIf(String::isNotBlank)?.let { message -> item("selector-message") { InfoBanner(message, tone = UiTone.Warning) } }
        item("selector-term-title") { SectionTitle(AppText.Schedule.TERMS) }
        itemsIndexed(state.terms, key = { index, term -> "term:$index:${term.id}" }) { _, term ->
            Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues()) {
                ArrowPreference(term.name,
                    summary = listOfNotNull(if (term.isDefault) AppText.Schedule.SERVER_DEFAULT else null,
                        if (term.id == state.snapshot?.term?.id) AppText.Schedule.CURRENT_VIEW else null).joinToString(" · "),
                    enabled = state.owner != null && !state.busy,
                    onClick = { scope.launch { runtime.selectTerm(term.id) } })
            }
        }
        item("selector-weeks-title") { SectionTitle(AppText.Schedule.WEEKS) }
        itemsIndexed(weeks.chunked(4), key = { index, _ -> "weeks:$index" }) { _, group ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap / 2)) {
                group.forEach { week -> Button(modifier = Modifier.weight(1f), enabled = state.owner != null && !state.busy && state.snapshot != null,
                    colors = if (week == state.selectedWeek) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                    onClick = { runtime.selectWeek(week); onBack() }) { Text(AppText.Schedule.weekLabel(week)) } }
                repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    } }
}

@Composable
internal fun ScheduleDetailPage(runtime: ScheduleRuntime, encoded: String, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val enteredScope = remember(encoded) { state.owner to state.boundAccount }
    val selection = remember(encoded) { ScheduleDetailSelection.decode(encoded) }
    val snapshot = scheduleDetailSnapshot(state, selection, encoded)
    val courses = if (enteredScope == (state.owner to state.boundAccount) && state.owner != null && state.boundAccount != null) {
        val resolved = selection ?: snapshot?.let { ScheduleDetailSelection(it.term.id, listOf(encoded), state.selectedWeek) }
        detailCourses(snapshot, resolved)
    } else emptyList()
    Screen(AppText.Schedule.DETAILS_TITLE, onBack) { padding -> PageList(padding) {
        if (courses.isEmpty()) item("detail-unavailable") { InfoBanner(AppText.Schedule.DETAIL_UNAVAILABLE, tone = UiTone.Warning) }
        if (courses.size > 1) item("detail-overlap") { InfoBanner(AppText.Schedule.OVERLAP_HINT, tone = UiTone.Neutral) }
        itemsIndexed(courses, key = { index, course -> "detail:$index:${course.id}" }) { _, course ->
            SchedulePanel {
                Text(course.title.ifBlank { AppText.Common.UNTITLED })
                Text(AppText.Schedule.dayLabel(course.dayOfWeek))
                ScheduleField(AppText.Schedule.TEACHER, course.teacher)
                ScheduleField(AppText.Schedule.LOCATION, course.location)
                ScheduleField(AppText.Schedule.PERIODS, course.periods.distinct().sorted().joinToString(", "))
                Text(coursePeriodTimes(course, state.periods).ifBlank { AppText.Common.NOT_RETURNED })
                ScheduleField(AppText.Schedule.COURSE_WEEKS,
                    course.weekText.ifBlank { course.weeks.distinct().sorted().joinToString(", ") })
                val week = selection?.week
                if (week != null && week in course.weeks && snapshot != null) {
                    scheduleDateForWeek(snapshot, week, course.dayOfWeek)?.let { date -> Text(date.toString()) }
                }
            }
        }
        if (courses.isNotEmpty() && snapshot != null) {
            if (snapshot.remarks.isNotEmpty()) item("detail-remarks-title") { SectionTitle(AppText.Schedule.REMARKS) }
            itemsIndexed(snapshot.remarks, key = { index, _ -> "detail-remark:$index" }) { _, remark -> SchedulePanel { Text(remark) } }
        }
    } }
}

@Composable
internal fun ScheduleManagementPage(runtime: ScheduleRuntime, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmUnbind by remember { mutableStateOf(false) }
    var unbinding by remember { mutableStateOf(false) }
    var notice by remember(state.owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.owner, state.boundAccount) { confirmUnbind = false }
    val actionable = state.owner != null && !state.busy && !unbinding
    Screen(AppText.Schedule.MANAGE, onBack) { padding ->
        PageList(padding) {
            item("management-account") { SchedulePanel {
                Text(AppText.Schedule.accountLabel(state.boundAccount?.let(::maskedAcademicAccount) ?: AppText.Common.NOT_PROVIDED))
                if (state.boundAccount != null) Text(AppText.Schedule.ENCRYPTED_PASSWORD_HINT)
                ScheduleSyncStamp(state.snapshot?.syncedAtMillis ?: 0)
            } }
            if (state.storageFailure) item("management-storage") { InfoBanner(AppText.Schedule.STORAGE_FAILURE, tone = UiTone.Warning) }
            state.message?.takeIf(String::isNotBlank)?.let { message -> item("management-message") { InfoBanner(message, tone = UiTone.Warning) } }
            notice?.let { message -> item("management-local-message") { InfoBanner(message, tone = UiTone.Warning) } }
            item("management-privacy") { InfoBanner(AppText.Schedule.SECURITY_HINT, tone = UiTone.Neutral) }
            item("management-actions") { Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues()) {
                ArrowPreference(if (state.boundAccount == null) AppText.Schedule.BIND else AppText.Schedule.UPDATE_CREDENTIALS,
                    enabled = actionable && !state.storageFailure,
                    onClick = { onOpen(scheduleRoute(ScheduleRoutes.BIND, if (state.boundAccount != null) "password" else "")) })
                ArrowPreference(AppText.Common.REFRESH, enabled = actionable && state.boundAccount != null,
                    onClick = { scope.launch { runtime.refresh() } })
                ArrowPreference(AppText.Schedule.REVERIFY, enabled = actionable && state.boundAccount != null,
                    onClick = { onOpen(scheduleRoute(ScheduleRoutes.BIND, "password")) })
                ArrowPreference(AppText.Schedule.CHANGE_ACCOUNT, enabled = actionable && !state.storageFailure,
                    onClick = { onOpen(scheduleRoute(ScheduleRoutes.BIND)) })
                ArrowPreference(AppText.Schedule.PERIODS_TITLE, enabled = actionable,
                    onClick = { onOpen(scheduleRoute(ScheduleRoutes.PERIODS)) })
                if (runtime.native.supported) {
                    ArrowPreference(AppText.Schedule.REMINDERS_TITLE, enabled = actionable,
                        onClick = { onOpen(scheduleRoute(ScheduleRoutes.REMINDERS)) })
                    ArrowPreference(AppText.Schedule.WIDGET_TITLE, enabled = actionable,
                        onClick = { onOpen(scheduleRoute(ScheduleRoutes.WIDGET)) })
                }
            } }
            if (state.sourceTermId != null) item("management-source-term") {
                Text(AppText.Schedule.reminderSource(state.terms.firstOrNull { it.id == state.sourceTermId }?.name ?: AppText.Common.UNKNOWN))
            }
            item("management-unbind-help") { InfoBanner(AppText.Schedule.UNBIND_CONFIRM_HINT, tone = UiTone.Neutral) }
            item("management-unbind") { TextButton(AppText.Schedule.UNBIND, enabled = actionable && state.boundAccount != null,
                onClick = { confirmUnbind = true }, modifier = Modifier.fillMaxWidth()) }
            if (unbinding) item("management-unbinding") { ScheduleProgress(AppText.Common.SUBMITTING) }
        }
        ConfirmDialog(confirmUnbind, AppText.Schedule.UNBIND_CONFIRM_TITLE, AppText.Schedule.UNBIND_CONFIRM_HINT,
            { confirmUnbind = false }) {
            confirmUnbind = false
            if (!unbinding && state.boundAccount != null) {
                unbinding = true
                scope.launch { try {
                    if (runtime.unbind()) onBack() else notice = AppText.Schedule.UNBIND_FAILED
                } finally { unbinding = false } }
            }
        }
    }
}
