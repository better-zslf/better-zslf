package io.github.betterzslf.feature.home.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.home.presentation.*
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.PageList
import io.github.betterzslf.ui.PrimaryAction
import io.github.betterzslf.ui.SectionTitle
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.betterzslf.ui.AppCard
import io.github.betterzslf.ui.appSquircleClip

/** Complete home content, usable without an application graph, repository, clock or navigation host. */
@Composable
fun HomeContent(state: HomeUiState, onEvent: (HomeEvent) -> Unit) {
    val open: (HomeOccurrenceId) -> Unit = { state.eventFor(it)?.let(onEvent) }
    val action: (HomeAction) -> Unit = { state.eventFor(it)?.let(onEvent) }
    // P00 deliberately has no title bar above the date headline.
    Scaffold(containerColor = MiuixTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top)) { padding ->
        PageList(padding) {
            item("home-greeting") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(state.dateLine, style = MiuixTheme.textStyles.title1)
                    Text(state.subtitle, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            item("home-hero") { HomeHeroCard(state.hero, state.today, open, action) }
            if (state.today.isNotEmpty()) {
                item("home-today-title") { SectionTitle(AppText.Home.todayCourses(state.today.size)) }
                item("home-today-list") {
                    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            state.today.forEach { row -> HomeCourseRow(row, open) }
                        }
                    }
                }
            }
        }
    }
}

/** Blue-gradient A/B/C/G; surface D/E/F/H. The hero is always present. */
@Composable
private fun HomeHeroCard(hero: HomeHero, today: List<HomeRow>, open: (HomeOccurrenceId) -> Unit,
                         action: (HomeAction) -> Unit) {
    when (hero.mode) {
        HomeHeroMode.BEFORE, HomeHeroMode.JUST_BEFORE, HomeHeroMode.IN_CLASS, HomeHeroMode.LONG_GAP ->
            BlueHeroCard(hero, today, open)
        else -> SurfaceHeroCard(hero, open, action)
    }
}

@Composable
private fun BlueHeroCard(hero: HomeHero, today: List<HomeRow>, open: (HomeOccurrenceId) -> Unit) {
    val course = hero.course
    val gradient = when (hero.mode) {
        HomeHeroMode.JUST_BEFORE -> listOf(Color(0xFF3480FF), Color(0xFF1A4BE5))
        HomeHeroMode.LONG_GAP -> listOf(Color(0xFF4D90FF), Color(0xFF2A68E7))
        else -> listOf(Color(0xFF3F8CFF), Color(0xFF245CEB))
    }
    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = Color.Transparent),
        onClick = course?.let { { open(it.occurrenceId) } }) {
        Box(Modifier.fillMaxWidth().appSquircleClip(20.dp).background(Brush.linearGradient(gradient))) {
            Column(Modifier.padding(AppUiTokens.CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (hero.mode) {
                    HomeHeroMode.LONG_GAP -> LongGapContent(hero, today, course)
                    HomeHeroMode.JUST_BEFORE -> JustBeforeContent(hero, course)
                    HomeHeroMode.IN_CLASS -> InClassContent(hero, course)
                    else -> BeforeContent(hero, course)
                }
            }
        }
    }
}

@Composable
private fun HeroBadge(text: String, whitePill: Boolean) {
    Box(Modifier.appSquircleClip(12.dp)
        .background(if (whitePill) Color.White else Color.White.copy(alpha = 0.2f))
        .padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, color = if (whitePill) Color(0xFF1A6BFF) else Color.White, fontSize = 12.sp)
    }
}

private fun periodAndTime(hero: HomeHero): String =
    listOf(hero.periodLabel, hero.timeRange).filter { it.isNotBlank() }.joinToString(" · ")

@Composable
private fun BeforeContent(hero: HomeHero, course: HomeCourseUi?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroBadge(AppText.Home.NEXT_BADGE, whitePill = false)
        Text(periodAndTime(hero), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
    }
    if (course != null) {
        Text(course.title.ifBlank { AppText.Common.UNTITLED }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val meta = listOf(course.location, course.teacher).filter { it.isNotBlank() }.joinToString(" · ")
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
private fun JustBeforeContent(hero: HomeHero, course: HomeCourseUi?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroBadge(AppText.Home.BADGE_JUST_BEFORE, whitePill = true)
        val meta = periodAndTime(hero) + if (course?.location.isNullOrBlank()) "" else " · ${course.location}"
        Text(meta, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (course != null) {
                Text(course.title.ifBlank { AppText.Common.UNTITLED }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                val sub = listOf(course.location, course.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                if (sub.isNotBlank()) Text(sub, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            // A room change can select B even when the next class is more than an hour away.
            if (hero.totalMinutes >= 60) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${hero.hours}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(AppText.Home.HOUR, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 4.dp, end = 6.dp))
                    Text("${hero.minutes}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    Text(AppText.Home.MINUTE, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 4.dp, end = 6.dp))
                }
                Text(AppText.Home.AFTER_CLASS, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            } else {
                Text("${hero.minutes}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(AppText.Home.MINUTES_LATER_START, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun InClassContent(hero: HomeHero, course: HomeCourseUi?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroBadge(AppText.Home.BADGE_IN_CLASS, whitePill = true)
        Text(periodAndTime(hero), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
    }
    if (course != null) {
        Text(course.title.ifBlank { AppText.Common.UNTITLED }, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        val meta = listOf(course.location, course.teacher).filter { it.isNotBlank() }.joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(AppText.Home.CLASS_ENDS_IN_PREFIX, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp))
        // Explicit bugfix: contiguous multi-period classes must not lose the hour component.
        if (hero.totalMinutes >= 60) {
            Text("${hero.hours}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(AppText.Home.HOUR, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 4.dp, end = 6.dp))
        }
        Text("${hero.minutes}", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(AppText.Home.MINUTE, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp, end = 2.dp))
        Text(AppText.Home.CLASS_ENDS_IN_SUFFIX, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 4.dp))
    }
    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(3.dp))
        .background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(hero.progress ?: 0f).height(4.dp).clip(RoundedCornerShape(3.dp))
            .background(Color.White.copy(alpha = 0.95f)))
    }
    hero.next?.let { next ->
        Text(AppText.Home.NEXT_HINT_PREFIX + listOf(next.title.ifBlank { AppText.Common.UNTITLED }, next.location)
            .filter { it.isNotBlank() }.joinToString(" · "),
            color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
    }
}

@Composable
private fun LongGapContent(hero: HomeHero, today: List<HomeRow>, course: HomeCourseUi?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HeroBadge(AppText.Home.BADGE_LONG_GAP, whitePill = false)
        Text(AppText.Home.gapDuration(hero.hours, hero.minutes), color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
    }
    Text(AppText.Home.FREE_TIME_TITLE, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    if (course != null) {
        Text(AppText.Home.longGapSubtitle(hero.remainingToday, course.start, course.title, course.location),
            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        today.dropWhile { it.occurrence.occurrenceId != course?.occurrenceId }.take(2).forEach { row ->
            Box(Modifier.appSquircleClip(12.dp).background(Color.White.copy(alpha = 0.16f))
                .padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(AppText.Home.courseLine(row.start, row.occurrence.title), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SurfaceHeroCard(hero: HomeHero, open: (HomeOccurrenceId) -> Unit, action: (HomeAction) -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (hero.mode) {
                HomeHeroMode.DONE_TODAY -> DoneTodayContent(hero, open, action)
                HomeHeroMode.FREE_TODAY -> FreeTodayContent(hero, open, action)
                HomeHeroMode.UNAVAILABLE -> UnavailableContent(hero, action)
                HomeHeroMode.NO_PLAN -> NoPlanContent(action)
                else -> Unit
            }
        }
    }
}

@Composable
private fun SurfaceBadge(text: String, emphasized: Boolean) {
    Box(Modifier.appSquircleClip(12.dp)
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
private fun UpcomingCourseBlock(course: HomeCourseUi, open: (HomeOccurrenceId) -> Unit) {
    Row(Modifier.fillMaxWidth().appSquircleClip(14.dp)
        .background(MiuixTheme.colorScheme.surface).padding(12.dp)
        .clickable { open(course.occurrenceId) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(3.dp).height(32.dp).clip(RoundedCornerShape(2.dp)).background(course.accent.color()))
        Column(Modifier.weight(1f)) {
            Text(course.title.ifBlank { AppText.Common.UNTITLED }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(AppText.Home.courseMeta("", "${course.start}–${course.end}", course.location, course.teacher),
                fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Text(AppText.Home.ACTION_ARROW, color = MiuixTheme.colorScheme.primary, fontSize = 16.sp)
    }
}

@Composable
private fun DoneTodayContent(hero: HomeHero, open: (HomeOccurrenceId) -> Unit, action: (HomeAction) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SurfaceBadge(AppText.Home.BADGE_DONE_TODAY, emphasized = true)
        Text(AppText.Home.doneTodaySummary(hero.todayCount, hero.lastEnd),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
    }
    hero.tomorrow?.let { course ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(AppText.Home.tomorrowHeading(AppText.Home.weekday(course.dayOfWeek)),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
            Text("${course.start}–${course.end} · ${course.periodLabel}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        UpcomingCourseBlock(course, open)
    }
    ActionRow(AppText.Home.WEEK_TIMETABLE_ACTION) { action(HomeAction.OPEN_TIMETABLE) }
}

@Composable
private fun FreeTodayContent(hero: HomeHero, open: (HomeOccurrenceId) -> Unit, action: (HomeAction) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SurfaceBadge(AppText.Home.BADGE_FREE_TODAY, emphasized = false)
        Text(AppText.Home.FREE_TODAY_SUBTITLE, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
    }
    hero.next?.let { course ->
        Text(AppText.Home.nextClassHeading(AppText.Home.weekday(course.dayOfWeek)),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 12.sp)
        UpcomingCourseBlock(course, open)
    }
    ActionRow(AppText.Home.OPEN_WEEK_TIMETABLE_ACTION) { action(HomeAction.OPEN_TIMETABLE) }
}

private data class UnavailableCopy(val badge: String, val sub: String, val body: String, val action: String, val bind: Boolean)

private fun unavailableCopy(reason: HomeUnavailableReason?): UnavailableCopy = when (reason) {
    HomeUnavailableReason.UNSYNCED -> UnavailableCopy(AppText.Home.BADGE_UNAVAILABLE_UNSYNCED,
        AppText.Home.UNAVAILABLE_UNSYNCED_SUB, AppText.Home.UNAVAILABLE_UNSYNCED_BODY, AppText.Home.ACTION_SYNC, false)
    HomeUnavailableReason.PARTIAL -> UnavailableCopy(AppText.Home.BADGE_UNAVAILABLE_PARTIAL,
        AppText.Home.UNAVAILABLE_PARTIAL_SUB, AppText.Home.UNAVAILABLE_PARTIAL_BODY, AppText.Home.ACTION_SYNC, false)
    HomeUnavailableReason.CALENDAR -> UnavailableCopy(AppText.Home.BADGE_UNAVAILABLE_CALENDAR,
        AppText.Home.UNAVAILABLE_CALENDAR_SUB, AppText.Home.UNAVAILABLE_CALENDAR_BODY, AppText.Home.ACTION_CHECK_WEEK, false)
    else -> UnavailableCopy(AppText.Home.BADGE_UNAVAILABLE_UNBOUND,
        AppText.Home.UNAVAILABLE_UNBOUND_SUB, AppText.Home.UNAVAILABLE_UNBOUND_BODY, AppText.Home.ACTION_BIND, true)
}

@Composable
private fun UnavailableContent(hero: HomeHero, action: (HomeAction) -> Unit) {
    val copy = unavailableCopy(hero.reason)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SurfaceBadge(copy.badge, emphasized = false)
        Text(copy.sub, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
    }
    Text(copy.body, fontSize = 13.sp)
    PrimaryAction(copy.action, onClick = { action(if (copy.bind) HomeAction.BIND_SCHEDULE else HomeAction.OPEN_TIMETABLE) })
}

@Composable
private fun NoPlanContent(action: (HomeAction) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SurfaceBadge(AppText.Home.BADGE_NO_PLAN, emphasized = false)
        Text(AppText.Home.NO_PLAN_SUB, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
    }
    Text(AppText.Home.NO_PLAN_TITLE, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Text(AppText.Home.NO_PLAN_BODY, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(AppText.Home.ACTION_CHECK_WEEK, onClick = { action(HomeAction.OPEN_TERMS) })
        PrimaryAction(AppText.Home.ACTION_SYNC, onClick = { action(HomeAction.OPEN_TIMETABLE) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun HomeCourseRow(row: HomeRow, open: (HomeOccurrenceId) -> Unit) {
    val course = row.occurrence
    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp,
        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 12.dp), onClick = { open(course.occurrenceId) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(40.dp)) {
                Text(row.start, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(row.end, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(course.accent.color()))
            Column(Modifier.weight(1f)) {
                Text(course.title.ifBlank { AppText.Common.UNTITLED }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                val meta = AppText.Home.periodLine(row.periodLabel, course.location, course.teacher)
                if (meta.isNotBlank()) Text(meta, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            if (row.isNext) {
                Box(Modifier.appSquircleClip(10.dp).background(MiuixTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(AppText.Home.NEXT_BADGE, fontSize = 11.sp, color = MiuixTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}

/** Same stable title tints as schedule's four-course rotation, without depending on schedule UI. */
private fun HomeCourseAccent.color(): Color = when (this) {
    HomeCourseAccent.BLUE -> Color(0xFF2C5FE8)
    HomeCourseAccent.GREEN -> Color(0xFF1F8A4C)
    HomeCourseAccent.ORANGE -> Color(0xFFB25E00)
    HomeCourseAccent.PURPLE -> Color(0xFF5B3FD4)
}
