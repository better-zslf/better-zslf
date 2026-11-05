package io.github.betterzslf.feature.campusnet.crypto

import io.github.betterzslf.feature.campusnet.CAMPUS_ALGO_FAMILIES
import kotlin.test.*

/**
 * Pins the ported primitives against vectors produced by an independent Python implementation
 * (`work/crypto-oracle/make_vectors.py`).
 *
 * That oracle's own building blocks are checked against the published standards first
 * (FIPS-197 for AES, FIPS-46 for DES, GB/T 32907 for SM4, 3GPP TS 35.221 for ZUC), so a match here
 * means two independent implementations agree on the carrier's actual output — not that the port
 * agrees with itself.
 *
 * Every assertion is exact equality: a single wrong byte produces a login the gateway rejects, and
 * repeated attempts lock the account for 30 minutes.
 */
class DormCipherVectorTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    private fun u8(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

    private val plain = "{\"test\":\"hello\"}".encodeToByteArray()

    // ------------------------------------------------------------ triple modified-TEA

    /** `key_319FC5AB_...`: 48 bytes, straight from the reference `KeyData.c`. */
    private val teaEcbKey = u8(
        122, 122, 103, 106, 39, 126, 74, 115, 62, 67, 41, 108, 87, 125, 125, 122,
        61, 60, 105, 95, 113, 121, 122, 116, 68, 95, 87, 99, 111, 105, 39, 101,
        91, 90, 104, 61, 46, 87, 42, 119, 74, 71, 68, 101, 102, 61, 126, 92)

    @Test fun teaTripleEcbMatchesTheReferenceVector() {
        // 319FC5AB-EC0E-46B9-A252-2285F9DAE813 and 3084D519-... share this vector.
        val expected = "4eb07b5c6562f972247c134ca61133e8"
        assertEquals(expected, teaTripleEcbEncrypt(teaEcbKey, plain).toHex())
    }

    @Test fun teaTripleCbcMatchesTheReferenceVector() {
        // 35101415-A20F-4DFE-B00B-0B4F3B2F8C66 and 406BD7C5-... share this vector.
        val key = u8(
            121, 109, 120, 85, 41, 123, 35, 85, 88, 125, 114, 110, 77, 61, 68, 35,
            124, 112, 82, 93, 90, 88, 93, 61, 65, 62, 64, 41, 40, 117, 93, 106,
            66, 94, 95, 110, 70, 117, 78, 36, 80, 123, 35, 61, 45, 100, 70, 65)
        // The reference declares a 16-byte IV for this family but CBC consumes 8 bytes.
        val iv = u8(84, 76, 47, 63, 111, 72, 81, 33, 163, 118, 176, 155, 97, 240, 150, 57)
        val expected = "78b2f48497bb8a3081c4d796e59ed0ce"
        assertEquals(expected, teaTripleCbcEncrypt(key, iv, plain).toHex())
    }

    @Test fun teaKeyLayersAreThreeFourWordGroups() {
        val key = ByteArray(48) { it.toByte() }
        val layers = teaKeyLayers(key)
        assertEquals(3, layers.size)
        // Byte-swapped: layer 0 word 0 comes from bytes 0..3 reversed.
        assertEquals(Integer.reverseBytes(key.be32(0)), layers[0][0])
        assertEquals(Integer.reverseBytes(key.be32(12)), layers[0][3])
        assertEquals(Integer.reverseBytes(key.be32(32)), layers[2][0])
    }

    @Test fun teaLayersInvertEachOther() {
        val k = IntArray(4) { 0x01020304 * (it + 1) }
        var v0 = 0x11223344
        var v1 = 0x55667788
        val start = v0 to v1
        repeat(2) { val r = teaEncryptLayer(v0, v1, k); v0 = r.first; v1 = r.second }
        repeat(2) { val r = teaDecryptLayer(v0, v1, k); v0 = r.first; v1 = r.second }
        assertEquals(start, v0 to v1)
    }

    // ------------------------------------------------------------ XTEA

    private val xteaKeys = listOf(
        intArrayOf(2054842218, 662588019, 1044588908, 1467841914),
        intArrayOf(1027369311, 1903786612, 1147098979, 1869162341),
        intArrayOf(1532651581, 777464439, 1246184549, 1715306076))

    @Test fun modXteaMatchesTheReferenceVector() {
        // B3047D4E-67DF-4864-A6A5-DF9B9E525C79: three XTEA passes, no chaining.
        val expected = "132d719a0a11842c635d974701ec3ee2"
        assertEquals(expected, modXteaEncrypt(xteaKeys, swapKeyEndian = false, data = plain).toHex())
    }

    @Test fun modXteaIvMatchesTheReferenceVector() {
        // C32C68F9-CA81-4260-A329-BBAFD1A9CCD1: chained, and key order is 3,2,1.
        val keys = listOf(
            intArrayOf(2037217365, 695935829, 1484616302, 1295860771),
            intArrayOf(2087735901, 1515740477, 1094598697, 678780266),
            intArrayOf(1113481070, 1182092836, 1350247229, 761546305))
        val iv = intArrayOf(1414278975, 1867010337)
        val expected = "55973976d4d527edea705875e97a8a9a"
        assertEquals(expected, modXteaIvEncrypt(keys, iv, plain).toHex())
    }

    @Test fun modXteaRoundTripsThroughDecrypt() {
        val block = hex("0011223344556677")
        var v0 = block.be32(0)
        var v1 = block.be32(4)
        for (k in xteaKeys) { val r = xteaEncryptBlock(v0, v1, k, false); v0 = r.first; v1 = r.second }
        for (k in xteaKeys.reversed()) { val r = xteaDecryptBlock(v0, v1, k, false); v0 = r.first; v1 = r.second }
        assertEquals(block.be32(0), v0)
        assertEquals(block.be32(4), v1)
    }

    @Test fun keyEndianSwapChangesTheResult() {
        // The reference fixes this per platform; getting it wrong silently produces other bytes.
        val k = IntArray(4) { 0x01020304 }
        val a = xteaEncryptBlock(1, 2, k, swapKeyEndian = false)
        val b = xteaEncryptBlock(1, 2, k, swapKeyEndian = true)
        assertNotEquals(a, b)
    }

    @Test fun modXteaIvChainsSoIdenticalBlocksDiffer() {
        val iv = intArrayOf(0x11111111, 0x22222222)
        val twoSameBlocks = hex("0011223344556677").let { it + it }
        val out = modXteaIvEncrypt(xteaKeys, iv, twoSameBlocks)
        // CBC-style chaining: equal plaintext blocks must not encrypt to equal ciphertext blocks.
        assertFalse(out.copyOfRange(0, 8).contentEquals(out.copyOfRange(8, 16)))
    }

    // ------------------------------------------------------------ family table sanity

    @Test fun theFamilyTableStillDescribesEveryPortedAlgoId() {
        // The table drives the diagnosis report; it must stay in step with what is implemented.
        assertEquals(27, CAMPUS_ALGO_FAMILIES.size)
        val families = CAMPUS_ALGO_FAMILIES.values.toSet()
        // Every family the port covers appears at least once.
        for (name in listOf("AES-CBC", "AES-ECB", "double-AES-128-CBC", "double-AES-128-ECB",
            "triple-modified-TEA-CBC", "triple-modified-TEA-ECB", "mod-XTEA", "mod-XTEA-IV")) {
            assertTrue(name in families, name)
        }
    }
}
