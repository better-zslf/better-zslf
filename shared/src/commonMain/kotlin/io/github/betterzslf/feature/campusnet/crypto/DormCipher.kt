package io.github.betterzslf.feature.campusnet.crypto

/**
 * Turns an `algo_id` into the actual encrypted payload the gateway expects.
 *
 * This is the piece that makes the ported primitives usable: the gateway answers the cipher
 * negotiation with one of 27 Android-channel ids, and every subsequent request body must be
 * encrypted with the family that id selects.
 *
 * Coverage is deliberately explicit rather than silently partial. [DormCipher.statusOf] reports
 * `Unsupported` for the four ids this build cannot do (ZUC, SNOW3G, 3DES-ECB), and the caller shows
 * that instead of sending a request that could only fail. Sending plausible-but-wrong bytes is
 * worse than saying "not supported": the gateway answers with a generic credential error and the
 * account gets locked for 30 minutes after a few attempts.
 */
sealed interface DormCipherStatus {
    /** This id is implemented and its output is pinned by a reference-produced vector. */
    data class Supported(val family: String) : DormCipherStatus

    /** A known Android-channel id this build has no implementation for. */
    data class Unsupported(val family: String, val reason: String) : DormCipherStatus

    /** Not an Android-channel id at all: the gateway will not issue this one to us. */
    data object UnknownId : DormCipherStatus
}

object DormCipher {

    /**
     * Encrypt [plaintext] for [algoId], returning the uppercase hex the protocol carries.
     *
     * `null` means "this build cannot do it", never "it failed quietly". The caller must not send
     * anything in that case.
     */
    fun encryptHex(algoId: String, plaintext: ByteArray): String? {
        val id = algoId.trim().uppercase()
        return when (id) {
            // AES, single layer. The reference passes two key arrays here but only the first is
            // used; the second exists because the family signature takes key1/key2/iv.
            "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E" ->
                aesCbcEncrypt(Aes(DormCipherKeys.key1_CAFBCBAD_B6E7_4CAB_8A67_14D39F00CE1E),
                    DormCipherKeys.iv1_CAFBCBAD_B6E7_4CAB_8A67_14D39F00CE1E,
                    plaintext.zeroPadTo(16)).toHex()
            "A474B1C2-3DE0-4EA2-8C5F-7093409CE6C4" ->
                aesEcbEncrypt(Aes(DormCipherKeys.key2_A474B1C2_3DE0_4EA2_8C5F_7093409CE6C4),
                    plaintext.zeroPadTo(16)).toHex()
            // AES double: the first key is the outer layer, the second the inner one.
            "BB2EA626-590B-4C42-82BE-E052FCBBB88E", "3D74E736-AB35-47CD-B934-77EBEF2B42D3" -> {
                val (key, iv) = if (id == "BB2EA626-590B-4C42-82BE-E052FCBBB88E")
                    DormCipherKeys.key_BB2EA626_590B_4C42_82BE_E052FCBBB88E to
                        DormCipherKeys.iv_BB2EA626_590B_4C42_82BE_E052FCBBB88E
                else DormCipherKeys.key_3D74E736_AB35_47CD_B934_77EBEF2B42D3 to
                    DormCipherKeys.iv_3D74E736_AB35_47CD_B934_77EBEF2B42D3
                aesCbcEncrypt(Aes(key), iv, plaintext.zeroPadTo(16)).toHex()
            }
            "DEABB8C8-A2BC-48CA-8ED0-8CDF1BD62F61", "7E6A7DE0-4C56-47E6-A25A-1B4DCD33B950" -> {
                val key = if (id == "DEABB8C8-A2BC-48CA-8ED0-8CDF1BD62F61")
                    DormCipherKeys.key_DEABB8C8_A2BC_48CA_8ED0_8CDF1BD62F61
                else DormCipherKeys.key_7E6A7DE0_4C56_47E6_A25A_1B4DCD33B950
                aesEcbEncrypt(Aes(key), plaintext.zeroPadTo(16)).toHex()
            }
            // Triple modified-TEA.
            "319FC5AB-EC0E-46B9-A252-2285F9DAE813", "3084D519-54D7-4434-9564-26A8615BCEF2" -> {
                val key = if (id == "319FC5AB-EC0E-46B9-A252-2285F9DAE813")
                    DormCipherKeys.key_319FC5AB_EC0E_46B9_A252_2285F9DAE813
                else DormCipherKeys.key_3084D519_54D7_4434_9564_26A8615BCEF2
                teaTripleEcbEncrypt(key, plaintext).toHex()
            }
            "35101415-A20F-4DFE-B00B-0B4F3B2F8C66", "406BD7C5-C48A-4F48-B072-82D5948B537B" -> {
                val (key, iv) = if (id == "35101415-A20F-4DFE-B00B-0B4F3B2F8C66")
                    DormCipherKeys.key_35101415_A20F_4DFE_B00B_0B4F3B2F8C66 to
                        DormCipherKeys.iv_35101415_A20F_4DFE_B00B_0B4F3B2F8C66
                else DormCipherKeys.key_406BD7C5_C48A_4F48_B072_82D5948B537B to
                    DormCipherKeys.iv_406BD7C5_C48A_4F48_B072_82D5948B537B
                teaTripleCbcEncrypt(key, iv, plaintext).toHex()
            }
            // mod-XTEA: three DIFFERENT 4-word keys, applied in order 1, 2, 3. Using one key three
            // times produces well-formed but wrong output, so the three arrays are kept distinct.
            "B3047D4E-67DF-4864-A6A5-DF9B9E525C79" ->
                modXteaEncrypt(listOf(
                    DormCipherKeys.key1_B3047D4E_67DF_4864_A6A5_DF9B9E525C79,
                    DormCipherKeys.key2_B3047D4E_67DF_4864_A6A5_DF9B9E525C79,
                    DormCipherKeys.key3_B3047D4E_67DF_4864_A6A5_DF9B9E525C79),
                    swapKeyEndian = false, data = plaintext).toHex()
            "C32C68F9-CA81-4260-A329-BBAFD1A9CCD1" ->
                modXteaIvEncrypt(listOf(
                    DormCipherKeys.key1_C32C68F9_CA81_4260_A329_BBAFD1A9CCD1,
                    DormCipherKeys.key2_C32C68F9_CA81_4260_A329_BBAFD1A9CCD1,
                    DormCipherKeys.key3_C32C68F9_CA81_4260_A329_BBAFD1A9CCD1),
                    DormCipherKeys.iv_C32C68F9_CA81_4260_A329_BBAFD1A9CCD1, plaintext).toHex()
            // SM4 and its (identical) variant. Note D6544CFE is a *variant-CBC* id despite sharing
            // the plain SM4-CBC output for this key, so it must route to the variant entry point.
            "F3974434-C0DD-4C20-9E87-DDB6814A1C48" ->
                sm4CbcEncrypt(DormCipherKeys.key_F3974434_C0DD_4C20_9E87_DDB6814A1C48,
                    DormCipherKeys.iv_F3974434_C0DD_4C20_9E87_DDB6814A1C48, plaintext).toHex()
            "ED382482-F72C-4C41-A76D-28EEA0F1F2AF" ->
                sm4EcbEncrypt(DormCipherKeys.key_ED382482_F72C_4C41_A76D_28EEA0F1F2AF, plaintext).toHex()
            "73B3F4AE-643F-471F-B08C-A5893D7B3601", "D6544CFE-F2DE-459B-9B77-0F2B367EF169" -> {
                val (key, iv) = if (id == "73B3F4AE-643F-471F-B08C-A5893D7B3601")
                    DormCipherKeys.key_73B3F4AE_643F_471F_B08C_A5893D7B3601 to
                        DormCipherKeys.iv_73B3F4AE_643F_471F_B08C_A5893D7B3601
                else DormCipherKeys.key_D6544CFE_F2DE_459B_9B77_0F2B367EF169 to
                    DormCipherKeys.iv_D6544CFE_F2DE_459B_9B77_0F2B367EF169
                sm4VariantCbcEncrypt(key, iv, plaintext).toHex()
            }
            "D522E657-2CC3-46BE-BBE9-9CF1DF896C41", "D755A536-B551-468C-BD87-322182B223D4" -> {
                val key = if (id == "D522E657-2CC3-46BE-BBE9-9CF1DF896C41")
                    DormCipherKeys.key_D522E657_2CC3_46BE_BBE9_9CF1DF896C41
                else DormCipherKeys.key_D755A536_B551_468C_BD87_322182B223D4
                sm4VariantEcbEncrypt(key, plaintext).toHex()
            }
            // DES.
            "AD8BB5B0-0E72-4198-A362-96D52C1B7ED1", "252F0396-5254-4A42-9390-FA2EF1EB8D6E" -> {
                val key = if (id == "AD8BB5B0-0E72-4198-A362-96D52C1B7ED1")
                    DormCipherKeys.key_AD8BB5B0_0E72_4198_A362_96D52C1B7ED1
                else DormCipherKeys.key_252F0396_5254_4A42_9390_FA2EF1EB8D6E
                desEcbSixEncrypt(key, plaintext).toHex()
            }
            "5BFBA864-BBA9-42DB-8EAD-49B5F412BD81" ->
                desedeCbcEncrypt(
                    DormCipherKeys.key1_5BFBA864_BBA9_42DB_8EAD_49B5F412BD81,
                    DormCipherKeys.key2_5BFBA864_BBA9_42DB_8EAD_49B5F412BD81,
                    DormCipherKeys.iv1_5BFBA864_BBA9_42DB_8EAD_49B5F412BD81,
                    DormCipherKeys.iv2_5BFBA864_BBA9_42DB_8EAD_49B5F412BD81, plaintext).toHex()
            "9ABF4D29-34DB-4CE9-BB8C-7E371D637758", "64BD1BB8-49F4-448F-9D63-C4E96FC068CC" -> {
                val (key48, ivRaw) = if (id == "9ABF4D29-34DB-4CE9-BB8C-7E371D637758")
                    DormCipherKeys.key_9ABF4D29_34DB_4CE9_BB8C_7E371D637758 to
                        DormCipherKeys.iv_9ABF4D29_34DB_4CE9_BB8C_7E371D637758
                else DormCipherKeys.key_64BD1BB8_49F4_448F_9D63_C4E96FC068CC to
                    DormCipherKeys.iv_64BD1BB8_49F4_448F_9D63_C4E96FC068CC
                // Layout is NOT two 24-byte keys. The reference reads six 8-byte DES keys in this
                // order: second pass uses key+24/+32/+40, first pass uses key+0/+8/+16. Both passes
                // reuse the same 8-byte IV.
                val iv = ivRaw.copyOf(8)
                desedeDoubleCbcEncrypt(key48, iv, plaintext).toHex()
            }
            else -> null
        }
    }

    /** What this build can do with [algoId]. Never guesses: unknown ids report honestly. */
    fun statusOf(algoId: String): DormCipherStatus {
        val id = algoId.trim().uppercase()
        val family = "io.github.betterzslf.feature.campusnet.CAMPUS_ALGO_FAMILIES"[0].let {
            io.github.betterzslf.feature.campusnet.campusAlgoFamilyName(id)
        }
        if (family.isBlank()) return DormCipherStatus.UnknownId
        return when (id) {
            // Known Android-channel ids this build does not implement.
            "07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA", "5038A11D-75EA-4F75-AF8D-2DAD8E0E6A19" ->
                DormCipherStatus.Unsupported(family, "SNOW3G")
            "B809531F-0007-4B5B-923B-4BD560398113" ->
                DormCipherStatus.Unsupported(family, "ZUC")
            "6E0B65FF-0B5B-459C-8FCE-EC7F2BEA9FF5" ->
                DormCipherStatus.Unsupported(family, "3DES-ECB")
            else -> DormCipherStatus.Supported(family)
        }
    }

    /**
     * The protocol carries uppercase hex, and the reference implementation emits uppercase
     * (`bytes_2_hex` uses `%02X`). Producing lowercase would still parse server-side in some paths
     * but is a needless deviation from what every official client sends.
     */
    private fun ByteArray.toHex(): String =
        joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1).uppercase() }
}
