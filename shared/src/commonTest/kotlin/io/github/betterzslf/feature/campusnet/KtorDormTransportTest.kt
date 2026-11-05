package io.github.betterzslf.feature.campusnet

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * The real transport, exercised through a mock engine.
 *
 * The dialer tests use a fake [DormTransport], so nothing until now proved that the HTTP layer
 * itself sends what the gateway validates: the header set, the form body, and the ZSM detection.
 * Those are exactly the things the live gateway rejected one by one (Error-Code 1 → 13 → 12 → 15 as
 * each header was added), so they are worth asserting offline.
 */
class KtorDormTransportTest {

    private val host = DORM_AUTH_SERVERS.first().host
    private val url = "http://$host:7001/ticket.cgi?wlanuserip=10.0.0.2&wlanacip=10.0.0.1"

    private class Captured {
        var headers: Map<String, String> = emptyMap()
        var body: String = ""
        var contentType: String = ""
        var method: String = ""
    }

    private fun transport(
        captured: Captured,
        status: HttpStatusCode = HttpStatusCode.OK,
        errorCode: String = "200",
        subError: String = "",
        body: String = "",
    ): KtorDormTransport {
        val engine = MockEngine { request ->
            captured.headers = buildMap {
                for (name in listOf("Client-ID", "Algo-ID", "CDC-SchoolId", "CDC-Domain", "CDC-Area",
                    HttpHeaders.UserAgent, HttpHeaders.Accept)) {
                    request.headers[name]?.let { put(name, it) }
                }
            }
            captured.contentType = request.body.contentType?.toString().orEmpty()
            captured.method = request.method.value
            captured.body = (request.body as? io.ktor.http.content.TextContent)?.text.orEmpty()
            val headers = headersOf(HttpHeaders.ContentType, "text/html").let {
                if (errorCode.isBlank() && subError.isBlank()) it
                else headersOf(
                    *(buildList {
                        if (errorCode.isNotBlank()) add("Error-Code" to listOf(errorCode))
                        if (subError.isNotBlank()) add("Sub-Error" to listOf(subError))
                    }.toTypedArray()))
            }
            respond(body, status, headers)
        }
        return KtorDormTransport(HttpClient(engine))
    }

    @Test fun everyHeaderTheGatewayValidatesIsSent() = runTest {
        recordDormSchoolIdentity("SCHOOL", "DOMAIN", "AREA")
        try {
            val captured = Captured()
            val t = transport(captured)
            t.post(url, "0102", DORM_NIL_ALGO_ID, "client-id-1")
            // Each of these was proved necessary against the live gateway, in this order.
            assertEquals("client-id-1", captured.headers["Client-ID"], captured.headers.toString())
            assertEquals(DORM_NIL_ALGO_ID, captured.headers["Algo-ID"])
            assertEquals("SCHOOL", captured.headers["CDC-SchoolId"])
            assertEquals("DOMAIN", captured.headers["CDC-Domain"])
            assertEquals("AREA", captured.headers["CDC-Area"])
            assertEquals(DORM_ANDROID_USER_AGENT, captured.headers[HttpHeaders.UserAgent])
            assertNotNull(captured.headers[HttpHeaders.Accept], captured.headers.toString())
            t.close()
        } finally { clearDormSchoolIdentity() }
    }

    @Test fun theBodyIsPostedAsAFormAndUnchanged() = runTest {
        val captured = Captured()
        val t = transport(captured)
        val hex = "AABBCCDDEEFF"
        t.post(url, hex, "319FC5AB-EC0E-46B9-A252-2285F9DAE813", "cid")
        assertEquals("POST", captured.method)
        // The ciphertext is sent verbatim: encoding it would corrupt it.
        assertEquals(hex, captured.body)
        assertTrue(captured.contentType.startsWith("application/x-www-form-urlencoded"),
            captured.contentType)
        t.close()
    }

    @Test fun aCipherNegotiationIsDetectedFromTheBodyNotJustTheCode() = runTest {
        // Error-Code 200 also covers ordinary empty successes, so the 001 prefix decides.
        val zsm = buildZsm("name", "319FC5AB-EC0E-46B9-A252-2285F9DAE813")
        val captured = Captured()
        val t = transport(captured, errorCode = "200", body = zsm.decodeToString())
        val result = t.post(url, "00000000-0000-0000-0000-000000000000", DORM_NIL_ALGO_ID, "cid")
        val update = assertIs<DormRoundResult.CipherUpdate>(result)
        assertEquals("319FC5AB-EC0E-46B9-A252-2285F9DAE813", update.algoId)
        t.close()
    }

    @Test fun a200WithAnEmptyBodyIsAReplyNotANegotiation() = runTest {
        val captured = Captured()
        val t = transport(captured, errorCode = "200", body = "")
        val result = t.post(url, "00", DORM_NIL_ALGO_ID, "cid")
        assertIs<DormRoundResult.Reply>(result)
        t.close()
    }

    @Test fun errorHeadersAreCarriedThroughVerbatim() = runTest {
        val captured = Captured()
        val t = transport(captured, errorCode = "13012000", subError = "11010000")
        val result = t.post(url, "00", DORM_NIL_ALGO_ID, "cid")
        val reply = assertIs<DormRoundResult.Reply>(result)
        assertEquals("13012000", reply.errorCode)
        assertEquals("11010000", reply.subError)
        t.close()
    }

    @Test fun aNonAllowlistedHostIsRefusedBeforeAnyRequest() = runTest {
        var requests = 0
        val t = KtorDormTransport(HttpClient(MockEngine { requests++; respond("", HttpStatusCode.OK) }))
        assertEquals(DormRoundResult.Unreachable, t.post("http://10.1.2.3:7001/ticket.cgi", "00",
            DORM_NIL_ALGO_ID, "cid"))
        // The allowlist is enforced locally: nothing left the app.
        assertEquals(0, requests)
        t.close()
    }

    @Test fun aThrowingEngineBecomesUnreachableRatherThanAnException() = runTest {
        val t = KtorDormTransport(HttpClient(MockEngine { throw kotlinx.io.IOException("boom") }))
        assertEquals(DormRoundResult.Unreachable, t.post(url, "00", DORM_NIL_ALGO_ID, "cid"))
        t.close()
    }

    @Test fun afterCloseNothingIsSent() = runTest {
        var requests = 0
        val t = KtorDormTransport(HttpClient(MockEngine { requests++; respond("", HttpStatusCode.OK) }))
        t.close()
        t.close() // idempotent
        assertEquals(DormRoundResult.Unreachable, t.post(url, "00", DORM_NIL_ALGO_ID, "cid"))
        assertEquals(0, requests)
    }

    /** Builds a ZSM the way the gateway does: 3-byte header then two length-prefixed strings. */
    private fun buildZsm(first: String, second: String): ByteArray {
        val out = mutableListOf<Byte>(0x30, 0x30, 0x31)
        for (part in listOf(first, second)) {
            out += part.length.toByte()
            out += part.encodeToByteArray().toList()
        }
        return out.toByteArray()
    }
}
