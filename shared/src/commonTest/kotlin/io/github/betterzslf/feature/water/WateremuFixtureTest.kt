/*
 * Source-derived offline fixtures from celesWuff/wateremu, MIT ©2024 celesWuff.
 * src/main.cpp at d7942feca3ad92a17154bcb3f4304b02b49c6b8f (2024-05-02).
 * See licenses/waterctl-MIT.txt for the retained notice.
 * This is callback/frame replay, NOT an ESP32/BLE runtime integration test.
 */
package io.github.betterzslf.feature.water

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Only the upstream callback's response arrays; no emulated cryptographic check. */
private class WateremuSourceFixture(private val newFirmware: Boolean) {
    fun write(payload: ByteArray): List<ByteArray> = when (payload[3].toInt() and 0xff) {
        0xb0 -> if (newFirmware) listOf(
            waterHex("fdfd09b11d000000"),
            waterHex("fdfd09ae00000000000003040506070809101112"), // upstream: malformed, to be fixed
        ) else listOf(waterHex("fdfd09b00142020007e2eb200101000000000000"))
        0xaf -> if (newFirmware) listOf(waterHex("fdfd09af00000102010203040506070809101112")) else emptyList()
        0xb2 -> listOf(waterHex("fdfd09b20142090100006d6c00027932"),
            waterHex("fdfd09ba07420d6d6c000279320014b810000000"))
        0xb3 -> listOf(waterHex("fdfd09b338bb020070e2eb200101000000000000"))
        else -> emptyList()
    }
}

class WateremuFixtureTest {
    @Test
    fun upstreamOldFirmwareFramesCompleteOfflineStartEnd() {
        val emulator = WateremuSourceFixture(false)
        val machine = WaterSessionMachine("wateremu", "Water12345", { waterFixtureContext })
        val startWrite = machine.begin(0).filterIsInstance<WaterEffect.Write>().single().toByteArray()
        val oldResponse = emulator.write(startWrite).single()
        val timer = machine.onNotification(oldResponse, 1).filterIsInstance<WaterEffect.Schedule>().single().timer
        val b2 = machine.onTimer(timer, 501).filterIsInstance<WaterEffect.Write>().single().toByteArray()
        val replies = emulator.write(b2)
        assertTrue(machine.onNotification(replies[0], 502).none { it is WaterEffect.Write })
        assertEquals(WaterSessionState.Active, machine.state)
        val ack = machine.onNotification(replies[1], 503).filterIsInstance<WaterEffect.Write>().single().toByteArray()
        assertContentEquals(WaterProtocol.baAck, ack)
        assertTrue(emulator.write(ack).isEmpty())
        val b3 = machine.end(600).filterIsInstance<WaterEffect.Write>().single().toByteArray()
        val end = machine.onNotification(emulator.write(b3).single(), 601)
        val b4 = end.filterIsInstance<WaterEffect.Write>().single().toByteArray()
        assertContentEquals(WaterProtocol.endEpilogue, b4)
        assertTrue(emulator.write(b4).isEmpty())
        assertTrue(end.any { it is WaterEffect.Disconnect })
        assertEquals(WaterSessionState.Closed, machine.state)
    }

    @Test
    fun upstreamNewFirmwareMalformedChallengeIsDetectedNotClaimedAsSuccess() {
        val emulator = WateremuSourceFixture(true)
        val machine = WaterSessionMachine("wateremu", "Water12345", { waterFixtureContext })
        val start = machine.begin(0).filterIsInstance<WaterEffect.Write>().single().toByteArray()
        val responses = emulator.write(start)
        machine.onNotification(responses[0], 1)
        val malformed = machine.onNotification(responses[1], 2)
        assertTrue(malformed.none { it is WaterEffect.Write })
        assertTrue(malformed.any { it is WaterEffect.Disconnect })
        assertEquals(WaterFailureKind.InvalidNotification, machine.lastFailure?.kind)
        assertEquals(WaterSessionState.Failed, machine.state)
    }

    @Test
    fun upstreamNewFirmwareMalformedAuthResultIsAlsoFailClosed() {
        val emulator = WateremuSourceFixture(true)
        val machine = WaterSessionMachine("wateremu", "Water12345", { waterFixtureContext })
        machine.begin(0)
        // A valid waterctl golden AE isolates the emulator's separate AF=00 defect.
        val af = machine.onNotification(waterFixtureChallenge, 1).filterIsInstance<WaterEffect.Write>().single().toByteArray()
        val effects = machine.onNotification(emulator.write(af).single(), 2)
        assertTrue(effects.none { it is WaterEffect.Write })
        assertEquals(WaterFailureKind.UnknownAuthenticationStatus, machine.lastFailure?.kind)
    }
}
