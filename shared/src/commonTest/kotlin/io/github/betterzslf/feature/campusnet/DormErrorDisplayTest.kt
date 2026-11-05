package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DormErrorDisplayTest {

    @Test fun theTableCarriesEveryCodeTheReferenceClientKnows() {
        // §5.2 is captioned "34 条" but the table and ErrorCode.c both carry 48; the data wins.
        assertEquals(48, DORM_ERROR_CODE_COUNT)
        // Every code resolves to real Chinese copy, not the English fallback and not the unknown text.
        for (code in listOf("13012000", "140100", "401", "-600", "108", "140000")) {
            val error = assertNotNull(dormErrorCode(null, code), code)
            val text = dormErrorDisplayText(error)
            assertTrue(text.isNotBlank(), code)
            assertFalse(text.contains("未收录"), code)
            assertNotEquals(error.message, text, code)
        }
    }

    @Test fun subErrorWinsOverErrorCodeBecauseItIsMoreSpecific() {
        // 102 alone reads as a network problem; 13012000 says it is really a wrong password.
        val error = assertNotNull(dormErrorCode("102", "13012000"))
        assertEquals("13012000", error.code)
        assertEquals(DormErrorFamily.FATAL, error.family)
        assertEquals("帐号或密码错误", dormErrorDisplayText(error))
        // With no Sub-Error the portal code is used.
        assertEquals("102", assertNotNull(dormErrorCode("102", null)).code)
        assertEquals("102", assertNotNull(dormErrorCode("102", "  ")).code)
        assertNull(dormErrorCode(null, null))
        assertNull(dormErrorCode("not-a-code", null))
    }

    @Test fun anUnrecordedCodeIsShownAsItselfRatherThanSwallowed() {
        // A new server code must reach the user verbatim; inventing a sentence would hide it.
        val unknown = dormErrorCode(null, "999999")
        assertNull(unknown)
        assertTrue(AppText.CampusNetwork.dormUnknownErrorText("999999").contains("999999"))
    }

    @Test fun onlyFatalErrorsStopTheUserFromRetrying() {
        // Retrying a FATAL code is exactly what the reference client refuses to do: 30-minute lockout.
        assertFalse(dormErrorRetryable(assertNotNull(dormErrorCode(null, "13012000"))))
        assertTrue(dormErrorRetryable(assertNotNull(dormErrorCode(null, "13003000"))))
        assertTrue(dormErrorRetryable(assertNotNull(dormErrorCode(null, "13014000"))))
        assertFalse(dormErrorRetryable(assertNotNull(dormErrorCode(null, "140002"))))
    }

    @Test fun theBackoffEndsAtThirtyFiveMinutesNotThirty() {
        // 30 would land back inside the server's own lockout window.
        assertEquals(listOf(1, 3, 5, 10, 20, 35), DORM_AUTH_RETRY_BACKOFF_MINUTES)
        assertEquals(1, dormRetryDelayMinutes(DormErrorFamily.TRANSIENT, 1))
        assertEquals(35, dormRetryDelayMinutes(DormErrorFamily.TRANSIENT, 6))
        assertEquals(35, dormRetryDelayMinutes(DormErrorFamily.TRANSIENT, 99))
        assertEquals(35, dormRetryDelayMinutes(DormErrorFamily.SLOW, 3))
        assertNull(dormRetryDelayMinutes(DormErrorFamily.FATAL, 3))
    }

    @Test fun twoFixedAuthServersAreAlwaysRankedAndNeverDropped() {
        assertEquals(7001, DORM_AUTH_SERVER_PORT)
        assertEquals(listOf("14.146.227.141", "121.8.177.212"), DORM_AUTH_SERVERS.map { it.host })
    }

    @Test fun portalAddressIsTakenFromEitherPlainOrPercentEncodedQuery() {
        val plain = assertNotNull(parsePortalAddress("http://9.9.9.9/a/wifi?wlanuserip=10.1.2.3&wlanacip=10.1.2.1"))
        assertEquals("10.1.2.3", plain.clientIp)
        assertEquals("10.1.2.1", plain.acIp)
        val encoded = assertNotNull(parsePortalAddress("http://9.9.9.9/a/wifi?wlanuserip%3D10.1.2.3%26wlanacip%3D10.1.2.1"))
        assertEquals("10.1.2.3", encoded.clientIp)
        // A lookalike key must not match, and a non-IP value is refused rather than carried through.
        assertNull(parsePortalAddress("http://9.9.9.9/a/wifi?xwlanuserip=10.1.2.3"))
        assertNull(parsePortalAddress("http://9.9.9.9/a/wifi?wlanuserip=999.1.2.3&wlanacip=10.1.2.1"))
        assertNull(parsePortalAddress("http://9.9.9.9/"))
    }

    @Test fun ticketUrlIsBuiltForTheChosenServerWithoutAPortalNode() {
        val server = DORM_AUTH_SERVERS.first()
        val address = PortalAddress(clientIp = "10.1.2.3", acIp = "10.1.2.1")
        val url = buildDormTicketUrl(server, address)
        assertTrue(url.startsWith("http://14.146.227.141:7001/"), url)
        assertTrue(url.contains("wlanuserip=10.1.2.3"), url)
        assertTrue(url.contains("wlanacip=10.1.2.1"), url)
    }

    @Test fun serversThatDoNotAnswerSortLastButStillStayInTheList() {
        runTest {
            val probed = mutableListOf<String>()
            val ranked = rankAuthServers { host, _ ->
                probed += host
                when (host) {
                    "14.146.227.141" -> null
                    else -> 12L
                }
            }
            assertEquals(2, ranked.size)
            assertEquals("121.8.177.212", ranked.first().host)
            // Both were actually probed, concurrently, not short-circuited on the first answer.
            assertEquals(2, probed.size)
            // Even with nothing reachable the list is intact, so the caller can still try the first.
            val none = rankAuthServers { _, _ -> null }
            assertEquals(2, none.size)
            assertEquals("14.146.227.141", selectAuthServer { _, _ -> null }.host)
        }
    }
}
