/*
 * Offline protocol port of celesWuff/waterctl 2.1.4 (MIT).
 * Copyright (c) 2021 celesWuff
 * Key algorithm: Copyright (c) 2021-2024 celesWuff, Deputy
 * See licenses/waterctl-MIT.txt. No Bluetooth or network operations live here.
 */
package io.github.betterzslf.feature.water

/** A notification is one GATT notification, not a length-prefixed byte stream. */
sealed interface WaterNotification {
    class Frame internal constructor(bytes: ByteArray) : WaterNotification {
        private val content = bytes.copyOf()
        val type: Int get() = content[3].toInt() and 0xff
        fun toByteArray(): ByteArray = content.copyOf()
    }

    data class Ignored(val reason: IgnoreReason) : WaterNotification
    data class Malformed(val reason: String) : WaterNotification

    enum class IgnoreReason { FirmwareAtCommand, IncompletePrefix }
}

/** Local Shanghai wall-clock components, supplied by the caller, never read implicitly. */
data class WaterDateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
) {
    init {
        require(year in 2000..2099) { "Water protocol year must be in 2000..2099" }
        require(month in 1..12) { "Invalid month" }
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        require(day in 1..days) { "Invalid day" }
        require(hour in 0..23 && minute in 0..59 && second in 0..59) { "Invalid time" }
    }

    internal fun toBcd(): ByteArray = byteArrayOf(
        WaterProtocol.decAsHex(year % 100).toByte(),
        WaterProtocol.decAsHex(month).toByte(),
        WaterProtocol.decAsHex(day).toByte(),
        WaterProtocol.decAsHex(hour).toByte(),
        WaterProtocol.decAsHex(minute).toByte(),
        WaterProtocol.decAsHex(second).toByte(),
    )
}

/** Inputs sampled at B2 construction; [dateTime] must already be in Asia/Shanghai. */
data class WaterEpilogueContext(val userIdSeed: Int, val dateTime: WaterDateTime) {
    init {
        require(userIdSeed in 1..9999) { "User ID seed must be in 1..9999" }
    }
}

/** Pure, deterministic wire encoding; all returned arrays are fresh copies. */
object WaterProtocol {
    const val SERVICE_UUID_16: Int = 0xf1f0
    const val TXD_UUID_16: Int = 0xf1f1
    const val RXD_UUID_16: Int = 0xf1f2

    val startPrologue: ByteArray get() = bytes(0xfe, 0xfe, 0x09, 0xb0, 0x01, 0x01, 0x00, 0x00)
    val endPrologue: ByteArray get() = bytes(0xfe, 0xfe, 0x09, 0xb3, 0x00, 0x00)
    val endEpilogue: ByteArray get() = bytes(0xfe, 0xfe, 0x09, 0xb4, 0x00, 0x00)
    val baAck: ByteArray get() = bytes(0xfe, 0xfe, 0x09, 0xba, 0x00, 0x00)
    val offlinebombFix: ByteArray get() = bytes(0xfe, 0xfe, 0x09, 0xbc, 0x00, 0x00)

    /**
     * Repairs only the two documented missing-prefix variants. Ignores leaked AT+
     * commands and incomplete FD/FD09 prefixes. No concatenation/fragment guessing:
     * byte 4 is NOT a universal length (AE/AF use it as a checksum).
     */
    fun normalizeNotification(input: ByteArray): WaterNotification {
        if (input.size >= 3 && u(input[0]) == 0x41 && u(input[1]) == 0x54 && u(input[2]) == 0x2b) {
            return WaterNotification.Ignored(WaterNotification.IgnoreReason.FirmwareAtCommand)
        }
        if (input.isEmpty()) return WaterNotification.Ignored(WaterNotification.IgnoreReason.IncompletePrefix)
        val normalized = when {
            u(input[0]) == 0x09 -> bytes(0xfd, 0xfd) + input
            input.size >= 2 && u(input[0]) == 0xfd && u(input[1]) == 0x09 -> bytes(0xfd) + input
            u(input[0]) == 0xfd -> input.copyOf()
            else -> return WaterNotification.Malformed("Notification does not start with FD FD 09")
        }
        if (normalized.size == 1 || (normalized.size == 2 && u(normalized[1]) == 0xfd)) {
            return WaterNotification.Ignored(WaterNotification.IgnoreReason.IncompletePrefix)
        }
        if (normalized.size < 3 || u(normalized[1]) != 0xfd || u(normalized[2]) != 0x09) {
            return WaterNotification.Malformed("Invalid notification prefix")
        }
        if (normalized.size < 4) return WaterNotification.Ignored(WaterNotification.IgnoreReason.IncompletePrefix)
        return WaterNotification.Frame(normalized)
    }

    /** Exact upstream implementation, including UTF-16 code-unit handling. */
    fun crc16changgong(value: String): Int {
        var crc = 0x1017
        for (character in value) {
            crc = crc xor character.code
            repeat(8) { crc = if (crc and 1 != 0) (crc shr 1) xor 0xa001 else crc shr 1 }
        }
        return crc
    }

    /** CG AE/AF CRC is deliberately truncated to its low byte. */
    fun crc16cgaeaf(value: ByteArray): Int {
        var crc = 0x6a1f
        for (byte in value) {
            crc = crc xor u(byte)
            repeat(8) { crc = if (crc and 1 != 0) (crc shr 1) xor 0xa001 else crc shr 1 }
        }
        return (crc xor 0x75) and 0xff
    }

    /** nonce high/low then MAC tail high/low; output order matches solvers.ts. */
    fun makeKey(input: ByteArray): ByteArray = WaterKeyAlgorithm.makeKey(input)

    /**
     * AE must be a normalized 20-byte frame with a valid CGAEAF checksum. The FFFF
     * nonce advances to 0100, not 0000: this is intentional bug-for-bug compatibility.
     */
    fun makeUnlockResponse(unlockRequest: ByteArray, deviceName: String): ByteArray {
        require(unlockRequest.size == 20) { "AE challenge must contain exactly 20 bytes" }
        require(u(unlockRequest[0]) == 0xfd && u(unlockRequest[1]) == 0xfd &&
            u(unlockRequest[2]) == 0x09 && u(unlockRequest[3]) == 0xae) { "Expected normalized AE challenge" }
        require(crc16cgaeaf(unlockRequest.copyOfRange(5, 20)) == u(unlockRequest[4])) { "Bad AE checksum" }
        require(deviceName.length >= 4) { "Device name needs at least four characters" }
        val nonce = (u(unlockRequest[6]) shl 8) or u(unlockRequest[7])
        val nextNonce = if (nonce == 0xffff) 0x0100 else nonce + 1
        val rawKey = makeKey(unlockRequest.copyOfRange(6, 10))
        val mask = deviceName.takeLast(4)
        val checksumInput = bytes(
            u(unlockRequest[5]), nextNonce shr 8, nextNonce and 0xff,
            u(rawKey[0]) xor (mask[0].code - 0x30),
            u(rawKey[1]) xor (mask[1].code - 0x30),
            u(rawKey[2]) xor (mask[2].code - 0x30),
            u(rawKey[3]) xor (mask[3].code - 0x30),
            0xfe, 0x87, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        return bytes(0xfe, 0xfe, 0x09, 0xaf, crc16cgaeaf(checksumInput)) + checksumInput
    }

    fun makeStartEpilogue(
        deviceName: String,
        isKeyAuthPresent: Boolean,
        context: WaterEpilogueContext,
    ): ByteArray {
        require(deviceName.length >= 5) { "Device name needs at least five characters" }
        val crc = crc16changgong(deviceName.takeLast(5))
        return bytes(0xfe, 0xfe, 0x09, 0xb2, 0x01, crc and 0xff, crc shr 8,
            if (isKeyAuthPresent) 0x0b else 0xff, 0x00) +
            makeUserId(context.userIdSeed) + context.dateTime.toBcd() + bytes(0x0f, 0x27, 0x00)
    }

    /**
     * Archived upstream encoder, tested for parity only. Never selected by the
     * session machine and never exposed as a normal start mode.
     */
    @Deprecated("Archived upstream frame; normal sessions use makeStartEpilogue")
    fun makeStartEpilogueOfflinebomb(context: WaterEpilogueContext): ByteArray =
        bytes(0xfe, 0xfe, 0x09, 0xbb, 0x01, 0x01, 0x0d, 0x00, 0x50) +
            makeUserId(context.userIdSeed) + context.dateTime.toBcd() + bytes(0x00, 0x20, 0x00)

    /** Same recursive decimal-as-hex conversion as utils.ts (not a byte clamp). */
    fun decAsHex(value: Int): Int {
        require(value >= 0) { "Expected a non-negative decimal value" }
        return if (value == 0) 0 else (value % 10) or (decAsHex(value / 10) shl 4)
    }

    private fun makeUserId(seed: Int): ByteArray {
        // Upstream first splits the NUMBER into two bytes, THEN decimal-encodes
        // each byte and truncates it through Uint8Array. This is not normal BCD.
        return bytes(decAsHex(seed shr 8), decAsHex(seed and 0xff))
    }

    private fun bytes(vararg value: Int): ByteArray = ByteArray(value.size) { value[it].toByte() }
    private fun u(value: Byte): Int = value.toInt() and 0xff
}
