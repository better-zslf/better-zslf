package io.github.betterzslf.ui.devtools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.log.LogEntry
import io.github.betterzslf.core.log.LogLevel
import io.github.betterzslf.core.log.formatLogTimestamp
import io.github.betterzslf.ui.AppUiTokens
import top.yukonga.miuix.kmp.basic.Badge
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val DEBUG_LOG_WINDOW_LIMIT: Int = 200

internal enum class DebugLogFilter {
    ALL, ISSUES, ERRORS;

    fun accepts(level: LogLevel): Boolean = when (this) {
        ALL -> true
        ISSUES -> level == LogLevel.WARN || level == LogLevel.ERROR
        ERRORS -> level == LogLevel.ERROR
    }
}

/** Preserve append order even if the wall clock moves backwards; never expose a backed subList. */
internal fun debugLogWindow(
    entries: List<LogEntry>,
    filter: DebugLogFilter = DebugLogFilter.ALL,
    limit: Int = DEBUG_LOG_WINDOW_LIMIT,
): List<LogEntry> {
    val boundedLimit = limit.coerceIn(0, DEBUG_LOG_WINDOW_LIMIT)
    if (boundedLimit == 0) return emptyList()
    val result = ArrayList<LogEntry>(minOf(entries.size, boundedLimit))
    for (index in entries.lastIndex downTo 0) {
        val entry = entries[index]
        if (filter.accepts(entry.level)) result.add(entry)
        if (result.size == boundedLimit) break
    }
    return result.toList()
}

/** The same UTC formatter as export, with an explicit zone so local device time is not implied. */
internal fun debugLogHeader(entry: LogEntry): String =
    "${formatLogTimestamp(entry.timestampMillis)} UTC  ${entry.tag}"

/** Items join the owning PageList/LazyColumn; there is no nested vertical scrolling container. */
internal fun LazyListScope.debugLogRows(entries: List<LogEntry>) {
    itemsIndexed(entries, key = { index, entry -> "debug-log:$index:${entry.timestampMillis}" },
        contentType = { _, _ -> "debug-log" }) { _, entry ->
        DebugLogRow(entry)
    }
}

@Composable
private fun DebugLogRow(entry: LogEntry) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val levelColor = when (entry.level) {
        LogLevel.DEBUG -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        LogLevel.INFO -> MiuixTheme.colorScheme.primary
        LogLevel.WARN -> if (dark) AppUiTokens.WarningDark else AppUiTokens.Warning
        LogLevel.ERROR -> MiuixTheme.colorScheme.error
    }
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                    Badge(containerColor = levelColor.copy(alpha = 0.12f), contentColor = levelColor) {
                        Text(entry.level.name, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Text(debugLogHeader(entry), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                Text(entry.message, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    color = MiuixTheme.colorScheme.onSurface)
            }
        }
    }
}
