package io.github.betterzslf.edition

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.water.KableWaterBleTransport
import io.github.betterzslf.feature.water.WaterBleController
import io.github.betterzslf.feature.water.WaterBlePhase
import io.github.betterzslf.platform.createWaterEpilogueContext
import io.github.betterzslf.ui.CampusShortcut
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import io.github.betterzslf.ui.run.RunImportPage
import io.github.betterzslf.ui.water.WaterHeaterScreen
import kotlinx.coroutines.CoroutineScope
import top.yukonga.miuix.kmp.basic.TextButton

internal fun createEditionFeatures(scope: CoroutineScope): EditionFeatures = ProEditionFeatures(scope)

private class ProEditionFeatures(private val scope: CoroutineScope) : EditionFeatures {
    // Inert until an explicit Pro entry is composed; construction never scans or connects.
    private val water: WaterBleController by lazy {
        WaterBleController(scope, KableWaterBleTransport(), ::createWaterEpilogueContext)
    }

    @Composable override fun HomeEntry(open: (Route) -> Unit) {
        if (!EditionAccess.waterEnabled) return
        val state by water.state.collectAsState()
        val summary = when (state.phase) {
            WaterBlePhase.Disconnected -> AppText.Water.DISCONNECTED
            WaterBlePhase.Connecting -> AppText.Water.CONNECTING
            WaterBlePhase.Ready -> AppText.Water.CONNECTED_NOT_STARTED
            WaterBlePhase.Authenticating -> AppText.Water.AUTHENTICATING
            WaterBlePhase.Active -> AppText.Water.ACTIVE_MANUAL_STOP_REMINDER
            WaterBlePhase.Stopping -> AppText.Water.STOPPING
            WaterBlePhase.ManualConfirmationRequired -> AppText.Water.MANUAL_CONFIRMATION_BEFORE_RETRY
            WaterBlePhase.Failed -> AppText.Water.CONNECTION_CLOSED_SEE_NOTICE
            WaterBlePhase.Closed -> AppText.Common.CLOSED
        }
        val needsAttention = state.phase in setOf(WaterBlePhase.Active, WaterBlePhase.Stopping, WaterBlePhase.ManualConfirmationRequired, WaterBlePhase.Failed)
        CampusShortcut(AppText.Water.DORMITORY_HEATER_TITLE, MiuixIcons.Home, Color(0xFFFA3B3B),
            supportingText = if (needsAttention) summary else "",
            onClick = {
                if (EditionAccess.waterEnabled) open(Route.Page("water", title = AppText.Water.DORMITORY_HEATER_TITLE))
            })
    }

    @Composable override fun LoginEntry(open: () -> Unit) {
        if (EditionAccess.waterEnabled) TextButton(AppText.Water.OFFLINE_HEATER_ENTRY,
            onClick = { if (EditionAccess.waterEnabled) open() }, modifier = Modifier.fillMaxWidth())
    }

    @Composable override fun Page(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
        when {
            page.kind == "water" && EditionAccess.waterEnabled -> WaterHeaterScreen(water, back)
            page.kind == "runimport" && EditionAccess.routeEditorsEnabled -> RunImportPage(graph, page, open, back)
            else -> EditionUnavailablePage(back)
        }
    }
}
