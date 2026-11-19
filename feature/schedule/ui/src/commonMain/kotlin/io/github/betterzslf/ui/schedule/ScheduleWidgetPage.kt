package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime
import io.github.betterzslf.platform.ScheduleWidgetKind
import io.github.betterzslf.ui.*
import top.yukonga.miuix.kmp.basic.*

@Composable
public fun ScheduleWidgetPage(runtime: ScheduleRuntime, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val native = runtime.native
    val capabilities by native.capabilities.collectAsState()
    val now = scheduleDisplayClock()
    var notice by remember { mutableStateOf<String?>(null) }
    val source = scheduleWidgetSnapshot(state)
    val today = scheduleSchoolDate(now)
    val occurrences = remember(source, state.periods, now) {
        source?.let { scheduleOccurrences(it, state.periods, now - 86_400_000L, now + 14 * 86_400_000L) }.orEmpty()
    }
    val todayCourses = scheduleTodayOccurrences(occurrences, now)
    val previewCourses = todayCourses.take(3)
    val next = scheduleNextOccurrence(occurrences, now)
    val todayWeek = source?.let { scheduleWeekForDate(it, today) }
    val todayDay = if (source != null && todayWeek != null) (1..7).firstOrNull { scheduleDateForWeek(source, todayWeek, it) == today } else null
    val mappedToday = todayWeek != null
    val canConcludeTodayEmpty = source != null && todayWeek != null && todayDay != null && scheduleCanConcludeDayEmpty(source, todayWeek, todayDay)
    val pendingCount = source?.let(::scheduleUndatedCourseCount) ?: 0
    LaunchedEffect(native) { native.refreshCapabilities() }
    Screen(AppText.Schedule.WIDGET_TITLE, onBack, actions = { TextButton(AppText.Common.REFRESH, onClick = native::refreshCapabilities) }) { padding ->
        PageList(padding) {
            item("widget-explanation") { InfoBanner(AppText.Schedule.WIDGET_HINT, tone = UiTone.Neutral) }
            if (!native.supported) item("widget-unsupported") { InfoBanner(AppText.Schedule.NATIVE_UNSUPPORTED, tone = UiTone.Neutral) }
            else {
                item("widget-preview-disclaimer") { InfoBanner(AppText.Schedule.WIDGET_PREVIEW_HINT, tone = UiTone.Neutral) }
                when {
                    state.owner == null -> item("widget-owner") { InfoBanner(AppText.Schedule.OWNER_REQUIRED, tone = UiTone.Warning) }
                    state.boundAccount == null -> item("widget-unbound") { InfoBanner(AppText.Schedule.INTRO_BODY, tone = UiTone.Neutral) }
                    source == null -> item("widget-unsynced") { InfoBanner(AppText.Schedule.NO_CACHE, tone = UiTone.Neutral) }
                    else -> {
                        item("widget-synced") { ScheduleSyncStamp(source.syncedAtMillis) }
                        if (state.needsCredentials) item("widget-needs-login") { InfoBanner(AppText.Schedule.CREDENTIALS_REQUIRED, tone = UiTone.Warning) }
                        state.message?.takeIf(String::isNotBlank)?.let { message -> item("widget-cached-error") { InfoBanner(message, title = AppText.Schedule.CACHE_RETAINED_HINT, tone = UiTone.Warning) } }
                        if (!source.complete) item("widget-partial") { InfoBanner(AppText.Schedule.PARTIAL_HINT, tone = UiTone.Warning) }
                        if (pendingCount > 0) item("widget-pending") { InfoBanner(AppText.Schedule.pendingReminders(pendingCount), tone = UiTone.Warning) }
                        if (!mappedToday) item("widget-unmapped-today") { InfoBanner(AppText.Schedule.TODAY_UNMAPPED, tone = UiTone.Warning) }
                        item("widget-compact-title") { SectionTitle(AppText.Schedule.WIDGET_COMPACT) }
                        item("widget-compact") { SchedulePanel {
                            Text(AppText.Schedule.NEXT_COURSE)
                            if (next != null) {
                                Text(next.title)
                                Text(next.date + " · " + coursePeriodTimes(next.course.copy(periods = next.periods), state.periods))
                                Text(next.location.ifBlank { AppText.Common.NOT_RETURNED })
                            } else Text(AppText.Common.NOT_PROVIDED)
                        } }
                        item("widget-wide-title") { SectionTitle(AppText.Schedule.WIDGET_WIDE) }
                        item("widget-wide") { SchedulePanel {
                            Text(today.toString())
                            if (previewCourses.isEmpty()) {
                                Text(if (!mappedToday) AppText.Schedule.TODAY_UNMAPPED else if (!canConcludeTodayEmpty) AppText.Common.RESULT_NEEDS_REVIEW else AppText.Schedule.DAY_EMPTY)
                            } else previewCourses.forEach { occurrence ->
                                Column {
                                    Text(occurrence.title)
                                    Text(coursePeriodTimes(occurrence.course.copy(periods = occurrence.periods), state.periods))
                                    if (occurrence.location.isNotBlank()) Text(occurrence.location)
                                }
                            }
                            if (previewCourses.isNotEmpty()) Text(AppText.Schedule.displayedCourseCount(previewCourses.map { it.courseId }.distinct().size))
                        } }
                    }
                }
                if (capabilities.pinWidgetsSupported && state.owner != null) {
                    item("widget-add-today") { PrimaryAction(AppText.Schedule.PIN_WIDGET + " · " + AppText.Schedule.WIDGET_WIDE,
                        onClick = { notice = if (native.requestPinWidget(ScheduleWidgetKind.TODAY)) AppText.Schedule.PIN_REQUESTED else AppText.Schedule.ACTION_REQUEST_FAILED }) }
                    item("widget-add-next") { PrimaryAction(AppText.Schedule.PIN_WIDGET + " · " + AppText.Schedule.WIDGET_COMPACT,
                        onClick = { notice = if (native.requestPinWidget(ScheduleWidgetKind.NEXT)) AppText.Schedule.PIN_REQUESTED else AppText.Schedule.ACTION_REQUEST_FAILED }) }
                } else item("widget-manual-add") { InfoBanner(AppText.Schedule.PIN_UNSUPPORTED, tone = UiTone.Neutral) }
                notice?.let { message -> item("widget-local-message") { InfoBanner(message, tone = UiTone.Neutral) } }
            }
        }
    }
}
