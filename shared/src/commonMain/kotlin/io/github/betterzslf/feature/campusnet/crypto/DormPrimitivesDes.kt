package io.github.betterzslf.feature.campusnet.crypto

/**
 * DES (FIPS-46-3), plus the two compositions the carrier uses.
 *
 * DES is implemented here from the standard's tables rather than via a platform crypto API: this is
 * `commonMain`, and the two compositions below (`DES-ECB-six`, `3DES-CBC`) need the raw block
 * function anyway. Pinned by the FIPS known-answer vector in `DormPrimitiveVectorTest`.
 */

private val DES_IP = intArrayOf(
    58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4,
    62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40, 32, 24, 16, 8,
    57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
    61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7)

private val DES_FP = intArrayOf(
    40, 8, 48, 16, 56, 24, 64, 32, 39, 7, 47, 15, 55, 23, 63, 31,
    38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29,
    36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27,
    34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41, 9, 49, 17, 57, 25)

private val DES_E = intArrayOf(
    32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11, 12, 13,
    12, 13, 14, 15, 16, 17, 16, 17, 18, 19, 20, 21, 20, 21, 22, 23, 24, 25,
    24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1)

private val DES_P = intArrayOf(
    16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10,
    2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25)

private val DES_PC1 = intArrayOf(
    57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18, 10, 2,
    59, 51, 43, 35, 27, 19, 11, 3, 60, 52, 44, 36, 63, 55, 47, 39, 31, 23, 15, 7,
    62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 28, 20, 12, 4)

private val DES_PC2 = intArrayOf(
    14, 17, 11, 24, 1, 5, 3, 28, 15, 6, 21, 10, 23, 19, 12, 4,
    26, 8, 16, 7, 27, 20, 13, 2, 41, 52, 31, 37, 47, 55, 30, 40,
    51, 45, 33, 48, 44, 49, 39, 56, 34, 53, 46, 42, 50, 36, 29, 32)

private val DES_SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)

private val DES_SBOXES: Array<IntArray> = arrayOf(
    intArrayOf(14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7, 0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0, 15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13),
    intArrayOf(15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10, 3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15, 13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9),
    intArrayOf(10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8, 13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7, 1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12),
    intArrayOf(7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15, 13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4, 3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14),
    intArrayOf(2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9, 14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14, 11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3),
    intArrayOf(12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11, 10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6, 4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13),
    intArrayOf(4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1, 13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2, 6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12),
    intArrayOf(13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7, 1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8, 2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11))

private fun ByteArray.toBits(): IntArray {
    val out = IntArray(size * 8)
    for (i in indices) for (b in 0 until 8) out[i * 8 + b] = (u8(i) ushr (7 - b)) and 1
    return out
}

private fun IntArray.toBytes(): ByteArray {
    val out = ByteArray(size / 8)
    for (i in out.indices) {
        var v = 0
        for (b in 0 until 8) v = (v shl 1) or this[i * 8 + b]
        out[i] = v.toByte()
    }
    return out
}

private fun permute(src: IntArray, table: IntArray): IntArray =
    IntArray(table.size) { src[table[it] - 1] }

/**
 * DES key schedule.
 *
 * C and D are rotated as two independent 28-bit halves. Rotating the joined 56-bit value is a
 * different permutation and silently yields wrong subkeys — this was caught by the FIPS vector.
 */
private fun desSubkeys(key8: ByteArray): Array<IntArray> {
    require(key8.size == 8) { "DES key must be 8 bytes" }
    val cd = permute(key8.toBits(), DES_PC1)
    val c = cd.copyOfRange(0, 28)
    val d = cd.copyOfRange(28, 56)
    var cl = c
    var dl = d
    return Array(16) { round ->
        val s = DES_SHIFTS[round]
        cl = cl.copyOfRange(s, 28) + cl.copyOfRange(0, s)
        dl = dl.copyOfRange(s, 28) + dl.copyOfRange(0, s)
        permute(cl + dl, DES_PC2)
    }
}

/**
 * One DES block. Decryption is the same structure with the subkeys consumed in reverse, so the
 * caller passes the schedule and sets [decrypt] rather than reversing the array by hand.
 */
private fun desBlock(block: ByteArray, subkeys: Array<IntArray>, decrypt: Boolean = false): ByteArray {
    require(block.size == 8) { "DES block must be 8 bytes" }
    val ip = permute(block.toBits(), DES_IP)
    var l = ip.copyOfRange(0, 32)
    var r = ip.copyOfRange(32, 64)
    val order = if (decrypt) subkeys.reversed().toTypedArray() else subkeys
    for (ks in order) {
        val e = permute(r, DES_E)
        val x = IntArray(48) { e[it] xor ks[it] }
        val sOut = IntArray(32)
        for (box in 0 until 8) {
            val base = box * 6
            val row = (x[base] shl 1) or x[base + 5]
            val col = (x[base + 1] shl 3) or (x[base + 2] shl 2) or (x[base + 3] shl 1) or x[base + 4]
            val v = DES_SBOXES[box][row * 16 + col]
            sOut[box * 4] = (v ushr 3) and 1
            sOut[box * 4 + 1] = (v ushr 2) and 1
            sOut[box * 4 + 2] = (v ushr 1) and 1
            sOut[box * 4 + 3] = v and 1
        }
        val p = permute(sOut, DES_P)
        val newR = IntArray(32) { l[it] xor p[it] }
        l = r
        r = newR
    }
    return permute(r + l, DES_FP).toBytes()
}

/**
 * `DES-ECB-six`: six DES passes per block — E(K1) D(K2) E(K3) E(K4) D(K5) E(K6) — with the 48-byte
 * key split into six 8-byte keys. Zero-padded to 8 bytes.
 */
internal fun desEcbSixEncrypt(key48: ByteArray, data: ByteArray): ByteArray {
    require(key48.size == 48) { "DES-six key must be 48 bytes" }
    val keys = Array(6) { desSubkeys(key48.copyOfRange(it * 8, it * 8 + 8)) }
    val buf = data.zeroPadTo(8).let { if (it.isEmpty()) ByteArray(8) else it }
    for (off in buf.indices step 8) {
        var blk = buf.copyOfRange(off, off + 8)
        blk = desBlock(blk, keys[0])
        blk = desBlock(blk, keys[1], decrypt = true)
        blk = desBlock(blk, keys[2])
        blk = desBlock(blk, keys[3])
        blk = desBlock(blk, keys[4], decrypt = true)
        blk = desBlock(blk, keys[5])
        blk.copyInto(buf, off)
    }
    return buf
}

/** One 3DES EDE block: E(k1) D(k2) E(k3). */
private fun des3Block(block: ByteArray, key24: ByteArray, decrypt: Boolean = false): ByteArray {
    val s1 = desSubkeys(key24.copyOfRange(0, 8))
    val s2 = desSubkeys(key24.copyOfRange(8, 16))
    val s3 = desSubkeys(key24.copyOfRange(16, 24))
    return if (decrypt) {
        desBlock(desBlock(desBlock(block, s3, decrypt = true), s2), s1, decrypt = true)
    } else {
        desBlock(desBlock(desBlock(block, s1), s2, decrypt = true), s3)
    }
}

/**
 * `3DES-CBC` as the carrier composes it: the whole buffer is encrypted with **key2/iv2 first**, then
 * the result is encrypted again with key1/iv1.
 *
 * The order is not a detail: the reference calls `stage_encrypt(key2...)` before `stage_encrypt(key1...)`
 * even though `key1` is listed first in the constructor. Swapping them produces ciphertext that is
 * well-formed and wrong.
 */
internal fun desedeCbcEncrypt(key1: ByteArray, key2: ByteArray, iv1: ByteArray, iv2: ByteArray, data: ByteArray): ByteArray {
    require(key1.size == 24 && key2.size == 24) { "3DES keys must be 24 bytes" }
    var buf = data.zeroPadTo(8)
    for ((key, iv) in listOf(key2 to iv2, key1 to iv1)) {
        val out = ByteArray(buf.size)
        var prev = iv.copyOf(8)
        for (off in buf.indices step 8) {
            val enc = des3Block(buf.copyOfRange(off, off + 8).xorWith(prev), key)
            enc.copyInto(out, off)
            prev = enc
        }
        buf = out
    }
    return buf
}

/**
 * `3DES-double` CBC, which is a different composition from [desedeCbcEncrypt].
 *
 * The 48-byte key holds six independent 8-byte DES keys, not two 24-byte 3DES keys. The reference
 * runs two full CBC passes over the buffer: the second half of the key (offsets 24/32/40) first,
 * then the first half (0/8/16). Both passes reuse the same 8-byte IV.
 */
internal fun desedeDoubleCbcEncrypt(key48: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    require(key48.size == 48) { "3DES-double key must be 48 bytes" }
    fun triple(block: ByteArray, base: Int): ByteArray {
        val k1 = desSubkeys(key48.copyOfRange(base, base + 8))
        val k2 = desSubkeys(key48.copyOfRange(base + 8, base + 16))
        val k3 = desSubkeys(key48.copyOfRange(base + 16, base + 24))
        return desBlock(desBlock(desBlock(block, k1), k2, decrypt = true), k3)
    }
    var buf = data.zeroPadTo(8)
    for (base in intArrayOf(24, 0)) {
        val out = ByteArray(buf.size)
        var prev = iv.copyOf(8)
        for (off in buf.indices step 8) {
            val enc = triple(buf.copyOfRange(off, off + 8).xorWith(prev), base)
            enc.copyInto(out, off)
            prev = enc
        }
        buf = out
    }
    return buf
}

// ---------------------------------------------------------------- decrypt sides

/** `DES-ECB-six` decrypt: the six operations in reverse, each with its direction flipped. */
internal fun desEcbSixDecrypt(key48: ByteArray, data: ByteArray): ByteArray {
    require(key48.size == 48) { "DES-six key must be 48 bytes" }
    require(data.size % 8 == 0) { "DES block data must be a multiple of 8 bytes" }
    val keys = Array(6) { desSubkeys(key48.copyOfRange(it * 8, it * 8 + 8)) }
    val buf = data.copyOf()
    for (off in buf.indices step 8) {
        var blk = buf.copyOfRange(off, off + 8)
        blk = desBlock(blk, keys[5], decrypt = true)
        blk = desBlock(blk, keys[4])
        blk = desBlock(blk, keys[3], decrypt = true)
        blk = desBlock(blk, keys[2], decrypt = true)
        blk = desBlock(blk, keys[1])
        blk = desBlock(blk, keys[0], decrypt = true)
        blk.copyInto(buf, off)
    }
    return buf
}

/** `3DES-CBC` decrypt: the two passes run in the opposite order, each with the cipher reversed. */
internal fun desedeCbcDecrypt(key1: ByteArray, key2: ByteArray, iv1: ByteArray, iv2: ByteArray, data: ByteArray): ByteArray {
    require(data.size % 8 == 0) { "3DES block data must be a multiple of 8 bytes" }
    var buf = data.copyOf()
    for ((key, iv) in listOf(key1 to iv1, key2 to iv2)) {
        val out = ByteArray(buf.size)
        var prev = iv.copyOf(8)
        for (off in buf.indices step 8) {
            val ct = buf.copyOfRange(off, off + 8)
            des3Block(ct, key, decrypt = true).xorWith(prev).copyInto(out, off)
            prev = ct
        }
        buf = out
    }
    return buf
}

/** `3DES-double` decrypt: passes back out in the order 0 then 24, each reversed. */
internal fun desedeDoubleCbcDecrypt(key48: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    require(key48.size == 48) { "3DES-double key must be 48 bytes" }
    require(data.size % 8 == 0) { "3DES-double block data must be a multiple of 8 bytes" }
    fun tripleInverse(block: ByteArray, base: Int): ByteArray {
        val k1 = desSubkeys(key48.copyOfRange(base, base + 8))
        val k2 = desSubkeys(key48.copyOfRange(base + 8, base + 16))
        val k3 = desSubkeys(key48.copyOfRange(base + 16, base + 24))
        return desBlock(desBlock(desBlock(block, k3, decrypt = true), k2), k1, decrypt = true)
    }
    var buf = data.copyOf()
    for (base in intArrayOf(0, 24)) {
        val out = ByteArray(buf.size)
        var prev = iv.copyOf(8)
        for (off in buf.indices step 8) {
            val ct = buf.copyOfRange(off, off + 8)
            tripleInverse(ct, base).xorWith(prev).copyInto(out, off)
            prev = ct
        }
        buf = out
    }
    return buf
}
