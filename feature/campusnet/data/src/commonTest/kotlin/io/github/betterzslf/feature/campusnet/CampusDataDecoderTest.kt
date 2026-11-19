package io.github.betterzslf.feature.campusnet

import kotlinx.serialization.json.*
import kotlin.test.*

private fun accountRows(vararg rows: JsonElement): JsonObject = buildJsonObject { put("operatorAccount", JsonArray(rows.toList())) }
private fun accountRow(id: String, account: String) = buildJsonObject {
    put("id", id); put("operatorAccount", account); put("typeStatus", "dianxin")
}

class CampusDataDecoderTest {
    /** Moved from the controller suite: strict wire shape is now data's responsibility. */
    @Test fun strictListShapeDistinguishesUnknownFromEmptyAndKeepsAllRows() {
        assertEquals(CampusIssue.MALFORMED, assertIs<CampusOutcome.Failure>(parseCampusAccounts(buildJsonObject { put("list", JsonArray(emptyList())) })).issue)
        assertIs<CampusOutcome.Failure>(parseCampusAccounts(accountRows(JsonPrimitive("bad"))))
        val empty = assertIs<CampusOutcome.Success<List<CampusAccountRecord>>>(parseCampusAccounts(accountRows())).data
        assertTrue(empty.isEmpty())
        val many = (0 until 1489).map { accountRow("fixture-$it", "account-$it") }.toTypedArray()
        val all = assertIs<CampusOutcome.Success<List<CampusAccountRecord>>>(parseCampusAccounts(accountRows(*many))).data
        assertEquals(1489, all.size)
        val literals = accountRows(
            buildJsonObject { put("id", 123); put("operatorAccount", 13800000000L); put("typeStatus", "future") },
            buildJsonObject { put("operatorAccountId", "not-a-proven-response-field"); put("operatorAccount", "alias-only") },
            accountRow(" padded ", "fixture"),
            buildJsonObject { put("id", true); put("operatorAccount", false) },
        )
        val rows = assertIs<CampusOutcome.Success<List<CampusAccountRecord>>>(parseCampusAccounts(literals)).data
        assertEquals("123", rows[0].id); assertEquals("13800000000", rows[0].account)
        assertEquals("future", rows[0].typeStatus); assertEquals("", rows[1].id)
        assertEquals(" padded ", rows[2].id); assertEquals("", rows[3].id); assertEquals("", rows[3].account)
    }

    /** Moved from the portal controller suite, including the numeric compatibility assertions. */
    @Test fun serverSpellingAndStringStatusAreBothHonoured() {
        assertEquals(CampusOnlineStatus.Online, parseCampusOnlineStatus(buildJsonObject { put("status", "1"); put("massage", "") }))
        assertEquals(CampusOnlineStatus.Offline, parseCampusOnlineStatus(buildJsonObject { put("status", "0") }))
        assertEquals(CampusOnlineStatus.Online, parseCampusOnlineStatus(buildJsonObject { put("status", 1) }))
        assertEquals(CampusOnlineStatus.Offline, parseCampusOnlineStatus(buildJsonObject { put("status", 0) }))
        assertEquals(CampusOnlineStatus.Unreadable(""), parseCampusOnlineStatus(buildJsonObject { }))
        assertEquals(CampusOnlineStatus.Unreadable("true"), parseCampusOnlineStatus(buildJsonObject { put("status", true) }))
        assertEquals(CampusOnlineStatus.Unreadable(""), parseCampusOnlineStatus(buildJsonObject { put("status", JsonNull) }))
    }

    @Test fun typedPortalProjectionKeepsUnknownStatusRejectionPrecedenceAndBlankRedirect() {
        assertEquals(CampusOnlineStatus.Unreadable("x".repeat(32)), parseCampusOnlineStatus(buildJsonObject { put("status", "x".repeat(80)) }))
        assertEquals(CampusDialResult.Accepted, parseCampusDialResult(buildJsonObject { put("status", 1); put("msg", "PassWord error") }))
        val rejected = assertIs<CampusDialResult.Rejected>(parseCampusDialResult(buildJsonObject {
            put("status", "0"); put("msg", "PassWord error Product not ordered no redirect 01"); put("message", "fallback")
        }))
        assertEquals(CampusDialRejection.PasswordWrong, rejected.reason)
        assertEquals("PassWord error Product not ordered no redirect 01", rejected.raw)
        assertEquals(CampusDialRejection.Unpaid, classifyCampusDialRejection("Product not ordered no redirect 02"))
        assertEquals(CampusDialRejection.NoRedirect, classifyCampusDialRejection("no redirect 02"))
        assertEquals(CampusDialRejection.Other, classifyCampusDialRejection("password error"))
        val fallback = assertIs<CampusDialResult.Rejected>(parseCampusDialResult(buildJsonObject { put("message", "x".repeat(200)) }))
        assertEquals("x".repeat(120), fallback.raw)
        assertEquals("", assertIs<CampusDialResult.Rejected>(parseCampusDialResult(buildJsonObject { put("msg", ""); put("message", "fallback") })).raw)
        assertEquals("", parseCampusRedirectTarget(JsonNull).address)
        assertEquals("", parseCampusRedirectTarget(buildJsonObject { put("addr", buildJsonObject { }) }).address)
        assertEquals("9", parseCampusRedirectTarget(buildJsonObject { put("addr", 9) }).address)
        assertEquals("http://9.9.9.9", parseCampusRedirectTarget(buildJsonObject { put("addr", "http://9.9.9.9"); put("checkStr", "ignored") }).address)
        assertFalse(CampusRedirectTarget("private-address").toString().contains("private-address"))
    }

    @Test fun provisionedPhoneSkipsBadRowsInsteadOfBorrowingTheStrictAccountsParser() {
        val rows = accountRows(JsonPrimitive("bad"), buildJsonObject { put("operatorAccount", false) },
            accountRow("one", "not-a-phone"), accountRow("two", " 13800138000@163.gd "), accountRow("three", "17308221000@zsdzkd"))
        assertIs<CampusOutcome.Failure>(parseCampusAccounts(rows))
        assertEquals("13800138000", parseCampusProvisionedPhone(rows))
        assertEquals("13800138000", campusProvisionedPhonePrefix(" 13800138000@163.gd "))
        assertEquals("17308221000", campusProvisionedPhonePrefix("17308221000@zsdzkd"))
        assertNull(campusProvisionedPhonePrefix("1380013800@163.gd"))
        assertNull(campusProvisionedPhonePrefix("138001380001@163.gd"))
        assertNull(campusProvisionedPhonePrefix("1380013800a@163.gd"))
        assertNull(parseCampusProvisionedPhone(JsonNull)); assertNull(parseCampusProvisionedPhone(accountRows()))
        assertNull(parseCampusProvisionedPhone(accountRows(accountRow("one", "bad"))))
    }
}
