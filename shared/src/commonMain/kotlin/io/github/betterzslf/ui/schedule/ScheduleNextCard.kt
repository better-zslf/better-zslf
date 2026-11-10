package io.github.betterzslf.ui.schedule

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.feature.schedule.ScheduleOccurrence
import io.github.betterzslf.feature.schedule.SchedulePeriod
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.scheduleCurrentOccurrences
import io.github.betterzslf.feature.schedule.scheduleLiveMinutes
import io.github.betterzslf.feature.schedule.scheduleNextOccurrence
import io.github.betterzslf.feature.schedule.scheduleOccurrences
import io.github.betterzslf.feature.schedule.scheduleTodayOccurrences
import io.github.betterzslf.ui.AppUiTokens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * The "下一节 / 今日课程" focus card Ardot P00 draws.
 *
 * DESIGN.md §1.2 deliberately does NOT add a separate 首页 or a fifth tab, but explicitly allows
 * P00's content hierarchy ("下一节重点卡、今日课程分组") as a reference. So this card lives on the
 * existing 课程表 tab root, above the week board: same information, no information-architecture
 * change, no new route. It reuses the already-shipped, unit-tested occurrence resolver — no new
 * date/period inference, no invented data. When the calendar cannot place today, or the snapshot
 * is incomplete, the card is simply not shown rather than guessing.
 */
internal data class ScheduleFocus(
    val running: ScheduleOccurrence?,
    val next: ScheduleOccurrence?,
    val todayCount: Int,
    val remainingMinutes: Long,
    val startsInMinutes: Long,
)

/** Pure resolver so the card's state selection can be unit tested without Compose or a device. */
internal fun scheduleFocus(
    snapshot: ScheduleSnapshot,
    periods: List<SchedulePeriod>,
    nowMillis: Long,
): ScheduleFocus {
    // A sliding window around now: yesterday..+14 days, identical to the widget preview. Only
    // complete snapshots with explicit calendar mappings produce occurrences (scheduleOccurrences).
    val occurrences = scheduleOccurrences(snapshot, periods, nowMillis - 86_400_000L, nowMillis + 14 * 86_400_000L)
    val running = scheduleCurrentOccurrences(occurrences, nowMillis).firstOrNull()
    val next = scheduleNextOccurrence(occurrences, nowMillis)
    return ScheduleFocus(
        running = running,
        next = next,
        todayCount = scheduleTodayOccurrences(occurrences, nowMillis).map { it.courseId }.distinct().size,
        remainingMinutes = running?.let { scheduleLiveMinutes(it.endsAtMillis - nowMillis) } ?: 0L,
        startsInMinutes = next?.let { scheduleLiveMinutes(it.startsAtMillis - nowMillis) } ?: 0L,
    )
}

/**
 * One emphasis card. [focus] is resolved by the caller from the same `now` clock that drives the
 * week board, so it refreshes on the minute with no per-second recomposition (§6.2).
 */
@Composable
internal fun ScheduleNextCard(
    focus: ScheduleFocus,
    periods: List<SchedulePeriod>,
    onOpen: (ScheduleOccurrence) -> Unit,
) {
    val highlighted = focus.running ?: focus.next ?: return
    val inClass = focus.running != null
    val status = if (inClass) AppText.Schedule.IN_PROGRESS else AppText.NativeSchedule.NEXT_HEADER
    val countdown = if (inClass) AppText.Schedule.liveRemaining(focus.remainingMinutes)
    else AppText.Schedule.liveStartsIn(focus.startsInMinutes)
    val times = coursePeriodTimes(highlighted.course.copy(periods = highlighted.periods), periods)
    val meta = listOf(
        times, highlighted.location, highlighted.teacher,
        if (focus.todayCount > 0) AppText.NativeSchedule.todayCourseCount(focus.todayCount) else "",
    ).filter { it.isNotBlank() }.joinToString(" · ")

    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding),
        // The focus card uses the theme's emphasis container (§6.2), not a page-local gradient.
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer),
        onClick = { onOpen(highlighted) },
        pressFeedbackType = PressFeedbackType.Sink,
        showIndication = true,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(status, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onPrimaryContainer)
            Text(countdown, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(6.dp))
        // §3.3: read the emphasis container's paired content role, not a pastel course tint
        // that was tuned for a light block fill and would fail on the dark primaryContainer.
        Text(highlighted.title.ifBlank { AppText.Common.UNTITLED },
            fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onPrimaryContainer)
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(meta, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
