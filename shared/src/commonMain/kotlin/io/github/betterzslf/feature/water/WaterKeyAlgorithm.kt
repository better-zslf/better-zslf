/*
 * Port of waterctl src/deputy.wat and solvers.ts (MIT).
 * Copyright (c) 2021-2024 celesWuff, Deputy
 * See licenses/waterctl-MIT.txt.
 */
package io.github.betterzslf.feature.water

/** Integer-only translation of the WAT function; no WASM runtime dependency. */
internal object WaterKeyAlgorithm {
    fun makeKey(input: ByteArray): ByteArray {
        require(input.size == 4) { "Unlock key input must contain exactly four bytes" }
        var a = input[0].toInt() and 0xff
        val b = input[1].toInt() and 0xff
        var c = input[2].toInt() and 0xff
        var d = input[3].toInt() and 0xff

        if (b == 255 && a and 1 == 1) a += 2
        if (a == 257) a = 0

        var iA = foldHighBits(d)
        iA = iA xor WaterKeyTables[b]
        if (a and 0x01 != 0) iA = iA xor 1
        if (a and 0x02 != 0) iA = iA xor 1
        if (a and 0x04 != 0) iA = iA xor 2
        if (a and 0x08 != 0) iA = iA xor 7
        if (a and 0x10 != 0) iA = iA xor 1
        if (a and 0x20 != 0) iA = iA xor 1
        if (a and 0x40 != 0) iA = iA xor 1
        if (a and 0x80 != 0) iA = iA xor 1

        val iC = foldHighBits(c) xor WaterKeyTables[768 + (b and 15)]
        d = d xor WaterKeyTables[256 + b]
        d = d xor WaterKeyTables[512 + a]
        val iB = WaterKeyTables[1296 + d]
        c = c xor WaterKeyTables[784 + b]
        c = c xor WaterKeyTables[1040 + a]
        val iD = WaterKeyTables[1552 + c]

        // WAT writes memory[2099..2096]; TS reads Uint32Array[524] little-endian
        // and emits high-to-low. Returning memory[2096..2099] would reverse it.
        return byteArrayOf(
            WaterKeyTables[1938 + iA].toByte(),
            WaterKeyTables[1808 + iB].toByte(),
            WaterKeyTables[2082 + iC].toByte(),
            WaterKeyTables[1952 + iD].toByte(),
        )
    }

    private fun foldHighBits(value: Int): Int {
        var index = value and 7
        if (value and 0x08 != 0) index = index xor 4
        if (value and 0x10 != 0) index = index xor 4
        if (value and 0x20 != 0) index = index xor 4
        if (value and 0x40 != 0) index = index xor 4
        if (value and 0x80 != 0) index = index xor 4
        return index
    }
}
