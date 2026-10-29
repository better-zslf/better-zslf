package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference

internal object ScheduleRoutes {
    const val ROOT = "schedule"
    const val BIND = "schedulebind"
    const val TERMS = "scheduleterms"
    const val DETAIL = "scheduledetail"
    const val MANAGE = "schedulemanage"
    const val REMINDERS = "schedulereminders"
    const val WIDGET = "schedulewidget"
    const val PERIODS = "scheduleperiods"
}

internal fun scheduleRoute(kind: String, id: String = ""): Route.Page = Route.Page(kind, id, when (kind) {
    ScheduleRoutes.BIND -> AppText.Schedule.BIND_TITLE
    ScheduleRoutes.TERMS -> AppText.Schedule.SELECT_TERM_WEEK
    ScheduleRoutes.DETAIL -> AppText.Schedule.DETAILS_TITLE
    ScheduleRoutes.MANAGE -> AppText.Schedule.MANAGE
    ScheduleRoutes.REMINDERS -> AppText.Schedule.REMINDERS_TITLE
    ScheduleRoutes.WIDGET -> AppText.Schedule.WIDGET_TITLE
    ScheduleRoutes.PERIODS -> AppText.Schedule.PERIODS_TITLE
    else -> AppText.Schedule.TITLE
})

@Composable
fun SchedulePage(graph: AppGraph, page: Route.Page, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val runtime = graph.schedule
    when (page.kind) {
        ScheduleRoutes.ROOT -> ScheduleRootPage(graph, onOpen)
        ScheduleRoutes.BIND -> ScheduleBindingPage(runtime, page.id == "password", onBack)
        ScheduleRoutes.TERMS -> ScheduleTermWeekPage(runtime, onBack)
        ScheduleRoutes.DETAIL -> ScheduleDetailPage(runtime, page.id, onBack)
        ScheduleRoutes.MANAGE -> ScheduleManagementPage(runtime, onOpen, onBack)
        ScheduleRoutes.REMINDERS -> ScheduleReminderPage(runtime, onOpen, onBack)
        ScheduleRoutes.WIDGET -> ScheduleWidgetPage(runtime, onBack)
        ScheduleRoutes.PERIODS -> SchedulePeriodsPage(runtime, onBack)
        else -> Screen(AppText.Common.UNKNOWN_PAGE_TYPE, onBack) { padding -> PageList(padding) {
            item { InfoBanner(AppText.Common.UNKNOWN_PAGE_TYPE, tone = UiTone.Warning) }
        } }
    }
}

/** The shell owns the selected third tab and all navigation-bar/rail drawing. */
@Composable
fun ScheduleRootPage(graph: AppGraph, onOpen: (Route) -> Unit) {
    val runtime = graph.schedule
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val now = scheduleDisplayClock()
    val snapshot = state.snapshot
    val body = scheduleBody(state)
    val fontScale = LocalDensity.current.fontScale
    var dayOverride by remember(state.owner, snapshot?.term?.id) { mutableStateOf<Boolean?>(null) }
    val mappedToday = remember(snapshot, state.selectedWeek, scheduleSchoolDate(now)) {
        snapshot?.let { value -> (1..7).firstOrNull { scheduleDateForWeek(value, state.selectedWeek, it) == scheduleSchoolDate(now) } }
    }
    var selectedDay by remember(state.owner, snapshot?.term?.id, state.selectedWeek) { mutableIntStateOf(mappedToday ?: 1) }
    val openBinding = { onOpen(scheduleRoute(ScheduleRoutes.BIND, "password")) }
    Screen(AppText.Schedule.TITLE, actions = {
        TextButton(AppText.Schedule.MANAGE, enabled = state.owner != null,
            onClick = { onOpen(scheduleRoute(ScheduleRoutes.MANAGE)) })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val dayList = dayOverride ?: preferScheduleDayList(maxWidth.value, fontScale)
            PageList(padding) {
                if (state.owner != null) scheduleStatusItems(state, openBinding)
                when (body) {
                    ScheduleBody.SIGNED_OUT -> item("schedule-owner") { InfoBanner(AppText.Schedule.OWNER_REQUIRED, tone = UiTone.Neutral) }
                    ScheduleBody.STORAGE_UNAVAILABLE -> item("schedule-storage-retry") {
                        PrimaryAction(AppText.Common.RETRY, enabled = !state.busy, onClick = { scope.launch { runtime.onForeground() } })
                    }
                    ScheduleBody.UNBOUND -> {
                        item("schedule-intro") { SchedulePanel {
                            Text(AppText.Schedule.INTRO_TITLE)
                            Text(AppText.Schedule.INTRO_BODY)
                            Text(AppText.Schedule.READ_ONLY_HINT)
                        } }
                        item("schedule-intro-security") { InfoBanner(AppText.Schedule.SECURITY_HINT, tone = UiTone.Neutral) }
                        item("schedule-bind-entry") { PrimaryAction(AppText.Schedule.BIND, onClick = { onOpen(scheduleRoute(ScheduleRoutes.BIND)) }) }
                    }
                    ScheduleBody.FIRST_SYNC -> if (!state.reauthenticating) item("schedule-first-sync") { ScheduleProgress(AppText.Schedule.FIRST_SYNC) }
                    ScheduleBody.NO_CACHE -> {
                        item("schedule-no-cache") { InfoBanner(AppText.Schedule.NO_CACHE, tone = UiTone.Neutral) }
                        item("schedule-no-cache-refresh") { PrimaryAction(AppText.Common.REFRESH, enabled = !state.busy,
                            onClick = { scope.launch { runtime.refresh() } }) }
                    }
                    ScheduleBody.SCHEDULE -> if (snapshot != null) {
                        item("schedule-week-control") { Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues()) {
                            ArrowPreference(AppText.Schedule.SELECT_TERM_WEEK,
                                summary = snapshot.term.name + " · " + AppText.Schedule.weekLabel(state.selectedWeek),
                                enabled = !state.busy, onClick = { onOpen(scheduleRoute(ScheduleRoutes.TERMS)) })
                        } }
                        item("schedule-sync-control") { SchedulePanel {
                            ScheduleSyncStamp(snapshot.syncedAtMillis)
                            val monday = scheduleDateForWeek(snapshot, state.selectedWeek, 1)
                            val sunday = scheduleDateForWeek(snapshot, state.selectedWeek, 7)
                            if (monday != null && sunday != null) Text("$monday – $sunday")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                                TextButton(AppText.Common.REFRESH, enabled = !state.busy,
                                    onClick = { scope.launch { runtime.refresh() } })
                                val actualWeek = scheduleWeekForDate(snapshot, scheduleSchoolDate(now))
                                TextButton(AppText.Schedule.BACK_THIS_WEEK, enabled = actualWeek != null && !state.busy,
                                    onClick = { actualWeek?.let(runtime::selectWeek) })
                            }
                        } }
                        if (scheduleDateForWeek(snapshot, state.selectedWeek, 1) == null) {
                            item("schedule-calendar-missing") { InfoBanner(AppText.Schedule.CALENDAR_MISSING, tone = UiTone.Warning) }
                        }
                        item("schedule-mode") { TabRow(listOf(AppText.Schedule.GRID_VIEW, AppText.Schedule.DAY_VIEW),
                            selectedTabIndex = if (dayList) 1 else 0, onTabSelected = { dayOverride = it == 1 },
                            height = 48.dp * maxOf(1f, fontScale),
                            minWidth = 96.dp * maxOf(1f, fontScale), maxWidth = 180.dp * maxOf(1f, fontScale)) }
                        when (scheduleEmpty(snapshot, state.selectedWeek)) {
                            ScheduleEmpty.TERM -> item("schedule-term-empty") { InfoBanner(AppText.Schedule.TERM_EMPTY, tone = UiTone.Neutral) }
                            ScheduleEmpty.WEEK -> item("schedule-week-empty") { InfoBanner(AppText.Schedule.WEEK_EMPTY, tone = UiTone.Neutral) }
                            ScheduleEmpty.NONE -> Unit
                        }
                        if (dayList) {
                            item("schedule-day-selector") { TabRow((1..7).map(AppText.Schedule::dayLabel), selectedTabIndex = selectedDay - 1,
                                onTabSelected = { selectedDay = (it + 1).coerceIn(1, 7) },
                                height = 48.dp * maxOf(1f, fontScale), minWidth = 64.dp * maxOf(1f, fontScale),
                                maxWidth = 180.dp * maxOf(1f, fontScale)) }
                            val courses = scheduleDayCourses(snapshot, state.selectedWeek, selectedDay)
                            if (scheduleCanConcludeDayEmpty(snapshot, state.selectedWeek, selectedDay)) item("schedule-day-empty") { InfoBanner(AppText.Schedule.DAY_EMPTY, tone = UiTone.Neutral) }
                            itemsIndexed(courses, key = { index, course -> "day:$index:${course.id}" }) { _, course ->
                                ScheduleCourseCard(course, state.periods, onOpen = {
                                    onOpen(scheduleRoute(ScheduleRoutes.DETAIL, ScheduleDetailSelection(snapshot.term.id, listOf(course.id), state.selectedWeek).encode()))
                                })
                            }
                        } else item("schedule-board") { ScheduleWeekBoard(snapshot, state.selectedWeek, state.periods, now) { courses ->
                            onOpen(scheduleRoute(ScheduleRoutes.DETAIL, ScheduleDetailSelection(snapshot.term.id, courses.map { it.id }, state.selectedWeek).encode()))
                        } }
                        val uncertain = scheduleUnplacedCourses(snapshot)
                        if (uncertain.isNotEmpty()) item("schedule-uncertain-title") { SectionTitle(AppText.Schedule.UNPLACED_COURSES) }
                        itemsIndexed(uncertain, key = { index, course -> "uncertain:$index:${course.id}" }) { _, course ->
                            ScheduleCourseCard(course, state.periods, onOpen = {
                                onOpen(scheduleRoute(ScheduleRoutes.DETAIL, ScheduleDetailSelection(snapshot.term.id, listOf(course.id), state.selectedWeek).encode()))
                            })
                        }
                        if (snapshot.remarks.isNotEmpty()) item("schedule-remarks-title") { SectionTitle(AppText.Schedule.REMARKS) }
                        itemsIndexed(snapshot.remarks, key = { index, _ -> "remark:$index" }) { _, remark -> SchedulePanel { Text(remark) } }
                    }
                }
            }
        }
    }
}
