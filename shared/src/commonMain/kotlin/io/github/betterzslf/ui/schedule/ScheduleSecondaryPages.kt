package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime
import io.github.betterzslf.feature.settings.SettingsStore
import io.github.betterzslf.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.betterzslf.ui.AppCard
import io.github.betterzslf.ui.appSquircleClip

@Composable
internal fun ScheduleManagementPage(
    runtime: ScheduleRuntime,
    settings: SettingsStore,
    onOpen: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val state by runtime.state.collectAsState()
    val current by settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var confirmUnbind by remember { mutableStateOf(false) }
    var unbinding by remember { mutableStateOf(false) }
    var notice by remember(state.owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(state.owner, state.boundAccount) { confirmUnbind = false }
    val actionable = state.owner != null && !state.busy && !unbinding
    Screen(AppText.Schedule.MANAGE, onBack) { padding ->
        PageList(padding) {
            // Ardot P42 puts the account identity first, with the password state as a green
            // reassurance line beneath it rather than a second card.
            item("management-account") { AppCard(modifier = Modifier.fillMaxWidth(),
                cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(AppText.Schedule.ACCOUNT_FIELD, fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(state.boundAccount?.let(::maskedAcademicAccount) ?: AppText.Common.NOT_PROVIDED,
                        fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (state.boundAccount != null) {
                        Text(AppText.Schedule.PASSWORD_STATE_SAVED, fontSize = 12.sp,
                            color = if (MiuixTheme.colorScheme.surface.luminance() < 0.5f) AppUiTokens.PositiveDark else AppUiTokens.Positive)
                    }
                    ScheduleSyncStamp(state.snapshot?.syncedAtMillis ?: 0)
                }
            } }
            if (state.storageFailure) item("management-storage") { InfoBanner(AppText.Schedule.STORAGE_FAILURE, tone = UiTone.Warning) }
            state.message?.takeIf(String::isNotBlank)?.let { message -> item("management-message") { InfoBanner(message, tone = UiTone.Warning) } }
            notice?.let { message -> item("management-local-message") { InfoBanner(message, tone = UiTone.Warning) } }
            item("management-actions") { AppCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues()) {
                ArrowPreference(if (state.boundAccount == null) AppText.Schedule.BIND else AppText.Schedule.UPDATE_CREDENTIALS,
                    enabled = actionable && !state.storageFailure,
                    onClick = { onOpen(scheduleRoute(ScheduleRoutes.BIND, if (state.boundAccount != null) "password" else "")) })
                ArrowPreference(AppText.Schedule.SYNC_COURSE_TABLE, summary = scheduleSyncTime(state.snapshot?.syncedAtMillis ?: 0),
                    enabled = actionable && state.boundAccount != null,
                    onClick = { scope.launch { runtime.refresh() } })
                ArrowPreference(AppText.Schedule.REVERIFY, enabled = actionable && state.boundAccount != null,
                    onClick = { onOpen(scheduleRoute(ScheduleRoutes.BIND, "password")) })
                ArrowPreference(AppText.Schedule.CHANGE_ACCOUNT, enabled = actionable && !state.storageFailure,
                    onClick = { onOpen(scheduleRoute(ScheduleRoutes.BIND)) })
                ArrowPreference(AppText.Schedule.PERIODS_TITLE, enabled = actionable,
                    onClick = { onOpen(scheduleRoute(ScheduleRoutes.PERIODS)) })
                if (runtime.native.supported) {
                    ArrowPreference(AppText.Schedule.REMINDERS_TITLE, enabled = actionable,
                        onClick = { onOpen(scheduleRoute(ScheduleRoutes.REMINDERS)) })
                    ArrowPreference(AppText.Schedule.WIDGET_TITLE, enabled = actionable,
                        onClick = { onOpen(scheduleRoute(ScheduleRoutes.WIDGET)) })
                }
            } }
            if (state.sourceTermId != null) item("management-source-term") {
                Text(AppText.Schedule.reminderSource(state.terms.firstOrNull { it.id == state.sourceTermId }?.name ?: AppText.Common.UNKNOWN))
            }
            item("management-visibility-heading") { SectionTitle(AppText.Schedule.DISPLAY_SECTION_TITLE) }
            item("management-visibility") {
                AppCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp)) {
                    // Hiding the last visible day is refused rather than silently undone: a board
                    // that ignores the switch would look like a bug.
                    val visibleCount = 7 - current.hiddenWeekdays.count { it in 1..7 }
                    (1..7).forEachIndexed { index, day ->
                        if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        val shown = day !in current.hiddenWeekdays
                        SwitchPreference(
                            title = AppText.Schedule.dayLabel(day),
                            summary = when {
                                shown -> AppText.Schedule.DAY_SHOWN_SUMMARY
                                else -> AppText.Schedule.DAY_HIDDEN_SUMMARY
                            },
                            enabled = shown || visibleCount > 1,
                            checked = shown,
                            onCheckedChange = { next ->
                                settings.update { value ->
                                    value.copy(hiddenWeekdays =
                                        if (next) value.hiddenWeekdays - day else (value.hiddenWeekdays + day).distinct())
                                }
                            },
                        )
                    }
                }
            }
            // Ardot P42 draws the destructive area as one tinted block: explanation on top,
            // then an explicit full-width confirm button inside the same surface.
            item("management-unbind") {
                Column(
                    Modifier.fillMaxWidth().appSquircleClip(AppUiTokens.CardRadius)
                        .background(MiuixTheme.colorScheme.error.copy(alpha = 0.10f))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(AppText.Schedule.UNBIND, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.error)
                    Text(AppText.Schedule.UNBIND_CONFIRM_HINT, fontSize = 12.sp, lineHeight = 18.sp,
                        color = MiuixTheme.colorScheme.error.copy(alpha = 0.80f))
                    Button(
                        onClick = { confirmUnbind = true },
                        enabled = actionable && state.boundAccount != null,
                        modifier = Modifier.fillMaxWidth(),
                        minHeight = AppUiTokens.ButtonHeight,
                        cornerRadius = AppUiTokens.ButtonRadius,
                        colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.surface,
                            contentColor = MiuixTheme.colorScheme.error),
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    ) { Text(AppText.Schedule.UNBIND_BUTTON, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
                }
            }
            if (unbinding) item("management-unbinding") { ScheduleProgress(AppText.Common.SUBMITTING) }
            item("management-privacy") { InfoBanner(AppText.Schedule.SECURITY_HINT, tone = UiTone.Neutral) }
        }
        ConfirmDialog(confirmUnbind, AppText.Schedule.UNBIND_CONFIRM_TITLE, AppText.Schedule.UNBIND_CONFIRM_HINT,
            { confirmUnbind = false }) {
            confirmUnbind = false
            if (!unbinding && state.boundAccount != null) {
                unbinding = true
                scope.launch { try {
                    if (runtime.unbind()) onBack() else notice = AppText.Schedule.UNBIND_FAILED
                } finally { unbinding = false } }
            }
        }
    }
}
