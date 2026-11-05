package io.github.betterzslf.feature.campusnet.crypto

import kotlin.test.*

/**
 * Standard test vectors, not self-consistency checks.
 *
 * A cipher that only round-trips against itself can be wrong in a way that round-tripping hides.
 * These are the published values, so a transcription slip fails here instead of becoming a rejected
 * login on a rate-limited gateway.
 */
class DormPrimitiveVectorTest {

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }
    private fun hex(s: String): ByteArray {
        val clean = s.filterNot { it == ' ' || it == '\n' }
        require(clean.length % 2 == 0) { "odd hex" }
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }

    // ------------------------------------------------------------ FIPS-197 appendix C

    @Test fun aes128MatchesTheFips197Vector() {
        // C.1: key 000102...0f, plaintext 00112233445566778899aabbccddeeff
        val aes = Aes(hex("000102030405060708090a0b0c0d0e0f"))
        val plain = hex("00112233445566778899aabbccddeeff")
        val expected = "69c4e0d86a7b0430d8cdb78070b4c55a"
        assertEquals(expected, aes.encryptBlock(plain).toHex())
        assertEquals(plain.toHex(), aes.decryptBlock(hex(expected)).toHex())
    }

    @Test fun aes192MatchesTheFips197Vector() {
        val aes = Aes(hex("000102030405060708090a0b0c0d0e0f1011121314151617"))
        val plain = hex("00112233445566778899aabbccddeeff")
        val expected = "dda97ca4864cdfe06eaf70a0ec0d7191"
        assertEquals(expected, aes.encryptBlock(plain).toHex())
        assertEquals(plain.toHex(), aes.decryptBlock(hex(expected)).toHex())
    }

    @Test fun aes256MatchesTheFips197Vector() {
        val aes = Aes(hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"))
        val plain = hex("00112233445566778899aabbccddeeff")
        val expected = "8ea2b7ca516745bfeafc49904b496089"
        assertEquals(expected, aes.encryptBlock(plain).toHex())
        assertEquals(plain.toHex(), aes.decryptBlock(hex(expected)).toHex())
    }

    @Test fun theGeneratedSboxesAreTheStandardOnes() {
        // Spot-check the well-known entries, including the fixed point at 0x00 -> 0x63.
        assertEquals(0x63, Aes.SBOX[0x00])
        assertEquals(0x7c, Aes.SBOX[0x01])
        assertEquals(0x00, Aes.SBOX[0x52])
        assertEquals(0x16, Aes.SBOX[0xFF])
        assertEquals(0x00, Aes.SBOX_INV[0x63])
        // Inverse really is inverse for every byte, not just the checked ones.
        for (i in 0 until 256) assertEquals(i, Aes.SBOX_INV[Aes.SBOX[i]])
    }

    @Test fun aesCbcChainsAcrossBlocksAndRoundTrips() {
        // NIST SP 800-38A F.2.1 (CBC-AES128), first two blocks.
        val aes = Aes(hex("2b7e151628aed2a6abf7158809cf4f3c"))
        val iv = hex("000102030405060708090a0b0c0d0e0f")
        val plain = hex("6bc1bee22e409f96e93d7e117393172a" + "ae2d8a571e03ac9c9eb76fac45af8e51")
        val expected = "7649abac8119b246cee98e9b12e9197d" + "5086cb9b507219ee95db113a917678b2"
        val enc = aesCbcEncrypt(aes, iv, plain)
        assertEquals(expected, enc.toHex())
        assertEquals(plain.toHex(), aesCbcDecrypt(aes, iv, enc).toHex())
    }

    @Test fun aesEcbRoundTripsAndIsDeterministicPerBlock() {
        val aes = Aes(hex("000102030405060708090a0b0c0d0e0f"))
        val plain = hex("00112233445566778899aabbccddeeff" + "00112233445566778899aabbccddeeff")
        val enc = aesEcbEncrypt(aes, plain)
        // ECB: identical plaintext blocks give identical ciphertext blocks. That is the definition.
        assertEquals(enc.copyOfRange(0, 16).toHex(), enc.copyOfRange(16, 32).toHex())
        assertEquals(plain.toHex(), aesEcbDecrypt(aes, enc).toHex())
    }

    // ------------------------------------------------------------ shared helpers

    @Test fun paddingHelpersFollowPkcs7AndZeroPadRules() {
        // PKCS#7 always adds at least one byte, so a full block gains a whole extra block.
        assertEquals(16, bytes(1, 2, 3).pkcs7Pad(16).size)
        assertEquals(32, ByteArray(16).pkcs7Pad(16).size)
        assertEquals(0x0D.toByte(), bytes(1, 2, 3).pkcs7Pad(16).last())
        assertEquals(16, ByteArray(16).pkcs7Pad(16).last().toInt())
        assertEquals("010203".let { hex(it).toHex() }, hex("010203").pkcs7Pad(16).pkcs7Unpad()!!.toHex())
        // A wrong tail is refused rather than silently truncated.
        assertNull(byteArrayOf(1, 2, 3, 5).pkcs7Unpad())
        assertNull(byteArrayOf().pkcs7Unpad())

        // zeroPadTo never grows an already-aligned input.
        assertEquals(8, ByteArray(8).zeroPadTo(8).size)
        assertEquals(0, ByteArray(0).zeroPadTo(8).size)
        assertEquals(16, ByteArray(9).zeroPadTo(8).size)
    }

    @Test fun endianHelpersAgreeWithEachOther() {
        val b = ByteArray(4)
        b.putBe32(0, 0x01020304)
        assertEquals("01020304", b.toHex())
        assertEquals(0x01020304, b.be32(0))
        b.putLe32(0, 0x01020304)
        assertEquals("04030201", b.toHex())
        assertEquals(0x01020304, b.le32(0))
    }

    @Test fun hexAndByteHelpersRoundTrip() {
        val data = ByteArray(256) { it.toByte() }
        // 256 distinct byte values, so a masking slip anywhere shows up.
        assertEquals(256, data.size)
        assertEquals(0xFF, data.u8(255))
        assertEquals(0x80, ByteArray(1) { 0x80.toByte() }.u8(0))
        assertContentEquals(bytes(1, 2, 3), hex("010203"))
    }
}
