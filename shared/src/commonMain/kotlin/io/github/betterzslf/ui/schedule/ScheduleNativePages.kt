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
import io.github.betterzslf.ui.AppCard

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
                    val posted = native.testLiveNotification(state.capsuleLongText)
                    testNotice = if (posted) AppText.Schedule.LIVE_TEST_POSTED else AppText.Schedule.LIVE_TEST_FAILED
                    native.refreshCapabilities()
                })
                if (!capabilities.notificationsGranted) {
                    InfoBanner(AppText.Schedule.NATIVE_BLOCKED, tone = UiTone.Warning)
                    TextButton(AppText.Schedule.REQUEST_PERMISSION, onClick = { request(native::requestNotificationPermission) })
                }
                testNotice?.let { message -> InfoBanner(message,
                    tone = if (message == AppText.Schedule.LIVE_TEST_POSTED) UiTone.Positive else UiTone.Warning) }
                // Two more demonstrations, kept because they answer questions a single card cannot:
                // what the break styling looks like beside the in-class one, and whether the card
                // changes cleanly when a class ends and the next begins.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(AppText.Schedule.LIVE_BREAK_ACTION, onClick = {
                        val posted = native.testBreakCard(state.capsuleLongText)
                        testNotice = if (posted) AppText.Schedule.LIVE_TEST_POSTED else AppText.Schedule.LIVE_TEST_FAILED
                        native.refreshCapabilities()
                    }, modifier = Modifier.weight(1f))
                    TextButton(AppText.Schedule.LIVE_SEQUENCE_ACTION, onClick = {
                        native.testLiveSequence(state.capsuleLongText)
                        testNotice = AppText.Schedule.LIVE_SEQUENCE_STARTED
                    }, modifier = Modifier.weight(1f))
                }
                TextButton(AppText.Schedule.LIVE_CLEAR_ALL, onClick = {
                    native.clearTestNotifications()
                    testNotice = null
                }, modifier = Modifier.fillMaxWidth())
            } }
            if (capabilities.batteryRestricted || capabilities.vendor != ScheduleVendor.AOSP) {
                item("reminders-vendor") { InfoBanner(vendorHint(capabilities.vendor),
                    title = AppText.Schedule.BATTERY_OPTIMIZATION, tone = UiTone.Neutral) }
            }
            // Fluid Cloud has its own two-level switch on ColorOS. Without this the user finds only
            // the master toggle and concludes the app does not support live notifications at all.
            if (capabilities.vendor == ScheduleVendor.OPPO || capabilities.vendor == ScheduleVendor.ONEPLUS) {
                item("reminders-fluid-cloud") { SchedulePanel {
                    InfoBanner(AppText.Schedule.VENDOR_OPPO_FLUID_HINT,
                        title = AppText.Schedule.VENDOR_MODE_OPPO, tone = UiTone.Neutral)
                    // The two failures look identical to a user but need opposite fixes, so the
                    // screen states which one this device is in rather than a generic hint.
                    val allowed = capabilities.promotedAllowed
                    val shapeOk = capabilities.lastCardPromotable
                    val status = when {
                        !allowed -> AppText.Schedule.PROMOTED_SETTINGS_BLOCKED
                        !shapeOk -> AppText.Schedule.PROMOTED_SETTINGS_SHAPE_BAD
                        else -> AppText.Schedule.PROMOTED_SETTINGS_ALLOWED
                    }
                    InfoBanner(status, title = AppText.Schedule.PROMOTED_SETTINGS_TITLE,
                        tone = if (allowed && shapeOk) UiTone.Positive else UiTone.Warning)
                    TextButton(AppText.Schedule.PROMOTED_SETTINGS_TITLE, onClick = {
                        request(native::openPromotedNotificationSettings)
                    }, modifier = Modifier.fillMaxWidth())
                } }
            }
            // Capsule wording.
            //
            // Shown everywhere, not only on a vendor ROM: the status-bar chip exists on plain AOSP
            // as well (it is what setShortCriticalText feeds), and the preference is harmless when
            // nothing reads it. Gating it on the vendor family made the switch impossible to reach
            // in the mock build, which is where it most needs testing.
            item("reminders-capsule-text") { AppCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues()) {
                    SwitchPreference(checked = state.capsuleLongText,
                        title = AppText.Schedule.CAPSULE_TEXT_TITLE,
                        summary = AppText.Schedule.CAPSULE_TEXT_SUMMARY,
                        enabled = state.owner != null && !state.busy && !applying,
                        onCheckedChange = { long -> if (!applying) {
                            applying = true
                            scope.launch {
                                try {
                                    val saved = runtime.setCapsuleLongText(long)
                                    // Report the concrete cause, not just "failed": the switch was
                                    // silent for two rounds precisely because every failure looked
                                    // the same from here.
                                    notice = if (saved) null else AppText.Schedule.capsuleUnsaved(
                                        runtime.capsuleFailure)
                                } finally { applying = false }
                            }
                    } })
            } }
            item("reminders-switch") { AppCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues()) {
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
            item("reminders-capabilities") { AppCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues()) {
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
    // OPPO maps Android's Live Updates onto 流体云, so the mechanism is the standard one but the
    // name the user needs to look for is different. Saying "标准实况通知" here would send them
    // hunting for a setting ColorOS does not name that way.
    ScheduleVendor.OPPO -> AppText.Schedule.VENDOR_MODE_OPPO
    ScheduleVendor.ONEPLUS -> AppText.Schedule.VENDOR_MODE_OPPO
    else -> AppText.Schedule.VENDOR_MODE_STANDARD
}
