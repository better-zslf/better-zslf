package io.github.betterzslf.ui.water

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.feature.water.WaterBleController
import io.github.betterzslf.feature.water.WaterBleDevice
import io.github.betterzslf.feature.water.WaterFailureGuard
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.PageList
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionTitle
import io.github.betterzslf.ui.UiTone
import io.github.betterzslf.feature.waterui.WaterConfirmation
import io.github.betterzslf.feature.waterui.WaterHeaterModel
import io.github.betterzslf.feature.waterui.WaterHeaterPresenter
import io.github.betterzslf.feature.waterui.formatWaterObservation
import io.github.betterzslf.platform.BluetoothAccess
import io.github.betterzslf.platform.rememberBluetoothAccess
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
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
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    PredictiveBackHandler(
        enabled = model.confirmation == null,
        onProgress = { events -> events.collect {} },
        onCommit = presenter::requestBack,
        onCancel = {},
    )
    Screen(AppText.Water.DORMITORY_HEATER_TITLE, onBack = presenter::requestBack) { padding ->
        PageList(padding) {
            if (model.showBluetoothAccessCard) {
                item(key = "access") { BluetoothAccessCard(model, access) }
            }
            item(key = "session") { WaterSessionCard(model) }
            item(key = "begin") {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = presenter::requestBegin, enabled = model.canBegin,
                        modifier = Modifier.size(140.dp), minHeight = 140.dp, cornerRadius = 70.dp,
                        colors = ButtonDefaults.buttonColorsPrimary()) {
                        Text(model.beginActionLabel, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                    }
                    model.beginActionHint?.let { hint ->
                        Text(hint, textAlign = TextAlign.Center, fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            item(key = "device-details") { WaterDeviceDetailsCard(model, presenter) }
            item(key = "normal-end") {
                TextButton(AppText.Water.REQUEST_NORMAL_STOP, onClick = presenter::requestEnd,
                    enabled = model.canEnd, modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                    cornerRadius = AppUiTokens.ButtonRadius, colors = ButtonDefaults.textButtonColorsPrimary())
            }
            item(key = "end-notice") {
                InfoBanner(AppText.Water.OBSERVED_SESSION_DURATION_LIMITS_HINT + "\n" + AppText.Water.NORMAL_STOP_HANDSHAKE_HINT,
                    tone = UiTone.Neutral)
            }
            item(key = "session-notices") {
                listOfNotNull(model.notice, model.ble.failure?.message, model.ble.message).distinct().forEach {
                    InfoBanner(it, tone = if (model.ble.failure != null) UiTone.Warning else UiTone.Info,
                        modifier = Modifier.padding(bottom = AppUiTokens.Gap))
                }
                if (model.ble.retryStatus.recentFailures > 0 || model.ble.retryStatus.requiresManualConfirmation) {
                    InfoBanner(WaterFailureGuard.LOCKOUT_NOTICE, tone = UiTone.Negative)
                }
            }
            item(key = "scan-controls") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                        SectionTitle(AppText.Water.SELECT_CONTROLLER_TITLE)
                        Text(AppText.Water.MANUAL_SCAN_BEHAVIOR_HINT, fontSize = 13.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (model.ble.scanning) {
                            LinearProgressIndicator()
                            TextButton(AppText.Water.STOP_SCANNING, onClick = presenter::stopScan,
                                modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                                cornerRadius = AppUiTokens.ButtonRadius)
                        } else {
                            TextButton(AppText.Water.SCAN_NEARBY_DEVICES, onClick = presenter::startScan,
                                enabled = model.canScan, modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                                cornerRadius = AppUiTokens.ButtonRadius)
                        }
                    }
                }
            }
            if (model.ble.devices.isEmpty()) item(key = "empty-scan") {
                Text(if (model.ble.scanning) AppText.Water.WAITING_FOR_DEVICE_BROADCAST else AppText.Water.NO_SCAN_RESULTS_HINT,
                    modifier = Modifier.padding(8.dp), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            // Each result is a real LazyColumn item, not a nested eagerly composed list.
            items(model.ble.devices, key = { "device-${it.id}" }) { device ->
                WaterDeviceCard(device, enabled = model.canConnect) { presenter.connect(device.id) }
            }
            item(key = "debug-toggle") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius) {
                    ArrowPreference(AppText.Water.READ_ONLY_CONNECTION_STATUS_TITLE, holdDownState = diagnosticsExpanded,
                        onClick = { diagnosticsExpanded = !diagnosticsExpanded })
                }
            }
            if (diagnosticsExpanded) item(key = "debug") {
                InfoBanner(AppText.Water.connectionDiagnostics(model.ble.phase.toString(), model.ble.protocolState.toString(), model.ble.retryStatus.recentFailures),
                    title = AppText.Water.NO_CUSTOM_COMMANDS_HINT, tone = UiTone.Neutral)
                if (!model.showBluetoothAccessCard) BluetoothAccessCard(model, access)
            }
        }
        // Miuix overlays require the Scaffold-provided popup/dialog host.
        WaterConfirmationDialog(model, presenter)
    }
}

@Composable
private fun BluetoothAccessCard(model: WaterHeaterModel, access: BluetoothAccess) {
    val state = model.access
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
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
            TextButton(AppText.Water.GRANT_BLUETOOTH_PERMISSION, onClick = access::requestPermissions, modifier = Modifier.fillMaxWidth(),
                minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                colors = ButtonDefaults.textButtonColorsPrimary())
        } else if (state.available && !state.enabled) {
            Spacer(Modifier.height(12.dp))
            TextButton(AppText.Water.ENABLE_BLUETOOTH, onClick = access::requestEnable, modifier = Modifier.fillMaxWidth(),
                minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
private fun WaterSessionCard(model: WaterHeaterModel) {
    val primary = MiuixTheme.colorScheme.primary
    val foreground = MiuixTheme.colorScheme.onPrimary
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = primary, contentColor = foreground)) {
        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(primary,
            primary.copy(red = primary.red * 0.80f, green = primary.green * 0.84f, blue = primary.blue * 0.90f))))
            .padding(AppUiTokens.PagePadding).heightIn(min = 180.dp),
            verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
            Text(AppText.Water.DORMITORY_HEATER_TITLE, color = foreground, fontSize = 15.sp)
            // This is the presenter's real connection/protocol state, never a guessed water-flow state.
            Text(model.title, color = foreground, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(model.summary, color = foreground, fontSize = 13.sp)
            if (model.busy) LinearProgressIndicator(colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = foreground, backgroundColor = foreground.copy(alpha = 0.20f)))
        }
    }
}

@Composable
private fun WaterDeviceDetailsCard(model: WaterHeaterModel, presenter: WaterHeaterPresenter) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius) {
        Row(Modifier.fillMaxWidth().padding(AppUiTokens.CardPadding), horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(AppText.Water.DEVICE_LABEL, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(model.deviceName, fontSize = 16.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                model.ble.device?.rssi?.let { Text(AppText.Water.signalDbm(it), fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                Text(AppText.Water.PAGE_OBSERVATION_LABEL, fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(formatWaterObservation(model.observedMillis), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        ArrowPreference(AppText.Water.DISCONNECT_BLUETOOTH_ACTION,
            onClick = presenter::requestDisconnect, enabled = model.canDisconnect)
    }
}

@Composable
private fun WaterDeviceCard(device: WaterBleDevice, enabled: Boolean, onConnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
        Text(device.name, style = MiuixTheme.textStyles.title4, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(AppText.Water.deviceSignal((device.id), (device.rssi?.let { AppText.Water.signalDbm((it)) } ?: AppText.Common.UNKNOWN)),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(12.dp))
        TextButton(AppText.Water.CONNECT_WITHOUT_START, onClick = onConnect, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius)
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
