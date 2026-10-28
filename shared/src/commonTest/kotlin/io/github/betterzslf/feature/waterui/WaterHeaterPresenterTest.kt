@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.betterzslf.feature.waterui

import io.github.betterzslf.feature.water.WaterBleConnection
import io.github.betterzslf.feature.water.WaterBleController
import io.github.betterzslf.feature.water.WaterBleDevice
import io.github.betterzslf.feature.water.WaterBleLinkState
import io.github.betterzslf.feature.water.WaterBlePhase
import io.github.betterzslf.feature.water.WaterBleTransport
import io.github.betterzslf.feature.water.WaterDateTime
import io.github.betterzslf.feature.water.WaterEpilogueContext
import io.github.betterzslf.feature.water.WaterFailureGuard
import io.github.betterzslf.platform.BluetoothAccessState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val uiDevice = WaterBleDevice("offline-water-ui", "Water33982", -45)
private val grantedAccess = BluetoothAccessState(available = true, granted = true, enabled = true)
private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** No Kable, radio, scanner or external process is constructed in these tests. */
private class UiFakeConnection : WaterBleConnection {
    override val states = MutableStateFlow(WaterBleLinkState.Disconnected)
    override val isConnected: Boolean get() = states.value == WaterBleLinkState.Connected
    val rx = Channel<ByteArray>(Channel.UNLIMITED)
    val writes = mutableListOf<Int>()
    val attempts = mutableListOf<Int>()
    var disconnections = 0
    var closes = 0
    var blockB4 = false
    var failB4 = false
    val releaseB4 = CompletableDeferred<Unit>()
    private var subscribed: (suspend () -> Unit)? = null
    override fun notifications(onReady: suspend () -> Unit): Flow<ByteArray> = flow {
        subscribed = onReady
        for (bytes in rx) emit(bytes)
    }
    override suspend fun connect() {
        states.value = WaterBleLinkState.Connected
        subscribed?.invoke()
    }
    override suspend fun write(bytes: ByteArray) {
        val command = bytes[3].toInt() and 255
        attempts += command
        if (command == 0xb4 && blockB4) releaseB4.await()
        if (command == 0xb4 && failB4) error("Synthetic UI B4 failure")
        writes += command
    }
    override suspend fun disconnect() { disconnections++; states.value = WaterBleLinkState.Disconnected }
    override fun close() { closes++; states.value = WaterBleLinkState.Disconnected }
    suspend fun emit(value: String) { rx.send(hex(value)) }
}

private class UiFakeTransport : WaterBleTransport {
    val advertisements = MutableSharedFlow<WaterBleDevice>()
    var scans = 0
    var creations = 0
    var next = UiFakeConnection()
    override fun scan(): Flow<WaterBleDevice> = flow {
        scans++
        advertisements.collect { emit(it) }
    }
    override fun createConnection(device: WaterBleDevice): WaterBleConnection {
        creations++
        return next.also { next = UiFakeConnection() }
    }
}

private class UiFixture(val transport: UiFakeTransport, val controller: WaterBleController,
    val presenter: WaterHeaterPresenter, val access: MutableStateFlow<BluetoothAccessState>,
    val presentationScope: CoroutineScope, val guard: WaterFailureGuard, val backs: MutableList<Unit>)

private fun TestScope.fixture(accessState: BluetoothAccessState = grantedAccess,
    guard: WaterFailureGuard = WaterFailureGuard()): UiFixture {
    val transport = UiFakeTransport()
    val controller = WaterBleController(backgroundScope, transport,
        { WaterEpilogueContext(1234, WaterDateTime(2013, 1, 11, 12, 34, 56)) }, guard,
        nowMillis = { testScheduler.currentTime }, connectionTimeoutMillis = 1000,
        ioTimeoutMillis = 2000, disconnectTimeoutMillis = 100, operationTimeoutMillis = 1000,
        legacyDelayMillis = 50)
    val access = MutableStateFlow(accessState)
    val presentationScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
    val backs = mutableListOf<Unit>()
    val presenter = WaterHeaterPresenter(controller, access, presentationScope, { backs += Unit },
        nowMillis = { testScheduler.currentTime })
    return UiFixture(transport, controller, presenter, access, presentationScope, guard, backs)
}

private suspend fun TestScope.discover(fixture: UiFixture) {
    fixture.presenter.startScan()
    runCurrent()
    fixture.transport.advertisements.emit(uiDevice)
    runCurrent()
    fixture.presenter.stopScan()
    runCurrent()
}

private suspend fun TestScope.connect(fixture: UiFixture): UiFakeConnection {
    discover(fixture)
    val connection = fixture.transport.next
    fixture.presenter.connect(uiDevice.id)
    runCurrent()
    assertEquals(WaterBlePhase.Ready, fixture.presenter.state.value.ble.phase)
    return connection
}

private suspend fun TestScope.activate(fixture: UiFixture, connection: UiFakeConnection) {
    fixture.presenter.requestBegin()
    runCurrent()
    assertIs<WaterConfirmation.Begin>(fixture.presenter.state.value.confirmation)
    fixture.presenter.confirm()
    runCurrent()
    // A published waterctl golden challenge, not a real device notification.
    connection.emit("fdfd09ae38000005952703040506070809101112")
    runCurrent()
    connection.emit("fdfd09af0055")
    runCurrent()
    connection.emit("fdfd09b20000")
    runCurrent()
    assertEquals(WaterBlePhase.Active, fixture.presenter.state.value.ble.phase)
}

class WaterHeaterPresenterTest {
    @Test fun constructingOrGrantingPermissionsNeverStartsRadioOperations() = runTest {
        val f = fixture(BluetoothAccessState())
        runCurrent()
        assertTrue(f.presenter.state.value.showBluetoothAccessCard)
        assertEquals("需先连接设备", f.presenter.state.value.beginActionHint)
        f.presenter.startScan()
        f.presenter.connect(uiDevice.id)
        f.presenter.requestBegin()
        runCurrent()
        assertEquals(0, f.transport.scans)
        assertEquals(0, f.transport.creations)
        f.access.value = grantedAccess
        runCurrent()
        assertFalse(f.presenter.state.value.showBluetoothAccessCard)
        assertTrue(f.presenter.state.value.canScan)
        assertEquals(0, f.transport.scans)
        assertEquals(0, f.transport.creations)
        f.controller.close()
    }

    @Test fun deniedUnavailableOrPoweredOffAlwaysGatesScanAndConnection() = runTest {
        for (access in listOf(grantedAccess.copy(available = false), grantedAccess.copy(granted = false),
            grantedAccess.copy(enabled = false))) {
            val f = fixture(access)
            f.presenter.startScan(); f.presenter.connect(uiDevice.id); runCurrent()
            assertTrue(f.presenter.state.value.showBluetoothAccessCard)
            assertEquals("需先连接设备", f.presenter.state.value.beginActionHint)
            assertFalse(f.presenter.state.value.canScan)
            assertFalse(f.presenter.state.value.canConnect)
            assertEquals(0, f.transport.scans)
            assertEquals(0, f.transport.creations)
            f.controller.close()
        }
    }

    @Test fun explicitScanTimesOutAndNeverAutomaticallyRestarts() = runTest {
        val f = fixture()
        f.presenter.startScan(); runCurrent()
        assertTrue(f.presenter.state.value.ble.scanning)
        advanceTimeBy(15_001); runCurrent()
        assertFalse(f.presenter.state.value.ble.scanning)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, f.transport.scans)
        assertEquals(0, f.transport.creations)
        f.controller.close()
    }

    @Test fun connectionIsReadyButNotActiveAndWritesNoB0() = runTest {
        val f = fixture()
        val connection = connect(f)
        assertTrue(connection.writes.isEmpty())
        assertTrue(f.presenter.state.value.canBegin)
        assertFalse(f.presenter.state.value.canEnd)
        assertEquals("已连接，尚未开启", f.presenter.state.value.title)
        assertFalse(f.presenter.state.value.showBluetoothAccessCard)
        assertEquals("开启", f.presenter.state.value.beginActionLabel)
        assertEquals("点击请求开启用水（需确认）", f.presenter.state.value.beginActionHint)
        f.presenter.requestBegin(); runCurrent()
        assertNull(f.presenter.state.value.beginActionHint)
        assertTrue(connection.writes.isEmpty())
        f.presenter.dismissConfirmation(); runCurrent()
        assertEquals("点击请求开启用水（需确认）", f.presenter.state.value.beginActionHint)
        assertTrue(connection.writes.isEmpty())
        f.controller.close()
    }

    @Test fun beginAcknowledgementOnlyMeansB0SentNotDeviceActive() = runTest {
        val f = fixture()
        val connection = connect(f)
        f.presenter.requestBegin(); runCurrent(); f.presenter.confirm(); runCurrent()
        assertEquals(listOf(0xb0), connection.writes)
        assertEquals(WaterBlePhase.Authenticating, f.presenter.state.value.ble.phase)
        assertEquals("开启", f.presenter.state.value.beginActionLabel)
        assertNull(f.presenter.state.value.beginActionHint)
        assertFalse(f.presenter.state.value.canEnd)
        assertFalse(f.presenter.state.value.title.contains("已确认开启"))
        f.presenter.requestBegin(); f.presenter.confirm(); runCurrent()
        assertEquals(listOf(0xb0), connection.writes)
        f.controller.close()
    }

    @Test fun revokedPermissionInvalidatesPendingBeginWithoutWriting() = runTest {
        val f = fixture()
        val connection = connect(f)
        f.presenter.requestBegin(); runCurrent()
        f.access.value = grantedAccess.copy(granted = false)
        f.presenter.confirm(); runCurrent()
        assertTrue(connection.writes.isEmpty())
        assertNotNull(f.presenter.state.value.notice)
        f.controller.close()
    }

    @Test fun twoFailuresRequireFreshStrongConfirmationAndDismissNeverBypassesIt() = runTest {
        val guard = WaterFailureGuard().also { it.recordFailure(uiDevice.id, 0); it.recordFailure(uiDevice.id, 0) }
        val f = fixture(guard = guard)
        discover(f)
        f.presenter.connect(uiDevice.id); runCurrent()
        assertIs<WaterConfirmation.RetryConnect>(f.presenter.state.value.confirmation)
        f.presenter.confirm(); runCurrent()
        assertEquals(0, f.transport.creations)
        f.presenter.acknowledge(true)
        f.presenter.dismissConfirmation()
        f.presenter.connect(uiDevice.id); runCurrent()
        assertFalse(f.presenter.state.value.acknowledged)
        assertEquals(0, f.transport.creations)
        f.presenter.acknowledge(true); f.presenter.confirm(); runCurrent()
        assertEquals(1, f.transport.creations)
        assertEquals(WaterBlePhase.Ready, f.presenter.state.value.ble.phase)
        assertTrue(guard.status(uiDevice.id, testScheduler.currentTime).requiresManualConfirmation)
        f.controller.close()
    }

    @Test fun newFailuresWhileStartDialogIsOpenUpgradeToStrongConfirmation() = runTest {
        val f = fixture()
        val connection = connect(f)
        f.presenter.requestBegin(); runCurrent()
        assertFalse(assertIs<WaterConfirmation.Begin>(f.presenter.state.value.confirmation).requiresAcknowledgement)
        f.guard.recordFailure(uiDevice.id, testScheduler.currentTime)
        f.guard.recordFailure(uiDevice.id, testScheduler.currentTime)
        f.presenter.confirm(); runCurrent()
        assertTrue(assertIs<WaterConfirmation.Begin>(f.presenter.state.value.confirmation).requiresAcknowledgement)
        assertTrue(connection.writes.isEmpty())
        f.presenter.dismissConfirmation(); runCurrent()
        assertTrue(connection.writes.isEmpty())
        f.controller.close()
    }

    @Test fun activeBackConfirmationKeepsSharedSessionAliveAndAllowsRevisit() = runTest {
        val f = fixture()
        val connection = connect(f)
        activate(f, connection)
        assertEquals("开启", f.presenter.state.value.beginActionLabel)
        assertNull(f.presenter.state.value.beginActionHint)
        assertTrue(f.presenter.state.value.canEnd)
        f.presenter.requestBack(); runCurrent()
        assertEquals(WaterConfirmation.Leave, f.presenter.state.value.confirmation)
        assertTrue(f.backs.isEmpty())
        f.presenter.confirm(); runCurrent()
        assertEquals(1, f.backs.size)
        f.presentationScope.cancel(); runCurrent()
        assertEquals(WaterBlePhase.Active, f.controller.state.value.phase)
        assertEquals(0, connection.disconnections)
        assertEquals(0, connection.closes)
        val revisit = WaterHeaterPresenter(f.controller, f.access, backgroundScope, {})
        runCurrent()
        assertTrue(revisit.state.value.canEnd)
        assertEquals(WaterBlePhase.Active, revisit.state.value.ble.phase)
        f.controller.close()
    }

    @Test fun normalEndWaitsForB3AndB4RatherThanDisconnectingImmediately() = runTest {
        val f = fixture()
        val connection = connect(f)
        activate(f, connection)
        f.presenter.requestEnd(); runCurrent()
        assertFalse(connection.writes.contains(0xb3))
        f.presenter.confirm(); runCurrent()
        assertEquals(0xb3, connection.writes.last())
        assertEquals(WaterBlePhase.Stopping, f.presenter.state.value.ble.phase)
        assertEquals(0, connection.disconnections)
        connection.blockB4 = true
        connection.emit("fdfd09b30000"); runCurrent()
        assertTrue(connection.attempts.contains(0xb4))
        assertEquals(WaterBlePhase.Stopping, f.presenter.state.value.ble.phase)
        assertFalse(f.presenter.state.value.ble.normalEndCompleted)
        assertFalse(f.presenter.state.value.title.contains("流程已完成"))
        assertFalse(connection.writes.contains(0xb4))
        assertEquals(0, connection.disconnections)
        connection.releaseB4.complete(Unit); runCurrent()
        assertTrue(connection.writes.contains(0xb4))
        assertEquals(1, connection.disconnections)
        assertEquals(1, connection.closes)
        assertEquals(WaterBlePhase.Disconnected, f.presenter.state.value.ble.phase)
        assertTrue(f.presenter.state.value.ble.normalEndCompleted)
        assertEquals("正常结束流程已完成", f.presenter.state.value.title)
        assertEquals("开启", f.presenter.state.value.beginActionLabel)
        assertEquals("需先连接设备", f.presenter.state.value.beginActionHint)
        f.controller.close()
    }

    @Test fun failedB4NeverShowsNormalCompletion() = runTest {
        val f = fixture()
        val connection = connect(f)
        activate(f, connection)
        connection.failB4 = true
        f.presenter.requestEnd(); runCurrent(); f.presenter.confirm(); runCurrent()
        connection.emit("fdfd09b30000"); runCurrent()
        assertEquals(WaterBlePhase.Failed, f.presenter.state.value.ble.phase)
        assertFalse(f.presenter.state.value.ble.normalEndCompleted)
        assertFalse(f.presenter.state.value.title.contains("流程已完成"))
        assertEquals(1, connection.disconnections)
        f.controller.close()
    }

    @Test fun forceDisconnectIsSeparateFromEndAndNeverWritesB3OrB4() = runTest {
        val f = fixture()
        val connection = connect(f)
        activate(f, connection)
        f.presenter.requestDisconnect(); runCurrent()
        assertEquals(WaterConfirmation.Disconnect, f.presenter.state.value.confirmation)
        f.presenter.confirm(); runCurrent()
        assertFalse(connection.writes.contains(0xb3))
        assertFalse(connection.writes.contains(0xb4))
        assertEquals(1, connection.disconnections)
        assertFalse(f.presenter.state.value.ble.normalEndCompleted)
        assertFalse(f.presenter.state.value.title.contains("流程已完成"))
        assertTrue(f.presenter.state.value.notice.orEmpty().contains("不等于正常结束"))
        f.controller.close()
    }

    @Test fun staleStartDialogAfterDisconnectNeverAuthenticatesAnotherSession() = runTest {
        val f = fixture()
        val connection = connect(f)
        f.presenter.requestBegin(); runCurrent()
        f.controller.disconnect(); runCurrent()
        f.presenter.confirm(); runCurrent()
        assertTrue(connection.writes.isEmpty())
        assertEquals(1, f.transport.creations)
        f.controller.close()
    }

    @Test fun oldBeginDialogCannotAuthenticateAReconnectionToTheSameBleId() = runTest {
        val f = fixture()
        val first = connect(f)
        f.presenter.requestBegin(); runCurrent()
        f.controller.disconnect(); runCurrent()
        val second = f.transport.next
        assertTrue(f.controller.connect(uiDevice)); runCurrent()
        f.presenter.confirm(); runCurrent()
        assertTrue(first.writes.isEmpty())
        assertTrue(second.writes.isEmpty())
        assertNotNull(f.presenter.state.value.notice)
        f.controller.close()
    }

    @Test fun unknownDeviceCannotConnectAndDoubleTapConnectCreatesOnlyOneConnection() = runTest {
        val f = fixture()
        f.presenter.connect("not-discovered"); runCurrent()
        assertEquals(0, f.transport.creations)
        discover(f)
        f.presenter.connect(uiDevice.id)
        f.presenter.connect(uiDevice.id)
        runCurrent()
        assertEquals(1, f.transport.creations)
        assertEquals(WaterBlePhase.Ready, f.presenter.state.value.ble.phase)
        f.controller.close()
    }

    @Test fun localClockStartsOnlyOnActiveAndFreezesWhenSessionCloses() {
        val clock = WaterObservationClock()
        clock.observe(WaterBlePhase.Ready, 0)
        clock.observe(WaterBlePhase.Authenticating, 1000)
        assertEquals(0L, clock.elapsed(10_000))
        clock.observe(WaterBlePhase.Active, 10_000)
        assertEquals(2500L, clock.elapsed(12_500))
        clock.observe(WaterBlePhase.Stopping, 12_500)
        assertEquals(3000L, clock.elapsed(13_000))
        clock.observe(WaterBlePhase.Disconnected, 14_000)
        assertEquals(4000L, clock.elapsed(24_000))
        clock.observe(WaterBlePhase.Active, 25_000)
        assertEquals(0L, clock.elapsed(25_000))
        assertEquals(0L, clock.elapsed(24_000))
    }

    @Test fun observationFormattingIsLocalAndHasNoDurationLimitCommand() {
        assertEquals("00:00:00", formatWaterObservation(-1))
        assertEquals("01:02:03", formatWaterObservation(3_723_000))
        assertEquals("100:00:00", formatWaterObservation(360_000_000))
    }
}
