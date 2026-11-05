package io.github.betterzslf.feature.campusnet

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DormCipherNegotiationTest {

    /** Builds a ZSM the way the gateway does: 3-byte header, then two length-prefixed strings. */
    private fun zsm(first: String?, second: String?): ByteArray {
        val out = mutableListOf<Byte>(0x30, 0x30, 0x31)
        for (part in listOf(first, second)) {
            if (part == null) { out += 0; continue }
            out += part.length.toByte()
            out += part.encodeToByteArray().toList()
        }
        return out.toByteArray()
    }

    private val realId = "35101415-A20F-4DFE-B00B-0B4F3B2F8C66"

    @Test fun theSecondHeaderStringIsPreferredThenTheFirst() {
        // The newer gateway layout puts the id second; the reference client checks it first.
        assertEquals(realId, parseDormAlgoId(zsm("name", realId)))
        assertEquals(realId, parseDormAlgoId(zsm(realId, "name")))
        // Uppercased: the table is keyed uppercase whatever case the gateway used.
        assertEquals(realId, parseDormAlgoId(zsm("name", realId.lowercase())))
    }

    @Test fun theFallbackReadsTheLastUuidAfterTrailingWhitespace() {
        val blob = ("garbage" + realId + "\r\n\u0000 ").encodeToByteArray()
        assertEquals(realId, parseDormAlgoId(blob))
        // Without a UUID anywhere, nothing is invented.
        assertNull(parseDormAlgoId("no uuid here at all".encodeToByteArray()))
        assertNull(parseDormAlgoId(ByteArray(0)))
        assertNull(parseDormAlgoId(byteArrayOf(1, 2, 3)))
    }

    @Test fun onlyA001BodyIsACipherNegotiation() {
        // Error-Code 200 is also used for ordinary empty successes, so the body decides.
        assertTrue(zsm("a", "b").isDormZsm())
        assertFalse(byteArrayOf().isDormZsm())
        assertFalse("".encodeToByteArray().isDormZsm())
        assertFalse("abc".encodeToByteArray().isDormZsm())
        assertFalse("002x".encodeToByteArray().isDormZsm())
    }

    @Test fun everyAndroidChannelIdResolvesToAFamily() {
        // 27 ids, all from the reference cipher factory's Android section.
        assertEquals(27, CAMPUS_ALGO_FAMILIES.size)
        assertEquals(27, CAMPUS_ALGO_FAMILIES.keys.map(String::uppercase).toSet().size)
        for ((id, family) in CAMPUS_ALGO_FAMILIES) {
            assertTrue(isDormAlgoId(id), id)
            assertEquals(family, campusAlgoFamilyName(id), id)
            assertTrue(family.isNotBlank(), id)
        }
        // An unknown but well-formed id is reported as unknown, not guessed.
        assertEquals("", campusAlgoFamilyName("11111111-2222-3333-4444-555555555555"))
    }

    @Test fun clientIdsAreWellFormedUppercaseUuidV4() {
        val ids = List(20) { newDormClientId() }
        for (id in ids) {
            assertTrue(isDormAlgoId(id), id)
            assertEquals(id.uppercase(), id)
            // Version nibble 4 and variant 8/9/A/B, so the gateway sees a real UUID.
            assertEquals('4', id[14], id)
            assertTrue(id[19] in "89AB", id)
        }
        // Fresh per handshake: two attempts must not look like the same client.
        assertEquals(20, ids.toSet().size)
    }

    @Test fun theHandshakeEchoesTheRequiredHeadersAndReadsTheId() = runTest {
        val nil = DORM_NIL_ALGO_ID
        var seenSchool = ""
        var seenDomain = ""
        var seenArea = ""
        var seenAlgo = ""
        var seenClientId = ""
        var seenUa = ""
        val client = KtorDormHandshake(HttpClient(MockEngine { request ->
            seenSchool = request.headers["CDC-SchoolId"].orEmpty()
            seenDomain = request.headers["CDC-Domain"].orEmpty()
            seenArea = request.headers["CDC-Area"].orEmpty()
            seenAlgo = request.headers["Algo-ID"].orEmpty()
            seenClientId = request.headers["Client-ID"].orEmpty()
            seenUa = request.headers[HttpHeaders.UserAgent].orEmpty()
            respond(zsm("name", realId).decodeToString(), HttpStatusCode.OK,
                headersOf("Error-Code", "200"))
        }))
        try {
            val result = client.negotiate(DORM_AUTH_SERVERS.first(),
                PortalAddress("10.0.0.2", "10.0.0.1"), "school", "domain", "area")
            val negotiated = assertIs<DormHandshakeResult.Negotiated>(result)
            assertEquals(realId, negotiated.algoId)
            // The gateway validates these in order; each must be present and verbatim.
            assertEquals("school", seenSchool)
            assertEquals("domain", seenDomain)
            assertEquals("area", seenArea)
            // The first round always announces the all-zero id.
            assertEquals(nil, seenAlgo)
            assertEquals(DORM_ANDROID_USER_AGENT, seenUa)
            assertTrue(isDormAlgoId(seenClientId))
        } finally { client.close() }
    }

    @Test fun aRefusalCarriesTheGatewaysOwnCodeNotAGuess() = runTest {
        val client = KtorDormHandshake(HttpClient(MockEngine {
            respond("", HttpStatusCode.OK, headersOf("Error-Code", "15"))
        }))
        try {
            val result = client.negotiate(DORM_AUTH_SERVERS.first(),
                PortalAddress("10.0.0.2", "10.0.0.1"), "", "", "")
            val rejected = assertIs<DormHandshakeResult.Rejected>(result)
            assertEquals("15", rejected.errorCode)
        } finally { client.close() }
    }

    @Test fun a200WithoutA001BodyIsNotTreatedAsNegotiation() = runTest {
        // This is the ordinary-success case: no blob, therefore no id, therefore not negotiated.
        val client = KtorDormHandshake(HttpClient(MockEngine {
            respond("", HttpStatusCode.OK, headersOf("Error-Code", "200"))
        }))
        try {
            val result = client.negotiate(DORM_AUTH_SERVERS.first(),
                PortalAddress("10.0.0.2", "10.0.0.1"), "", "", "")
            assertIs<DormHandshakeResult.Rejected>(result)
        } finally { client.close() }
    }

    @Test fun aBlobWithoutAReadableIdIsReportedRatherThanFaked() = runTest {
        val client = KtorDormHandshake(HttpClient(MockEngine {
            respond("001nope", HttpStatusCode.OK, headersOf("Error-Code", "200"))
        }))
        try {
            val result = client.negotiate(DORM_AUTH_SERVERS.first(),
                PortalAddress("10.0.0.2", "10.0.0.1"), "", "", "")
            assertIs<DormHandshakeResult.UnreadableBlob>(result)
        } finally { client.close() }
    }

    @Test fun aThrowingEngineIsUnreachable() = runTest {
        val client = KtorDormHandshake(HttpClient(MockEngine { throw kotlinx.io.IOException("x") }))
        try {
            assertEquals(DormHandshakeResult.Unreachable, client.negotiate(DORM_AUTH_SERVERS.first(),
                PortalAddress("10.0.0.2", "10.0.0.1"), "", "", ""))
        } finally { client.close() }
    }
}
