package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
        ScheduleRoutes.MANAGE -> ScheduleManagementPage(runtime, graph.settings, onOpen, onBack)
        ScheduleRoutes.REMINDERS -> ScheduleReminderPage(runtime, onOpen, onBack)
        ScheduleRoutes.WIDGET -> ScheduleWidgetPage(runtime, onBack)
        ScheduleRoutes.PERIODS -> SchedulePeriodsPage(runtime, onBack)
        else -> Screen(AppText.Common.UNKNOWN_PAGE_TYPE, onBack) { padding -> PageList(padding) {
            item { InfoBanner(AppText.Common.UNKNOWN_PAGE_TYPE, tone = UiTone.Warning) }
        } }
    }
}

/**
 * The shell owns the selected third tab and all navigation-bar/rail drawing.
 *
 * Laid out as Ardot P39: a 34sp page title, one row holding the week pill and the date range,
 * then the board card. The design has no week/day view switcher on this screen, so the grid is
 * the only body mode; the day list appears automatically on narrow screens or large font
 * scales, where 46dp columns cannot hold text.
 *
 * The design also draws no toolbar button, but a timetable without a visible entry to its own
 * settings is not usable -- binding, credential update, period calibration, reminders and the
 * week-visibility setting all live behind it. So the top bar carries one settings button for
 * every body state, including the unbound one, where it is the only way to reach binding.
 */
@Composable
fun ScheduleRootPage(graph: AppGraph, onOpen: (Route) -> Unit) {
    val runtime = graph.schedule
    val state by runtime.state.collectAsState()
    val scope = rememberCoroutineScope()
    val now = scheduleDisplayClock()
    val snapshot = state.snapshot
    val body = scheduleBody(state)
    val settings by graph.settings.settings.collectAsState()
    val fontScale = LocalDensity.current.fontScale
    val openBinding = { onOpen(scheduleRoute(ScheduleRoutes.BIND, "password")) }
    val openTermWeek = { onOpen(scheduleRoute(ScheduleRoutes.TERMS)) }
    val openManagement = { onOpen(scheduleRoute(ScheduleRoutes.MANAGE)) }

    Screen(AppText.Schedule.TITLE, actions = {
        IconButton(onClick = openManagement) {
            Icon(MiuixIcons.Tune, contentDescription = AppText.Schedule.MANAGE,
                tint = MiuixTheme.colorScheme.onSurface)
        }
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val dayList = preferScheduleDayList(maxWidth.value, fontScale)
            val hiddenDays = settings.hiddenWeekdays.toSet()
            if (snapshot != null && body == ScheduleBody.SCHEDULE) {
                ScheduleWeekScreenContent(
                    snapshot = snapshot, state = state, dayList = dayList, now = now,
                    hiddenDays = hiddenDays,
                    padding = padding, openTermWeek = openTermWeek,
                    openDetail = onOpen, refresh = { scope.launch { runtime.refresh() } },
                )
            } else {
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
                        ScheduleBody.SCHEDULE -> Unit
                    }
                }
            }
        }
    }
}

/** Ardot P39 body: header, board card, then the out-of-grid lists that must never be dropped. */
@Composable
private fun ScheduleWeekScreenContent(
    snapshot: ScheduleSnapshot,
    state: io.github.betterzslf.feature.schedule.runtime.ScheduleState,
    dayList: Boolean,
    now: Long,
    hiddenDays: Set<Int>,
    padding: PaddingValues,
    openTermWeek: () -> Unit,
    openDetail: (Route) -> Unit,
    refresh: () -> Unit,
) {
    val mappedToday = remember(snapshot, state.selectedWeek, now) {
        (1..7).firstOrNull { scheduleDateForWeek(snapshot, state.selectedWeek, it) == scheduleSchoolDate(now) }
    }
    var selectedDay by remember(state.owner, snapshot.term.id, state.selectedWeek) { mutableIntStateOf(mappedToday ?: 1) }
    val monday = scheduleDateForWeek(snapshot, state.selectedWeek, 1)
    val sunday = scheduleDateForWeek(snapshot, state.selectedWeek, 7)

    PageList(padding) {
        item("schedule-header") {
            ScheduleHeader(
                week = state.selectedWeek,
                monday = monday, sunday = sunday, now = now, today = mappedToday,
                onWeekClick = openTermWeek,
            )
        }
        if (state.busy || state.reauthenticating || state.message != null || state.needsCredentials ||
            !snapshot.complete || snapshot.warnings.isNotEmpty() || state.storageFailure) {
            scheduleStatusItems(state, { openTermWeek() })
        }
        item("schedule-board") {
            ScheduleBoardCardContainer {
                if (dayList) {
                    ScheduleDayBody(snapshot, state.selectedWeek, state.periods, now, selectedDay, hiddenDays,
                        onSelectDay = { selectedDay = it }, openDetail = openDetail)
                } else {
                    ScheduleWeekBoard(snapshot, state.selectedWeek, state.periods, now, hiddenDays) { courses ->
                        openDetail(scheduleRoute(ScheduleRoutes.DETAIL,
                            ScheduleDetailSelection(snapshot.term.id, courses.map { it.id }, state.selectedWeek).encode()))
                    }
                }
            }
        }
        when (scheduleEmpty(snapshot, state.selectedWeek)) {
            ScheduleEmpty.TERM -> item("schedule-term-empty") { InfoBanner(AppText.Schedule.TERM_EMPTY, tone = UiTone.Neutral) }
            ScheduleEmpty.WEEK -> item("schedule-week-empty") { InfoBanner(AppText.Schedule.WEEK_EMPTY, tone = UiTone.Neutral) }
            ScheduleEmpty.NONE -> Unit
        }
        if (scheduleDateForWeek(snapshot, state.selectedWeek, 1) == null) {
            item("schedule-calendar-missing") { InfoBanner(AppText.Schedule.CALENDAR_MISSING, tone = UiTone.Warning) }
        }
        val uncertain = scheduleUnplacedCourses(snapshot)
        if (uncertain.isNotEmpty()) item("schedule-uncertain-title") { SectionTitle(AppText.Schedule.UNPLACED_COURSES) }
        itemsIndexed(uncertain, key = { index, course -> "uncertain:$index:${course.id}" }) { _, course ->
            ScheduleCourseCard(course, state.periods, onOpen = {
                openDetail(scheduleRoute(ScheduleRoutes.DETAIL, ScheduleDetailSelection(snapshot.term.id, listOf(course.id), state.selectedWeek).encode()))
            })
        }
        if (snapshot.remarks.isNotEmpty()) item("schedule-remarks-title") { SectionTitle(AppText.Schedule.REMARKS) }
        itemsIndexed(snapshot.remarks, key = { index, _ -> "remark:$index" }) { _, remark -> SchedulePanel { Text(remark) } }
        // The board itself is the primary body, so refresh is a quiet full-width row rather
        // than a toolbar action the design does not draw. Trailing spacer keeps it clear of
        // the floating tab pill, which overlays the scroll viewport.
        item("schedule-footer-actions") {
            TextButton(AppText.Common.REFRESH, enabled = !state.busy, onClick = refresh, modifier = Modifier.fillMaxWidth())
        }
        item("schedule-footer-inset") { Spacer(Modifier.height(AppUiTokens.SectionGap)) }
    }
}

/**
 * The white rounded card the design wraps the whole grid in (Ardot 14:47).
 *
 * `surfaceVariant` is white in both schemes and `surface` is the gray page in light mode, so
 * neither is the right card colour. The card tone is the design's `card` token, which the
 * scheme exposes as `secondaryContainer` (#FFFFFF light / #1A1C21 dark).
 */
@Composable
private fun ScheduleBoardCardContainer(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(10.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.secondaryContainer),
    ) { content() }
}

/**
 * Ardot P39 header: 34sp title is supplied by the host bar, so this row draws only the
 * week pill (primary, radius 18, height 36) and the right-aligned range/today label.
 */
@Composable
private fun ScheduleHeader(
    week: Int,
    monday: kotlinx.datetime.LocalDate?,
    sunday: kotlinx.datetime.LocalDate?,
    now: Long,
    today: Int?,
    onWeekClick: () -> Unit,
) {
    val todayLabel = remember(now, today) {
        if (today == null) "" else {
            val date = scheduleSchoolDate(now)
            AppText.Schedule.todayLong(
                AppText.Schedule.compactTodayStamp(
                    AppText.Schedule.monthDayCompact(date.month.ordinal + 1, date.day),
                    AppText.Schedule.dayLabel(today),
                )
            )
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(18.dp))
                .background(MiuixTheme.colorScheme.primary)
                .semantics(mergeDescendants = true) { contentDescription = AppText.Schedule.weekLabel(week) }
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(AppText.Schedule.weekLabel(week), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onPrimary, modifier = Modifier.clickableNoRipple(onWeekClick))
            Text("▾", fontSize = 11.sp, color = MiuixTheme.colorScheme.onPrimary,
                modifier = Modifier.clickableNoRipple(onWeekClick))
        }
        Text(
            when {
                monday != null && sunday != null && todayLabel.isNotBlank() ->
                    AppText.Schedule.weekRange(
                        AppText.Schedule.monthDay(monday.month.ordinal + 1, monday.day),
                        AppText.Schedule.monthDay(sunday.month.ordinal + 1, sunday.day),
                        todayLabel,
                    )
                monday != null && sunday != null ->
                    AppText.Schedule.weekRange(
                        AppText.Schedule.monthDay(monday.month.ordinal + 1, monday.day),
                        AppText.Schedule.monthDay(sunday.month.ordinal + 1, sunday.day),
                        AppText.Schedule.WEEK_TODAY,
                    )
                else -> AppText.Schedule.weekLabel(week)
            },
            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

/** Narrow/large-font fallback: the design's P39b day list, one card per course that day. */
@Composable
private fun ScheduleDayBody(
    snapshot: ScheduleSnapshot,
    week: Int,
    periods: List<SchedulePeriod>,
    now: Long,
    selectedDay: Int,
    hiddenDays: Set<Int>,
    onSelectDay: (Int) -> Unit,
    openDetail: (Route) -> Unit,
) {
    val visibleDays = remember(hiddenDays) { scheduleVisibleDays(hiddenDays) }
    // A hidden day can still be the remembered selection; fall back to the first visible day
    // so the list never shows an empty screen for a day the user asked not to see.
    val activeDay = if (selectedDay in visibleDays) selectedDay else visibleDays.first()
    val today = remember(snapshot, week, now) {
        (1..7).firstOrNull { scheduleDateForWeek(snapshot, week, it) == scheduleSchoolDate(now) }
    }
    val nowMinutes = remember(now) { scheduleSchoolMinutes(now) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The design still needs a way to reach other days, so the strip stays; it is drawn
        // with the same primary pill as the week selector rather than a second Miuix tab row.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (day in visibleDays) {
                val selected = day == activeDay
                Box(
                    Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant)
                        .semantics { contentDescription = AppText.Schedule.dayLabel(day) }
                        .clickableNoRipple { onSelectDay(day) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(AppText.Schedule.shortDayLabel(day), fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        val courses = scheduleDayCourses(snapshot, week, activeDay)
        if (courses.isEmpty() && scheduleCanConcludeDayEmpty(snapshot, week, activeDay)) {
            Text(AppText.Schedule.DAY_EMPTY, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        courses.forEach { course ->
            ScheduleDayCard(
                course = course,
                periods = periods,
                running = scheduleCourseRunning(course, activeDay, periods, nowMinutes) && activeDay == today,
                onOpen = {
                    openDetail(scheduleRoute(ScheduleRoutes.DETAIL,
                        ScheduleDetailSelection(snapshot.term.id, listOf(course.id), week).encode()))
                },
            )
        }
    }
}

/**
 * Ardot P39b day card: pastel fill, "课程名 · 1-2节" in the subject tint, then the time range
 * and room in the muted tint. This is the same information hierarchy as a board block, sized
 * for a full-width row instead of a 46dp column.
 */
@Composable
private fun ScheduleDayCard(
    course: ScheduleCourse,
    periods: List<SchedulePeriod>,
    running: Boolean,
    onOpen: () -> Unit,
) {
    val palette = scheduleCoursePalette(course, running)
    val periodText = if (course.periods.isEmpty()) AppText.Common.NOT_PROVIDED
        else AppText.Schedule.periodsCompact(compactPeriods(course.periods))
    val description = listOf(
        course.title.ifBlank { AppText.Common.UNTITLED }, periodText,
        coursePeriodTimes(course, periods), course.location,
        if (running) AppText.Schedule.IN_PROGRESS else "",
    ).filter { it.isNotBlank() }.joinToString(", ")
    Card(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        cornerRadius = 14.dp,
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        showIndication = true,
        onClick = onOpen,
        colors = CardDefaults.defaultColors(color = palette.background),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOf(course.title.ifBlank { AppText.Common.UNTITLED }, periodText).joinToString(" · "),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = palette.title,
            )
            val meta = listOf(coursePeriodTimes(course, periods), course.location,
                if (running) AppText.Schedule.IN_PROGRESS else "").filter { it.isNotBlank() }
            if (meta.isNotEmpty()) {
                Text(meta.joinToString(" · "), fontSize = 13.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, color = palette.meta)
            }
        }
    }
}
