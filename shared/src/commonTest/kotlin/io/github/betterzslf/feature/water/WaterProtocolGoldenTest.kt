/*
 * Golden vectors from waterctl 2.1.4 src/algorithms.spec.ts and solvers.spec.ts.
 * Copyright (c) 2021 celesWuff; deputy algorithm ©2021-2024 celesWuff, Deputy.
 * MIT: see licenses/waterctl-MIT.txt.
 */
package io.github.betterzslf.feature.water

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

internal fun waterHex(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal val waterFixtureContext = WaterEpilogueContext(1234, WaterDateTime(2013, 1, 11, 12, 34, 56))
internal val waterFixtureChallenge = waterHex("fdfd09ae38000005952703040506070809101112")

class WaterProtocolGoldenTest {
    @Test
    fun changgongAllUpstreamVectors() {
        listOf("11070" to 0xd015, "25333" to 0x21e2, "33982" to 0x9b39,
            "45130" to 0xe08b, "50312" to 0x8d97).forEach { (input, expected) ->
            assertEquals(expected, WaterProtocol.crc16changgong(input), input)
        }
        assertEquals(0x1017, WaterProtocol.crc16changgong(""))
    }

    @Test
    fun cgaeafAllUpstreamVectors() {
        listOf(
            "000005952703040506070809101112" to 0x38,
            "009b05952703040506070809101112" to 0xa2,
            "399b05952703040506070809101112" to 0x5b,
            "00ffff952703040506070809101112" to 0x9f,
            "000001cf7531d4fe87000000000000" to 0x69,
        ).forEach { (input, expected) -> assertEquals(expected, WaterProtocol.crc16cgaeaf(waterHex(input)), input) }
        assertEquals(0x6a, WaterProtocol.crc16cgaeaf(byteArrayOf()))
    }

    @Test
    fun unlockAllSevenUpstreamGoldenVectors() {
        val vectors = listOf(
            "fdfd09ae38000005952703040506070809101112" to "fefe09af72000006c3509ac4fe87000000000000",
            "fdfd09aea2009b05952703040506070809101112" to "fefe09af44009b06f1309ad3fe87000000000000",
            "fdfd09ae5b399b05952703040506070809101112" to "fefe09afbd399b06f1309ad3fe87000000000000",
            "fdfd09ae9f00ffff952703040506070809101112" to "fefe09afd9000100d6ac3f3bfe87000000000000",
            "fdfd09aeb3003464801403040506070809101112" to "fefe09afb1003465d6289ac1fe87000000000000",
            "fdfd09ae010081ff683003040506070809101112" to "fefe09af68008200f112b221fe87000000000000",
            "fdfd09aee50001ff732403040506070809101112" to "fefe09af4b000200ccca9a20fe87000000000000",
        )
        vectors.forEach { (request, response) ->
            val actual = WaterProtocol.makeUnlockResponse(waterHex(request), "Water33982")
            assertContentEquals(waterHex(response), actual, request)
            assertEquals(actual[4].toInt() and 0xff, WaterProtocol.crc16cgaeaf(actual.copyOfRange(5, 20)))
        }
    }

    @Test
    fun rawKeyOrderAndCarryBoundaries() {
        listOf(
            "00059527" to "c05992c6",
            "9b059527" to "f23992d1",
            "ffff9527" to "d5a53739",
            "34648014" to "d52192c3",
            "81ff6830" to "f21bba23",
            "01ff7324" to "cfc39222",
        ).forEach { (input, expected) -> assertContentEquals(waterHex(expected), WaterProtocol.makeKey(waterHex(input)), input) }
        assertFailsWith<IllegalArgumentException> { WaterProtocol.makeKey(byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { WaterProtocol.makeKey(ByteArray(5)) }
    }

    @Test
    fun deterministicOldAndNewEpilogueVectors() {
        assertContentEquals(waterHex("fefe09b201399bff0004101301111234560f2700"),
            WaterProtocol.makeStartEpilogue("Water33982", false, waterFixtureContext))
        assertContentEquals(waterHex("fefe09b201399b0b0004101301111234560f2700"),
            WaterProtocol.makeStartEpilogue("Water33982", true, waterFixtureContext))
        @Suppress("DEPRECATION")
        val archived = WaterProtocol.makeStartEpilogueOfflinebomb(waterFixtureContext)
        assertContentEquals(waterHex("fefe09bb01010d00500410130111123456002000"), archived)
        // The peculiar random ID conversion is pinned: seed 255 -> 00,55 (not 02,55).
        assertContentEquals(waterHex("0055"), WaterProtocol.makeStartEpilogue("Water33982", false,
            waterFixtureContext.copy(userIdSeed = 255)).copyOfRange(9, 11))
    }

    @Test
    fun fixedPayloadsAreUpstreamBytesAndDefensiveCopies() {
        assertContentEquals(waterHex("fefe09b001010000"), WaterProtocol.startPrologue)
        assertContentEquals(waterHex("fefe09b30000"), WaterProtocol.endPrologue)
        assertContentEquals(waterHex("fefe09b40000"), WaterProtocol.endEpilogue)
        assertContentEquals(waterHex("fefe09ba0000"), WaterProtocol.baAck)
        assertContentEquals(waterHex("fefe09bc0000"), WaterProtocol.offlinebombFix)
        WaterProtocol.startPrologue[0] = 0
        assertEquals(0xfe, WaterProtocol.startPrologue[0].toInt() and 0xff)
    }

    @Test
    fun normalizationRepairsOnlyDocumentedPrefixes() {
        val full = waterHex("fdfd09af0055")
        listOf(full, full.copyOfRange(1, full.size), full.copyOfRange(2, full.size)).forEach {
            val frame = assertIs<WaterNotification.Frame>(WaterProtocol.normalizeNotification(it))
            assertEquals(0xaf, frame.type)
            assertContentEquals(full, frame.toByteArray())
        }
        listOf("", "fd", "fdfd", "fd09", "09").forEach {
            assertIs<WaterNotification.Ignored>(WaterProtocol.normalizeNotification(waterHex(it)))
        }
        assertIs<WaterNotification.Ignored>(WaterProtocol.normalizeNotification("AT+STAS?".encodeToByteArray()))
        listOf("00", "fd00", "fdfd00af", "fefe09af0055").forEach {
            assertIs<WaterNotification.Malformed>(WaterProtocol.normalizeNotification(waterHex(it)))
        }
        val frame = assertIs<WaterNotification.Frame>(WaterProtocol.normalizeNotification(full))
        full[3] = 0
        frame.toByteArray()[3] = 0
        assertEquals(0xaf, frame.type)
    }

    @Test
    fun invalidChallengeNeverProducesAnAnswer() {
        assertFailsWith<IllegalArgumentException> { WaterProtocol.makeUnlockResponse(waterFixtureChallenge.copyOf(19), "Water33982") }
        val badCrc = waterFixtureChallenge.copyOf().also { it[4] = 0 }
        assertFailsWith<IllegalArgumentException> { WaterProtocol.makeUnlockResponse(badCrc, "Water33982") }
        val badType = waterFixtureChallenge.copyOf().also { it[3] = 0 }
        assertFailsWith<IllegalArgumentException> { WaterProtocol.makeUnlockResponse(badType, "Water33982") }
        assertFailsWith<IllegalArgumentException> { WaterProtocol.makeUnlockResponse(waterFixtureChallenge, "123") }
    }

    @Test
    fun dateAndBcdValidation() {
        assertEquals(0, WaterProtocol.decAsHex(0))
        assertEquals(0x42, WaterProtocol.decAsHex(42))
        assertEquals(0x255, WaterProtocol.decAsHex(255))
        assertContentEquals(waterHex("240229235959"), WaterDateTime(2024, 2, 29, 23, 59, 59).toBcd())
        assertFailsWith<IllegalArgumentException> { WaterDateTime(2023, 2, 29, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { WaterDateTime(2024, 4, 31, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { WaterDateTime(2100, 1, 1, 0, 0, 0) }
        assertFailsWith<IllegalArgumentException> { WaterDateTime(2024, 1, 1, 24, 0, 0) }
        assertFailsWith<IllegalArgumentException> { WaterEpilogueContext(0, waterFixtureContext.dateTime) }
        assertFailsWith<IllegalArgumentException> { WaterEpilogueContext(10000, waterFixtureContext.dateTime) }
    }
}
