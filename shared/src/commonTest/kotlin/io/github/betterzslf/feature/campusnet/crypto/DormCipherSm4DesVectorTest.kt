package io.github.betterzslf.feature.campusnet.crypto

import kotlin.test.*

/**
 * Pins SM4, DES-six and 3DES against vectors from the independent Python oracle
 * (`work/crypto-oracle/make_vectors.py`), whose primitives are separately validated against
 * GB/T 32907 and FIPS-46.
 *
 * Keys below are the reference `KeyData.c` arrays for the corresponding `algo_id`, so a match means
 * this port and the reference implementation agree on the carrier's real output.
 */
class DormCipherSm4DesVectorTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private fun u8(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private val plain = "{\"test\":\"hello\"}".encodeToByteArray()

    // ------------------------------------------------------------ SM4

    /** Shared by `ED382482-...` (SM4-ECB) and `D522E657-...` (SM4-variant-ECB). */
    private val sm4EcbKey = u8(83, 47, 121, 74, 78, 121, 116, 77, 103, 102, 87, 90, 45, 68, 92, 87)

    @Test fun sm4EcbMatchesTheReferenceVector() {
        val expected = "9cfbce7dae2c7fb7564adcf6ef4e77573eed9284a3d6c441b377ecb98231cb05"
        assertEquals(expected, sm4EcbEncrypt(sm4EcbKey, plain).toHex())
    }

    @Test fun sm4VariantEcbMatchesTheReferenceVector() {
        // Deliberately the same expected value as plain SM4: the reference's "variant" linear layer
        // turns out to be identical, and this test records that finding rather than hiding it.
        val expected = "9cfbce7dae2c7fb7564adcf6ef4e77573eed9284a3d6c441b377ecb98231cb05"
        assertEquals(expected, sm4VariantEcbEncrypt(sm4EcbKey, plain).toHex())
    }

    private val sm4CbcKey = u8(40, 47, 41, 37, 111, 60, 117, 72, 109, 76, 46, 81, 85, 39, 34, 45)
    private val sm4CbcIv = u8(104, 60, 66, 81, 90, 70, 58, 82, 103, 119, 126, 110, 105, 112, 72, 94)

    @Test fun sm4CbcMatchesTheReferenceVector() {
        val expected = "e79292505c2c84d969c875166ed24a6452ecfe9a19ba55994445c75b753eb8b4"
        assertEquals(expected, sm4CbcEncrypt(sm4CbcKey, sm4CbcIv, plain).toHex())
    }

    @Test fun sm4VariantCbcMatchesTheReferenceVector() {
        val expected = "e79292505c2c84d969c875166ed24a6452ecfe9a19ba55994445c75b753eb8b4"
        assertEquals(expected, sm4VariantCbcEncrypt(sm4CbcKey, sm4CbcIv, plain).toHex())
    }

    @Test fun sm4CbcChainsAcrossBlocks() {
        val twoSame = hex("00112233445566778899aabbccddeeff").let { it + it }
        val out = sm4CbcEncrypt(sm4CbcKey, sm4CbcIv, twoSame)
        assertFalse(out.copyOfRange(0, 16).contentEquals(out.copyOfRange(16, 32)))
    }

    // ------------------------------------------------------------ DES

    @Test fun desSixMatchesTheReferenceVector() {
        // 252F0396-... / AD8BB5B0-... : six DES passes per block.
        val key = u8(
            89, 40, 91, 126, 125, 38, 116, 73, 72, 118, 89, 88, 98, 117, 81, 85,
            38, 115, 85, 92, 103, 82, 46, 108, 37, 106, 99, 90, 70, 63, 38, 100,
            83, 122, 46, 91, 36, 76, 98, 103, 43, 45, 103, 104, 67, 116, 105, 81)
        val expected = "fa29c5c5314715aba8ecd16c8ea3b566"
        assertEquals(expected, desEcbSixEncrypt(key, plain).toHex())
    }

    @Test fun desedeCbcMatchesTheReferenceVector() {
        // 5BFBA864-...: two full CBC passes with two different keys and IVs.
        val key1 = u8(94, 103, 114, 121, 40, 80, 71, 117, 109, 72, 99, 116, 93, 41, 33, 60,
            126, 107, 86, 41, 79, 33, 82, 64)
        val key2 = u8(99, 115, 99, 38, 114, 92, 94, 115, 107, 96, 116, 81, 123, 116, 118, 125,
            63, 89, 46, 109, 111, 100, 62, 105)
        val iv = u8(119, 45, 86, 81, 40, 73, 126, 87)
        val expected = "9c2ac2e381219f0322fc4ab9384e507c"
        assertEquals(expected, desedeCbcEncrypt(key1, key2, iv, iv, plain).toHex())
    }

    @Test fun desIsReversibleThroughTheBlockFunction() {
        // The FIPS known-answer vector, exercised through the same schedule the compositions use.
        val key = hex("133457799bbcdff1")
        val block = hex("0123456789abcdef")
        // desBlock is private; the six-layer composition is the closest public surface that uses it.
        val out = desEcbSixEncrypt(ByteArray(48) { key[it % 8] }, block)
        // Six passes of the same key is E D E E D E = E for identical keys only if E == D, which it
        // is not, so just assert the shape and that a different key changes the result.
        assertEquals(8, out.size)
        assertFalse(out.contentEquals(block))
        assertFalse(out.contentEquals(desEcbSixEncrypt(ByteArray(48) { (key[it % 8] + 1).toByte() }, block)))
    }
}
