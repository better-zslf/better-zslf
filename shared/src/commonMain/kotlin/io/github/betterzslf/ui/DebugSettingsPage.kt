package io.github.betterzslf.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.betterzslf.AppGraph
import io.github.betterzslf.core.log.AppLog
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.ui.devtools.DebugSettingsScreen

/**
 * Bridges the developer option to the local log. Turning it off also drops retained records, so a
 * disabled app holds no log strings at all.
 */
@Composable
fun DebugSettingsPage(graph: AppGraph, back: () -> Unit) {
    val settings by graph.settings.settings.collectAsState()
    val enabled by AppLog.enabled.collectAsState()
    val entries by AppLog.records.collectAsState()
    val exportText = remember(entries) { if (enabled) AppLog.exportText() else "" }
    DebugSettingsScreen(
        enabled = enabled,
        onEnabledChange = { value ->
            val applied = graph.settings.update { it.copy(debugMode = value) }
            if (applied) {
                AppLog.setEnabled(value)
                if (value) AppLog.section(AppText.DevTools.ENABLED_NOTICE) else AppLog.clear()
            }
        },
        entries = entries,
        exportText = exportText,
        onClear = { AppLog.clear() },
        onExport = {
            if (exportText.isNotEmpty() && graph.platform.copyToClipboard(exportText)) {
                AppLog.info("devtools", "log copied to clipboard")
            }
        },
        onBack = back,
    )
    // A downgraded or externally reset setting must not leave recording on.
    if (settings.debugMode != enabled) AppLog.setEnabled(settings.debugMode)
}
