package io.github.betterzslf.feature.campusnet.crypto

/**
 * TEA and XTEA, in the modified forms the carrier's Android channel actually uses.
 *
 * These are not textbook TEA: the reference subtracts `0x61C88647` on the encrypt side (so the
 * running sum travels towards `0xC6EF3720`), keys are byte-swapped, and the decrypt loop tracks a
 * second index because the assembler in the shipped library did. Reproducing the shipped output is
 * the only thing that matters here, so the loops mirror the reference statement for statement and
 * the results are pinned by vectors.
 */

// Declared as Int deliberately: the hex literals exceed Int.MAX_VALUE, and a `const val` inferred
// as Long would widen every use site and change the 32-bit wrap these ciphers depend on.
private val TEA_DELTA: Int = 0x61C88647.toInt()
private val XTEA_DELTA: Int = 0x9E3779B9.toInt()

/** 32 rounds of XTEA delta, wrapped. `0x9E3779B9 * 32` does not fit in an Int. */
private val XTEA_SUM_INITIAL: Int = (XTEA_DELTA.toLong() * 32).toInt()

/** One modified-TEA layer, encrypt side: 32 rounds. */
internal fun teaEncryptLayer(v0In: Int, v1In: Int, k: IntArray): Pair<Int, Int> {
    var a = v0In
    var b = v1In
    var sum = 0
    do {
        a += (b xor sum) + k[sum and 3] + ((b shl 4) xor (b ushr 5))
        sum -= TEA_DELTA
        b += k[(sum ushr 11) and 3] + (a xor sum) + ((a shl 4) xor (a ushr 5))
    } while (sum != 0xC6EF3720.toInt())
    return a to b
}

/**
 * One modified-TEA layer, decrypt side.
 *
 * `kidx` is not `sum`: the shipped code uses a separate accumulator that starts at `-31 * delta`,
 * which is what makes this the exact inverse of [teaEncryptLayer] here.
 */
internal fun teaDecryptLayer(v0In: Int, v1In: Int, k: IntArray): Pair<Int, Int> {
    var a = v0In
    var b = v1In
    var sum = 0xC6EF3720.toInt()
    var kidx = 0x28B7BD67
    do {
        b -= (a xor sum) + ((a shl 4) xor (a ushr 5)) + k[(sum ushr 11) and 3]
        sum += TEA_DELTA
        a -= k[kidx and 3] + (b xor kidx) + ((b shl 4) xor (b ushr 5))
        kidx += TEA_DELTA
    } while (sum != 0)
    return a to b
}
/** The 48-byte key is three 4-word layers, each word byte-swapped. */
internal fun teaKeyLayers(key48: ByteArray): Array<IntArray> {
    require(key48.size == 48) { "TEA triple key must be 48 bytes" }
    return Array(3) { layer ->
        IntArray(4) { i -> Integer.reverseBytes(key48.be32((layer * 4 + i) * 4)) }
    }
}

/** Triple modified-TEA in ECB mode, zero-padded to 8 bytes. Layers run 2, 1, 0. */
internal fun teaTripleEcbEncrypt(key48: ByteArray, data: ByteArray): ByteArray {
    val buf = data.zeroPadTo(8).let { if (it.isEmpty()) ByteArray(8) else it }
    val k = teaKeyLayers(key48)
    for (off in buf.indices step 8) {
        var (v0, v1) = buf.be32(off) to buf.be32(off + 4)
        for (layer in intArrayOf(2, 1, 0)) {
            val r = teaEncryptLayer(v0, v1, k[layer]); v0 = r.first; v1 = r.second
        }
        buf.putBe32(off, v0); buf.putBe32(off + 4, v1)
    }
    return buf
}

/** Triple modified-TEA in CBC mode with an 8-byte IV. Layers run 2, 1, 0. */
internal fun teaTripleCbcEncrypt(key48: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    val buf = data.zeroPadTo(8).let { if (it.isEmpty()) ByteArray(8) else it }
    val k = teaKeyLayers(key48)
    var prev = iv.copyOf(8)
    for (off in buf.indices step 8) {
        var v0 = buf.be32(off) xor prev.be32(0)
        var v1 = buf.be32(off + 4) xor prev.be32(4)
        for (layer in intArrayOf(2, 1, 0)) {
            val r = teaEncryptLayer(v0, v1, k[layer]); v0 = r.first; v1 = r.second
        }
        buf.putBe32(off, v0); buf.putBe32(off + 4, v1)
        prev = buf.copyOfRange(off, off + 8)
    }
    return buf
}

// ---------------------------------------------------------------- XTEA

/** One XTEA block, 32 rounds. [swapKeyEndian] is fixed per family by the reference dispatch. */
internal fun xteaEncryptBlock(v0In: Int, v1In: Int, key: IntArray, swapKeyEndian: Boolean): Pair<Int, Int> {
    val ks = IntArray(4) { if (swapKeyEndian) Integer.reverseBytes(key[it]) else key[it] }
    var v0 = v0In
    var v1 = v1In
    var sum = 0
    repeat(32) {
        v0 += ((v1 shl 4) xor (v1 ushr 5)) + (sum xor v1) + ks[sum and 3]
        sum += XTEA_DELTA
        v1 += ((v0 shl 4) xor (v0 ushr 5)) + (v0 xor sum) + ks[(sum ushr 11) and 3]
    }
    return v0 to v1
}

internal fun xteaDecryptBlock(v0In: Int, v1In: Int, key: IntArray, swapKeyEndian: Boolean): Pair<Int, Int> {
    val ks = IntArray(4) { if (swapKeyEndian) Integer.reverseBytes(key[it]) else key[it] }
    var v0 = v0In
    var v1 = v1In
    // Written with explicit Int arithmetic: `XTEA_DELTA * 32` overflows, and letting Kotlin
    // promote the whole expression to Long would silently change the wrap-around behaviour.
    var sum: Int = XTEA_SUM_INITIAL
    repeat(32) {
        v1 -= ((v0 shl 4) xor (v0 ushr 5)) + (v0 xor sum) + ks[(sum ushr 11) and 3]
        sum -= XTEA_DELTA
        v0 -= ((v1 shl 4) xor (v1 ushr 5)) + (sum xor v1) + ks[sum and 3]
    }
    return v0 to v1
}

/** `mod-XTEA`: three consecutive XTEA passes, no chaining. */
internal fun modXteaEncrypt(keys: List<IntArray>, swapKeyEndian: Boolean, data: ByteArray): ByteArray {
    val buf = data.zeroPadTo(8)
    for (off in buf.indices step 8) {
        var v0 = buf.be32(off)
        var v1 = buf.be32(off + 4)
        for (k in keys) {
            val r = xteaEncryptBlock(v0, v1, k, swapKeyEndian); v0 = r.first; v1 = r.second
        }
        buf.putBe32(off, v0); buf.putBe32(off + 4, v1)
    }
    return buf
}

/**
 * `mod-XTEA-IV`: like CBC, but the chaining value is the previous ciphertext block and the key
 * order is reversed (3, 2, 1) relative to `mod-XTEA`.
 */
internal fun modXteaIvEncrypt(keys: List<IntArray>, iv: IntArray, data: ByteArray): ByteArray {
    val buf = data.zeroPadTo(8)
    var prev0 = iv[0]
    var prev1 = iv[1]
    for (off in buf.indices step 8) {
        var v0 = buf.be32(off) xor prev0
        var v1 = buf.be32(off + 4) xor prev1
        for (k in keys.reversed()) {
            val r = xteaEncryptBlock(v0, v1, k, swapKeyEndian = false); v0 = r.first; v1 = r.second
        }
        buf.putBe32(off, v0); buf.putBe32(off + 4, v1)
        prev0 = v0; prev1 = v1
    }
    return buf
}
