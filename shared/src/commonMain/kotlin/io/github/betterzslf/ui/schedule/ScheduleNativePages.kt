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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
    /** Result of the in-app live-card test; cleared whenever the screen is re-entered. */
    var testNotice by remember { mutableStateOf<String?>(null) }
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
            // First item on the page on purpose: this exists to answer "what will my ROM actually
            // draw", which is independent of reminders being enabled or an account being bound.
            item("reminders-live-test") { SchedulePanel {
                InfoBanner(vendorLiveMode(capabilities.vendor),
                    title = AppText.Schedule.LIVE_MODE_TITLE, tone = UiTone.Neutral)
                Text(AppText.Schedule.LIVE_TEST_HINT, fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                PrimaryAction(AppText.Schedule.LIVE_TEST_ACTION, onClick = {
                    val posted = native.testLiveNotification()
                    testNotice = if (posted) AppText.Schedule.LIVE_TEST_POSTED else AppText.Schedule.LIVE_TEST_FAILED
                    native.refreshCapabilities()
                })
                if (!capabilities.notificationsGranted) {
                    InfoBanner(AppText.Schedule.NATIVE_BLOCKED, tone = UiTone.Warning)
                    TextButton(AppText.Schedule.REQUEST_PERMISSION, onClick = { request(native::requestNotificationPermission) })
                }
                testNotice?.let { message -> InfoBanner(message,
                    tone = if (message == AppText.Schedule.LIVE_TEST_POSTED) UiTone.Positive else UiTone.Warning) }
                // Flyme's payload is reverse engineered, so rather than guess once, post every
                // combination and let one screenshot decide. Only shown where that path exists.
                if (capabilities.vendor == ScheduleVendor.MEIZU) {
                    Text(AppText.Schedule.LIVE_MATRIX_HINT, fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    PrimaryAction(AppText.Schedule.LIVE_MATRIX_ACTION, onClick = {
                        val posted = native.testVendorMatrix()
                        testNotice = AppText.Schedule.liveMatrixResult(posted, 8)
                        native.refreshCapabilities()
                    })
                    // One button per variant: the all-at-once run shows which payloads are
                    // accepted, but comparing two candidate layouts needs one card on screen.
                    val labels = remember(capabilities.vendor) { native.vendorVariantLabels() }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        labels.forEachIndexed { index, label ->
                            TextButton(label, onClick = {
                                val ok = native.testVendorVariant(index)
                                testNotice = AppText.Schedule.liveMatrixResult(if (ok) 1 else 0, 1)
                                native.refreshCapabilities()
                            }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    // A transition cannot be judged from one frame, so the same synthetic day is
                    // posted at three moments: mid-class, in the break, and after school.
                    Text(AppText.Schedule.LIVE_TRANSITION_ACTION, fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            AppText.Schedule.LIVE_TRANSITION_ACTION_A to -20L,
                            AppText.Schedule.LIVE_TRANSITION_ACTION_B to 5L,
                            AppText.Schedule.LIVE_TRANSITION_ACTION_C to 120L,
                        ).forEach { (label, minutes) ->
                            TextButton(label, onClick = {
                                val ok = native.testLiveTransition(minutes)
                                testNotice = if (ok) label else AppText.Schedule.LIVE_TRANSITION_NONE
                                native.refreshCapabilities()
                            }, modifier = Modifier.weight(1f))
                        }
                    }
                    PrimaryAction(AppText.Schedule.LIVE_COUNTDOWN_PROBE_ACTION, onClick = {
                        val ok = native.testVendorCountdown()
                        testNotice = AppText.Schedule.liveMatrixResult(if (ok) 1 else 0, 1)
                        native.refreshCapabilities()
                    })
                    TextButton(AppText.Schedule.LIVE_MATRIX_CLEAR, onClick = {
                        native.clearVendorMatrix()
                        testNotice = null
                    })
                }
            } }
            if (capabilities.batteryRestricted || capabilities.vendor != ScheduleVendor.AOSP) {
                item("reminders-vendor") { InfoBanner(vendorHint(capabilities.vendor),
                    title = AppText.Schedule.BATTERY_OPTIMIZATION, tone = UiTone.Neutral) }
            }
            // Capsule wording: only meaningful on a ROM whose pill we draw, so it is shown solely
            // where a vendor island exists.
            if (capabilities.vendor != ScheduleVendor.AOSP) {
                item("reminders-capsule-text") { Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues()) {
                    SwitchPreference(checked = state.capsuleLongText,
                        title = AppText.Schedule.CAPSULE_TEXT_TITLE,
                        summary = AppText.Schedule.CAPSULE_TEXT_SUMMARY,
                        enabled = state.owner != null && !state.busy && !applying,
                        onCheckedChange = { long -> if (!applying) {
                            applying = true
                            scope.launch { try { runtime.setCapsuleLongText(long) } finally { applying = false } }
                        } })
                } }
            }
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

/** Which live-notification mechanism this ROM will use, in the user's words. */
private fun vendorLiveMode(vendor: ScheduleVendor): String = when (vendor) {
    ScheduleVendor.XIAOMI -> AppText.Schedule.VENDOR_MODE_XIAOMI
    ScheduleVendor.VIVO -> AppText.Schedule.VENDOR_MODE_VIVO
    ScheduleVendor.MEIZU -> AppText.Schedule.VENDOR_MODE_MEIZU
    else -> AppText.Schedule.VENDOR_MODE_STANDARD
}
