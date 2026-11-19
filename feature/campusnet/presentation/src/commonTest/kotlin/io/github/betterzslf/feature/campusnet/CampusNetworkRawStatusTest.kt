package io.github.betterzslf.feature.campusnet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CampusNetworkRawStatusTest {
    @Test fun pinyinCodesRemainVerbatimWithAnExplicitServerValueMarker() {
        val account = CampusAccount("fixture", "12••••34", "dianxin", false)
        assertEquals("dianxin", account.typeStatus)
        assertEquals("账号状态：dianxin（服务端原值）", account.typeStatusLabel)
        assertFalse(account.canChangePassword)
    }

    @Test fun unrecognizedCodesAreNotTranslatedAndBlankStatusStillHasNoLabel() {
        for (raw in listOf("liantong", "future-code", "0")) {
            val account = CampusAccount("fixture", "12••••34", raw, false)
            assertEquals("账号状态：$raw（服务端原值）", account.typeStatusLabel)
            assertEquals(raw, account.typeStatus)
        }
        assertEquals("", CampusAccount("fixture", "12••••34", "", false).typeStatusLabel)
        assertEquals("", CampusAccount("fixture", "12••••34", "   ", false).typeStatusLabel)
    }

    @Test fun presentationDoesNotChangeParsedTargetOrPasswordEligibility() {
        val snapshot = campusAccountsSnapshot(listOf(CampusAccountRecord("fixture-target", "fixture-account", "dianxin")), 7)
        val account = snapshot.rows.single()
        assertEquals("dianxin", account.typeStatus)
        assertEquals("账号状态：dianxin（服务端原值）", account.typeStatusLabel)
        assertEquals("fixture-target", snapshot.targets[account.key])
        assertTrue(account.canChangePassword)
        assertFalse(account.maskedAccount.contains("fixture-account"))
    }
}
