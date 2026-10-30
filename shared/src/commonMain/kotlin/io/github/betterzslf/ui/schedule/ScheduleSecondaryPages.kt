package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime
import io.github.betterzslf.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun ScheduleTermWeekPage(runtime: ScheduleRuntime, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val weeks = remember(state.snapshot, state.selectedWeek) { scheduleUiWeeks(state.snapshot, state.selectedWeek) }
    Screen(AppText.Schedule.SELECT_TERM_WEEK, onBack) { padding -> PageList(padding) {
        if (state.owner == null) item("selector-owner") { InfoBanner(AppText.Schedule.OWNER_REQUIRED, tone = UiTone.Warning) }
        if (state.busy) item("selector-loading") { ScheduleProgress(AppText.Common.LOADING) }
        state.message?.takeIf(String::isNotBlank)?.let { message -> item("selector-message") { InfoBanner(message, tone = UiTone.Warning) } }
        item("selector-term-title") { SectionTitle(AppText.Schedule.TERMS) }
        // Ardot P40: terms are full-width pills in a wrapping row, the selected one filled primary.
        item("selector-terms") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.terms.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { term ->
                            val selected = term.id == state.snapshot?.term?.id
                            Box(Modifier.weight(1f)) {
                                Button(
                                    onClick = { scope.launch { runtime.selectTerm(term.id) } },
                                    enabled = state.owner != null && !state.busy,
                                    modifier = Modifier.fillMaxWidth(),
                                    minHeight = 44.dp,
                                    cornerRadius = 22.dp,
                                    colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    Text(term.name + if (selected) "" else optionalSuffix(term),
                                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item("selector-weeks-title") { SectionTitle(AppText.Schedule.WEEKS) }
        item("selector-weeks") {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                weeks.chunked(4).forEach { group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        group.forEach { week ->
                            val selected = week == state.selectedWeek
                            Box(Modifier.weight(1f)) {
                                Button(
                                    onClick = { runtime.selectWeek(week); onBack() },
                                    enabled = state.owner != null && !state.busy && state.snapshot != null,
                                    modifier = Modifier.fillMaxWidth(),
                                    minHeight = 40.dp,
                                    cornerRadius = 20.dp,
                                    colors = if (selected) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                                    insideMargin = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                                ) { Text(AppText.Schedule.weekLabel(week), fontSize = 13.sp, maxLines = 1) }
                            }
                        }
                        repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item("selector-help") { InfoBanner(AppText.Schedule.HISTORY_HINT + "\n" + AppText.Schedule.WEEK_ZERO_HINT, tone = UiTone.Neutral) }
    } }
}

/** Ardot P40 marks the non-current term with a muted suffix instead of an icon. */
private fun optionalSuffix(term: ScheduleTerm): String =
    if (term.isDefault) AppText.Schedule.SERVER_DEFAULT_SUFFIX else AppText.Schedule.TERM_VIEW_SUFFIX

/**
 * Ardot P41: the header block holds the course title and a primary-tinted status pill,
 * followed by a two-column field list (教师/地点/周次/备注) and, last, the other courses
 * sharing the same slot. Missing values show "—" rather than a fabricated empty string.
 */
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
        val week = selection?.week ?: state.selectedWeek
        // The first course is the one the design draws in full; the rest are the same-slot list.
        courses.firstOrNull()?.let { primary ->
            item("detail-header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(primary.title.ifBlank { AppText.Common.UNTITLED },
                        fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp)
                    val whenLabel = AppText.Schedule.courseWhen(
                        AppText.Schedule.dayLabel(primary.dayOfWeek),
                        AppText.Schedule.periodRange(compactPeriods(primary.periods)),
                        coursePeriodTimes(primary, state.periods),
                    )
                    Text(
                        whenLabel,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            item("detail-fields") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth()) {
                        // weekText already carries the service's own wording (e.g. "1-20(周)"), so it
                        // is shown verbatim; only a bare numeric list needs the "周" suffix added.
                        val weeks = primary.weekText.ifBlank {
                            primary.weeks.distinct().sorted().joinToString(",")
                                .takeIf(String::isNotBlank)?.let(AppText.Schedule::weekRangeShort).orEmpty()
                        }
                        val date = if (snapshot != null) scheduleDateForWeek(snapshot, week, primary.dayOfWeek) else null
                        DetailField(AppText.Schedule.TEACHER_FIELD, primary.teacher)
                        DetailField(AppText.Schedule.LOCATION_FIELD, primary.location)
                        DetailField(AppText.Schedule.WEEKS_FIELD, weeks)
                        DetailField(AppText.Schedule.REMARK_FIELD, date?.toString().orEmpty())
                    }
                }
            }
        }
        val others = courses.drop(1)
        if (others.isNotEmpty()) {
            item("detail-overlap-title") { SectionTitle(AppText.Schedule.sameSlotHint(courses.size)) }
            itemsIndexed(others, key = { index, course -> "detail:$index:${course.id}" }) { _, course ->
                SchedulePanel {
                    Text(course.title.ifBlank { AppText.Common.UNTITLED }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(AppText.Schedule.sameSlotSummary(
                        AppText.Schedule.dayLabel(course.dayOfWeek),
                        course.teacher,
                        course.location,
                        course.weekText.ifBlank {
                            AppText.Schedule.weekRangeShort(course.weeks.distinct().sorted().joinToString(","))
                        },
                    ), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        if (courses.isNotEmpty() && snapshot != null) {
            if (snapshot.remarks.isNotEmpty()) item("detail-remarks-title") { SectionTitle(AppText.Schedule.REMARKS) }
            itemsIndexed(snapshot.remarks, key = { index, _ -> "detail-remark:$index" }) { _, remark -> SchedulePanel { Text(remark) } }
        }
    } }
}

/** One label/value row of the P41 field card: label left in the muted tint, value right. */
@Composable
private fun DetailField(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f))
        Text(value.ifBlank { AppText.Common.NOT_PROVIDED }, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f))
    }
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
            // Ardot P42 puts the account identity first, with the password state as a green
            // reassurance line beneath it rather than a second card.
            item("management-account") { Card(modifier = Modifier.fillMaxWidth(),
                cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(AppText.Schedule.ACCOUNT_FIELD, fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(state.boundAccount?.let(::maskedAcademicAccount) ?: AppText.Common.NOT_PROVIDED,
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (state.boundAccount != null) {
                        Text(AppText.Schedule.PASSWORD_STATE_SAVED, fontSize = 12.sp,
                            color = if (MiuixTheme.colorScheme.surface.luminance() < 0.5f) AppUiTokens.PositiveDark else AppUiTokens.Positive)
                    }
                    ScheduleSyncStamp(state.snapshot?.syncedAtMillis ?: 0)
                }
            } }
            if (state.storageFailure) item("management-storage") { InfoBanner(AppText.Schedule.STORAGE_FAILURE, tone = UiTone.Warning) }
            state.message?.takeIf(String::isNotBlank)?.let { message -> item("management-message") { InfoBanner(message, tone = UiTone.Warning) } }
            notice?.let { message -> item("management-local-message") { InfoBanner(message, tone = UiTone.Warning) } }
            item("management-actions") { Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues()) {
                ArrowPreference(if (state.boundAccount == null) AppText.Schedule.BIND else AppText.Schedule.UPDATE_CREDENTIALS,
                    enabled = actionable && !state.storageFailure,
                    onClick = { onOpen(scheduleRoute(ScheduleRoutes.BIND, if (state.boundAccount != null) "password" else "")) })
                ArrowPreference(AppText.Schedule.SYNC_COURSE_TABLE, summary = scheduleSyncTime(state.snapshot?.syncedAtMillis ?: 0),
                    enabled = actionable && state.boundAccount != null,
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
            // Ardot P42 draws the destructive area as one tinted block: explanation on top,
            // then an explicit full-width confirm button inside the same surface.
            item("management-unbind") {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(AppUiTokens.CardRadius))
                        .background(MiuixTheme.colorScheme.error.copy(alpha = 0.10f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(AppText.Schedule.UNBIND, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.error)
                    Text(AppText.Schedule.UNBIND_CONFIRM_HINT, fontSize = 12.sp, lineHeight = 18.sp,
                        color = MiuixTheme.colorScheme.error.copy(alpha = 0.80f))
                    Button(
                        onClick = { confirmUnbind = true },
                        enabled = actionable && state.boundAccount != null,
                        modifier = Modifier.fillMaxWidth(),
                        minHeight = AppUiTokens.ButtonHeight,
                        cornerRadius = AppUiTokens.ButtonRadius,
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surface,
                            contentColor = MiuixTheme.colorScheme.error),
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) { Text(AppText.Schedule.UNBIND_BUTTON, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                }
            }
            if (unbinding) item("management-unbinding") { ScheduleProgress(AppText.Common.SUBMITTING) }
            item("management-privacy") { InfoBanner(AppText.Schedule.SECURITY_HINT, tone = UiTone.Neutral) }
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
