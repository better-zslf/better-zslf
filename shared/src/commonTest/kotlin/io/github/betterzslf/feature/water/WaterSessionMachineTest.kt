package io.github.betterzslf.feature.water

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun List<WaterEffect>.writes(): List<ByteArray> = filterIsInstance<WaterEffect.Write>().map { it.toByteArray() }
private fun List<WaterEffect>.scheduled(kind: WaterTimerKind): WaterTimer =
    filterIsInstance<WaterEffect.Schedule>().single { it.timer.kind == kind }.timer

private fun newMachine(guard: WaterFailureGuard = WaterFailureGuard(), id: String = "offline-device"): WaterSessionMachine =
    WaterSessionMachine(id, "Water33982", { waterFixtureContext }, guard)

private fun WaterSessionMachine.enterActive(now: Long = 0) {
    begin(now)
    onNotification(waterFixtureChallenge, now + 1)
    onNotification(waterHex("fdfd09af0055"), now + 2)
    onNotification(waterHex("fdfd09b20000"), now + 3)
    assertEquals(WaterSessionState.Active, state)
}

class WaterSessionMachineTest {
    @Test
    fun legacyFirmwareStartEndAndEffectOrdering() {
        val machine = newMachine()
        val initial = machine.begin(0)
        assertContentEquals(WaterProtocol.startPrologue, initial.writes().single())
        assertEquals(15_000L, initial.scheduled(WaterTimerKind.OperationTimeout).dueAtMillis)
        val legacy = machine.onNotification(waterHex("fdfd09b00000"), 10)
            .scheduled(WaterTimerKind.LegacyEpilogueDelay)
        assertEquals(510L, legacy.dueAtMillis)
        assertEquals(WaterSessionState.AwaitingLegacyDelay, machine.state)
        assertTrue(machine.onNotification(waterHex("fdfd09b10000"), 20).isEmpty())
        assertEquals(1L, assertIs<WaterEffect.Schedule>(machine.onTimer(legacy, 509).single()).delayMillis)
        val start = machine.onTimer(legacy, 510)
        assertContentEquals(WaterProtocol.makeStartEpilogue("Water33982", false, waterFixtureContext), start.writes().single())
        assertEquals(WaterSessionState.AwaitingStart, machine.state)
        assertTrue(machine.onTimer(legacy, 511).isEmpty())
        machine.onNotification(waterHex("fdfd09b20000"), 512)
        assertEquals(WaterSessionState.Active, machine.state)
        assertContentEquals(WaterProtocol.endPrologue, machine.end(600).writes().single())
        val end = machine.onNotification(waterHex("fdfd09b30000"), 601)
        val b4Index = end.indexOfFirst { it is WaterEffect.Write }
        val disconnectIndex = end.indexOfFirst { it is WaterEffect.Disconnect }
        assertTrue(b4Index >= 0 && b4Index < disconnectIndex)
        assertContentEquals(WaterProtocol.endEpilogue, end.writes().single())
        assertEquals(WaterSessionState.Closed, machine.state)
        assertEquals(WaterDisconnectReason.Completed, (end[disconnectIndex] as WaterEffect.Disconnect).reason)
        assertTrue(machine.onNotification(waterFixtureChallenge, 700).isEmpty())
    }

    @Test
    fun challengeCancelsLegacyTimerAndUsesAuthenticatedMagic() {
        val machine = newMachine()
        val initialTimer = machine.begin(0).scheduled(WaterTimerKind.OperationTimeout)
        val legacy = machine.onNotification(waterHex("fdfd09b10000"), 1)
            .scheduled(WaterTimerKind.LegacyEpilogueDelay)
        val challenge = machine.onNotification(waterFixtureChallenge.copyOfRange(1, 20), 100)
        assertContentEquals(waterHex("fefe09af72000006c3509ac4fe87000000000000"), challenge.writes().single())
        assertEquals(WaterSessionState.AwaitingAuthentication, machine.state)
        assertTrue(machine.onTimer(legacy, 501).isEmpty())
        assertTrue(machine.onTimer(initialTimer, 15_000).isEmpty())
        val authenticated = machine.onNotification(waterHex("fdfd09af0055"), 600)
        assertContentEquals(WaterProtocol.makeStartEpilogue("Water33982", true, waterFixtureContext), authenticated.writes().single())
        machine.onNotification(waterHex("09b20000"), 601)
        assertEquals(WaterSessionState.Active, machine.state)
    }

    @Test
    fun allKnownAuthFailuresDisconnectWithoutB2OrRetry() {
        listOf(0x01, 0x02, 0x04).forEach { status ->
            val guard = WaterFailureGuard()
            val machine = newMachine(guard)
            machine.begin(0)
            val timer = machine.onNotification(waterFixtureChallenge, 1).scheduled(WaterTimerKind.OperationTimeout)
            val effects = machine.onNotification(byteArrayOf(0xfd.toByte(), 0xfd.toByte(), 9, 0xaf.toByte(), 0, status.toByte()), 2)
            assertTrue(effects.writes().isEmpty())
            assertTrue(effects.any { it is WaterEffect.Disconnect })
            assertEquals(WaterSessionState.Failed, machine.state)
            assertEquals(status, machine.lastFailure?.authenticationStatus)
            assertEquals(1, guard.status("offline-device", 2).recentFailures)
            assertTrue(machine.onTimer(timer, 30_000).isEmpty())
            assertTrue(machine.onNotification(waterHex("fdfd09af0055"), 3).isEmpty())
        }
    }

    @Test
    fun unknownAuthStatusIsFailClosedRatherThanUpstreamFallthrough() {
        val machine = newMachine()
        machine.begin(0)
        machine.onNotification(waterFixtureChallenge, 1)
        assertTrue(machine.onNotification(waterHex("fdfd09af0000"), 2).writes().isEmpty())
        assertEquals(WaterFailureKind.UnknownAuthenticationStatus, machine.lastFailure?.kind)
        assertEquals(WaterSessionState.Failed, machine.state)
    }

    @Test
    fun noPrologueResponseTimesOutAndStaleTimersCannotRetry() {
        val guard = WaterFailureGuard()
        val machine = newMachine(guard)
        val timer = machine.begin(0).scheduled(WaterTimerKind.OperationTimeout)
        assertIs<WaterEffect.Schedule>(machine.onTimer(timer, 14_999).single())
        val timeout = machine.onTimer(timer, 15_000)
        assertTrue(timeout.writes().isEmpty())
        assertTrue(timeout.any { it is WaterEffect.Disconnect })
        assertEquals(WaterFailureKind.OperationTimedOut, machine.lastFailure?.kind)
        assertEquals(1, guard.status("offline-device", 15_000).recentFailures)
        machine.begin(15_001) // explicit user action, never an effect emitted by the timeout
        assertTrue(machine.onTimer(timer, 16_000).isEmpty())
        assertEquals(WaterSessionState.AwaitingPrologue, machine.state)
    }

    @Test
    fun twoFailuresRequireExplicitConfirmationOnEveryFurtherAttempt() {
        val guard = WaterFailureGuard()
        val machine = newMachine(guard)
        repeat(2) { index ->
            val time = index * 100L
            machine.begin(time)
            machine.onNotification(waterHex("fdfd09c80000"), time + 1)
        }
        val blocked = machine.begin(201)
        assertTrue(blocked.writes().isEmpty())
        assertIs<WaterEffect.RequireManualConfirmation>(blocked.first())
        assertEquals(WaterSessionState.RetryConfirmationRequired, machine.state)
        assertContentEquals(WaterProtocol.startPrologue, machine.begin(202, manuallyConfirmedRetry = true).writes().single())
        machine.onTransportFailure(203)
        assertTrue(machine.begin(204).writes().isEmpty())
        assertTrue(guard.status("offline-device", 204).requiresManualConfirmation)
    }

    @Test
    fun guardSeparatesDevicesExpiresAndKeepsRecordsOnClockRollback() {
        val guard = WaterFailureGuard(failureWindowMillis = 1000)
        guard.recordFailure("A", 100)
        guard.recordFailure("A", 200)
        assertEquals(1100L, guard.status("A", 200).retryWithoutConfirmationAtMillis)
        assertTrue(guard.status("A", 50).requiresManualConfirmation)
        assertEquals(0, guard.status("B", 200).recentFailures)
        assertTrue(!guard.status("A", 1100).requiresManualConfirmation)
        assertEquals(1, guard.status("A", 1100).recentFailures)
        assertEquals(0, guard.status("A", 1200).recentFailures)
    }

    @Test
    fun invalidOrRepeatedChallengesNeverReceiveAnotherKey() {
        val corrupt = waterFixtureChallenge.copyOf().also { it[4] = 0 }
        listOf(corrupt, waterFixtureChallenge.copyOf(10)).forEach { bytes ->
            val machine = newMachine()
            machine.begin(0)
            assertTrue(machine.onNotification(bytes, 1).writes().isEmpty())
            assertEquals(WaterSessionState.Failed, machine.state)
        }
        val repeated = newMachine()
        repeated.begin(0)
        repeated.onNotification(waterFixtureChallenge, 1)
        assertTrue(repeated.onNotification(waterFixtureChallenge, 2).writes().isEmpty())
        assertEquals(WaterSessionState.Failed, repeated.state)
    }

    @Test
    fun truncatedAndOutOfOrderResultsNeverStartAHeater() {
        val truncated = newMachine()
        truncated.begin(0)
        truncated.onNotification(waterFixtureChallenge, 1)
        assertTrue(truncated.onNotification(waterHex("fdfd09af00"), 2).writes().isEmpty())
        assertEquals(WaterFailureKind.InvalidNotification, truncated.lastFailure?.kind)
        val wrongOrder = newMachine()
        wrongOrder.begin(0)
        assertTrue(wrongOrder.onNotification(waterHex("fdfd09af0055"), 1).writes().isEmpty())
        assertEquals(WaterFailureKind.UnexpectedNotification, wrongOrder.lastFailure?.kind)
    }

    @Test
    fun telemetryAckAndOfflineSessionCleanupStayLocal() {
        val machine = newMachine()
        machine.enterActive()
        listOf("fdfd09aa0000", "fdfd09b50000", "fdfd09b80000").forEach {
            assertTrue(machine.onNotification(waterHex(it), 10).isEmpty())
        }
        assertContentEquals(WaterProtocol.baAck, machine.onNotification(waterHex("fdfd09ba0000"), 10).writes().single())
        assertContentEquals(WaterProtocol.offlinebombFix, machine.onNotification(waterHex("fdfd09bc0000"), 10).writes().single())
        assertEquals(WaterSessionState.Active, machine.state)
    }

    @Test
    fun ignoredFirmwareNoiseDoesNotExtendTimeout() {
        val machine = newMachine()
        val timer = machine.begin(0).scheduled(WaterTimerKind.OperationTimeout)
        assertTrue(machine.onNotification("AT+STAS?".encodeToByteArray(), 10).isEmpty())
        assertTrue(machine.onNotification(waterHex("fd"), 11).isEmpty())
        machine.onTimer(timer, 15_000)
        assertEquals(WaterSessionState.Failed, machine.state)
    }

    @Test
    fun endTimeoutDisconnectsWithoutCountingAnotherAuthFailure() {
        val guard = WaterFailureGuard()
        val machine = newMachine(guard)
        machine.enterActive()
        val endTimer = machine.end(10).scheduled(WaterTimerKind.OperationTimeout)
        assertTrue(machine.onTimer(endTimer, 15_010).writes().isEmpty())
        assertEquals(WaterSessionState.Failed, machine.state)
        assertEquals(0, guard.status("offline-device", 15_010).recentFailures)
    }

    @Test
    fun cancellationAndTransportFailureDoNotReconnect() {
        val guard = WaterFailureGuard()
        val machine = newMachine(guard)
        val timer = machine.begin(0).scheduled(WaterTimerKind.OperationTimeout)
        assertTrue(machine.cancel().any { it is WaterEffect.Disconnect })
        assertTrue(machine.onTimer(timer, 15_000).isEmpty())
        assertEquals(0, guard.status("offline-device", 15_000).recentFailures)
        machine.begin(16_000)
        assertTrue(machine.onTransportFailure(16_001).writes().isEmpty())
        assertEquals(1, guard.status("offline-device", 16_001).recentFailures)
    }

    @Test
    fun finalB4WriteFailureStillRequestsDisconnect() {
        val guard = WaterFailureGuard()
        val machine = newMachine(guard)
        machine.enterActive()
        machine.end(10)
        machine.onNotification(waterHex("fdfd09b30000"), 11)
        assertEquals(WaterSessionState.Closed, machine.state)
        val failedWrite = machine.onTransportFailure(12)
        assertTrue(failedWrite.any { it is WaterEffect.Disconnect })
        assertTrue(failedWrite.any { it is WaterEffect.ReportFailure })
        assertTrue(failedWrite.writes().isEmpty())
        assertEquals(0, guard.status("offline-device", 12).recentFailures)
    }

    @Test
    fun authenticationAndStartAcknowledgementTimeoutsCountAsFailedAttempts() {
        for (authenticated in listOf(false, true)) {
            val guard = WaterFailureGuard()
            val machine = newMachine(guard)
            machine.begin(0)
            val auth = machine.onNotification(waterFixtureChallenge, 1)
            val timer = if (authenticated) machine.onNotification(waterHex("fdfd09af0055"), 2)
                .scheduled(WaterTimerKind.OperationTimeout) else auth.scheduled(WaterTimerKind.OperationTimeout)
            val effects = machine.onTimer(timer, timer.dueAtMillis)
            assertTrue(effects.writes().isEmpty())
            assertEquals(WaterSessionState.Failed, machine.state)
            assertEquals(1, guard.status("offline-device", timer.dueAtMillis).recentFailures)
        }
    }

    @Test
    fun commandsHaveStatePreconditionsAndDefensiveCopies() {
        val machine = newMachine()
        assertFailsWith<IllegalStateException> { machine.end(0) }
        val write = machine.begin(0).filterIsInstance<WaterEffect.Write>().single()
        write.toByteArray()[0] = 0
        assertEquals(0xfe, write.toByteArray()[0].toInt() and 0xff)
        assertFailsWith<IllegalStateException> { machine.begin(1) }
    }
}
