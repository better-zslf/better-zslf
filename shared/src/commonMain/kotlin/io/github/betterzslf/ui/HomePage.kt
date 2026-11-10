package io.github.betterzslf.ui

import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import io.github.betterzslf.feature.schedule.ScheduleOccurrence
import io.github.betterzslf.feature.schedule.SchedulePeriod
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.scheduleCurrentOccurrences
import io.github.betterzslf.feature.schedule.scheduleLiveMinutes
import io.github.betterzslf.feature.schedule.scheduleNextOccurrence
import io.github.betterzslf.feature.schedule.scheduleOccurrences
import io.github.betterzslf.feature.schedule.scheduleSchoolDate
import io.github.betterzslf.feature.schedule.scheduleTodayOccurrences
import io.github.betterzslf.feature.schedule.scheduleWeekForDate
import io.github.betterzslf.ui.schedule.ScheduleDetailSelection
import io.github.betterzslf.ui.schedule.ScheduleRoutes
import io.github.betterzslf.ui.schedule.coursePeriodTimes
import io.github.betterzslf.ui.schedule.scheduleCoursePalette
import io.github.betterzslf.ui.schedule.scheduleDisplayClock
import io.github.betterzslf.ui.schedule.scheduleRoute
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.time.Instant

/**
 * Ardot P00 首页 (44:47): date greeting, a blue gradient "下一节" hero with a live countdown,
 * and the "今天的课 · N 节" grouped list. Landing tab of the Ardot 24:22 five-tab navigation.
 *
 * All course data comes from the shipped, tested occurrence resolver — no invented class, no
 * placeholder numbers, no guessed campus/teacher. When the timetable is unbound, unsynced,
 * incomplete or the calendar cannot place today, the page says so instead of rendering the
 * design's sample numbers.
 */
@Composable
fun HomePage(graph: AppGraph, open: (Route) -> Unit) {
    val state by graph.schedule.state.collectAsState()
    val now = scheduleDisplayClock()
    val snapshot = state.snapshot
    val view = homeView(snapshot, state.periods, state.selectedWeek, now)
    // Ardot P00 (44:47) has NO top-app-bar title: the content area opens directly with the
    // large date headline. A titled Screen would duplicate "首页" above it.
    Scaffold(containerColor = MiuixTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)) { padding ->
        PageList(padding) {
            item("home-greeting") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(view.dateLine, style = MiuixTheme.textStyles.title1)
                    Text(view.subtitle, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (view.hero == null && view.today.isEmpty()) {
                item("home-empty") { InfoBanner(view.emptyMessage, tone = UiTone.Neutral) }
            } else {
                view.hero?.let { hero -> item("home-hero") { HomeHeroCard(hero, snapshot, open) } }
                item("home-today-title") { SectionTitle(AppText.Home.todayCourses(view.today.size)) }
                item("home-today-list") {
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            view.today.forEach { row -> HomeCourseRow(row, snapshot, open) }
                        }
                    }
                }
            }
        }
    }
}

internal data class HomeHero(
    val occurrence: ScheduleOccurrence,
    val inClass: Boolean,
    val hours: Long,
    val minutes: Long,
    val periodLabel: String,
    val timeRange: String,
)

internal data class HomeRow(
    val occurrence: ScheduleOccurrence,
    val start: String,
    val end: String,
    val periodLabel: String,
    val isNext: Boolean,
    val barColor: Color,
)

internal data class HomeView(
    val dateLine: String,
    val subtitle: String,
    val hero: HomeHero?,
    val today: List<HomeRow>,
    val emptyMessage: String,
)

private fun periodLabel(periods: List<Int>): String {
    val valid = periods.filter { it in 1..12 }.distinct().sorted()
    return if (valid.isEmpty()) "" else AppText.Home.periodRange(valid.first(), valid.last())
}

private fun timeBounds(o: ScheduleOccurrence, all: List<SchedulePeriod>): Pair<String, String> =
    (all.firstOrNull { it.number == o.periods.minOrNull() }?.start.orEmpty() to
        all.firstOrNull { it.number == o.periods.maxOrNull() }?.end.orEmpty())

/** Pure projection so the home state selection is unit-testable without Compose. */
internal fun homeView(snapshot: ScheduleSnapshot?, periods: List<SchedulePeriod>, week: Int, nowMillis: Long): HomeView {
    val local = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(TimeZone.of(SCHEDULE_TIME_ZONE_ID))
    val dateLine = AppText.Home.date(local.date.month.ordinal + 1, local.date.day,
        AppText.Home.weekday(local.date.dayOfWeek.ordinal + 1))
    val subtitle = AppText.Home.subtitle(AppText.Home.greeting(local.hour), snapshot?.term?.name.orEmpty(), week)
    if (snapshot == null || !snapshot.complete) {
        return HomeView(dateLine, subtitle, null, emptyList(), AppText.Home.NO_CACHE_HINT)
    }
    val occurrences = scheduleOccurrences(snapshot, periods, nowMillis - 86_400_000L, nowMillis + 14 * 86_400_000L)
    if (scheduleWeekForDate(snapshot, scheduleSchoolDate(nowMillis)) == null) {
        return HomeView(dateLine, subtitle, null, emptyList(), AppText.Home.CALENDAR_MISSING_HINT)
    }
    val today = scheduleTodayOccurrences(occurrences, nowMillis)
    val running = scheduleCurrentOccurrences(occurrences, nowMillis).firstOrNull()
    val next = scheduleNextOccurrence(occurrences, nowMillis)
    val highlighted = running ?: next
    val hero = highlighted?.let { o ->
        val total = if (running != null) scheduleLiveMinutes(o.endsAtMillis - nowMillis)
        else scheduleLiveMinutes(o.startsAtMillis - nowMillis)
        HomeHero(o, running != null, total / 60, total % 60, periodLabel(o.periods),
            coursePeriodTimes(o.course.copy(periods = o.periods), periods))
    }
    val rows = today.map { o ->
        val (start, end) = timeBounds(o, periods)
        HomeRow(o, start, end, periodLabel(o.periods), next?.id == o.id,
            scheduleCoursePalette(o.course).title)
    }
    val empty = if (rows.isEmpty()) AppText.Home.NO_CLASS_TODAY else if (hero == null) AppText.Home.NO_NEXT_CLASS else ""
    return HomeView(dateLine, subtitle, hero, rows, empty)
}

private fun detailRoute(o: ScheduleOccurrence, snapshot: ScheduleSnapshot?): Route {
    val termId = snapshot?.term?.id.orEmpty()
    return scheduleRoute(ScheduleRoutes.DETAIL, ScheduleDetailSelection(termId, listOf(o.courseId), o.week).encode())
}

@Composable
private fun HomeHeroCard(hero: HomeHero, snapshot: ScheduleSnapshot?, open: (Route) -> Unit) {
    val o = hero.occurrence
    // Ardot 44:67: blue gradient #3F8CFF -> #245CEB, radius 20, white text.
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
        onClick = { open(detailRoute(o, snapshot)) }) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3F8CFF), Color(0xFF245CEB))))) {
            Column(Modifier.padding(AppUiTokens.CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.2f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(if (hero.inClass) AppText.Schedule.IN_PROGRESS else AppText.Home.NEXT_BADGE,
                            color = Color.White, fontSize = 12.sp)
                    }
                    Text(listOf(hero.periodLabel, hero.timeRange).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                }
                Text(o.title.ifBlank { AppText.Common.UNTITLED }, color = Color.White,
                    fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                val meta = listOf(o.location, o.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${hero.hours}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(AppText.Home.HOUR, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 3.dp, end = 6.dp))
                    Text("${hero.minutes}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(AppText.Home.MINUTE, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 3.dp, end = 6.dp))
                    Text(AppText.Home.AFTER_CLASS, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeCourseRow(row: HomeRow, snapshot: ScheduleSnapshot?, open: (Route) -> Unit) {
    val o = row.occurrence
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        onClick = { open(detailRoute(o, snapshot)) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(40.dp)) {
                Text(row.start, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(row.end, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(row.barColor))
            Column(Modifier.weight(1f)) {
                Text(o.title.ifBlank { AppText.Common.UNTITLED }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                val meta = AppText.Home.periodLine(row.periodLabel, o.location, o.teacher)
                if (meta.isNotBlank()) Text(meta, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (row.isNext) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(MiuixTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(AppText.Home.NEXT_BADGE, fontSize = 11.sp, color = MiuixTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}
