package io.github.betterzslf.ui.water

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
            SmallTopAppBar(title = "热水器", navigationIcon = {
                TextButton(text = "返回", onClick = presenter::requestBack)
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
                        TextButton("确认开启…", onClick = presenter::requestBegin,
                            enabled = model.canBegin, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.textButtonColorsPrimary())
                        TextButton("正常结束…", onClick = presenter::requestEnd,
                            enabled = model.canEnd, modifier = Modifier.fillMaxWidth())
                        TextButton("仅断开蓝牙…", onClick = presenter::requestDisconnect,
                            enabled = model.canDisconnect, modifier = Modifier.fillMaxWidth())
                        Text("正常结束会等待设备 B3 确认及 B4 收尾。仅断开蓝牙不代表结束用水。",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            item(key = "scan-controls") {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Text("选择水控器", style = MiuixTheme.textStyles.title4)
                    Spacer(Modifier.height(8.dp))
                    Text("仅按下扫描按钮后才搜索，约 15 秒自动停止；不会自动连接或重新扫描。")
                    Spacer(Modifier.height(12.dp))
                    if (model.ble.scanning) {
                        TextButton("停止扫描", onClick = presenter::stopScan,
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        TextButton("扫描附近设备", onClick = presenter::startScan,
                            enabled = model.canScan, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (model.ble.devices.isEmpty()) item(key = "empty-scan") {
                Text(if (model.ble.scanning) "正在等待设备广播…" else "尚无扫描结果。请先确认权限和蓝牙已开启。",
                    modifier = Modifier.padding(8.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            // Each result is a real LazyColumn item, not a nested eagerly composed list.
            items(model.ble.devices, key = { "device-${it.id}" }) { device ->
                WaterDeviceCard(device, enabled = model.canConnect) { presenter.connect(device.id) }
            }
            item(key = "debug") {
                Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                    Text("只读连接状态", style = MiuixTheme.textStyles.title4)
                    Spacer(Modifier.height(8.dp))
                    Text("连接：${model.ble.phase}\n协议：${model.ble.protocolState}\n近期失败：${model.ble.retryStatus.recentFailures} 次")
                    Text("无自定义指令或任意 HEX 发包入口。",
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
        Text("蓝牙访问", style = MiuixTheme.textStyles.title4)
        Spacer(Modifier.height(8.dp))
        Text(when {
            !state.available -> "此平台当前未提供可用的蓝牙访问。"
            !state.granted -> "请先授予蓝牙权限；授予后仍需手动扫描。"
            !state.enabled -> "蓝牙尚未开启。开启后仍需手动扫描。"
            else -> "权限已就绪，蓝牙已开启。"
        })
        state.message?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp)); Text(it, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (state.available && !state.granted) {
            Spacer(Modifier.height(12.dp))
            TextButton("授予蓝牙权限", onClick = access::requestPermissions, modifier = Modifier.fillMaxWidth())
        } else if (state.available && !state.enabled) {
            Spacer(Modifier.height(12.dp))
            TextButton("打开蓝牙", onClick = access::requestEnable, modifier = Modifier.fillMaxWidth())
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
        Text("本页观察用时  ${formatWaterObservation(model.observedMillis)}")
        Text("仅记录本页观察到的开启/结束等待时间，不含离页期间；不是硬件计时，也不会定时断水。",
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
        Text("${device.id} · 信号 ${device.rssi?.let { "$it dBm" } ?: "未知"}",
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.height(12.dp))
        TextButton("连接（不开启）", onClick = onConnect, enabled = enabled, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WaterConfirmationDialog(model: WaterHeaterModel, presenter: WaterHeaterPresenter) {
    val confirmation = model.confirmation
    val title = when (confirmation) {
        is WaterConfirmation.RetryConnect -> "多次失败：仍要手动重试？"
        is WaterConfirmation.Begin -> if (confirmation.requiresAcknowledgement) "再次开启前请强确认" else "确认开启所选设备？"
        WaterConfirmation.End -> "正常结束本次用水？"
        WaterConfirmation.Disconnect -> "仅断开蓝牙连接？"
        WaterConfirmation.Leave -> "保留当前会话并返回？"
        null -> ""
    }
    val summary = when (confirmation) {
        is WaterConfirmation.RetryConnect -> "${confirmation.device.name}\n${WaterFailureGuard.LOCKOUT_NOTICE}\n继续只连接一次，之后仍需单独确认开启。"
        is WaterConfirmation.Begin -> "将发送一次开启请求。等待设备确认前不代表开启成功；不会自动重试。" +
            if (confirmation.requiresAcknowledgement) "\n${WaterFailureGuard.LOCKOUT_NOTICE}" else ""
        WaterConfirmation.End -> "将请求正常结束，等待设备 B3 确认并完成 B4 收尾。等待期间不会把断开当作成功。"
        WaterConfirmation.Disconnect -> "这只释放蓝牙连接，不代表设备停止出水。优先选择正常结束；断开后请确认现场水流。"
        WaterConfirmation.Leave -> "返回不会结束或断开共享会话。设备可能继续工作，可再次进入此页操作；要停止请留在此页正常结束。"
        null -> ""
    }
    val confirmLabel = when (confirmation) {
        is WaterConfirmation.RetryConnect -> "仍然手动重试连接"
        is WaterConfirmation.Begin -> "确认发送开启请求"
        WaterConfirmation.End -> "请求正常结束"
        WaterConfirmation.Disconnect -> "仅断开，不代表断水"
        WaterConfirmation.Leave -> "保持会话并返回"
        null -> "确认"
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
                    Text("我已理解再次失败可能锁机约一小时，仍选择本次手动重试。", modifier = Modifier.weight(1f))
                }
            }
            TextButton(confirmLabel, onClick = presenter::confirm, modifier = Modifier.fillMaxWidth(),
                enabled = confirmation != null && (!confirmation.requiresAcknowledgement || model.acknowledged),
                colors = ButtonDefaults.textButtonColorsPrimary())
            TextButton("取消 / 留在此页", onClick = presenter::dismissConfirmation, modifier = Modifier.fillMaxWidth())
        }
    }
}
