package io.github.betterzslf.feature.waterui

import io.github.betterzslf.feature.water.WaterBleController
import io.github.betterzslf.feature.water.WaterBleDevice
import io.github.betterzslf.feature.water.WaterBlePhase
import io.github.betterzslf.feature.water.WaterBleUiState
import io.github.betterzslf.feature.water.WaterSessionState
import io.github.betterzslf.platform.BluetoothAccessState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

sealed interface WaterConfirmation {
    val requiresAcknowledgement: Boolean get() = false
    data class RetryConnect(val device: WaterBleDevice) : WaterConfirmation {
        override val requiresAcknowledgement = true
    }
    data class Begin(val deviceId: String, override val requiresAcknowledgement: Boolean,
        internal val readySnapshot: WaterBleUiState) : WaterConfirmation
    data object End : WaterConfirmation
    data object Disconnect : WaterConfirmation
    data object Leave : WaterConfirmation
}

private data class WaterInteraction(
    val confirmation: WaterConfirmation? = null,
    val acknowledged: Boolean = false,
    val operations: Int = 0,
    val disconnecting: Boolean = false,
    val notice: String? = null,
)

/** UI-only projection. Ready is deliberately not counted as running. */
data class WaterHeaterModel(
    val ble: WaterBleUiState,
    val access: BluetoothAccessState,
    val confirmation: WaterConfirmation?,
    val acknowledged: Boolean,
    val busy: Boolean,
    val canScan: Boolean,
    val canConnect: Boolean,
    val canBegin: Boolean,
    val canEnd: Boolean,
    val canDisconnect: Boolean,
    val observedMillis: Long,
    val notice: String?,
) {
    val title: String get() = if (ble.normalEndCompleted && ble.phase == WaterBlePhase.Disconnected) "正常结束流程已完成" else when (ble.phase) {
        WaterBlePhase.Disconnected -> "尚未连接 / 连接已关闭"
        WaterBlePhase.Connecting -> "正在连接并订阅通知"
        WaterBlePhase.Ready -> "已连接，尚未开启"
        WaterBlePhase.Authenticating -> "正在认证，等待设备响应"
        WaterBlePhase.Active -> "设备已确认开启"
        WaterBlePhase.Stopping -> "正在正常结束，等待设备确认"
        WaterBlePhase.Failed -> "操作未完成，连接已关闭"
        WaterBlePhase.ManualConfirmationRequired -> "重试保护：需要手动强确认"
        WaterBlePhase.Closed -> "控制器已关闭"
    }
    val summary: String get() = if (ble.normalEndCompleted && ble.phase == WaterBlePhase.Disconnected)
        "设备 B3 已确认、B4 写入及蓝牙资源收尾已完成；仍请确认现场实际水流。" else when (ble.phase) {
        WaterBlePhase.Ready -> "连接只完成蓝牙通知订阅，没有发送开启命令。请另行确认开启。"
        WaterBlePhase.Authenticating -> "开启请求不等于开启成功；只有设备的开启通知到达后才显示成功。"
        WaterBlePhase.Active -> "返回页面不会自动结束或断开，可再次进入此页操作。请留意现场水流。"
        WaterBlePhase.Stopping -> if (ble.protocolState == WaterSessionState.Closed)
            "设备结束确认已到达，正在完成 B4 与连接资源收尾；尚未确认流程完成。"
            else "正在等待 B3 确认并完成 B4 收尾；此时不要把断开蓝牙当作正常结束。"
        WaterBlePhase.Disconnected -> "蓝牙连接状态不代表现场水流状态；结束后请确认设备的实际状态。"
        WaterBlePhase.Failed, WaterBlePhase.ManualConfirmationRequired -> "不会自动重试或自动重连。请先阅读失败说明，再自行选择下一步。"
        WaterBlePhase.Closed -> "请返回后重新打开功能；本页不会自行重建共享控制器。"
        WaterBlePhase.Connecting -> "只连接所选设备，不会自动认证或开启。"
    }
}

private val connectionPhases = setOf(WaterBlePhase.Connecting, WaterBlePhase.Ready,
    WaterBlePhase.Authenticating, WaterBlePhase.Active, WaterBlePhase.Stopping)
private fun BluetoothAccessState.ready() = available && granted && enabled
private fun WaterBleUiState.acceptsConnection() = phase in setOf(WaterBlePhase.Disconnected,
    WaterBlePhase.Failed, WaterBlePhase.ManualConfirmationRequired)

private fun project(ble: WaterBleUiState, access: BluetoothAccessState,
    interaction: WaterInteraction, elapsed: Long): WaterHeaterModel {
    val idle = interaction.operations == 0 && interaction.confirmation == null
    return WaterHeaterModel(ble, access, interaction.confirmation, interaction.acknowledged,
        interaction.operations > 0,
        canScan = access.ready() && ble.acceptsConnection() && !ble.scanning && idle,
        canConnect = access.ready() && ble.acceptsConnection() && idle,
        canBegin = access.ready() && ble.phase == WaterBlePhase.Ready && idle,
        canEnd = ble.phase == WaterBlePhase.Active && idle,
        canDisconnect = ble.phase in connectionPhases && !interaction.disconnecting && interaction.confirmation == null,
        observedMillis = elapsed, notice = interaction.notice)
}

/** A local observation clock, not a device timer; intentionally restarts on page recreation. */
class WaterObservationClock {
    private var started: Long? = null
    private var total = 0L
    fun observe(next: WaterBlePhase, nowMillis: Long) {
        val running = next == WaterBlePhase.Active || next == WaterBlePhase.Stopping
        if (running && started == null) {
            if (next == WaterBlePhase.Active) total = 0
            started = nowMillis
        } else if (!running && started != null) {
            total = elapsed(nowMillis)
            started = null
        }
    }
    fun elapsed(nowMillis: Long): Long {
        val from = started ?: return total
        val delta = if (nowMillis >= from) nowMillis - from else 0L
        return total + delta.coerceAtMost(Long.MAX_VALUE - total)
    }
}

fun formatWaterObservation(millis: Long): String {
    val seconds = millis.coerceAtLeast(0L) / 1000
    val hours = seconds / 3600
    val minutes = seconds / 60 % 60
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

private val clockOrigin = TimeSource.Monotonic.markNow()

/**
 * Presentation lifetime only. Collecting state NEVER requests permissions, scans,
 * connects, authenticates, retries or closes the app-scoped controller.
 * Explicit operations finish in the controller's bounded lifecycle even if this
 * page's scope is disposed. In particular navigation must not cancel connect().
 */
class WaterHeaterPresenter(
    private val controller: WaterBleController,
    private val access: StateFlow<BluetoothAccessState>,
    private val scope: CoroutineScope,
    private val onBack: () -> Unit,
    private val nowMillis: () -> Long = { clockOrigin.elapsedNow().inWholeMilliseconds },
) {
    private val interaction = MutableStateFlow(WaterInteraction())
    private val elapsed = MutableStateFlow(0L)
    val state: StateFlow<WaterHeaterModel> = combine(controller.state, access, interaction, elapsed, ::project)
        .stateIn(scope, SharingStarted.Eagerly, project(controller.state.value, access.value, interaction.value, 0))

    init {
        scope.launch {
            val clock = WaterObservationClock()
            controller.state.map { it.phase }.distinctUntilChanged().collectLatest { phase ->
                clock.observe(phase, nowMillis())
                do {
                    elapsed.value = clock.elapsed(nowMillis())
                    if (phase != WaterBlePhase.Active && phase != WaterBlePhase.Stopping) break
                    delay(1000)
                } while (isActive)
            }
        }
    }

    private fun current() = project(controller.state.value, access.value, interaction.value, elapsed.value)
    private fun notice(message: String) { interaction.update { it.copy(notice = message) } }
    private fun dialog(value: WaterConfirmation) {
        interaction.update { it.copy(confirmation = value, acknowledged = false, notice = null) }
    }
    fun acknowledge(value: Boolean) { interaction.update { it.copy(acknowledged = value) } }
    fun dismissConfirmation() { interaction.update { it.copy(confirmation = null, acknowledged = false) } }

    fun startScan() {
        if (!current().canScan) return
        perform { if (access.value.ready() && controller.state.value.acceptsConnection()) controller.startScan() }
    }
    fun stopScan() { if (controller.state.value.scanning) perform(allowConcurrent = true) { controller.stopScan() } }

    fun connect(deviceId: String) {
        if (!current().canConnect) return
        val device = controller.state.value.devices.firstOrNull { it.id == deviceId } ?: return
        perform {
            if (!eligible(device)) return@perform
            val retry = controller.retryStatus(device.id)
            if (retry.requiresManualConfirmation) dialog(WaterConfirmation.RetryConnect(device))
            else controller.connect(device) // Connection/notifications ONLY. Never chains begin().
        }
    }

    fun requestBegin() {
        if (!current().canBegin) return
        val id = controller.state.value.device?.id ?: return
        perform {
            val retry = controller.retryStatus(id)
            val ready = controller.state.value
            if (ready.phase == WaterBlePhase.Ready && ready.device?.id == id && access.value.ready()) {
                dialog(WaterConfirmation.Begin(id, retry.requiresManualConfirmation, ready))
            }
        }
    }
    fun requestEnd() { if (current().canEnd) dialog(WaterConfirmation.End) }
    fun requestDisconnect() { if (current().canDisconnect) dialog(WaterConfirmation.Disconnect) }
    fun requestBack() {
        if (controller.state.value.phase in connectionPhases) dialog(WaterConfirmation.Leave)
        else onBack()
    }

    /** Cancelling/dismissing an overlay never calls this function or grants retry. */
    fun confirm() {
        val snapshot = interaction.value
        val confirmation = snapshot.confirmation ?: return
        if (confirmation.requiresAcknowledgement && !snapshot.acknowledged) return
        val acknowledged = snapshot.acknowledged
        dismissConfirmation()
        when (confirmation) {
            WaterConfirmation.Leave -> onBack() // Keep the shared session alive; no close/disconnect.
            is WaterConfirmation.RetryConnect -> perform {
                if (eligible(confirmation.device)) controller.connect(confirmation.device, manuallyConfirmedRetry = true)
                else notice("权限或设备状态已变化，请重新扫描并选择。")
            }
            is WaterConfirmation.Begin -> perform {
                if (!readyFor(confirmation)) {
                    notice("连接或权限状态已变化，尚未发送开启命令。")
                    return@perform
                }
                val retry = controller.retryStatus(confirmation.deviceId)
                if (!readyFor(confirmation)) {
                    notice("连接或权限状态已变化，请重新确认开启。")
                } else if (retry.requiresManualConfirmation && !acknowledged) {
                    dialog(confirmation.copy(requiresAcknowledgement = true))
                } else if (!controller.begin(manuallyConfirmedRetry = retry.requiresManualConfirmation && acknowledged)) {
                    notice("开启请求未被当前会话接受；请以设备通知及失败说明为准。")
                }
                // begin(true) means B0 was sent, NOT that water is active.
            }
            WaterConfirmation.End -> perform {
                if (controller.state.value.phase == WaterBlePhase.Active && !controller.end()) {
                    notice("结束请求未被当前会话接受，尚未确认设备停止。")
                }
            }
            WaterConfirmation.Disconnect -> perform(allowConcurrent = true, disconnecting = true) {
                controller.disconnect()
                notice("已请求断开蓝牙；这不等于正常结束或硬件断水，请确认现场水流。")
            }
        }
    }

    private fun readyFor(confirmation: WaterConfirmation.Begin): Boolean {
        val ready = controller.state.value
        // Identity binds a dialog to its exact connection, even reconnecting the same BLE ID.
        return access.value.ready() && ready === confirmation.readySnapshot &&
            ready.phase == WaterBlePhase.Ready && ready.device?.id == confirmation.deviceId
    }

    private fun eligible(device: WaterBleDevice): Boolean = access.value.ready() &&
        controller.state.value.acceptsConnection() &&
        controller.state.value.devices.any { it.id == device.id && it.name == device.name }

    private fun perform(allowConcurrent: Boolean = false, disconnecting: Boolean = false, action: suspend () -> Unit) {
        if (!scope.isActive || (!allowConcurrent && interaction.value.operations > 0)) return
        if (disconnecting && interaction.value.disconnecting) return
        interaction.update { it.copy(operations = it.operations + 1,
            disconnecting = it.disconnecting || disconnecting, notice = null) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // The controller owns timeouts/resources; UI disposal is not a disconnect action.
                withContext(NonCancellable) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("操作未完成，请检查蓝牙、权限和当前会话状态后手动操作；不会自动重试。")
            } finally {
                interaction.update { it.copy(operations = (it.operations - 1).coerceAtLeast(0),
                    disconnecting = if (disconnecting) false else it.disconnecting) }
            }
        }
    }
}
