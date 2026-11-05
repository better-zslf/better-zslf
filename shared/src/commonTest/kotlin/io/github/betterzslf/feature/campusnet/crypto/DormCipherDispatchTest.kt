package io.github.betterzslf.feature.campusnet.crypto

import kotlin.test.*

/**
 * The dispatcher, checked against every reference vector for the ids it claims to support.
 *
 * This is the test that matters for the dialer: the per-family tests prove the primitives, and
 * this proves the dispatcher wires each `algo_id` to the right primitive with the right key.
 * A swapped constant here would pass every primitive test and still fail on the network.
 */
class DormCipherDispatchTest {

    private val plain = "{\"test\":\"hello\"}".encodeToByteArray()

    @Test fun dispatch_252F0396_5254_4A42_9390_FA2EF1EB8D6E() {
        assertEquals("FA29C5C5314715ABA8ECD16C8EA3B566", DormCipher.encryptHex("252F0396-5254-4A42-9390-FA2EF1EB8D6E", plain))
    }
    @Test fun dispatch_3084D519_54D7_4434_9564_26A8615BCEF2() {
        assertEquals("4EB07B5C6562F972247C134CA61133E8", DormCipher.encryptHex("3084D519-54D7-4434-9564-26A8615BCEF2", plain))
    }
    @Test fun dispatch_319FC5AB_EC0E_46B9_A252_2285F9DAE813() {
        assertEquals("4EB07B5C6562F972247C134CA61133E8", DormCipher.encryptHex("319FC5AB-EC0E-46B9-A252-2285F9DAE813", plain))
    }
    @Test fun dispatch_35101415_A20F_4DFE_B00B_0B4F3B2F8C66() {
        assertEquals("78B2F48497BB8A3081C4D796E59ED0CE", DormCipher.encryptHex("35101415-A20F-4DFE-B00B-0B4F3B2F8C66", plain))
    }
    @Test fun dispatch_3D74E736_AB35_47CD_B934_77EBEF2B42D3() {
        assertEquals("E5125718C29CCF4BBC973C75AAFE4DFF", DormCipher.encryptHex("3D74E736-AB35-47CD-B934-77EBEF2B42D3", plain))
    }
    @Test fun dispatch_406BD7C5_C48A_4F48_B072_82D5948B537B() {
        assertEquals("78B2F48497BB8A3081C4D796E59ED0CE", DormCipher.encryptHex("406BD7C5-C48A-4F48-B072-82D5948B537B", plain))
    }
    @Test fun dispatch_5BFBA864_BBA9_42DB_8EAD_49B5F412BD81() {
        assertEquals("9C2AC2E381219F0322FC4AB9384E507C", DormCipher.encryptHex("5BFBA864-BBA9-42DB-8EAD-49B5F412BD81", plain))
    }
    @Test fun dispatch_64BD1BB8_49F4_448F_9D63_C4E96FC068CC() {
        assertEquals("B2880C5B9D43E21BC4E019AB7CE869B6", DormCipher.encryptHex("64BD1BB8-49F4-448F-9D63-C4E96FC068CC", plain))
    }
    @Test fun dispatch_73B3F4AE_643F_471F_B08C_A5893D7B3601() {
        assertEquals("E79292505C2C84D969C875166ED24A6452ECFE9A19BA55994445C75B753EB8B4", DormCipher.encryptHex("73B3F4AE-643F-471F-B08C-A5893D7B3601", plain))
    }
    @Test fun dispatch_7E6A7DE0_4C56_47E6_A25A_1B4DCD33B950() {
        assertEquals("E0A0A13B2183FF8E6D6A7E6A59006ECA", DormCipher.encryptHex("7E6A7DE0-4C56-47E6-A25A-1B4DCD33B950", plain))
    }
    @Test fun dispatch_9ABF4D29_34DB_4CE9_BB8C_7E371D637758() {
        assertEquals("B2880C5B9D43E21BC4E019AB7CE869B6", DormCipher.encryptHex("9ABF4D29-34DB-4CE9-BB8C-7E371D637758", plain))
    }
    @Test fun dispatch_A474B1C2_3DE0_4EA2_8C5F_7093409CE6C4() {
        assertEquals("0AD2715CDB5924692A0F363816B846A0", DormCipher.encryptHex("A474B1C2-3DE0-4EA2-8C5F-7093409CE6C4", plain))
    }
    @Test fun dispatch_AD8BB5B0_0E72_4198_A362_96D52C1B7ED1() {
        assertEquals("FA29C5C5314715ABA8ECD16C8EA3B566", DormCipher.encryptHex("AD8BB5B0-0E72-4198-A362-96D52C1B7ED1", plain))
    }
    @Test fun dispatch_B3047D4E_67DF_4864_A6A5_DF9B9E525C79() {
        assertEquals("132D719A0A11842C635D974701EC3EE2", DormCipher.encryptHex("B3047D4E-67DF-4864-A6A5-DF9B9E525C79", plain))
    }
    @Test fun dispatch_BB2EA626_590B_4C42_82BE_E052FCBBB88E() {
        assertEquals("E5125718C29CCF4BBC973C75AAFE4DFF", DormCipher.encryptHex("BB2EA626-590B-4C42-82BE-E052FCBBB88E", plain))
    }
    @Test fun dispatch_C32C68F9_CA81_4260_A329_BBAFD1A9CCD1() {
        assertEquals("55973976D4D527EDEA705875E97A8A9A", DormCipher.encryptHex("C32C68F9-CA81-4260-A329-BBAFD1A9CCD1", plain))
    }
    @Test fun dispatch_CAFBCBAD_B6E7_4CAB_8A67_14D39F00CE1E() {
        assertEquals("8EEDC0FC1AB47A016EB4DCB168A7932F", DormCipher.encryptHex("CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E", plain))
    }
    @Test fun dispatch_D522E657_2CC3_46BE_BBE9_9CF1DF896C41() {
        assertEquals("9CFBCE7DAE2C7FB7564ADCF6EF4E77573EED9284A3D6C441B377ECB98231CB05", DormCipher.encryptHex("D522E657-2CC3-46BE-BBE9-9CF1DF896C41", plain))
    }
    @Test fun dispatch_D6544CFE_F2DE_459B_9B77_0F2B367EF169() {
        assertEquals("E79292505C2C84D969C875166ED24A6452ECFE9A19BA55994445C75B753EB8B4", DormCipher.encryptHex("D6544CFE-F2DE-459B-9B77-0F2B367EF169", plain))
    }
    @Test fun dispatch_D755A536_B551_468C_BD87_322182B223D4() {
        assertEquals("9CFBCE7DAE2C7FB7564ADCF6EF4E77573EED9284A3D6C441B377ECB98231CB05", DormCipher.encryptHex("D755A536-B551-468C-BD87-322182B223D4", plain))
    }
    @Test fun dispatch_DEABB8C8_A2BC_48CA_8ED0_8CDF1BD62F61() {
        assertEquals("E0A0A13B2183FF8E6D6A7E6A59006ECA", DormCipher.encryptHex("DEABB8C8-A2BC-48CA-8ED0-8CDF1BD62F61", plain))
    }
    @Test fun dispatch_ED382482_F72C_4C41_A76D_28EEA0F1F2AF() {
        assertEquals("9CFBCE7DAE2C7FB7564ADCF6EF4E77573EED9284A3D6C441B377ECB98231CB05", DormCipher.encryptHex("ED382482-F72C-4C41-A76D-28EEA0F1F2AF", plain))
    }
    @Test fun dispatch_F3974434_C0DD_4C20_9E87_DDB6814A1C48() {
        assertEquals("E79292505C2C84D969C875166ED24A6452ECFE9A19BA55994445C75B753EB8B4", DormCipher.encryptHex("F3974434-C0DD-4C20-9E87-DDB6814A1C48", plain))
    }

    @Test fun everySupportedIdProducesHex() {
        for (id in SUPPORTED_IDS) {
            val out = assertNotNull(DormCipher.encryptHex(id, plain), id)
            assertTrue(out.isNotEmpty(), id)
            assertTrue(out.all { it in "0123456789ABCDEF" }, "$id produced non-uppercase hex: $out")
            assertEquals(0, out.length % 2, id)
            assertIs<DormCipherStatus.Supported>(DormCipher.statusOf(id), id)
        }
    }

    @Test fun theUnsupportedIdsAreReportedNotSilentlyEncrypted() {
        // Sending plausible-but-wrong bytes would burn a rate-limited login attempt.
        for ((id, reason) in listOf(
            "B809531F-0007-4B5B-923B-4BD560398113" to "ZUC",
            "07E824B2-9E5C-4D1B-BBB0-5E07C251E4AA" to "SNOW3G",
            "5038A11D-75EA-4F75-AF8D-2DAD8E0E6A19" to "SNOW3G",
            "6E0B65FF-0B5B-459C-8FCE-EC7F2BEA9FF5" to "3DES-ECB")) {
            assertNull(DormCipher.encryptHex(id, plain), id)
            val status = assertIs<DormCipherStatus.Unsupported>(DormCipher.statusOf(id), id)
            assertEquals(reason, status.reason, id)
        }
    }

    @Test fun anUnknownIdIsNeitherEncryptedNorClaimed() {
        assertNull(DormCipher.encryptHex("11111111-2222-3333-4444-555555555555", plain))
        assertEquals(DormCipherStatus.UnknownId, DormCipher.statusOf("11111111-2222-3333-4444-555555555555"))
        assertEquals(DormCipherStatus.UnknownId, DormCipher.statusOf(""))
    }

    @Test fun everyPortedIdIsCoveredByAVectorHere() {
        // 23 of the 27 Android-channel ids: the other 4 are the unsupported ones above.
        assertEquals(23, SUPPORTED_IDS.size)
        assertEquals(4, io.github.betterzslf.feature.campusnet.CAMPUS_ALGO_FAMILIES.size - SUPPORTED_IDS.size)
    }

    private companion object {
        val SUPPORTED_IDS = listOf(
            "252F0396-5254-4A42-9390-FA2EF1EB8D6E", "3084D519-54D7-4434-9564-26A8615BCEF2",
            "319FC5AB-EC0E-46B9-A252-2285F9DAE813", "35101415-A20F-4DFE-B00B-0B4F3B2F8C66",
            "3D74E736-AB35-47CD-B934-77EBEF2B42D3", "406BD7C5-C48A-4F48-B072-82D5948B537B",
            "5BFBA864-BBA9-42DB-8EAD-49B5F412BD81", "64BD1BB8-49F4-448F-9D63-C4E96FC068CC",
            "73B3F4AE-643F-471F-B08C-A5893D7B3601", "7E6A7DE0-4C56-47E6-A25A-1B4DCD33B950",
            "9ABF4D29-34DB-4CE9-BB8C-7E371D637758", "A474B1C2-3DE0-4EA2-8C5F-7093409CE6C4",
            "AD8BB5B0-0E72-4198-A362-96D52C1B7ED1", "B3047D4E-67DF-4864-A6A5-DF9B9E525C79",
            "BB2EA626-590B-4C42-82BE-E052FCBBB88E", "C32C68F9-CA81-4260-A329-BBAFD1A9CCD1",
            "CAFBCBAD-B6E7-4CAB-8A67-14D39F00CE1E", "D522E657-2CC3-46BE-BBE9-9CF1DF896C41",
            "D6544CFE-F2DE-459B-9B77-0F2B367EF169", "D755A536-B551-468C-BD87-322182B223D4",
            "DEABB8C8-A2BC-48CA-8ED0-8CDF1BD62F61", "ED382482-F72C-4C41-A76D-28EEA0F1F2AF",
            "F3974434-C0DD-4C20-9E87-DDB6814A1C48",
        )
    }
}
