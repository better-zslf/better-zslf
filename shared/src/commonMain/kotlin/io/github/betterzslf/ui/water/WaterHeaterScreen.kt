package io.github.betterzslf.ui.water

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.betterzslf.feature.water.WaterBleController
import io.github.betterzslf.feature.water.WaterBleDevice
import io.github.betterzslf.feature.water.WaterFailureGuard
import io.github.betterzslf.feature.waterui.WaterConfirmation
import io.github.betterzslf.feature.waterui.WaterHeaterModel
import io.github.betterzslf.feature.waterui.WaterHeaterPresenter
import io.github.betterzslf.feature.waterui.formatWaterObservation
import io.github.betterzslf.platform.BluetoothAccess
import io.github.betterzslf.platform.rememberBluetoothAccess
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The application owns [controller]. Leaving this screen never disposes it. */
@Composable
fun WaterHeaterScreen(controller: WaterBleController, onBack: () -> Unit) {
    val access = rememberBluetoothAccess()
    val scope = rememberCoroutineScope()
    val currentBack by rememberUpdatedState(onBack)
    val presenter = remember(controller, access, scope) {
        WaterHeaterPresenter(controller, access.state, scope, onBack = { currentBack() })
    }
    val model by presenter.state.collectAsState()
    PredictiveBackHandler(
        enabled = model.confirmation == null,
        onProgress = { events -> events.collect {} },
        onCommit = presenter::requestBack,
        onCancel = {},
    )
    Scaffold(
        topBar = {
            SmallTopAppBar(title = AppText.Water.HEATER_TITLE, navigationIcon = {
                TextButton(text = AppText.Common.BACK, onClick = presenter::requestBack)
            })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "access") { BluetoothAccessCard(model, access) }
            item(key = "session") { WaterSessionCard(model) }
            item(key = "controls") {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(AppText.Water.CONFIRM_START_ACTION, onClick = presenter::requestBegin,
                            enabled = model.canBegin, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary())
                        TextButton(AppText.Water.NORMAL_STOP_ACTION, onClick = presenter::requestEnd,
                            enabled = model.canEnd, modifier = Modifier.fillMaxWidth())
                        TextButton(AppText.Water.DISCONNECT_BLUETOOTH_ACTION, onClick = presenter::requestDisconnect,
                            enabled = model.canDisconnect, modifier = Modifier.fillMaxWidth())
                        Text(AppText.Water.NORMAL_STOP_HANDSHAKE_HINT,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            item(key = "scan-controls") {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Text(AppText.Water.SELECT_CONTROLLER_TITLE, style = MiuixTheme.textStyles.title4)
                    Spacer(Modifier.height(8.dp))
                    Text(AppText.Water.MANUAL_SCAN_BEHAVIOR_HINT)
                    Spacer(Modifier.height(12.dp))
                    if (model.ble.scanning) {
                        TextButton(AppText.Water.STOP_SCANNING, onClick = presenter::stopScan,
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        TextButton(AppText.Water.SCAN_NEARBY_DEVICES, onClick = presenter::startScan,
                            enabled = model.canScan, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (model.ble.devices.isEmpty()) item(key = "empty-scan") {
                Text(if (model.ble.scanning) AppText.Water.WAITING_FOR_DEVICE_BROADCAST else AppText.Water.NO_SCAN_RESULTS_HINT,
                    modifier = Modifier.padding(8.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            // Each result is a real LazyColumn item, not a nested eagerly composed list.
            items(model.ble.devices, key = { "device-${it.id}" }) { device ->
                WaterDeviceCard(device, enabled = model.canConnect) { presenter.connect(device.id) }
            }
            item(key = "debug") {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Text(AppText.Water.READ_ONLY_CONNECTION_STATUS_TITLE, style = MiuixTheme.textStyles.title4)
                    Spacer(Modifier.height(8.dp))
                    Text(AppText.Water.connectionDiagnostics((model.ble.phase).toString(), (model.ble.protocolState).toString(), (model.ble.retryStatus.recentFailures)))
                    Text(AppText.Water.NO_CUSTOM_COMMANDS_HINT,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
        // Miuix overlays require the Scaffold-provided popup/dialog host.
        WaterConfirmationDialog(model, presenter)
    }
}

@Composable
private fun BluetoothAccessCard(model: WaterHeaterModel, access: BluetoothAccess) {
    val state = model.access
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Text(AppText.Water.BLUETOOTH_ACCESS_TITLE, style = MiuixTheme.textStyles.title4)
        Spacer(Modifier.height(8.dp))
        Text(when {
            !state.available -> AppText.Water.BLUETOOTH_UNAVAILABLE_ON_PLATFORM
            !state.granted -> AppText.Water.GRANT_PERMISSION_THEN_MANUAL_SCAN_HINT
            !state.enabled -> AppText.Water.ENABLE_BLUETOOTH_THEN_MANUAL_SCAN_HINT
            else -> AppText.Water.BLUETOOTH_READY
        })
        state.message?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp)); Text(it, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (state.available && !state.granted) {
            Spacer(Modifier.height(12.dp))
            TextButton(AppText.Water.GRANT_BLUETOOTH_PERMISSION, onClick = access::requestPermissions, modifier = Modifier.fillMaxWidth())
        } else if (state.available && !state.enabled) {
            Spacer(Modifier.height(12.dp))
            TextButton(AppText.Water.ENABLE_BLUETOOTH, onClick = access::requestEnable, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun WaterSessionCard(model: WaterHeaterModel) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp)) {
        Text(model.title, style = MiuixTheme.textStyles.title4)
        Spacer(Modifier.height(8.dp))
        Text(model.summary)
        model.ble.device?.let {
            Spacer(Modifier.height(8.dp))
            Text(it.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        Text(AppText.Water.observedDuration((formatWaterObservation(model.observedMillis))))
        Text(AppText.Water.OBSERVED_SESSION_DURATION_LIMITS_HINT,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (model.busy) {
            Spacer(Modifier.height(8.dp)); CircularProgressIndicator()
        }
        listOfNotNull(model.notice, model.ble.failure?.message, model.ble.message).distinct().forEach {
            Spacer(Modifier.height(8.dp)); Text(it, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (model.ble.retryStatus.recentFailures > 0 || model.ble.retryStatus.requiresManualConfirmation) {
            Spacer(Modifier.height(12.dp))
            Text(WaterFailureGuard.LOCKOUT_NOTICE, color = MiuixTheme.colorScheme.error)
        }
    }
}

@Composable
private fun WaterDeviceCard(device: WaterBleDevice, enabled: Boolean, onConnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Text(device.name, style = MiuixTheme.textStyles.title4, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(AppText.Water.deviceSignal((device.id), (device.rssi?.let { AppText.Water.signalDbm((it)) } ?: AppText.Common.UNKNOWN)),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(12.dp))
        TextButton(AppText.Water.CONNECT_WITHOUT_START, onClick = onConnect, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WaterConfirmationDialog(model: WaterHeaterModel, presenter: WaterHeaterPresenter) {
    val confirmation = model.confirmation
    val title = when (confirmation) {
        is WaterConfirmation.RetryConnect -> AppText.Water.REPEATED_FAILURE_RETRY_CONFIRM_TITLE
        is WaterConfirmation.Begin -> if (confirmation.requiresAcknowledgement) AppText.Water.RESTART_REQUIRES_STRONG_CONFIRMATION_TITLE else AppText.Water.START_SELECTED_DEVICE_CONFIRM_TITLE
        WaterConfirmation.End -> AppText.Water.NORMAL_STOP_CONFIRM_TITLE
        WaterConfirmation.Disconnect -> AppText.Water.DISCONNECT_CONFIRM_TITLE
        WaterConfirmation.Leave -> AppText.Water.KEEP_SESSION_AND_RETURN_CONFIRM_TITLE
        null -> ""
    }
    val summary = when (confirmation) {
        is WaterConfirmation.RetryConnect -> AppText.Water.retryConnectionNotice((confirmation.device.name), (WaterFailureGuard.LOCKOUT_NOTICE))
        is WaterConfirmation.Begin -> AppText.Water.SINGLE_START_REQUEST_HINT +
            if (confirmation.requiresAcknowledgement) "\n${WaterFailureGuard.LOCKOUT_NOTICE}" else ""
        WaterConfirmation.End -> AppText.Water.NORMAL_STOP_CONFIRM_DESCRIPTION
        WaterConfirmation.Disconnect -> AppText.Water.DISCONNECT_DOES_NOT_STOP_WATER_HINT
        WaterConfirmation.Leave -> AppText.Water.BACK_KEEPS_SHARED_SESSION_HINT
        null -> ""
    }
    val confirmLabel = when (confirmation) {
        is WaterConfirmation.RetryConnect -> AppText.Water.RETRY_CONNECTION_MANUALLY
        is WaterConfirmation.Begin -> AppText.Water.SEND_START_REQUEST
        WaterConfirmation.End -> AppText.Water.REQUEST_NORMAL_STOP
        WaterConfirmation.Disconnect -> AppText.Water.DISCONNECT_WITHOUT_WATER_STOP
        WaterConfirmation.Leave -> AppText.Water.KEEP_SESSION_AND_RETURN
        null -> AppText.Common.CONFIRM
    }
    OverlayDialog(show = confirmation != null, title = title, summary = summary,
        onDismissRequest = presenter::dismissConfirmation) {
        Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (confirmation?.requiresAcknowledgement == true) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .semantics(mergeDescendants = true) {}
                        .toggleable(value = model.acknowledged, role = Role.Checkbox, onValueChange = presenter::acknowledge),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(state = if (model.acknowledged) ToggleableState.On else ToggleableState.Off, onClick = null)
                    Text(AppText.Water.LOCKOUT_RISK_ACKNOWLEDGEMENT, modifier = Modifier.weight(1f))
                }
            }
            TextButton(confirmLabel, onClick = presenter::confirm, modifier = Modifier.fillMaxWidth(),
                enabled = confirmation != null && (!confirmation.requiresAcknowledgement || model.acknowledged),
                colors = ButtonDefaults.textButtonColorsPrimary())
            TextButton(AppText.Water.CANCEL_AND_STAY, onClick = presenter::dismissConfirmation, modifier = Modifier.fillMaxWidth())
        }
    }
}
