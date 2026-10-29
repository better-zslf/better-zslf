package io.github.betterzslf.ui.devtools

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.core.log.LogEntry
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.ConfirmDialog
import io.github.betterzslf.ui.EmptyCard
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.PageList
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionTitle
import io.github.betterzslf.ui.UiTone
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val VISIBLE_LOG_LIMIT: Int = DEBUG_LOG_WINDOW_LIMIT

/** Retained helper signature; non-positive requests are empty and oversized requests stay capped. */
internal fun visibleEntries(entries: List<LogEntry>, limit: Int = VISIBLE_LOG_LIMIT): List<LogEntry> =
    debugLogWindow(entries, limit = limit)

@Composable
fun DebugSettingsScreen(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    entries: List<LogEntry>,
    exportText: String,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    var levelFilter by remember { mutableIntStateOf(0) }
    val filter = when (levelFilter) {
        1 -> DebugLogFilter.ISSUES
        2 -> DebugLogFilter.ERRORS
        else -> DebugLogFilter.ALL
    }
    val shown = remember(entries, filter) { debugLogWindow(entries, filter) }
    val canExport = exportText.isNotEmpty()
    // A cleared list must not reopen an old confirmation when later records arrive.
    LaunchedEffect(entries.isEmpty()) { if (entries.isEmpty()) confirmClear = false }
    Screen(AppText.DevTools.TITLE, onBack, actions = {
        Badge(modifier = Modifier.padding(end = AppUiTokens.PagePadding),
            containerColor = if (enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceContainer,
            contentColor = if (enabled) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurfaceVariantSummary) {
            Text(if (enabled) AppText.DevTools.RECORDING_LABEL else AppText.DevTools.RECORDING_OFF_LABEL)
        }
    }) { padding ->
        // PageList is the only vertical LazyColumn; log rows join this same LazyListScope.
        PageList(padding) {
            item("debug-mode") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(0.dp)) {
                    SwitchPreference(
                        title = AppText.DevTools.DEBUG_MODE_TITLE,
                        summary = AppText.DevTools.DEBUG_MODE_SUMMARY + "\n" + AppText.DevTools.DEFAULT_OFF_HINT,
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                    )
                }
            }
            item("debug-filter") {
                // A separate Miuix segmented control, as in P36: no card clipping or competing
                // inside margins. Let all three tabs share narrow layouts instead of overflowing.
                TabRowWithContour(
                    tabs = listOf(AppText.DevTools.FILTER_ALL, AppText.DevTools.FILTER_ISSUES, AppText.DevTools.FILTER_ERRORS),
                    selectedTabIndex = levelFilter,
                    onTabSelected = { levelFilter = it.coerceIn(0, 2) },
                    minWidth = 0.dp,
                    itemSpacing = 4.dp,
                    height = 44.dp,
                    cornerRadius = 22.dp,
                )
            }
            item("debug-local-only") { InfoBanner(AppText.DevTools.LOCAL_ONLY_HINT, tone = UiTone.Neutral) }
            item("debug-export") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(0.dp)) {
                    ArrowPreference(title = AppText.DevTools.EXPORT,
                        summary = AppText.DevTools.logSummary(entries.size, exportText.length),
                        enabled = canExport,
                        onClick = { if (exportText.isNotEmpty()) onExport() })
                    HorizontalDivider(modifier = Modifier.padding(horizontal = AppUiTokens.CardPadding))
                    TextButton(AppText.DevTools.CLEAR, enabled = entries.isNotEmpty(),
                        modifier = Modifier.padding(horizontal = AppUiTokens.CardPadding, vertical = 4.dp),
                        onClick = { confirmClear = true })
                }
            }
            item("debug-log-title") { SectionTitle(AppText.DevTools.LOG_TITLE) }
            if (entries.size > VISIBLE_LOG_LIMIT) {
                item("debug-window-hint") {
                    InfoBanner(AppText.DevTools.TRUNCATED_HINT.replace("{0}", VISIBLE_LOG_LIMIT.toString()), tone = UiTone.Neutral)
                }
            }
            when {
                entries.isEmpty() -> item("debug-log-empty") { EmptyCard(AppText.DevTools.LOG_EMPTY) }
                shown.isEmpty() -> item("debug-filter-empty") { EmptyCard(AppText.DevTools.FILTER_EMPTY) }
                else -> debugLogRows(shown)
            }
        }
        ConfirmDialog(confirmClear && entries.isNotEmpty(), AppText.DevTools.CLEAR_CONFIRM_TITLE,
            AppText.DevTools.CLEAR_CONFIRM_DESCRIPTION, { confirmClear = false }) {
            if (confirmClear) {
                confirmClear = false
                onClear()
            }
        }
    }
}
