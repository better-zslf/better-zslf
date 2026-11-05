package io.github.betterzslf.feature.campusnet.crypto

/**
 * Block-cipher primitives for the carrier dialer.
 *
 * Every routine here is a straightforward software implementation of a published standard, so each
 * one is checked against that standard's own test vectors (see `DormPrimitiveVectorTest`) rather
 * than only against itself. That matters: the dialer's payloads are tiny and a single wrong byte
 * would produce a rejected login with no diagnostic, and the gateway is rate-limited.
 *
 * All arithmetic is on Int and masked to 32 bits, which is what Common Kotlin gives us without
 * dragging in a JVM-only crypto API. Key material never leaves this file.
 */

// ---------------------------------------------------------------- shared helpers

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF

/** Big-endian 32-bit read, the convention every block cipher below uses for its state. */
internal fun ByteArray.be32(i: Int): Int =
    (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)

internal fun ByteArray.le32(i: Int): Int =
    u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16) or (u8(i + 3) shl 24)

internal fun ByteArray.putBe32(i: Int, v: Int) {
    this[i] = (v ushr 24).toByte(); this[i + 1] = (v ushr 16).toByte()
    this[i + 2] = (v ushr 8).toByte(); this[i + 3] = v.toByte()
}

internal fun ByteArray.putLe32(i: Int, v: Int) {
    this[i] = v.toByte(); this[i + 1] = (v ushr 8).toByte()
    this[i + 2] = (v ushr 16).toByte(); this[i + 3] = (v ushr 24).toByte()
}

/** Zero-fills to a multiple of [multiple], never adding a whole extra block. */
internal fun ByteArray.zeroPadTo(multiple: Int): ByteArray {
    val pad = (multiple - size % multiple) % multiple
    if (pad == 0) return this.copyOf()
    val out = ByteArray(size + pad)
    copyInto(out)
    return out
}

/** Always appends 1..[block] padding bytes, as PKCS#7 requires. */
internal fun ByteArray.pkcs7Pad(block: Int): ByteArray {
    val pad = block - size % block
    val out = ByteArray(size + pad)
    copyInto(out)
    for (i in size until out.size) out[i] = pad.toByte()
    return out
}

/**
 * Removes PKCS#7 padding, or returns `null` when the tail is not valid padding.
 * Returning `null` rather than throwing keeps a malformed response a normal outcome.
 */
internal fun ByteArray.pkcs7Unpad(): ByteArray? {
    if (isEmpty()) return null
    val pad = u8(size - 1)
    if (pad == 0 || pad > size || pad > 255) return null
    for (i in size - pad until size) if (u8(i) != pad) return null
    return copyOf(size - pad)
}

internal fun ByteArray.xorWith(other: ByteArray, length: Int = minOf(size, other.size)): ByteArray {
    val out = copyOf()
    for (i in 0 until length) out[i] = (u8(i) xor other.u8(i)).toByte()
    return out
}

// ---------------------------------------------------------------- AES

/**
 * AES block cipher (FIPS-197), encryption and decryption, 128/192/256-bit keys.
 *
 * The S-box and its inverse are generated from field arithmetic instead of being pasted in as 256
 * constants: fewer transcribed bytes means fewer chances to mistype one, and generation is checked
 * by the standard's vectors anyway.
 *
 * State is held flat in a 16-byte array in FIPS column-major order (`4*column + row`). Flat bytes
 * keep `ShiftRows` obviously correct, which an earlier word-packed version was not.
 */
internal class Aes(key: ByteArray) {
    private val rounds: Int
    private val roundKeys: ByteArray

    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "AES key must be 16, 24 or 32 bytes" }
        rounds = key.size / 4 + 6
        val words = key.size / 4
        val w = IntArray(4 * (rounds + 1))
        for (i in 0 until words) w[i] = key.be32(i * 4)
        var rcon = 1
        for (i in words until w.size) {
            var t = w[i - 1]
            if (i % words == 0) {
                t = subWord(Integer.rotateLeft(t, 8)) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (words > 6 && i % words == 4) {
                t = subWord(t)
            }
            w[i] = w[i - words] xor t
        }
        roundKeys = ByteArray(w.size * 4).also { out -> for (i in w.indices) out.putBe32(i * 4, w[i]) }
    }

    fun encryptBlock(input: ByteArray): ByteArray {
        require(input.size == 16) { "AES block must be 16 bytes" }
        val s = input.copyOf()
        addRoundKey(s, 0)
        for (round in 1 until rounds) {
            subBytes(s, inverse = false); shiftRows(s, inverse = false)
            mixColumns(s, inverse = false); addRoundKey(s, round)
        }
        // The final round omits MixColumns but still uses the FORWARD transformations.
        subBytes(s, inverse = false); shiftRows(s, inverse = false); addRoundKey(s, rounds)
        return s
    }

    fun decryptBlock(input: ByteArray): ByteArray {
        require(input.size == 16) { "AES block must be 16 bytes" }
        val s = input.copyOf()
        addRoundKey(s, rounds)
        // FIPS-197 Fig. 12 (InvCipher): the operations run in the reverse order of Fig. 5, and
        // InvMixColumns comes AFTER AddRoundKey, not before it.
        for (round in rounds - 1 downTo 1) {
            shiftRows(s, inverse = true); subBytes(s, inverse = true)
            addRoundKey(s, round); mixColumns(s, inverse = true)
        }
        shiftRows(s, inverse = true); subBytes(s, inverse = true); addRoundKey(s, 0)
        return s
    }

    private fun addRoundKey(s: ByteArray, round: Int) {
        val base = round * 16
        for (i in 0 until 16) s[i] = (s.u8(i) xor roundKeys.u8(base + i)).toByte()
    }

    private fun subBytes(s: ByteArray, inverse: Boolean) {
        val box = if (inverse) SBOX_INV else SBOX
        for (i in 0 until 16) s[i] = box[s.u8(i)].toByte()
    }

    /**
     * Row r is rotated left by r for encryption and right by r for decryption.
     *
     * With flat `index = 4*column + row`, the *source* column for output column c is `c + r` (mod 4)
     * when encrypting: row 0 is untouched, row 1 shifts by one column, and so on.
     */
    private fun shiftRows(s: ByteArray, inverse: Boolean) {
        val out = ByteArray(16)
        for (column in 0 until 4) {
            for (row in 0 until 4) {
                // Encrypt reads from column (c + r); decrypt reads from (c - r).
                val from = if (inverse) (column - row + 4) % 4 else (column + row) % 4
                out[4 * column + row] = s[4 * from + row]
            }
        }
        out.copyInto(s)
    }

    private fun mixColumns(s: ByteArray, inverse: Boolean) {
        for (column in 0 until 4) {
            val base = 4 * column
            val a0 = s.u8(base); val a1 = s.u8(base + 1)
            val a2 = s.u8(base + 2); val a3 = s.u8(base + 3)
            if (inverse) {
                s[base] = (mul(a0, 14) xor mul(a1, 11) xor mul(a2, 13) xor mul(a3, 9)).toByte()
                s[base + 1] = (mul(a0, 9) xor mul(a1, 14) xor mul(a2, 11) xor mul(a3, 13)).toByte()
                s[base + 2] = (mul(a0, 13) xor mul(a1, 9) xor mul(a2, 14) xor mul(a3, 11)).toByte()
                s[base + 3] = (mul(a0, 11) xor mul(a1, 13) xor mul(a2, 9) xor mul(a3, 14)).toByte()
            } else {
                s[base] = (mul(a0, 2) xor mul(a1, 3) xor a2 xor a3).toByte()
                s[base + 1] = (a0 xor mul(a1, 2) xor mul(a2, 3) xor a3).toByte()
                s[base + 2] = (a0 xor a1 xor mul(a2, 2) xor mul(a3, 3)).toByte()
                s[base + 3] = (mul(a0, 3) xor a1 xor a2 xor mul(a3, 2)).toByte()
            }
        }
    }

    private fun subWord(v: Int): Int =
        (SBOX[(v ushr 24) and 0xFF] shl 24) or (SBOX[(v ushr 16) and 0xFF] shl 16) or
            (SBOX[(v ushr 8) and 0xFF] shl 8) or SBOX[v and 0xFF]

    companion object {
        /** Multiply in GF(2^8) modulo x^8+x^4+x^3+x+1. `mul(x, 2)` is `xtime`. */
        internal fun mul(a: Int, b: Int): Int {
            var result = 0
            var x = a and 0xFF
            var y = b
            while (y > 0) {
                if (y and 1 != 0) result = result xor x
                x = xtime(x)
                y = y shr 1
            }
            return result and 0xFF
        }

        internal fun xtime(x: Int): Int {
            val shifted = (x shl 1) and 0xFF
            return if (x and 0x80 != 0) shifted xor 0x1B else shifted
        }

        /** A circular left-rotate inside 8 bits; a 32-bit rotate would lose the top bit. */
        private fun rotl8(v: Int, n: Int): Int = ((v shl n) or (v ushr (8 - n))) and 0xFF

        /** Built by inverting the field element and applying the affine transform. */
        internal val SBOX: IntArray = IntArray(256).also { box ->
            for (i in 0 until 256) box[i] = affine(inverse(i))
        }
        internal val SBOX_INV: IntArray = IntArray(256).also { inv ->
            for (i in 0 until 256) inv[SBOX[i]] = i
        }

        private fun inverse(a: Int): Int {
            if (a == 0) return 0
            var result = 1
            var base = a
            var exp = 254
            while (exp > 0) {
                if (exp and 1 != 0) result = mul(result, base)
                base = mul(base, base)
                exp = exp shr 1
            }
            return result
        }

        private fun affine(b: Int): Int {
            val x = b xor rotl8(b, 1) xor rotl8(b, 2) xor rotl8(b, 3) xor rotl8(b, 4)
            return (x xor 0x63) and 0xFF
        }
    }
}

/** CBC over any 16-byte block cipher. [iv] is a copy; the caller's array is never modified. */
internal fun aesCbcEncrypt(aes: Aes, iv: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(data.size)
    var prev = iv.copyOf()
    for (off in data.indices step 16) {
        val block = data.copyOfRange(off, off + 16).xorWith(prev)
        val enc = aes.encryptBlock(block)
        enc.copyInto(out, off)
        prev = enc
    }
    return out
}

internal fun aesCbcDecrypt(aes: Aes, iv: ByteArray, data: ByteArray): ByteArray {
    val out = ByteArray(data.size)
    var prev = iv.copyOf()
    for (off in data.indices step 16) {
        val ct = data.copyOfRange(off, off + 16)
        aes.decryptBlock(ct).xorWith(prev).copyInto(out, off)
        prev = ct
    }
    return out
}

internal fun aesEcbEncrypt(aes: Aes, data: ByteArray): ByteArray =
    ByteArray(data.size).also { out ->
        for (off in data.indices step 16) aes.encryptBlock(data.copyOfRange(off, off + 16)).copyInto(out, off)
    }

internal fun aesEcbDecrypt(aes: Aes, data: ByteArray): ByteArray =
    ByteArray(data.size).also { out ->
        for (off in data.indices step 16) aes.decryptBlock(data.copyOfRange(off, off + 16)).copyInto(out, off)
    }
