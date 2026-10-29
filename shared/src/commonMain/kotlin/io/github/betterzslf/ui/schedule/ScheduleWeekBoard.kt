package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.ui.AppUiTokens
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Exactly one horizontal axis owns both the header and all seven course columns. */
@Composable
internal fun ScheduleWeekBoard(snapshot: ScheduleSnapshot, week: Int, periods: List<SchedulePeriod>, nowMillis: Long,
    onOpen: (List<ScheduleCourse>) -> Unit) {
    val rows = remember(snapshot, week) { scheduleGridRows(snapshot, week) }
    val scroll = rememberScrollState()
    val axisWidth = 68.dp
    val columnWidth = 104.dp
    val today = scheduleSchoolDate(nowMillis)
    Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Column(Modifier.width(axisWidth + columnWidth * 7)) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(axisWidth).padding(8.dp)) { Text(AppText.Schedule.PERIODS, fontSize = 12.sp) }
                for (day in 1..7) {
                    val date = scheduleDateForWeek(snapshot, week, day)
                    Column(Modifier.width(columnWidth).fillMaxHeight()
                        .background(if (date == today) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else MiuixTheme.colorScheme.surface)
                        .padding(8.dp)) {
                        Text(AppText.Schedule.dayLabel(day), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (date != null) Text(date.toString().takeLast(5), fontSize = 12.sp)
                    }
                }
            }
            rows.forEach { row ->
                HorizontalDivider()
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Column(Modifier.width(axisWidth).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(AppText.Schedule.periodLabel(row.period), fontSize = 12.sp)
                        periods.firstOrNull { it.number == row.period }?.let { period ->
                            Text(period.start, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            Text(period.end, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    row.days.forEach { courses ->
                        Column(Modifier.width(columnWidth).fillMaxHeight().heightIn(min = 76.dp).padding(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // Preview is bounded; the explicit count opens every overlapping course lazily.
                            courses.take(2).forEach { course ->
                                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.FieldRadius,
                                    insideMargin = PaddingValues(8.dp), showIndication = true,
                                    onClick = { onOpen(listOf(course) + courses.filter { it !== course }) },
                                    colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primary.copy(alpha = 0.10f))) {
                                    Text(course.title.ifBlank { AppText.Common.UNTITLED }, maxLines = 3,
                                        overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                    if (course.location.isNotBlank()) Text(course.location, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis, fontSize = 11.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                            }
                            scheduleOverlapLabels(courses.size)?.let { labels ->
                                TextButton(labels.compact, onClick = { onOpen(courses) },
                                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = labels.accessibility },
                                    minHeight = 48.dp,
                                    insideMargin = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                                    textStyle = MiuixTheme.textStyles.button.copy(fontSize = 13.sp, lineHeight = 18.sp))
                            }
                        }
                    }
                }
            }
        }
    }
}
