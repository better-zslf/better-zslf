package io.github.betterzslf.feature.waterui

import io.github.betterzslf.feature.water.WaterBleDevice
import io.github.betterzslf.feature.water.WaterBlePhase
import io.github.betterzslf.feature.water.WaterBleUiState
import io.github.betterzslf.platform.BluetoothAccessState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pure immutable display projections: no controller, transport, radio, native host or coroutine. */
class WaterHeaterPresentationTest {
    private val readyAccess = BluetoothAccessState(available = true, granted = true, enabled = true)

    private fun model(
        phase: WaterBlePhase = WaterBlePhase.Disconnected,
        access: BluetoothAccessState = readyAccess,
        canBegin: Boolean = false,
        normalEndCompleted: Boolean = false,
        device: WaterBleDevice? = null,
        confirmation: WaterConfirmation? = null,
        busy: Boolean = false,
    ) = WaterHeaterModel(
        ble = WaterBleUiState(phase = phase, device = device, normalEndCompleted = normalEndCompleted),
        access = access,
        confirmation = confirmation,
        acknowledged = false,
        busy = busy,
        canScan = false,
        canConnect = false,
        canBegin = canBegin,
        canEnd = false,
        canDisconnect = false,
        observedMillis = 0,
        notice = null,
    )

    @Test fun disconnectedCaptionIsTheNextActionNotARepeatedStatus() {
        val state = model()
        assertEquals("尚未连接 / 连接已关闭", state.title)
        assertEquals("开启", state.beginActionLabel)
        assertEquals("需先连接设备", state.beginActionHint)
        assertEquals("尚未选择", state.deviceName)
        assertNotEquals(state.title, state.beginActionLabel)
        assertNotEquals(state.title, state.beginActionHint)
        assertNotEquals(state.title, state.deviceName)
        assertFalse(state.canBegin)
    }

    @Test fun readyCaptionDescribesAConfirmedRequestNotAnAlreadyStartedDevice() {
        val state = model(WaterBlePhase.Ready, canBegin = true)
        assertEquals("已连接，尚未开启", state.title)
        assertEquals("连接只完成蓝牙通知订阅，没有发送开启命令。请另行确认开启。", state.summary)
        assertEquals("开启", state.beginActionLabel)
        assertEquals("点击请求开启用水（需确认）", state.beginActionHint)
        assertNotEquals(state.title, state.beginActionHint)
        assertTrue(state.canBegin)
        assertFalse(state.canEnd)
    }

    @Test fun connectableFailureStatesKeepTheirMainWarningAndShowTheConnectAction() {
        val titles = mapOf(
            WaterBlePhase.Failed to "操作未完成，连接已关闭",
            WaterBlePhase.ManualConfirmationRequired to "重试前，请再次确认",
        )
        titles.forEach { (phase, title) ->
            val state = model(phase)
            assertEquals(title, state.title)
            assertEquals("不会自动重试或自动重连。请先阅读失败说明，再自行选择下一步。", state.summary)
            assertEquals("需先连接设备", state.beginActionHint)
            assertEquals("开启", state.beginActionLabel)
            assertNotEquals(state.title, state.beginActionHint)
            assertFalse(state.canBegin)
        }
    }

    @Test fun inFlightActiveStoppingAndClosedDoNotRepeatStatusBelowTheButton() {
        val titles = mapOf(
            WaterBlePhase.Connecting to "正在连接并订阅通知",
            WaterBlePhase.Authenticating to "正在认证，等待设备响应",
            WaterBlePhase.Active to "设备已确认开启",
            WaterBlePhase.Stopping to "正在正常结束，等待设备确认",
            WaterBlePhase.Closed to "控制器已关闭",
        )
        titles.forEach { (phase, title) ->
            val state = model(phase)
            assertEquals(title, state.title)
            assertEquals("开启", state.beginActionLabel)
            assertNull(state.beginActionHint)
            assertFalse(state.canBegin)
        }
    }

    @Test fun readyButBlockedBusyOrAwaitingConfirmationDoesNotAdvertiseAnotherStart() {
        val pending = WaterConfirmation.Begin(
            deviceId = "offline-presentation-device",
            requiresAcknowledgement = false,
            readySnapshot = WaterBleUiState(phase = WaterBlePhase.Ready),
        )
        val states = listOf(
            model(WaterBlePhase.Ready, access = readyAccess.copy(granted = false)),
            model(WaterBlePhase.Ready, access = readyAccess.copy(enabled = false)),
            model(WaterBlePhase.Ready, busy = true),
            model(WaterBlePhase.Ready, confirmation = pending),
        )
        states.forEach { state ->
            assertEquals("已连接，尚未开启", state.title)
            assertEquals("开启", state.beginActionLabel)
            assertNull(state.beginActionHint)
            assertFalse(state.canBegin)
        }
    }

    @Test fun everyUnavailableDeniedOrPoweredOffCombinationKeepsTheAccessCardInEveryPhase() {
        val blocked = listOf(
            BluetoothAccessState(available = false, granted = false, enabled = false),
            BluetoothAccessState(available = false, granted = false, enabled = true),
            BluetoothAccessState(available = false, granted = true, enabled = false),
            BluetoothAccessState(available = false, granted = true, enabled = true),
            BluetoothAccessState(available = true, granted = false, enabled = false),
            BluetoothAccessState(available = true, granted = false, enabled = true),
            BluetoothAccessState(available = true, granted = true, enabled = false),
        )
        WaterBlePhase.entries.forEach { phase ->
            blocked.forEach { access ->
                assertTrue(model(phase, access).showBluetoothAccessCard, "$phase / $access")
                assertTrue(model(phase, access.copy(message = "offline access issue")).showBluetoothAccessCard)
            }
        }
    }

    @Test fun readySystemMessagesDoNotCreateAnAccessWarningInAnyPhase() {
        WaterBlePhase.entries.forEach { phase ->
            listOf(null, "", " ", "offline informational system message").forEach { message ->
                val access = readyAccess.copy(message = message)
                val state = model(phase, access)
                assertFalse(state.showBluetoothAccessCard, "$phase / $message")
                assertSame(access, state.access)
                assertEquals(message, state.access.message)
            }
        }
    }

    @Test fun normalEndCompletionAndPhysicalWaterBoundaryRemainInTheMainCard() {
        val state = model(normalEndCompleted = true)
        assertEquals("正常结束流程已完成", state.title)
        assertEquals("已收到设备结束通知，后续通信和蓝牙清理已完成。仍请确认现场实际水流。", state.summary)
        assertEquals("需先连接设备", state.beginActionHint)
        assertEquals("开启", state.beginActionLabel)
        assertTrue(state.ble.normalEndCompleted)
        assertFalse(state.canBegin)
    }

    @Test fun stoppingDoesNotTurnAnEndFlagIntoACompletedOrReconnectCaption() {
        val state = model(WaterBlePhase.Stopping, normalEndCompleted = true)
        assertEquals("正在正常结束，等待设备确认", state.title)
        assertEquals("开启", state.beginActionLabel)
        assertNull(state.beginActionHint)
    }

    @Test fun deviceFieldShowsTheSelectionOrActualNameInsteadOfConnectionStatus() {
        val device = WaterBleDevice("offline-presentation-device", "Offline heater", -45)
        WaterBlePhase.entries.forEach { phase ->
            assertEquals("尚未选择", model(phase).deviceName)
            assertEquals("Offline heater", model(phase, device = device).deviceName)
        }
    }

    @Test fun accessRevokedDuringActiveKeepsPermissionRecoveryAndExistingEndActions() {
        val state = model(WaterBlePhase.Active, readyAccess.copy(granted = false))
            .copy(canEnd = true, canDisconnect = true)
        val before = state.copy()
        assertTrue(state.showBluetoothAccessCard)
        assertEquals("设备已确认开启", state.title)
        assertEquals("返回页面不会自动结束或断开，可再次进入此页操作。请留意现场水流。", state.summary)
        assertEquals("开启", state.beginActionLabel)
        assertNull(state.beginActionHint)
        assertFalse(state.canBegin)
        assertTrue(state.canEnd)
        assertTrue(state.canDisconnect)
        assertEquals(before, state)
    }
}
