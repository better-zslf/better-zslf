package io.github.betterzslf.feature.profile

import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AvatarByteRepositoryTest {
    private val resource = ProfilePhotoMetadata.Resource(AvatarImagePolicy.filesBaseUrl + "image/fixture.png")
    private fun issue(result: AvatarLoadResult, expected: AvatarIssue) = assertEquals(expected, assertIs<AvatarLoadResult.Failed>(result).issue)

    @Test
    fun constructionHasZeroHttpAndOnlyValidatedBytesLeaveThePublicClientWithoutOwnerHeaders() = runTest {
        var requests = 0
        val engine = MockEngine { request ->
            requests++; assertEquals(HttpMethod.Get, request.method)
            assertNull(request.headers[HttpHeaders.Authorization]); assertNull(request.headers[HttpHeaders.Cookie])
            assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
            respond(profilePngHeader(), headers = headersOf(HttpHeaders.SetCookie, "fixture-cookie=secret"))
        }
        val owner = HttpClient(engine) { defaultRequest { headers.append(HttpHeaders.Authorization, "fixture-school-secret"); headers.append(HttpHeaders.Cookie, "fixture-school-cookie") } }
        val repository = AvatarByteRepository(owner)
        try {
            assertEquals(0, requests)
            for (attempt in 0..1) {
                val image = assertIs<AvatarLoadResult.Image>(repository.load(resource)).image
                assertEquals(64, image.width); assertEquals(AvatarFormat.PNG, image.format)
                assertFalse(image.toString().contains("fixture")); assertFalse(resource.toString().contains(resource.url))
                image.clear(); assertTrue(image.encoded.all { it == 0.toByte() })
            }
            assertEquals(2, requests)
        } finally { repository.close(); repository.close() }
        issue(repository.load(resource), AvatarIssue.CLOSED); assertEquals(2, requests)
    }

    @Test
    fun wrongOriginAndAbsoluteOrDoublePrefixedResourcesAreLocalFailures() = runTest {
        var requests = 0
        val repository = AvatarByteRepository(HttpClient(MockEngine { requests++; error("No HTTP expected") }))
        try {
            for (url in listOf("https://evil.invalid/a.png", AvatarImagePolicy.filesBaseUrl + "userfiles/a.png", AvatarImagePolicy.filesBaseUrl + "a.png?secret=fixture", AvatarImagePolicy.filesBaseUrl + "../a.png")) {
                issue(repository.load(ProfilePhotoMetadata.Resource(url)), AvatarIssue.UNSUPPORTED_PATH)
            }
            assertEquals(0, requests)
        } finally { repository.close() }
    }

    @Test
    fun redirectsNeverFollowAnd401NeverInvalidatesTheSchoolSession() = runTest {
        val sessions = SessionStore(MemoryKeyValueStore()).apply { save(fixtureSession()) }
        val before = sessions.session.value
        for (status in listOf(HttpStatusCode.Found, HttpStatusCode.Unauthorized, HttpStatusCode.InternalServerError)) {
            var requests = 0
            val repository = AvatarByteRepository(HttpClient(MockEngine { request ->
                requests++; assertNull(request.headers[HttpHeaders.Authorization]); assertNull(request.headers[HttpHeaders.Cookie])
                respond("fixture-secret", status, headersOf(HttpHeaders.Location, "https://evil.invalid/fixture-secret"))
            }))
            try {
                val result = repository.load(resource)
                issue(result, if (status.value in 300..399) AvatarIssue.REDIRECT else AvatarIssue.HTTP)
                assertEquals(1, requests); assertSame(before, sessions.session.value)
                assertFalse(result.toString().contains("fixture-secret"))
            } finally { repository.close() }
        }
    }

    @Test
    fun contentLengthAndStreamingBytesAreBothHardBoundedAtOneMiB() = runTest {
        val declared = AvatarByteRepository(HttpClient(MockEngine { respond(profilePngHeader(), headers = headersOf(HttpHeaders.ContentLength, (AvatarImagePolicy.MAX_BYTES + 1).toString())) }))
        try { issue(declared.load(resource), AvatarIssue.TOO_LARGE) } finally { declared.close() }
        val streaming = AvatarByteRepository(HttpClient(MockEngine { respond(ByteArray(AvatarImagePolicy.MAX_BYTES + 1)) }))
        try { issue(streaming.load(resource), AvatarIssue.TOO_LARGE) } finally { streaming.close() }
        val malformed = AvatarByteRepository(HttpClient(MockEngine { respond(profilePngHeader(), headers = headersOf(HttpHeaders.ContentLength, "fixture-secret")) }))
        try { issue(malformed.load(resource), AvatarIssue.MALFORMED_IMAGE) } finally { malformed.close() }
    }

    @Test
    fun unsupportedFormatDimensionsAndTimeoutDoNotBecomeSuccessfulImages() = runTest {
        val cases = listOf("RIFFxxxxWEBP".encodeToByteArray() to AvatarIssue.UNSUPPORTED_FORMAT,
            profilePngHeader(4096, 4096) to AvatarIssue.DIMENSIONS, profileJpegHeader().copyOf(8) to AvatarIssue.MALFORMED_IMAGE)
        for ((bytes, expected) in cases) {
            val repository = AvatarByteRepository(HttpClient(MockEngine { respond(bytes) }))
            try { issue(repository.load(resource), expected) } finally { repository.close() }
        }
        val timeout = AvatarByteRepository(HttpClient(MockEngine { throw HttpRequestTimeoutException("https://fixture.invalid/fixture-secret", 100) }))
        try { issue(timeout.load(resource), AvatarIssue.TIMEOUT) } finally { timeout.close() }
        val network = AvatarByteRepository(HttpClient(MockEngine { error("fixture-secret") }))
        try { issue(network.load(resource), AvatarIssue.NETWORK) } finally { network.close() }
    }

    @Test
    fun callerCancellationStopsThePendingReadWithoutAnAutomaticRetry() = runTest {
        var requests = 0
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val repository = AvatarByteRepository(HttpClient(MockEngine {
            requests++
            entered.complete(Unit)
            try { gate.await(); respond(profilePngHeader()) }
            finally { stopped.complete(Unit) }
        }))
        try {
            val pending = async { repository.load(resource) }
            // MockEngine may use another dispatcher: runCurrent() alone does not start its handler.
            entered.await(); assertEquals(1, requests)
            pending.cancel(); pending.join(); stopped.await()
            assertTrue(pending.isCancelled)
            assertEquals(1, requests)
        } finally { gate.complete(Unit); repository.close() }
    }
}
