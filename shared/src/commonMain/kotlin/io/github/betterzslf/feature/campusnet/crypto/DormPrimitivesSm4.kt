package io.github.betterzslf.feature.campusnet.crypto

/**
 * SM4 (GB/T 32907) and the carrier's "SM4 variant".
 *
 * The variant keeps the standard S-box and the FK/CK constants but substitutes a **different linear
 * layer**, so it is not just SM4 in another mode. Both share the same round structure, which is why
 * one implementation parameterised by the linear transform covers all four families.
 *
 * The S-box is transcribed (it is a fixed published table, not derivable from a compact rule like
 * AES's), so it is pinned by the standard vector in `DormPrimitiveVectorTest`.
 */

private val SM4_SBOX: IntArray = intArrayOf(
    0xd6, 0x90, 0xe9, 0xfe, 0xcc, 0xe1, 0x3d, 0xb7, 0x16, 0xb6, 0x14, 0xc2, 0x28, 0xfb, 0x2c, 0x05,
    0x2b, 0x67, 0x9a, 0x76, 0x2a, 0xbe, 0x04, 0xc3, 0xaa, 0x44, 0x13, 0x26, 0x49, 0x86, 0x06, 0x99,
    0x9c, 0x42, 0x50, 0xf4, 0x91, 0xef, 0x98, 0x7a, 0x33, 0x54, 0x0b, 0x43, 0xed, 0xcf, 0xac, 0x62,
    0xe4, 0xb3, 0x1c, 0xa9, 0xc9, 0x08, 0xe8, 0x95, 0x80, 0xdf, 0x94, 0xfa, 0x75, 0x8f, 0x3f, 0xa6,
    0x47, 0x07, 0xa7, 0xfc, 0xf3, 0x73, 0x17, 0xba, 0x83, 0x59, 0x3c, 0x19, 0xe6, 0x85, 0x4f, 0xa8,
    0x68, 0x6b, 0x81, 0xb2, 0x71, 0x64, 0xda, 0x8b, 0xf8, 0xeb, 0x0f, 0x4b, 0x70, 0x56, 0x9d, 0x35,
    0x1e, 0x24, 0x0e, 0x5e, 0x63, 0x58, 0xd1, 0xa2, 0x25, 0x22, 0x7c, 0x3b, 0x01, 0x21, 0x78, 0x87,
    0xd4, 0x00, 0x46, 0x57, 0x9f, 0xd3, 0x27, 0x52, 0x4c, 0x36, 0x02, 0xe7, 0xa0, 0xc4, 0xc8, 0x9e,
    0xea, 0xbf, 0x8a, 0xd2, 0x40, 0xc7, 0x38, 0xb5, 0xa3, 0xf7, 0xf2, 0xce, 0xf9, 0x61, 0x15, 0xa1,
    0xe0, 0xae, 0x5d, 0xa4, 0x9b, 0x34, 0x1a, 0x55, 0xad, 0x93, 0x32, 0x30, 0xf5, 0x8c, 0xb1, 0xe3,
    0x1d, 0xf6, 0xe2, 0x2e, 0x82, 0x66, 0xca, 0x60, 0xc0, 0x29, 0x23, 0xab, 0x0d, 0x53, 0x4e, 0x6f,
    0xd5, 0xdb, 0x37, 0x45, 0xde, 0xfd, 0x8e, 0x2f, 0x03, 0xff, 0x6a, 0x72, 0x6d, 0x6c, 0x5b, 0x51,
    0x8d, 0x1b, 0xaf, 0x92, 0xbb, 0xdd, 0xbc, 0x7f, 0x11, 0xd9, 0x5c, 0x41, 0x1f, 0x10, 0x5a, 0xd8,
    0x0a, 0xc1, 0x31, 0x88, 0xa5, 0xcd, 0x7b, 0xbd, 0x2d, 0x74, 0xd0, 0x12, 0xb8, 0xe5, 0xb4, 0xb0,
    0x89, 0x69, 0x97, 0x4a, 0x0c, 0x96, 0x77, 0x7e, 0x65, 0xb9, 0xf1, 0x09, 0xc5, 0x6e, 0xc6, 0x84,
    0x18, 0xf0, 0x7d, 0xec, 0x3a, 0xdc, 0x4d, 0x20, 0x79, 0xee, 0x5f, 0x3e, 0xd7, 0xcb, 0x39, 0x48)

/**
 * Constants are declared as Int on purpose: `0xa3b1bac6.toInt()` and friends exceed Int.MAX_VALUE, and a
 * Long-inferred constant would widen every use site and change the 32-bit wrap the cipher needs.
 */
private val SM4_FK: IntArray = intArrayOf(
    0xa3b1bac6.toInt(), 0x56aa3350.toInt(), 0x677d9197.toInt(), 0xb27022dc.toInt())

private val SM4_CK: IntArray = intArrayOf(
    0x00070e15, 0x1c232a31, 0x383f464d, 0x545b6269, 0x70777e85, 0x8c939aa1.toInt(), 0xa8afb6bd.toInt(), 0xc4cbd2d9.toInt(),
    0xe0e7eef5.toInt(), 0xfc030a11.toInt(), 0x181f262d, 0x343b4249, 0x50575e65, 0x6c737a81, 0x888f969d.toInt(), 0xa4abb2b9.toInt(),
    0xc0c7ced5.toInt(), 0xdce3eaf1.toInt(), 0xf8ff060d.toInt(), 0x141b2229, 0x30373e45, 0x4c535a61, 0x686f767d, 0x848b9299.toInt(),
    0xa0a7aeb5.toInt(), 0xbcc3cad1.toInt(), 0xd8dfe6ed.toInt(), 0xf4fb0209.toInt(), 0x10171e25, 0x2c333a41, 0x484f565d, 0x646b7279)

private fun sm4Tau(a: Int): Int =
    (SM4_SBOX[(a ushr 24) and 0xFF] shl 24) or (SM4_SBOX[(a ushr 16) and 0xFF] shl 16) or
        (SM4_SBOX[(a ushr 8) and 0xFF] shl 8) or SM4_SBOX[a and 0xFF]

/** Standard SM4 linear layer. */
private fun sm4L(b: Int): Int =
    b xor Integer.rotateLeft(b, 2) xor Integer.rotateLeft(b, 10) xor
        Integer.rotateLeft(b, 18) xor Integer.rotateLeft(b, 24)

/** The variant family's key-schedule layer. */
private fun sm4Lp(b: Int): Int =
    b xor Integer.rotateLeft(b, 13) xor Integer.rotateLeft(b, 23)

/**
 * The carrier's "SM4 variant" round function.
 *
 * Despite the name, the reference implementation computes the **same** linear layer as standard
 * SM4 — it is written with shifts and ORs instead of rotate calls, but the rotations are 2/10/18/24
 * in both. Verified two ways: the reference vectors for `SM4-ECB` and `SM4-variant-ECB` are
 * byte-identical for the same key, and the disassembly comments show the same tap positions.
 * Keeping the two entry points separate anyway, because they select different `algo_id`s and a
 * future divergence must not silently alias them.
 */
private fun sm4VariantL(b: Int): Int =
    // Written as shifts on purpose: this mirrors the reference's shift/OR form so the two can be
    // compared line by line if a future gateway ever diverges.
    (b.toLong() or 0).let {
        val e0 = (b ushr 30) or (b shl 2)
        val e1 = (b shl 10) or (b ushr 22)
        (b xor e0 xor e1 xor
            ((b shl 18) or (b ushr 14)) xor
            ((b shl 24) or (b ushr 8)))
    }

private class Sm4(private val variant: Boolean) {
    /** Block-level linear layer. */
    private fun lRound(b: Int) = if (variant) sm4VariantL(b) else sm4L(b)

    /**
     * Key-schedule linear layer. Both SM4 and its variant use `L'` here — using `L` in the key
     * schedule produces round keys that are wrong from the very first one, while the block function
     * still looks plausible. That was a real defect caught by the standard vector.
     */
    private fun lpRound(b: Int) = sm4Lp(b)

    fun expandKey(key: ByteArray): IntArray {
        require(key.size == 16) { "SM4 key must be 16 bytes" }
        // Separate arrays on purpose: the round keys and the key state must not alias, or writing
        // the next round key would clobber the value just produced.
        val k = IntArray(36)
        val rk = IntArray(32)
        for (i in 0 until 4) k[i] = key.be32(i * 4) xor SM4_FK[i]
        for (i in 0 until 32) {
            val t = k[i + 1] xor k[i + 2] xor k[i + 3] xor SM4_CK[i]
            val next = k[i] xor lpRound(sm4Tau(t))
            rk[i] = next
            k[i + 4] = next
        }
        return rk
    }

    fun encryptBlock(block: ByteArray, rk: IntArray): ByteArray {
        require(block.size == 16) { "SM4 block must be 16 bytes" }
        val x = IntArray(36)
        for (i in 0 until 4) x[i] = block.be32(i * 4)
        for (i in 0 until 32) {
            val t = x[i + 1] xor x[i + 2] xor x[i + 3] xor rk[i]
            x[i + 4] = x[i] xor lRound(sm4Tau(t))
        }
        // Ciphertext is (X35, X34, X33, X32), i.e. the last four words in reverse order.
        val out = ByteArray(16)
        for (i in 0 until 4) out.putBe32(i * 4, x[35 - i])
        return out
    }

    /**
     * Decryption runs the same round function with the round keys in reverse order, then undoes the
     * output reversal. No inverse S-box is needed: SM4's structure is a Feistel-like network.
     */
    fun decryptBlock(block: ByteArray, rk: IntArray): ByteArray {
        require(block.size == 16) { "SM4 block must be 16 bytes" }
        val x = IntArray(36)
        for (i in 0 until 4) x[i] = block.be32(i * 4)
        for (i in 0 until 32) {
            val t = x[i + 1] xor x[i + 2] xor x[i + 3] xor rk[31 - i]
            x[i + 4] = x[i] xor lRound(sm4Tau(t))
        }
        val out = ByteArray(16)
        for (i in 0 until 4) out.putBe32(i * 4, x[35 - i])
        return out
    }
}

/** Standard SM4-ECB with PKCS#7 padding. */
internal fun sm4EcbEncrypt(key: ByteArray, data: ByteArray): ByteArray {
    val sm4 = Sm4(variant = false)
    val rk = sm4.expandKey(key)
    val padded = data.pkcs7Pad(16)
    return ByteArray(padded.size).also { out ->
        for (off in padded.indices step 16) sm4.encryptBlock(padded.copyOfRange(off, off + 16), rk).copyInto(out, off)
    }
}

/** Standard SM4-CBC with PKCS#7 padding. */
internal fun sm4CbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    val sm4 = Sm4(variant = false)
    val rk = sm4.expandKey(key)
    val padded = data.pkcs7Pad(16)
    val out = ByteArray(padded.size)
    var prev = iv.copyOf(16)
    for (off in padded.indices step 16) {
        val enc = sm4.encryptBlock(padded.copyOfRange(off, off + 16).xorWith(prev), rk)
        enc.copyInto(out, off)
        prev = enc
    }
    return out
}

/** `SM4-variant` ECB: same S-box and constants, different linear layer. */
internal fun sm4VariantEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray {
    val sm4 = Sm4(variant = true)
    val rk = sm4.expandKey(key)
    val padded = data.pkcs7Pad(16)
    return ByteArray(padded.size).also { out ->
        for (off in padded.indices step 16) sm4.encryptBlock(padded.copyOfRange(off, off + 16), rk).copyInto(out, off)
    }
}

/** `SM4-variant` CBC. */
internal fun sm4VariantCbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
    val sm4 = Sm4(variant = true)
    val rk = sm4.expandKey(key)
    val padded = data.pkcs7Pad(16)
    val out = ByteArray(padded.size)
    var prev = iv.copyOf(16)
    for (off in padded.indices step 16) {
        val enc = sm4.encryptBlock(padded.copyOfRange(off, off + 16).xorWith(prev), rk)
        enc.copyInto(out, off)
        prev = enc
    }
    return out
}

// ---------------------------------------------------------------- decrypt sides

private fun sm4DecryptBlocks(sm4: Sm4, rk: IntArray, data: ByteArray): ByteArray {
    require(data.isNotEmpty() && data.size % 16 == 0) { "SM4 ciphertext must be whole blocks" }
    return ByteArray(data.size).also { out ->
        for (off in data.indices step 16) sm4.decryptBlock(data.copyOfRange(off, off + 16), rk).copyInto(out, off)
    }
}

/** Standard SM4-ECB decrypt; returns `null` when the PKCS#7 padding is invalid. */
internal fun sm4EcbDecrypt(key: ByteArray, data: ByteArray): ByteArray? {
    val sm4 = Sm4(variant = false)
    return sm4DecryptBlocks(sm4, sm4.expandKey(key), data).pkcs7Unpad()
}

internal fun sm4CbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray? {
    val sm4 = Sm4(variant = false)
    val rk = sm4.expandKey(key)
    require(data.isNotEmpty() && data.size % 16 == 0) { "SM4 ciphertext must be whole blocks" }
    val out = ByteArray(data.size)
    var prev = iv.copyOf(16)
    for (off in data.indices step 16) {
        val ct = data.copyOfRange(off, off + 16)
        sm4.decryptBlock(ct, rk).xorWith(prev).copyInto(out, off)
        prev = ct
    }
    return out.pkcs7Unpad()
}

internal fun sm4VariantEcbDecrypt(key: ByteArray, data: ByteArray): ByteArray? {
    val sm4 = Sm4(variant = true)
    return sm4DecryptBlocks(sm4, sm4.expandKey(key), data).pkcs7Unpad()
}

internal fun sm4VariantCbcDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray? {
    val sm4 = Sm4(variant = true)
    val rk = sm4.expandKey(key)
    require(data.isNotEmpty() && data.size % 16 == 0) { "SM4 ciphertext must be whole blocks" }
    val out = ByteArray(data.size)
    var prev = iv.copyOf(16)
    for (off in data.indices step 16) {
        val ct = data.copyOfRange(off, off + 16)
        sm4.decryptBlock(ct, rk).xorWith(prev).copyInto(out, off)
        prev = ct
    }
    return out.pkcs7Unpad()
}
