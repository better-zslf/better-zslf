package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One design row of the body grid: 12 periods of 35dp (Ardot 14:178 lineHeight 35). */
private val ScheduleRowHeight: Dp = 35.dp
/** Ardot 14:49 column header is 46x40 with a 1dp inner gap. */
private val ScheduleHeaderHeight: Dp = 40.dp
/** Ardot 14:177 axis is 24dp wide; the header row reserves the same gutter. */
private val ScheduleAxisWidth: Dp = 24.dp
/** Ardot 14:48 gap between the axis gutter and the first day column. */
private val ScheduleColumnGap: Dp = 4.dp

/**
 * The weekly board exactly as Ardot P39 draws it: a 24dp period axis, seven equal day
 * columns, a date row where today is highlighted, and course blocks laid over the columns
 * at their period offsets. A three-period lecture is one card, not three.
 *
 * Columns always fit the available width -- the design has no horizontal scrolling -- so a
 * narrow screen shrinks the cards rather than the board; [ScheduleRootPage] switches to the
 * day list below 360dp, where 46dp columns would no longer fit readable text.
 */
@Composable
internal fun ScheduleWeekBoard(
    snapshot: ScheduleSnapshot,
    week: Int,
    periods: List<SchedulePeriod>,
    nowMillis: Long,
    hiddenDays: Set<Int> = emptySet(),
    onOpen: (List<ScheduleCourse>) -> Unit,
) {
    val visibleDays = remember(hiddenDays) { scheduleVisibleDays(hiddenDays) }
    val todayDate = scheduleSchoolDate(nowMillis)
    val today = (1..7).firstOrNull { scheduleDateForWeek(snapshot, week, it) == todayDate }
    val nowMinutes = remember(nowMillis) { scheduleSchoolMinutes(nowMillis) }
    val blocks = remember(snapshot, week, periods, today, nowMinutes, hiddenDays) {
        scheduleBoardBlocks(snapshot, week, periods, today, nowMinutes).filter { it.day in visibleDays }
    }
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val todayColumn = MiuixTheme.colorScheme.primary.copy(alpha = if (dark) 0.22f else 0.10f)
    // Ardot 14:190 draws every non-today column fully transparent inside the white card;
    // only the today column (14:198 primarySoft) is tinted.
    val gridSurface = Color.Transparent
    val dayWeight = 1f / visibleDays.size

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ScheduleColumnGap)) {
            Spacer(Modifier.width(ScheduleAxisWidth))
            for (day in visibleDays) {
                val date = scheduleDateForWeek(snapshot, week, day)
                val isToday = day == today
                Column(
                    Modifier.weight(dayWeight).height(ScheduleHeaderHeight).clip(RoundedCornerShape(8.dp))
                        .background(if (isToday) todayColumn else gridSurface),
                    verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        AppText.Schedule.shortDayLabel(day),
                        fontSize = 12.sp,
                        color = if (isToday) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (date != null) {
                        Text(
                            AppText.Schedule.dayOfMonth(date.day),
                            fontSize = 14.sp,
                            fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isToday) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            // Period axis: fixed 24dp, one number per 35dp row, Ardot 14:177.
            Column(Modifier.width(ScheduleAxisWidth)) {
                for (period in 1..12) {
                    Box(Modifier.fillMaxWidth().height(ScheduleRowHeight), contentAlignment = Alignment.Center) {
                        Text(AppText.Schedule.axisPeriod(period), fontSize = 9.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            Spacer(Modifier.width(ScheduleColumnGap))
            // Body: the visible columns share one baseline, blocks absolutely offset by period.
            BoxWithConstraints(Modifier.weight(1f).height(ScheduleRowHeight * 12)) {
                val columnWidth = maxWidth / visibleDays.size
                Row(Modifier.fillMaxSize()) {
                    for (day in visibleDays) {
                        Box(
                            Modifier.weight(dayWeight).fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (day == today) todayColumn else gridSurface),
                        )
                    }
                }
                blocks.forEach { block ->
                    // Column order, not the ISO weekday, decides the horizontal offset.
                    val column = visibleDays.indexOf(block.day).coerceAtLeast(0)
                    ScheduleBoardCard(
                        block = block,
                        modifier = Modifier
                            .width(columnWidth)
                            .height(ScheduleRowHeight * block.spanRows)
                            .offset(x = columnWidth * column, y = ScheduleRowHeight * (block.startRow - 1))
                            .padding(horizontal = 1.dp, vertical = 1.dp),
                        periods = periods,
                        onOpen = {
                            onOpen(listOf(block.course) + blocks.filter { it.day == block.day && it.startRow == block.startRow && it.course.id != block.course.id }.map { it.course })
                        },
                    )
                }
            }
        }
    }
}

/**
 * Weekdays to draw, in ISO order. Hiding is only ever a narrowing: an empty or fully-hidden
 * selection falls back to all seven days rather than drawing an empty board, because an empty
 * board would look like "no classes" instead of "you hid everything".
 */
internal fun scheduleVisibleDays(hidden: Set<Int>): List<Int> {
    val visible = (1..7).filter { it !in hidden }
    return visible.ifEmpty { (1..7).toList() }
}

/** One course block: fill, title, and the location/room line, matching Ardot 14:191. */
@Composable
private fun ScheduleBoardCard(
    block: ScheduleBoardBlock,
    modifier: Modifier,
    periods: List<SchedulePeriod>,
    onOpen: () -> Unit,
) {
    val palette = scheduleCoursePalette(block.course, block.running)
    val shape = RoundedCornerShape(8.dp)
    val description = listOf(
        block.course.title.ifBlank { AppText.Common.UNTITLED },
        AppText.Schedule.dayLabel(block.day),
        coursePeriodTimes(block.course, periods),
        block.course.location,
        if (block.running) AppText.Schedule.IN_PROGRESS else "",
    ).filter { it.isNotBlank() }.joinToString(", ")

    Card(
        modifier = modifier.semantics { contentDescription = description },
        cornerRadius = 8.dp,
        insideMargin = PaddingValues(vertical = 4.dp, horizontal = 4.dp),
        showIndication = true,
        onClick = onOpen,
        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = palette.background),
    ) {
        Column(
            Modifier.fillMaxHeight().clip(shape),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                block.course.title.ifBlank { AppText.Common.UNTITLED },
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 12.sp,
                maxLines = if (block.spanRows >= 2) 3 else 2,
                overflow = TextOverflow.Ellipsis,
                color = palette.title,
            )
            if (block.course.location.isNotBlank()) {
                Text(
                    block.course.location,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    maxLines = if (block.spanRows >= 3) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.meta,
                )
            }
            if (block.running) {
                Text(AppText.Schedule.IN_PROGRESS, fontSize = 9.sp, lineHeight = 11.sp,
                    fontWeight = FontWeight.Medium, color = palette.meta, maxLines = 1)
            }
        }
    }
}
