package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime
import io.github.betterzslf.platform.ScheduleVendor
import io.github.betterzslf.platform.ScheduleWidgetKind
import io.github.betterzslf.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun ScheduleReminderPage(runtime: ScheduleRuntime, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val state by runtime.state.collectAsState()
    val native = runtime.native
    val capabilities by native.capabilities.collectAsState()
    val scope = rememberCoroutineScope()
    var applying by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(native) { native.refreshCapabilities() }
    fun request(action: () -> Boolean) {
        notice = if (action()) null else AppText.Schedule.ACTION_REQUEST_FAILED
        native.refreshCapabilities()
    }
    Screen(AppText.Schedule.REMINDERS_TITLE, onBack, actions = {
        TextButton(AppText.Common.REFRESH, onClick = native::refreshCapabilities)
    }) { padding -> PageList(padding) {
        if (!native.supported) item("reminders-unsupported") { InfoBanner(AppText.Schedule.NATIVE_UNSUPPORTED, tone = UiTone.Neutral) }
        else {
            if (state.owner == null) item("reminders-owner") { InfoBanner(AppText.Schedule.OWNER_REQUIRED, tone = UiTone.Warning) }
            item("reminders-switch") { Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues()) {
                SwitchPreference(checked = state.remindersEnabled,
                    title = AppText.Schedule.REMINDERS_SWITCH, summary = AppText.Schedule.REMINDER_RULES,
                    enabled = state.owner != null && !state.busy && !applying && (state.boundAccount != null || state.remindersEnabled),
                    onCheckedChange = { enabled -> if (!applying) {
                        applying = true
                        scope.launch { try { runtime.setReminders(enabled, state.reminderLeadMinutes) } finally { applying = false } }
                    } })
            } }
            item("reminders-lead-time") { SchedulePanel {
                Text(AppText.Schedule.leadMinutes(state.reminderLeadMinutes))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                    listOf(0, 5, 10, 15, 30).forEach { minutes -> TextButton(AppText.Schedule.leadMinutes(minutes),
                        enabled = state.owner != null && state.boundAccount != null && !state.busy && !applying,
                        onClick = { if (!applying) {
                            applying = true
                            scope.launch { try { runtime.setReminders(state.remindersEnabled, minutes) } finally { applying = false } }
                        } }) }
                }
            } }
            item("reminders-capabilities") { Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues()) {
                ArrowPreference(AppText.Schedule.NOTIFICATION_PERMISSION,
                    summary = if (capabilities.notificationsGranted) AppText.Schedule.NATIVE_GRANTED else AppText.Schedule.NATIVE_BLOCKED,
                    onClick = { request { if (capabilities.notificationsGranted) native.openNotificationSettings() else native.requestNotificationPermission() } })
                ArrowPreference(AppText.Schedule.EXACT_ALARM,
                    summary = if (capabilities.exactAlarmsAllowed) AppText.Schedule.NATIVE_GRANTED else AppText.Schedule.EXACT_DELAYED,
                    onClick = { request(native::openExactAlarmSettings) })
                if (capabilities.batteryRestricted) ArrowPreference(AppText.Schedule.BATTERY_OPTIMIZATION,
                    summary = AppText.Schedule.BATTERY_RESTRICTED, onClick = { request(native::openBatteryOptimizationSettings) })
                ArrowPreference(AppText.Schedule.PERIODS_TITLE,
                    onClick = { onOpen(scheduleRoute(ScheduleRoutes.PERIODS)) })
            } }
            if (!capabilities.notificationsGranted) item("reminders-permission-actions") {
                SchedulePanel {
                    InfoBanner(AppText.Schedule.NATIVE_BLOCKED, title = AppText.Schedule.NOTIFICATION_PERMISSION, tone = UiTone.Warning)
                    TextButton(AppText.Schedule.REQUEST_PERMISSION, onClick = { request(native::requestNotificationPermission) })
                    TextButton(AppText.Schedule.OPEN_SYSTEM_SETTINGS, onClick = { request(native::openNotificationSettings) })
                }
            }
            if (!capabilities.exactAlarmsAllowed) item("reminders-not-exact") { InfoBanner(AppText.Schedule.EXACT_DELAYED, tone = UiTone.Warning) }
            // Vendor battery managers are invisible to public APIs, so name the exact menu the user
            // needs instead of leaving a generic "restricted" line they cannot act on.
            if (capabilities.batteryRestricted || capabilities.vendor != ScheduleVendor.AOSP) {
                item("reminders-vendor") { InfoBanner(vendorHint(capabilities.vendor),
                    title = AppText.Schedule.BATTERY_OPTIMIZATION, tone = UiTone.Neutral) }
            }
            if (state.pendingReminderCourses > 0) item("reminders-pending") { InfoBanner(AppText.Schedule.pendingReminders(state.pendingReminderCourses), tone = UiTone.Warning) }
            if (state.sourceSnapshot?.complete == false) item("reminders-partial") { InfoBanner(AppText.Schedule.PARTIAL_HINT, tone = UiTone.Warning) }
            item("reminders-source") { SchedulePanel {
                Text(AppText.Schedule.reminderSource(state.sourceSnapshot?.term?.name ?: AppText.Common.NOT_PROVIDED))
                ScheduleSyncStamp(state.sourceSnapshot?.syncedAtMillis ?: 0)
            } }
            state.message?.takeIf(String::isNotBlank)?.let { message -> item("reminders-message") { InfoBanner(message, tone = UiTone.Warning) } }
            notice?.let { message -> item("reminders-local-message") { InfoBanner(message, tone = UiTone.Warning) } }
            if (applying) item("reminders-applying") { ScheduleProgress(AppText.Common.SUBMITTING) }
        }
    } }
}

@Composable
internal fun ScheduleWidgetPage(runtime: ScheduleRuntime, onBack: () -> Unit) {
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

/** The concrete settings path for this vendor's battery manager. */
private fun vendorHint(vendor: ScheduleVendor): String = when (vendor) {
    ScheduleVendor.XIAOMI -> AppText.Schedule.VENDOR_XIAOMI_HINT
    ScheduleVendor.HUAWEI -> AppText.Schedule.VENDOR_HUAWEI_HINT
    ScheduleVendor.OPPO -> AppText.Schedule.VENDOR_OPPO_HINT
    ScheduleVendor.VIVO -> AppText.Schedule.VENDOR_VIVO_HINT
    ScheduleVendor.MEIZU -> AppText.Schedule.VENDOR_MEIZU_HINT
    ScheduleVendor.SAMSUNG -> AppText.Schedule.VENDOR_SAMSUNG_HINT
    else -> AppText.Schedule.VENDOR_GENERIC_HINT
}
