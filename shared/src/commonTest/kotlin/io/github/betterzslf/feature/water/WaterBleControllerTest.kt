@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package io.github.betterzslf.feature.water

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val bleFixtureDevice = WaterBleDevice("offline-controller-test", "Water33982", -55)

private class FakeWaterConnection : WaterBleConnection {
    override val states = MutableStateFlow(WaterBleLinkState.Disconnected)
    override val isConnected: Boolean get() = states.value == WaterBleLinkState.Connected
    val rx = Channel<ByteArray>(Channel.UNLIMITED)
    val attempts = mutableListOf<Int>()
    val writes = mutableListOf<ByteArray>()
    val order = mutableListOf<String>()
    var connectCount = 0
    var disconnectCount = 0
    var closeCount = 0
    var observers = 0
    var automaticallyReady = true
    var failType: Int? = null
    var blockType: Int? = null
    var failDisconnect = false
    var failClose = false
    var disconnectGate: CompletableDeferred<Unit>? = null
    var connectGate: CompletableDeferred<Unit>? = null
    val writeGate = CompletableDeferred<Unit>()
    private var ready: (suspend () -> Unit)? = null

    override fun notifications(onReady: suspend () -> Unit): Flow<ByteArray> = flow {
        observers++
        ready = onReady
        try { for (bytes in rx) emit(bytes) } finally { observers-- }
    }
    suspend fun signalReady() { ready?.invoke() }
    override suspend fun connect() {
        connectCount++
        states.value = WaterBleLinkState.Connecting
        connectGate?.await()
        states.value = WaterBleLinkState.Connected
        if (automaticallyReady) signalReady()
    }
    override suspend fun write(bytes: ByteArray) {
        val type = bytes[3].toInt() and 255
        attempts += type
        order += "start-$type"
        check(closeCount == 0) { "Write after close" }
        if (blockType == type) writeGate.await()
        if (failType == type) error("Injected write failure")
        writes += bytes.copyOf()
        order += "done-$type"
    }
    override suspend fun disconnect() {
        disconnectCount++
        order += "disconnect"
        disconnectGate?.await()
        states.value = WaterBleLinkState.Disconnected
        if (failDisconnect) error("Injected disconnect failure")
    }
    override fun close() {
        closeCount++
        order += "close"
        states.value = WaterBleLinkState.Disconnected
        if (failClose) error("Injected close failure")
    }
    fun emit(hex: String) { check(rx.trySend(waterHex(hex)).isSuccess) }
}

private class FakeWaterTransport : WaterBleTransport {
    val advertisements = MutableSharedFlow<WaterBleDevice>()
    val connections = mutableListOf<FakeWaterConnection>()
    var next: FakeWaterConnection = FakeWaterConnection()
    var scans = 0
    var scansStopped = 0
    var creations = 0
    override fun scan(): Flow<WaterBleDevice> = flow {
        scans++
        try { advertisements.collect { emit(it) } } finally { scansStopped++ }
    }
    override fun createConnection(device: WaterBleDevice): WaterBleConnection {
        creations++
        return next.also { connections += it; next = FakeWaterConnection() }
    }
}

private fun TestScope.controller(
    transport: FakeWaterTransport,
    guard: WaterFailureGuard = WaterFailureGuard(),
    owner: CoroutineScope = backgroundScope,
): WaterBleController = WaterBleController(owner, transport, { waterFixtureContext }, guard,
    nowMillis = { testScheduler.currentTime }, connectionTimeoutMillis = 1000, ioTimeoutMillis = 2000,
    disconnectTimeoutMillis = 100, operationTimeoutMillis = 1000, legacyDelayMillis = 50)

private suspend fun TestScope.activate(controller: WaterBleController, connection: FakeWaterConnection) {
    assertTrue(controller.begin())
    connection.rx.send(waterFixtureChallenge)
    runCurrent()
    connection.emit("fdfd09af0055")
    runCurrent()
    connection.emit("fdfd09b20000")
    runCurrent()
    assertEquals(WaterBlePhase.Active, controller.state.value.phase)
}

class WaterBleControllerTest {
    @Test
    fun creationAndConnectionNeverStartAuthenticationImplicitly() = runTest {
        val transport = FakeWaterTransport()
        val controller = controller(transport)
        assertEquals(0, transport.creations)
        assertEquals(0, transport.scans)
        val connection = transport.next
        assertTrue(controller.connect(bleFixtureDevice))
        runCurrent()
        assertEquals(WaterBlePhase.Ready, controller.state.value.phase)
        assertTrue(connection.writes.isEmpty())
        connection.rx.send(waterFixtureChallenge) // pre-begin RX is ignored, never answered
        runCurrent()
        assertTrue(connection.writes.isEmpty())
        assertTrue(controller.begin())
        assertFalse(controller.begin())
        assertEquals(listOf(0xb0), connection.attempts)
        controller.close()
    }

    @Test
    fun scanIsExplicitDeduplicatedBoundedAndCancellable() = runTest {
        val transport = FakeWaterTransport()
        val controller = controller(transport)
        controller.startScan(100)
        runCurrent()
        transport.advertisements.emit(bleFixtureDevice)
        transport.advertisements.emit(bleFixtureDevice.copy(rssi = -40))
        assertEquals(1, controller.state.value.devices.size)
        assertEquals(-40, controller.state.value.devices.single().rssi)
        assertEquals(0, transport.creations)
        advanceTimeBy(100)
        runCurrent()
        assertFalse(controller.state.value.scanning)
        assertEquals(1, transport.scansStopped)
        advanceTimeBy(1000)
        assertEquals(1, transport.scans)
        controller.close()
    }

    @Test
    fun subscriptionReadyBarrierPreventsAnEarlyB0() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next.also { it.automaticallyReady = false }
        val controller = controller(transport)
        val connecting = async { controller.connect(bleFixtureDevice) }
        runCurrent()
        assertFalse(connecting.isCompleted)
        assertEquals(WaterBlePhase.Connecting, controller.state.value.phase)
        assertTrue(connection.writes.isEmpty())
        connection.signalReady()
        runCurrent()
        assertTrue(connecting.await())
        assertTrue(connection.writes.isEmpty())
        assertTrue(controller.begin())
        controller.close()
    }

    @Test
    fun newFirmwareStartEndWritesB4BeforeDisconnectAndReleasesCollectors() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next
        val controller = controller(transport)
        assertTrue(controller.connect(bleFixtureDevice))
        activate(controller, connection)
        assertContentEquals(waterHex("fefe09af72000006c3509ac4fe87000000000000"), connection.writes[1])
        assertTrue(controller.end())
        connection.emit("fdfd09b30000")
        runCurrent()
        assertEquals(listOf(0xb0, 0xaf, 0xb2, 0xb3, 0xb4), connection.attempts)
        assertTrue(connection.order.indexOf("done-180") < connection.order.indexOf("disconnect"))
        assertEquals(1, connection.disconnectCount)
        assertEquals(1, connection.closeCount)
        assertEquals(0, connection.observers)
        assertTrue(controller.state.value.normalEndCompleted)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(5, connection.writes.size)
        controller.close()
    }

    @Test
    fun normalEndCompletionWaitsForB4AndResourceReleaseThenResetsOnConnect() = runTest {
        val transport = FakeWaterTransport()
        val release = CompletableDeferred<Unit>()
        val connection = transport.next.also { it.blockType = 0xb4; it.disconnectGate = release }
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        activate(controller, connection)
        assertFalse(controller.state.value.normalEndCompleted)
        controller.end()
        connection.emit("fdfd09b30000")
        runCurrent()
        assertEquals(WaterSessionState.Closed, controller.state.value.protocolState)
        assertEquals(WaterBlePhase.Stopping, controller.state.value.phase)
        assertFalse(controller.state.value.normalEndCompleted)
        assertEquals(0, connection.disconnectCount)
        assertEquals(0, connection.closeCount)
        connection.writeGate.complete(Unit)
        runCurrent()
        assertEquals(0xb4, connection.writes.last()[3].toInt() and 255)
        assertEquals(1, connection.disconnectCount)
        assertEquals(0, connection.closeCount)
        assertEquals(WaterBlePhase.Stopping, controller.state.value.phase)
        assertFalse(controller.state.value.normalEndCompleted)
        release.complete(Unit)
        runCurrent()
        assertEquals(1, connection.closeCount)
        assertEquals(0, connection.observers)
        assertEquals(WaterBlePhase.Disconnected, controller.state.value.phase)
        assertTrue(controller.state.value.normalEndCompleted)
        assertTrue(controller.connect(bleFixtureDevice))
        assertEquals(WaterBlePhase.Ready, controller.state.value.phase)
        assertFalse(controller.state.value.normalEndCompleted)
        controller.close()
    }

    @Test
    fun explicitDisconnectDuringB4IsNotNormalCompletion() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next.also { it.blockType = 0xb4 }
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        activate(controller, connection)
        controller.end()
        connection.emit("fdfd09b30000")
        runCurrent()
        assertEquals(WaterBlePhase.Stopping, controller.state.value.phase)
        controller.disconnect()
        assertFalse(controller.state.value.normalEndCompleted)
        assertEquals(WaterBlePhase.Disconnected, controller.state.value.phase)
        assertEquals(1, connection.closeCount)
        connection.writeGate.complete(Unit)
        runCurrent()
        assertTrue(connection.writes.none { (it[3].toInt() and 255) == 0xb4 })
        assertFalse(controller.state.value.normalEndCompleted)
        controller.close()
    }

    @Test
    fun cancellationAfterB4WhileCleanupIsPendingIsNotNormalCompletion() = runTest {
        val transport = FakeWaterTransport()
        val release = CompletableDeferred<Unit>()
        val connection = transport.next.also { it.disconnectGate = release }
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        activate(controller, connection)
        controller.end()
        connection.emit("fdfd09b30000")
        runCurrent()
        assertEquals(0xb4, connection.writes.last()[3].toInt() and 255)
        assertEquals(WaterBlePhase.Stopping, controller.state.value.phase)
        assertFalse(controller.state.value.normalEndCompleted)
        val disconnecting = async { controller.disconnect() }
        runCurrent()
        assertFalse(disconnecting.isCompleted)
        assertFalse(controller.state.value.normalEndCompleted)
        release.complete(Unit)
        runCurrent()
        disconnecting.await()
        assertEquals(1, connection.closeCount)
        assertEquals(WaterBlePhase.Disconnected, controller.state.value.phase)
        assertFalse(controller.state.value.normalEndCompleted)
        controller.close()
    }

    @Test
    fun finalB4TimeoutNeverClaimsNormalCompletion() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next.also { it.blockType = 0xb4 }
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        activate(controller, connection)
        controller.end()
        connection.emit("fdfd09b30000")
        runCurrent()
        assertEquals(WaterBlePhase.Stopping, controller.state.value.phase)
        advanceTimeBy(2000)
        runCurrent()
        assertEquals(WaterBlePhase.Failed, controller.state.value.phase)
        assertFalse(controller.state.value.normalEndCompleted)
        assertEquals(1, connection.closeCount)
        assertTrue(connection.writes.none { (it[3].toInt() and 255) == 0xb4 })
        controller.close()
    }

    @Test
    fun cleanupFailureOrTimeoutNeverClaimsNormalCompletion() = runTest {
        repeat(3) { mode ->
            val transport = FakeWaterTransport()
            val connection = transport.next.also {
                it.failDisconnect = mode == 0
                if (mode == 1) it.disconnectGate = CompletableDeferred()
                it.failClose = mode == 2
            }
            val controller = controller(transport)
            controller.connect(bleFixtureDevice)
            activate(controller, connection)
            controller.end()
            connection.emit("fdfd09b30000")
            runCurrent()
            if (mode == 1) {
                assertEquals(WaterBlePhase.Stopping, controller.state.value.phase)
                assertFalse(controller.state.value.normalEndCompleted)
                advanceTimeBy(100)
                runCurrent()
            }
            assertEquals(0xb4, connection.writes.last()[3].toInt() and 255)
            assertEquals(1, connection.disconnectCount)
            assertEquals(1, connection.closeCount)
            assertEquals(0, connection.observers)
            assertFalse(controller.state.value.normalEndCompleted)
            assertEquals(WaterBlePhase.Disconnected, controller.state.value.phase)
            controller.close()
        }
    }

    @Test
    fun parentCancellationDuringB4NeverClaimsNormalCompletion() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next.also { it.blockType = 0xb4 }
        val parent = SupervisorJob(backgroundScope.coroutineContext[Job])
        val controller = controller(transport, owner = CoroutineScope(backgroundScope.coroutineContext + parent))
        controller.connect(bleFixtureDevice)
        activate(controller, connection)
        controller.end()
        connection.emit("fdfd09b30000")
        runCurrent()
        assertEquals(WaterBlePhase.Stopping, controller.state.value.phase)
        parent.cancel()
        runCurrent()
        parent.join()
        assertFalse(controller.state.value.normalEndCompleted)
        assertEquals(1, connection.closeCount)
        assertEquals(0, connection.observers)
        controller.close()
    }

    @Test
    fun aFailedWriteStopsTheOldEffectBatchAndNeverRetries() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next.also { it.failType = 0xb0 }
        val guard = WaterFailureGuard()
        val controller = controller(transport, guard)
        controller.connect(bleFixtureDevice)
        assertFalse(controller.begin())
        runCurrent()
        assertEquals(listOf(0xb0), connection.attempts)
        assertTrue(connection.writes.isEmpty())
        assertEquals(1, connection.closeCount)
        assertEquals(WaterBlePhase.Failed, controller.state.value.phase)
        assertEquals(1, guard.status(bleFixtureDevice.id, testScheduler.currentTime).recentFailures)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, transport.creations)
        assertEquals(listOf(0xb0), connection.attempts)
        controller.close()
    }

    @Test
    fun finalB4FailureStillDisconnectsAndCloses() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        activate(controller, connection)
        controller.end()
        connection.failType = 0xb4
        connection.emit("fdfd09b30000")
        runCurrent()
        assertEquals(1, connection.disconnectCount)
        assertEquals(1, connection.closeCount)
        assertEquals(WaterBlePhase.Failed, controller.state.value.phase)
        assertEquals(WaterFailureKind.TransportFailure, controller.state.value.failure?.kind)
        assertFalse(controller.state.value.normalEndCompleted)
        controller.close()
    }

    @Test
    fun sequentialAwaitKeepsNextCommandBehindAnUnfinishedWrite() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next.also { it.blockType = 0xb0 }
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        val beginning = async { controller.begin() }
        runCurrent()
        connection.rx.send(waterFixtureChallenge)
        runCurrent()
        assertEquals(listOf(0xb0), connection.attempts)
        assertFalse(beginning.isCompleted)
        connection.writeGate.complete(Unit)
        runCurrent()
        assertTrue(beginning.await())
        assertEquals(listOf(0xb0, 0xaf), connection.attempts)
        controller.close()
    }

    @Test
    fun hungWriteTimesOutAndCannotInstallItsStaleTimer() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next.also { it.blockType = 0xb0 }
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        val beginning = async { controller.begin() }
        runCurrent()
        advanceTimeBy(2000)
        runCurrent()
        assertFalse(beginning.await())
        assertEquals(1, connection.closeCount)
        assertEquals(WaterBlePhase.Failed, controller.state.value.phase)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(listOf(0xb0), connection.attempts)
        controller.close()
    }

    @Test
    fun authenticationFailureCancelsPendingLegacyTimerAndCloses() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        controller.begin()
        connection.emit("fdfd09b10000")
        runCurrent()
        connection.rx.send(waterFixtureChallenge)
        runCurrent()
        connection.emit("fdfd09af0001")
        runCurrent()
        assertEquals(1, connection.closeCount)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(0xb0, 0xaf), connection.attempts)
        assertEquals(WaterFailureKind.AuthenticationFailed, controller.state.value.failure?.kind)
        controller.close()
    }

    @Test
    fun noResponseTimeoutDisconnectsWithoutAnAutomaticReconnect() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        controller.begin()
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, connection.closeCount)
        assertEquals(WaterFailureKind.OperationTimedOut, controller.state.value.failure?.kind)
        advanceTimeBy(5000)
        runCurrent()
        assertEquals(1, transport.creations)
        controller.close()
    }

    @Test
    fun disconnectInterruptsAWriteAndCompletesQueuedCommands() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next.also { it.blockType = 0xb0 }
        val guard = WaterFailureGuard()
        val controller = controller(transport, guard)
        controller.connect(bleFixtureDevice)
        val first = async { controller.begin() }
        val second = async { controller.begin() }
        runCurrent()
        controller.disconnect()
        assertFalse(first.await())
        assertFalse(second.await())
        assertEquals(1, connection.closeCount)
        assertEquals(0, guard.status(bleFixtureDevice.id, testScheduler.currentTime).recentFailures)
        assertEquals(WaterBlePhase.Disconnected, controller.state.value.phase)
        controller.close()
    }

    @Test
    fun cancellationImmediatelyAfterConnectionCreationStillClosesTheHandle() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next.also { it.connectGate = CompletableDeferred() }
        val controller = controller(transport)
        val connecting = async { controller.connect(bleFixtureDevice) }
        runCurrent()
        controller.disconnect()
        assertFalse(connecting.await())
        assertEquals(1, connection.closeCount)
        assertEquals(0, connection.observers)
        controller.close()
    }

    @Test
    fun linkDropAndNotificationCompletionReleaseWithoutRetry() = runTest {
        for (endObservation in listOf(false, true)) {
            val transport = FakeWaterTransport()
            val connection = transport.next
            val controller = controller(transport)
            controller.connect(bleFixtureDevice)
            runCurrent()
            if (endObservation) connection.rx.close() else connection.states.value = WaterBleLinkState.Disconnected
            runCurrent()
            assertEquals(1, connection.closeCount)
            assertEquals(WaterBlePhase.Failed, controller.state.value.phase)
            assertEquals(1, transport.creations)
            controller.close()
        }
    }

    @Test
    fun twoFailuresGateBeforeGattAndConfirmationIsConsumedOnce() = runTest {
        val transport = FakeWaterTransport()
        val guard = WaterFailureGuard()
        val controller = controller(transport, guard)
        repeat(2) {
            val connection = transport.next
            controller.connect(bleFixtureDevice)
            controller.begin()
            connection.rx.send(waterFixtureChallenge)
            runCurrent()
            connection.emit("fdfd09af0002")
            runCurrent()
        }
        assertFalse(controller.connect(bleFixtureDevice))
        assertEquals(2, transport.creations)
        assertEquals(WaterBlePhase.ManualConfirmationRequired, controller.state.value.phase)
        val confirmed = transport.next
        assertTrue(controller.connect(bleFixtureDevice, manuallyConfirmedRetry = true))
        assertTrue(confirmed.writes.isEmpty())
        assertTrue(controller.begin()) // consumes only the explicit connect confirmation
        confirmed.emit("fdfd09c80000")
        runCurrent()
        assertFalse(controller.connect(bleFixtureDevice))
        assertEquals(3, transport.creations)
        controller.close()
    }

    @Test
    fun defaultFailureGuardSurvivesControllerRecreation() = runTest {
        val device = bleFixtureDevice.copy(id = "process-guard-test-only")
        repeat(2) {
            val transport = FakeWaterTransport()
            val connection = transport.next.also { it.failType = 0xb0 }
            val controller = WaterBleController(backgroundScope, transport, { waterFixtureContext },
                nowMillis = { testScheduler.currentTime })
            assertTrue(controller.connect(device))
            assertFalse(controller.begin())
            runCurrent()
            assertEquals(1, connection.closeCount)
            controller.close()
        }
        val transport = FakeWaterTransport()
        val controller = WaterBleController(backgroundScope, transport, { waterFixtureContext },
            nowMillis = { testScheduler.currentTime })
        assertFalse(controller.connect(device))
        assertEquals(0, transport.creations)
        controller.close()
    }

    @Test
    fun subscriptionTimeoutAndDisconnectExceptionStillForceClose() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next.also { it.automaticallyReady = false; it.failDisconnect = true }
        val controller = controller(transport)
        val connecting = async { controller.connect(bleFixtureDevice) }
        runCurrent()
        advanceTimeBy(1000)
        runCurrent()
        assertFalse(connecting.await())
        assertEquals(1, connection.disconnectCount)
        assertEquals(1, connection.closeCount)
        assertEquals(0, connection.observers)
        controller.close()
    }

    @Test
    fun parentScopeCancellationAlsoReleasesCollectorsAndConnection() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next
        val guard = WaterFailureGuard()
        val parent = SupervisorJob(backgroundScope.coroutineContext[Job])
        val owner = CoroutineScope(backgroundScope.coroutineContext + parent)
        val controller = controller(transport, guard, owner)
        controller.connect(bleFixtureDevice)
        controller.begin()
        parent.cancel()
        runCurrent()
        parent.join()
        assertEquals(1, connection.disconnectCount)
        assertEquals(1, connection.closeCount)
        assertEquals(0, connection.observers)
        assertEquals(0, guard.status(bleFixtureDevice.id, testScheduler.currentTime).recentFailures)
        controller.close()
    }

    @Test
    fun reconnectDoesNotConsumeOldNotificationsOrLegacyTimers() = runTest {
        val transport = FakeWaterTransport()
        val first = transport.next
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        controller.begin()
        first.emit("fdfd09b10000")
        runCurrent()
        controller.disconnect()
        val second = transport.next
        assertTrue(controller.connect(bleFixtureDevice))
        assertTrue(controller.begin())
        first.rx.send(waterFixtureChallenge)
        advanceTimeBy(51)
        runCurrent()
        assertEquals(listOf(0xb0), first.attempts)
        assertEquals(listOf(0xb0), second.attempts)
        assertEquals(0, first.observers)
        assertEquals(WaterSessionState.AwaitingPrologue, controller.state.value.protocolState)
        controller.close()
    }

    @Test
    fun legacyTimerStartsOnlyOnceAndUsesOldFirmwareMagic() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        controller.begin()
        connection.emit("fdfd09b00000")
        runCurrent()
        connection.emit("fdfd09b10000")
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf(0xb0, 0xb2), connection.attempts)
        assertEquals(0xff, connection.writes.last()[7].toInt() and 255)
        connection.emit("fdfd09b20000")
        runCurrent()
        assertEquals(WaterBlePhase.Active, controller.state.value.phase)
        controller.close()
    }

    @Test
    fun closeIsIdempotentAndFurtherCommandsDoNothing() = runTest {
        val transport = FakeWaterTransport()
        val connection = transport.next
        val controller = controller(transport)
        controller.connect(bleFixtureDevice)
        controller.close()
        controller.close()
        assertEquals(1, connection.closeCount)
        assertEquals(WaterBlePhase.Closed, controller.state.value.phase)
        assertFalse(controller.begin())
        assertFalse(controller.end())
    }
}
