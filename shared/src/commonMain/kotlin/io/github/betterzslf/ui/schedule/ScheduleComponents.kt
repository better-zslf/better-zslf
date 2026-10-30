package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.feature.schedule.runtime.ScheduleState
import io.github.betterzslf.ui.*
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SchedulePanel(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap), content = content)
    }
}

@Composable
internal fun ScheduleProgress(message: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
        CircularProgressIndicator()
        Text(message, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun ScheduleField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap / 3)) {
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value.ifBlank { AppText.Common.NOT_RETURNED }, fontSize = 15.sp)
    }
}

/** Minute-level app display clock, not a promise of real-time widget/notification delivery. */
@Composable
internal fun scheduleDisplayClock(): Long {
    val now by produceState(Clock.System.now().toEpochMilliseconds()) {
        while (true) {
            value = Clock.System.now().toEpochMilliseconds()
            delay(60_000L)
        }
    }
    return now
}

internal fun scheduleSyncTime(millis: Long): String = if (millis <= 0) AppText.Schedule.LAST_SYNC_UNKNOWN else
    runCatching {
        AppText.Schedule.syncedAt(Instant.fromEpochMilliseconds(millis)
            .toLocalDateTime(TimeZone.of(SCHEDULE_TIME_ZONE_ID)).toString().replace('T', ' ').take(16) + " · " + AppText.Schedule.TIME_ZONE_SHORT)
    }.getOrElse { AppText.Schedule.LAST_SYNC_UNKNOWN }

@Composable
internal fun ScheduleSyncStamp(millis: Long) {
    Text(scheduleSyncTime(millis), fontSize = 13.sp, lineHeight = 18.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

/**
 * Continuous time coverage of a course, as Ardot P41/P39b show it: "08:00–09:40".
 * Adjacent periods overlap in real timetables (period 1 ends 08:45, period 2 starts 08:55),
 * so the union is reported as first start to last end rather than one range per period.
 * A gap between non-adjacent periods keeps them as separate ranges -- never a filled span.
 */
internal fun coursePeriodTimes(course: ScheduleCourse, periods: List<SchedulePeriod>): String {
    val ranges = runOfPeriods(course.periods).mapNotNull { (start, span) ->
        val first = periods.firstOrNull { it.number == start } ?: return@mapNotNull null
        val last = periods.firstOrNull { it.number == start + span - 1 } ?: first
        "${first.start}–${last.end}"
    }
    return ranges.joinToString(" · ")
}

internal fun scheduleUiWeeks(snapshot: ScheduleSnapshot?, selectedWeek: Int): List<Int> {
    val known = snapshot?.courses.orEmpty().flatMap { it.weeks } + snapshot?.weekMondays.orEmpty().keys + listOf(selectedWeek)
    // The domain calendar contract accepts 0..99. Keep all intermediate empty weeks selectable.
    return (0..(known.filter { it in 0..99 }.maxOrNull() ?: 1)).toList()
}

/**
 * The academic service returns a long history of terms (the school has run this system for
 * years), which makes the selector unusable. The design only needs "this term, one before, one
 * after", so the list is trimmed around the service's own default term.
 *
 * Trimming is by the default term's position in the service's ordered list, not by parsing
 * "2025-2026 第1学期" semantics: the service owns that ordering, and its selected term is the
 * only one it explicitly marks as current. If no term is marked default the whole list is kept
 * rather than guessing a centre and hiding a term the user may need.
 */
internal fun scheduleVisibleTerms(terms: List<ScheduleTerm>, radius: Int = 1): List<ScheduleTerm> {
    val centre = terms.indexOfFirst { it.isDefault }
    if (centre < 0) return terms
    val from = (centre - radius).coerceAtLeast(0)
    val to = (centre + radius).coerceAtMost(terms.lastIndex)
    return terms.subList(from, to + 1).toList()
}

@Composable
internal fun ScheduleCourseCard(course: ScheduleCourse, periods: List<SchedulePeriod>, onOpen: () -> Unit) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val accent = when ((course.id.hashCode().toUInt() % 3u).toInt()) {
        1 -> if (dark) AppUiTokens.PositiveDark else AppUiTokens.Positive
        2 -> if (dark) AppUiTokens.WarningDark else AppUiTokens.Warning
        else -> MiuixTheme.colorScheme.primary
    }
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding), showIndication = true, onClick = onOpen,
        colors = CardDefaults.defaultColors(color = accent.copy(alpha = if (dark) 0.15f else 0.08f))) {
        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap / 2)) {
            Text(course.title.ifBlank { AppText.Common.UNTITLED }, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(AppText.Schedule.dayLabel(course.dayOfWeek) + " · " + AppText.Schedule.periodList(course.periods.joinToString(", ").ifBlank { AppText.Common.NOT_RETURNED }),
                fontSize = 13.sp, color = accent)
            Text(coursePeriodTimes(course, periods).ifBlank { AppText.Common.NOT_RETURNED }, fontSize = 13.sp)
            if (course.location.isNotBlank()) Text(course.location, fontSize = 13.sp)
            if (course.teacher.isNotBlank()) Text(course.teacher, fontSize = 13.sp)
        }
    }
}

internal fun LazyListScope.scheduleStatusItems(state: ScheduleState, onUpdateCredentials: () -> Unit) {
    if (state.storageFailure) item("schedule-storage-warning") { InfoBanner(AppText.Schedule.STORAGE_FAILURE, tone = UiTone.Warning) }
    if (state.reauthenticating) item("schedule-reauth") { ScheduleProgress(AppText.Schedule.REAUTHENTICATING) }
    else if (state.busy && state.snapshot != null) item("schedule-syncing") { ScheduleProgress(AppText.Schedule.SYNCING) }
    state.message?.takeIf(String::isNotBlank)?.let { message -> item("schedule-message") {
        InfoBanner(message, title = if (state.snapshot != null) AppText.Schedule.CACHE_RETAINED_HINT else AppText.Common.READ_FAILED,
            tone = UiTone.Warning)
    } }
    if (state.needsCredentials) item("schedule-needs-credentials") {
        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
            InfoBanner(AppText.Schedule.CREDENTIALS_REQUIRED, tone = UiTone.Warning)
            PrimaryAction(AppText.Schedule.UPDATE_CREDENTIALS, onClick = onUpdateCredentials, enabled = !state.busy)
        }
    }
    state.snapshot?.let { snapshot ->
        if (!snapshot.complete) item("schedule-partial") { InfoBanner(AppText.Schedule.PARTIAL_HINT, tone = UiTone.Warning) }
        if (snapshot.warnings.isNotEmpty()) item("schedule-warnings-title") { SectionTitle(AppText.Schedule.WARNINGS) }
        snapshot.warnings.forEachIndexed { index, warning -> item("schedule-warning:$index") { InfoBanner(warning, tone = UiTone.Warning) } }
    }
}
