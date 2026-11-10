package io.github.betterzslf.ui

import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
            // Ardot 94:1: the 下一节栏 is ALWAYS present (8 states); only its content changes.
            item("home-hero") { HomeHeroCard(view.hero, view.today, view.periods, snapshot, open) }
            if (view.today.isNotEmpty()) {
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

/**
 * Ardot 94:1 「下一节栏 · 八态状态板」. The bar is ALWAYS present; only its content layer
 * changes. A/B/C/G are blue-gradient (there is still a class to care about today), D/E/F/H are
 * surface cards (no class-affairs info today).
 */
internal enum class HomeHeroMode {
    BEFORE,        // A 课前: today has a next class, > 15 min away
    JUST_BEFORE,   // B 课间: <= 15 min away, or a room change from the last class
    IN_CLASS,      // C 上课中: a class is running
    LONG_GAP,      // G 长空档: today has a next class but >= 2 h away
    DONE_TODAY,    // D 今日已上完: today had classes, all finished (bar no longer disappears)
    FREE_TODAY,    // E 今天没课: no class today, but the 14-day window has one
    NO_PLAN,       // H 近期无排课: no class in the whole 14-day window
    UNAVAILABLE,   // F 数据不可用: unbound / unsynced / incomplete / calendar cannot place today
}

/** Why F is showing — the badge names the concrete reason (Ardot 94:1 "说清是哪一种"). */
internal enum class HomeUnavailableReason { UNBOUND, UNSYNCED, PARTIAL, CALENDAR }

internal data class HomeHero(
    val mode: HomeHeroMode,
    val course: ScheduleOccurrence?,   // A/B/C/G: the class being highlighted
    val next: ScheduleOccurrence?,     // C (today's next after current) / E (next in window)
    val tomorrow: ScheduleOccurrence?, // D: first occurrence after today
    val hours: Long,                   // A/G countdown hours
    val minutes: Long,                 // A/B/C/G countdown minutes
    val periodLabel: String,
    val timeRange: String,
    val todayCount: Int,               // D 今日小结
    val lastEnd: String,               // D 最后一节下课时间
    val remainingToday: Int,           // G 今天还剩几节
    val roomChanged: Boolean,          // B 换教室
    val reason: HomeUnavailableReason?,// F
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
    val hero: HomeHero,   // never null: Ardot 94:1 bar is always present
    val today: List<HomeRow>,
    val emptyMessage: String,
    val periods: List<SchedulePeriod>,
)

private fun periodLabel(periods: List<Int>): String {
    val valid = periods.filter { it in 1..12 }.distinct().sorted()
    return if (valid.isEmpty()) "" else AppText.Home.periodRange(valid.first(), valid.last())
}

private fun timeBounds(o: ScheduleOccurrence, all: List<SchedulePeriod>): Pair<String, String> =
    (all.firstOrNull { it.number == o.periods.minOrNull() }?.start.orEmpty() to
        all.firstOrNull { it.number == o.periods.maxOrNull() }?.end.orEmpty())

private fun emptyHero(mode: HomeHeroMode, reason: HomeUnavailableReason? = null) = HomeHero(
    mode = mode, course = null, next = null, tomorrow = null,
    hours = 0, minutes = 0, periodLabel = "", timeRange = "", todayCount = 0, lastEnd = "",
    remainingToday = 0, roomChanged = false, reason = reason)

private fun unavailableHero(reason: HomeUnavailableReason) = emptyHero(HomeHeroMode.UNAVAILABLE, reason)

/** First occurrence in the 14-day window whose date is strictly after today (D/E). */
private fun firstAfterToday(all: List<ScheduleOccurrence>, today: String): ScheduleOccurrence? =
    all.filter { it.date > today && it.startsAtMillis < it.endsAtMillis }
        .sortedWith(compareBy<ScheduleOccurrence> { it.date }.thenBy { it.startsAtMillis }).firstOrNull()

/**
 * Pure projection. Decision order is fixed by Ardot 94:1 — first match wins:
 * F → C → B → G → A → D → E → H. G must precede A (A is a superset); D and E are disjoint
 * (today had classes vs. today never had classes); D must never degrade into E or H.
 */
internal fun homeView(snapshot: ScheduleSnapshot?, periods: List<SchedulePeriod>, week: Int, nowMillis: Long): HomeView {
    val local = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(TimeZone.of(SCHEDULE_TIME_ZONE_ID))
    val dateLine = AppText.Home.date(local.date.month.ordinal + 1, local.date.day,
        AppText.Home.weekday(local.date.dayOfWeek.ordinal + 1))
    val subtitle = AppText.Home.subtitle(AppText.Home.greeting(local.hour), snapshot?.term?.name.orEmpty(), week)
    val todayStr = scheduleSchoolDate(nowMillis).toString()
    fun view(hero: HomeHero, rows: List<HomeRow> = emptyList(), empty: String = "") =
        HomeView(dateLine, subtitle, hero, rows, empty, periods)

    // 1 · F 数据不可用 (highest priority): name the concrete reason.
    if (snapshot == null) return view(unavailableHero(HomeUnavailableReason.UNBOUND))
    if (!snapshot.complete) return view(unavailableHero(HomeUnavailableReason.PARTIAL))
    if (scheduleWeekForDate(snapshot, scheduleSchoolDate(nowMillis)) == null) {
        return view(unavailableHero(HomeUnavailableReason.CALENDAR))
    }

    val occurrences = scheduleOccurrences(snapshot, periods, nowMillis - 86_400_000L, nowMillis + 14 * 86_400_000L)
    val today = scheduleTodayOccurrences(occurrences, nowMillis)
    val running = scheduleCurrentOccurrences(occurrences, nowMillis).firstOrNull()
    val next = scheduleNextOccurrence(today, nowMillis)   // TODAY's next class only
    val rows = today.map { o ->
        val (start, end) = timeBounds(o, periods)
        HomeRow(o, start, end, periodLabel(o.periods), next?.id == o.id,
            scheduleCoursePalette(o.course).title)
    }

    fun base(mode: HomeHeroMode, course: ScheduleOccurrence?, total: Long,
             extraNext: ScheduleOccurrence? = null, tomorrow: ScheduleOccurrence? = null,
             roomChanged: Boolean = false, remainingToday: Int = 0) = HomeHero(
        mode = mode, course = course, next = extraNext, tomorrow = tomorrow,
        hours = total / 60, minutes = total % 60,
        periodLabel = course?.let { periodLabel(it.periods) }.orEmpty(),
        timeRange = course?.let { coursePeriodTimes(it.course.copy(periods = it.periods), periods) }.orEmpty(),
        todayCount = today.size, lastEnd = today.lastOrNull()?.let { timeBounds(it, periods).second }.orEmpty(),
        remainingToday = remainingToday, roomChanged = roomChanged, reason = null)

    // 2 · C 上课中 — running class takes the main slot; hint today's next class below.
    if (running != null) {
        val total = scheduleLiveMinutes(running.endsAtMillis - nowMillis)
        return view(base(HomeHeroMode.IN_CLASS, running, total, extraNext = next), rows)
    }

    if (next != null) {
        val untilStart = scheduleLiveMinutes(next.startsAtMillis - nowMillis)
        val lastFinished = today.lastOrNull { it.endsAtMillis <= nowMillis }
        val roomChanged = lastFinished != null && lastFinished.location.isNotBlank() &&
            next.location.isNotBlank() && lastFinished.location != next.location
        // 3 · B 课间 — <= 15 min to start, or a room change from the class just finished.
        if (untilStart <= 15 || roomChanged) {
            return view(base(HomeHeroMode.JUST_BEFORE, next, untilStart, roomChanged = roomChanged), rows)
        }
        // 4 · G 长空档 — >= 2 h to the next class (checked before A: A would swallow it).
        if (untilStart >= 120) {
            return view(base(HomeHeroMode.LONG_GAP, next, untilStart,
                remainingToday = today.size - today.indexOf(next)), rows)
        }
        // 5 · A 课前 — any other "next class today" case.
        return view(base(HomeHeroMode.BEFORE, next, untilStart), rows)
    }

    // 6 · D 今日已上完 — today had classes and all ended. Bar stays as a summary card.
    if (today.isNotEmpty()) {
        val tomorrow = firstAfterToday(occurrences, todayStr)
        return view(base(HomeHeroMode.DONE_TODAY, null, 0, tomorrow = tomorrow), rows)
    }

    // 7 · E 今天没课 — no class today, but the 14-day window still has one.
    val upcoming = firstAfterToday(occurrences, todayStr)
    if (upcoming != null) {
        return view(base(HomeHeroMode.FREE_TODAY, null, 0, extraNext = upcoming), rows)
    }

    // 8 · H 近期无排课 — honest "none", no invented next class.
    return view(emptyHero(HomeHeroMode.NO_PLAN), rows)
}

private fun detailRoute(o: ScheduleOccurrence, snapshot: ScheduleSnapshot?): Route {
    val termId = snapshot?.term?.id.orEmpty()
    return scheduleRoute(ScheduleRoutes.DETAIL, ScheduleDetailSelection(termId, listOf(o.courseId), o.week).encode())
}

/** Ardot 94:1: blue-gradient states A/B/C/G, surface (white/deep) states D/E/F/H. */
@Composable
private fun HomeHeroCard(hero: HomeHero, today: List<HomeRow>, periods: List<SchedulePeriod>,
                         snapshot: ScheduleSnapshot?, open: (Route) -> Unit) {
    when (hero.mode) {
        HomeHeroMode.BEFORE, HomeHeroMode.JUST_BEFORE, HomeHeroMode.IN_CLASS, HomeHeroMode.LONG_GAP ->
            BlueHeroCard(hero, today, periods, snapshot, open)
        else -> SurfaceHeroCard(hero, periods, snapshot, open)
    }
}

@Composable
private fun BlueHeroCard(hero: HomeHero, today: List<HomeRow>, periods: List<SchedulePeriod>,
                         snapshot: ScheduleSnapshot?, open: (Route) -> Unit) {
    val o = hero.course
    val gradient = when (hero.mode) {
        HomeHeroMode.JUST_BEFORE -> listOf(Color(0xFF3480FF), Color(0xFF1A4BE5))
        HomeHeroMode.LONG_GAP -> listOf(Color(0xFF4D90FF), Color(0xFF2A68E7))
        else -> listOf(Color(0xFF3F8CFF), Color(0xFF245CEB))
    }
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
        onClick = o?.let { { open(detailRoute(it, snapshot)) } }) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(gradient))) {
            Column(Modifier.padding(AppUiTokens.CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (hero.mode) {
                    HomeHeroMode.LONG_GAP -> LongGapContent(hero, today, periods, o)
                    HomeHeroMode.JUST_BEFORE -> JustBeforeContent(hero, o)
                    HomeHeroMode.IN_CLASS -> InClassContent(hero, o)
                    else -> BeforeContent(hero, o)   // A
                }
            }
        }
    }
}

@Composable
private fun HeroBadge(text: String, whitePill: Boolean) {
    Box(Modifier.clip(RoundedCornerShape(12.dp))
        .background(if (whitePill) Color.White else Color.White.copy(alpha = 0.2f))
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = if (whitePill) Color(0xFF1A6BFF) else Color.White, fontSize = 12.sp)
    }
}

private fun periodAndTime(hero: HomeHero): String =
    listOf(hero.periodLabel, hero.timeRange).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun BeforeContent(hero: HomeHero, o: ScheduleOccurrence?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroBadge(AppText.Home.NEXT_BADGE, whitePill = false)
        Text(periodAndTime(hero), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
    }
    if (o != null) {
        Text(o.title.ifBlank { AppText.Common.UNTITLED }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val meta = listOf(o.location, o.teacher).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text("${hero.hours}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(AppText.Home.HOUR, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp, end = 6.dp))
        Text("${hero.minutes}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(AppText.Home.MINUTE, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp, end = 6.dp))
        Text(AppText.Home.AFTER_CLASS, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun JustBeforeContent(hero: HomeHero, o: ScheduleOccurrence?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroBadge(AppText.Home.BADGE_JUST_BEFORE, whitePill = true)
        val meta = periodAndTime(hero) + if (o?.location.isNullOrBlank()) "" else " · ${o.location}"
        Text(meta, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (o != null) {
                Text(o.title.ifBlank { AppText.Common.UNTITLED }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val sub = listOf(o.location, o.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("${hero.minutes}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(AppText.Home.MINUTES_LATER_START, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
        }
    }
}

@Composable
private fun InClassContent(hero: HomeHero, o: ScheduleOccurrence?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroBadge(AppText.Home.BADGE_IN_CLASS, whitePill = true)
        Text(periodAndTime(hero), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
    }
    if (o != null) {
        Text(o.title.ifBlank { AppText.Common.UNTITLED }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val meta = listOf(o.location, o.teacher).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(AppText.Home.CLASS_ENDS_IN_PREFIX, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp))
        Text("${hero.minutes}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(AppText.Home.MINUTE, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp, end = 2.dp))
        Text(AppText.Home.CLASS_ENDS_IN_SUFFIX, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp))
    }
    // progress track + next-class hint
    val progress = hero.next
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(3.dp))
        .background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(0.6f).height(4.dp).clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.95f)))
    }
    if (progress != null) {
        Text(AppText.Home.NEXT_HINT_PREFIX + listOf(
            progress.course.title.ifBlank { AppText.Common.UNTITLED }, progress.location)
            .filter { it.isNotBlank() }.joinToString(" · "),
            color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun LongGapContent(hero: HomeHero, today: List<HomeRow>, periods: List<SchedulePeriod>,
                           o: ScheduleOccurrence?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroBadge(AppText.Home.BADGE_LONG_GAP, whitePill = false)
        Text(AppText.Home.gapDuration(hero.hours, hero.minutes),
            color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
    }
    Text(AppText.Home.FREE_TIME_TITLE, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    if (o != null) {
        val start = timeBounds(o, periods).first
        Text(AppText.Home.longGapSubtitle(hero.remainingToday, start, o.title, o.location),
            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
    // remaining-today capsules
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        today.dropWhile { it.occurrence.id != o?.id }.take(2).forEach { row ->
            Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.16f))
                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(AppText.Home.courseLine(row.start, row.occurrence.title),
                    color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

/** D/E/F/H: surface card + 1dp border, dark text (Ardot 94:1). */
@Composable
private fun SurfaceHeroCard(hero: HomeHero, periods: List<SchedulePeriod>,
                            snapshot: ScheduleSnapshot?, open: (Route) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp,
        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (hero.mode) {
                HomeHeroMode.DONE_TODAY -> DoneTodayContent(hero, periods, snapshot, open)
                HomeHeroMode.FREE_TODAY -> FreeTodayContent(hero, periods, snapshot, open)
                HomeHeroMode.UNAVAILABLE -> UnavailableContent(hero, open)
                HomeHeroMode.NO_PLAN -> NoPlanContent(open)
                else -> Unit
            }
        }
    }
}

@Composable
private fun SurfaceBadge(text: String, emphasized: Boolean) {
    Box(Modifier.clip(RoundedCornerShape(12.dp))
        .background(if (emphasized) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surface)
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = if (emphasized) MiuixTheme.colorScheme.onPrimaryContainer
        else MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = MiuixTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f))
        Text(AppText.Home.ACTION_ARROW, color = MiuixTheme.colorScheme.primary, fontSize = 16.sp)
    }
}

@Composable
private fun UpcomingCourseBlock(o: ScheduleOccurrence, periods: List<SchedulePeriod>, open: (Route) -> Unit) {
    val (start, end) = timeBounds(o, periods)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(MiuixTheme.colorScheme.surface).padding(12.dp)
        .clickable { open(detailRoute(o, null)) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).height(32.dp).clip(RoundedCornerShape(2.dp))
            .background(scheduleCoursePalette(o.course).title))
        Column(Modifier.weight(1f)) {
            Text(o.title.ifBlank { AppText.Common.UNTITLED }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(AppText.Home.courseMeta("", "$start–$end", o.location, o.teacher),
                fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Text(AppText.Home.ACTION_ARROW, color = MiuixTheme.colorScheme.primary, fontSize = 16.sp)
    }
}

@Composable
private fun DoneTodayContent(hero: HomeHero, periods: List<SchedulePeriod>,
                             snapshot: ScheduleSnapshot?, open: (Route) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SurfaceBadge(AppText.Home.BADGE_DONE_TODAY, emphasized = true)
        Text(AppText.Home.doneTodaySummary(hero.todayCount, hero.lastEnd),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
    }
    hero.tomorrow?.let { o ->
        val (start, end) = timeBounds(o, periods)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AppText.Home.tomorrowHeading(AppText.Home.weekday(o.course.dayOfWeek)),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
            Text("$start–$end · ${periodLabel(o.periods)}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        UpcomingCourseBlock(o, periods, open)
    }
    ActionRow(AppText.Home.WEEK_TIMETABLE_ACTION) { open(scheduleRoute(ScheduleRoutes.ROOT)) }
}

@Composable
private fun FreeTodayContent(hero: HomeHero, periods: List<SchedulePeriod>,
                             snapshot: ScheduleSnapshot?, open: (Route) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SurfaceBadge(AppText.Home.BADGE_FREE_TODAY, emphasized = false)
        Text(AppText.Home.FREE_TODAY_SUBTITLE,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
    }
    hero.next?.let { o ->
        Text(AppText.Home.nextClassHeading(AppText.Home.weekday(o.course.dayOfWeek)),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
        UpcomingCourseBlock(o, periods, open)
    }
    ActionRow(AppText.Home.OPEN_WEEK_TIMETABLE_ACTION) { open(scheduleRoute(ScheduleRoutes.ROOT)) }
}

private data class UnavailableCopy(val badge: String, val sub: String, val body: String,
                                   val action: String, val bind: Boolean)

private fun unavailableCopy(reason: HomeUnavailableReason?): UnavailableCopy = when (reason) {
    HomeUnavailableReason.UNSYNCED -> UnavailableCopy(
        AppText.Home.BADGE_UNAVAILABLE_UNSYNCED, AppText.Home.UNAVAILABLE_UNSYNCED_SUB,
        AppText.Home.UNAVAILABLE_UNSYNCED_BODY, AppText.Home.ACTION_SYNC, false)
    HomeUnavailableReason.PARTIAL -> UnavailableCopy(
        AppText.Home.BADGE_UNAVAILABLE_PARTIAL, AppText.Home.UNAVAILABLE_PARTIAL_SUB,
        AppText.Home.UNAVAILABLE_PARTIAL_BODY, AppText.Home.ACTION_SYNC, false)
    HomeUnavailableReason.CALENDAR -> UnavailableCopy(
        AppText.Home.BADGE_UNAVAILABLE_CALENDAR, AppText.Home.UNAVAILABLE_CALENDAR_SUB,
        AppText.Home.UNAVAILABLE_CALENDAR_BODY, AppText.Home.ACTION_CHECK_WEEK, false)
    else -> UnavailableCopy(
        AppText.Home.BADGE_UNAVAILABLE_UNBOUND, AppText.Home.UNAVAILABLE_UNBOUND_SUB,
        AppText.Home.UNAVAILABLE_UNBOUND_BODY, AppText.Home.ACTION_BIND, true)
}

@Composable
private fun UnavailableContent(hero: HomeHero, open: (Route) -> Unit) {
    val copy = unavailableCopy(hero.reason)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SurfaceBadge(copy.badge, emphasized = false)
        Text(copy.sub, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
    }
    Text(copy.body, fontSize = 13.sp)
    PrimaryAction(copy.action, onClick = {
        open(if (copy.bind) scheduleRoute(ScheduleRoutes.BIND) else scheduleRoute(ScheduleRoutes.ROOT))
    })
}

@Composable
private fun NoPlanContent(open: (Route) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SurfaceBadge(AppText.Home.BADGE_NO_PLAN, emphasized = false)
        Text(AppText.Home.NO_PLAN_SUB, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
    }
    Text(AppText.Home.NO_PLAN_TITLE, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Text(AppText.Home.NO_PLAN_BODY, fontSize = 13.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(AppText.Home.ACTION_CHECK_WEEK, onClick = { open(scheduleRoute(ScheduleRoutes.TERMS)) })
        PrimaryAction(AppText.Home.ACTION_SYNC, onClick = { open(scheduleRoute(ScheduleRoutes.ROOT)) },
            modifier = Modifier.weight(1f))
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
