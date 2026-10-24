package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.ApkUpdateSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class GithubReleaseRepositoryTest {
    private val filename = "better-zslf-1.1.0.apk"
    private val apkUrl = GithubReleasePolicy.ASSET_PREFIX + "v1.1.0/$filename"
    private val sumsUrl = GithubReleasePolicy.ASSET_PREFIX + "v1.1.0/SHA256SUMS"
    private val sha = "a".repeat(64)
    private val sums = "$sha  $filename\n"
    private fun asset(name: String, url: String, size: Long) = buildJsonObject {
        put("name", name); put("browser_download_url", url); put("size", size)
    }
    private fun apk(url: String = apkUrl, size: Long = 1024) = asset(filename, url, size)
    private fun checksumAsset(url: String = sumsUrl, text: String = sums) = asset("SHA256SUMS", url, text.encodeToByteArray().size.toLong())
    private fun release(assets: List<JsonObject> = listOf(apk(), checksumAsset()), extra: Map<String, JsonElement> = emptyMap()): String = buildJsonObject {
        put("tag_name", "v1.1.0"); put("draft", false); put("prerelease", false)
        put("body", "Plain release notes <script>not executed</script>")
        put("assets", JsonArray(assets)); put("future_field", buildJsonObject { put("ignored", true) })
        for ((key, value) in extra) put(key, value)
    }.toString()
    private fun assertIssue(result: GithubReleaseCheck, issue: ReleaseIssue) = assertEquals(issue, assertIs<GithubReleaseCheck.Failed>(result).reason)

    @Test
    fun constructionDoesZeroHttp404IsNotPublishedAndCloseIsLocalAndIdempotent() = runTest {
        var requests = 0
        val repo = GithubReleaseRepository(HttpClient(MockEngine { requests++; respond("fixture-secret", HttpStatusCode.NotFound) }))
        assertEquals(0, requests)
        assertEquals(GithubReleaseCheck.NotPublished, repo.check("1.0.0"))
        assertEquals(1, requests)
        repo.close(); repo.close()
        assertIssue(repo.check("1.0.0"), ReleaseIssue.CLOSED)
        assertEquals(1, requests)
    }

    @Test
    fun strictVersionNumbersNeverOverflowAndUnknownInstalledVersionIsNotUpToDate() = runTest {
        assertTrue(assertNotNull(StableVersion.parse("1.9999999999999999999999999999.0")) > assertNotNull(StableVersion.parse("1.9999999999999999999999999998.9")))
        assertTrue(assertNotNull(StableVersion.parse("100000000000000000000.0.0")) > assertNotNull(StableVersion.parse("99999999999999999999.99.99")))
        assertEquals(0, assertNotNull(StableVersion.parse("v1.2.3")).compareTo(assertNotNull(StableVersion.parse("1.2.3"))))
        val repo = GithubReleaseRepository(HttpClient(MockEngine { error("Unknown installed versions must stay local") }))
        try {
            for (bad in listOf("", "unknown", "1.2", "01.2.3", "1.2.3-beta", "1.2.3+build", " 1.2.3", "v", "1." + "9".repeat(130) + ".0")) {
                assertNull(StableVersion.parse(bad))
                assertIssue(repo.check(bad), ReleaseIssue.UNKNOWN_CURRENT_VERSION)
            }
            assertIssue(repo.check("v1.0.0"), ReleaseIssue.UNKNOWN_CURRENT_VERSION)
        } finally { repo.close() }
    }

    @Test
    fun candidateUsesSameReleaseChecksumAndInheritsNoOwnerHeadersOrCookies() = runTest {
        val urls = mutableListOf<String>()
        val engine = MockEngine { request ->
            urls += request.url.toString()
            assertEquals(HttpMethod.Get, request.method)
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie])
            assertEquals("identity", request.headers[HttpHeaders.AcceptEncoding])
            assertEquals("better-zslf-updater", request.headers[HttpHeaders.UserAgent])
            if (request.url.host == "api.github.com") respond(release(), headers = headersOf(HttpHeaders.SetCookie, "fixture=secret; Domain=github.com"))
            else respond(sums.uppercase().replace(filename.uppercase(), filename))
        }
        val owner = HttpClient(engine) { defaultRequest { headers.append(HttpHeaders.Authorization, "fixture-school-secret"); headers.append(HttpHeaders.Cookie, "fixture-school-cookie") } }
        val repo = GithubReleaseRepository(owner)
        try {
            val candidate = assertIs<GithubReleaseCheck.Available>(repo.check("1.0.0")).candidate
            assertEquals("1.1.0", candidate.versionName)
            assertEquals(sha, candidate.apk.sha256)
            assertEquals(apkUrl, candidate.apk.downloadUrl)
            assertTrue(candidate.notes.contains("<script>not executed</script>")) // Plain text only.
            assertFalse(candidate.toString().contains(apkUrl))
            assertFalse(candidate.toString().contains(sha))
            assertEquals(listOf(GithubReleasePolicy.LATEST_API_URL, sumsUrl), urls)
        } finally { repo.close() }
    }

    @Test
    fun equalAndAheadStableVersionsDoNotDownloadAnyChecksumOrApk() = runTest {
        var requests = 0
        val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
            requests++; assertEquals("api.github.com", request.url.host); respond(release())
        }))
        try {
            assertEquals(GithubReleaseCheck.UpToDate("1.1.0"), repo.check("1.1.0"))
            assertEquals(GithubReleaseCheck.UpToDate("1.1.0", true), repo.check("2.0.0"))
            assertEquals(2, requests)
        } finally { repo.close() }
    }

    @Test
    fun draftPrereleaseAndWrongRequiredTypesHaveAccurateFailures() = runTest {
        val cases = listOf(
            release(extra = mapOf("draft" to JsonPrimitive(true))) to ReleaseIssue.DRAFT,
            release(extra = mapOf("prerelease" to JsonPrimitive(true))) to ReleaseIssue.PRERELEASE,
            release(extra = mapOf("tag_name" to JsonPrimitive("v1.1.0-beta"))) to ReleaseIssue.INVALID_RELEASE_VERSION,
            release(extra = mapOf("draft" to JsonPrimitive("false"))) to ReleaseIssue.INVALID_RESPONSE,
            release(extra = mapOf("prerelease" to JsonNull)) to ReleaseIssue.INVALID_RESPONSE,
            release(extra = mapOf("tag_name" to JsonPrimitive(110))) to ReleaseIssue.INVALID_RESPONSE,
            release(extra = mapOf("assets" to JsonObject(emptyMap()))) to ReleaseIssue.INVALID_RESPONSE,
            release(extra = mapOf("body" to JsonPrimitive(false))) to ReleaseIssue.INVALID_RESPONSE,
            "not-json fixture-secret" to ReleaseIssue.INVALID_RESPONSE,
        )
        for ((body, issue) in cases) {
            val repo = GithubReleaseRepository(HttpClient(MockEngine { respond(body) }))
            try { assertIssue(repo.check("1.0.0"), issue) } finally { repo.close() }
        }
    }

    @Test
    fun noApkMultipleApkNoSumsAndWrongAssetTypesAreDistinct() = runTest {
        val malformed = JsonObject(apk() + ("size" to JsonPrimitive("1024")))
        val cases = listOf(
            release(listOf(checksumAsset())) to ReleaseIssue.NO_APK,
            release(listOf(apk(), apk(), checksumAsset())) to ReleaseIssue.AMBIGUOUS_APK,
            release(listOf(apk())) to ReleaseIssue.NO_CHECKSUM,
            release(listOf(apk(), checksumAsset(), checksumAsset())) to ReleaseIssue.INVALID_CHECKSUM,
            release(listOf(malformed, checksumAsset())) to ReleaseIssue.INVALID_RESPONSE,
            release(listOf(apk(size = 0), checksumAsset())) to ReleaseIssue.INVALID_ASSET,
            release(listOf(apk(size = GithubReleasePolicy.MAX_APK_BYTES + 1), checksumAsset())) to ReleaseIssue.INVALID_ASSET,
            release(listOf(apk(), checksumAsset(url = "https://evil.invalid/SHA256SUMS"))) to ReleaseIssue.INVALID_ASSET,
            release(listOf(apk(), asset("SHA256SUMS.txt", sumsUrl, 100))) to ReleaseIssue.NO_CHECKSUM,
            release(listOf(asset("other.apk", apkUrl, 100), checksumAsset())) to ReleaseIssue.INVALID_ASSET,
        )
        for ((body, issue) in cases) {
            val repo = GithubReleaseRepository(HttpClient(MockEngine { respond(body) }))
            try { assertIssue(repo.check("1.0.0"), issue) } finally { repo.close() }
        }
    }

    @Test
    fun initialAssetsRejectForeignHostCredentialsQueryPortTraversalAndWrongTag() = runTest {
        val badUrls = listOf(
            apkUrl.replace("https:", "http:"), apkUrl.replace("github.com", "github.com.evil.invalid"),
            apkUrl.replace("github.com", "fixture:secret@github.com"), apkUrl.replace("github.com", "github.com:443"),
            "$apkUrl?token=fixture-secret", "$apkUrl#fragment", apkUrl.replace("v1.1.0/", "v1.1.0/../"),
            apkUrl.replace("v1.1.0/", "v1.1.0/%2e%2e/"), apkUrl.replace("v1.1.0", "v9.9.9"),
            apkUrl.replace("better-zslf/better-zslf", "other/repository"), apkUrl.replace("v1.1.0/", "1.1.0/"),
        )
        for (url in badUrls) {
            val repo = GithubReleaseRepository(HttpClient(MockEngine { respond(release(listOf(apk(url), checksumAsset()))) }))
            try { assertIssue(repo.check("1.0.0"), ReleaseIssue.INVALID_ASSET) } finally { repo.close() }
        }
        assertTrue(GithubReleasePolicy.isInstallSpec(ApkUpdateSpec("1.1.0", apkUrl, sha, GithubReleasePolicy.MAX_APK_BYTES)))
        assertFalse(GithubReleasePolicy.isInstallSpec(ApkUpdateSpec("v1.1.0", apkUrl, sha, 100)))
    }

    @Test
    fun checksumMustContainExactlyOneMatching64HexEntry() = runTest {
        val bad = listOf(
            "${"a".repeat(63)}  $filename\n", "${"z".repeat(64)}  $filename\n",
            "$sha  other.apk\n", sums + sums, "$sha  ../$filename\n", "fixture-secret",
        )
        for (text in bad) {
            val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
                if (request.url.host == "api.github.com") respond(release(listOf(apk(), checksumAsset(text = text)))) else respond(text)
            }))
            try { assertIssue(repo.check("1.0.0"), ReleaseIssue.INVALID_CHECKSUM) } finally { repo.close() }
        }
        assertEquals(sha, GithubReleasePolicy.checksum("$sha *$filename\r\n", filename))
    }

    @Test
    fun checksumRedirectsOnlyToAllowedHttpsHostsWithoutHeaders() = runTest {
        val urls = mutableListOf<String>()
        val cdn = "https://release-assets.githubusercontent.com/asset/fixture?sig=fixture-public-signature"
        val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
            urls += request.url.toString()
            assertNull(request.headers[HttpHeaders.Authorization]); assertNull(request.headers[HttpHeaders.Cookie])
            when (request.url.host) {
                "api.github.com" -> respond(release())
                "github.com" -> respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, cdn))
                "release-assets.githubusercontent.com" -> respond(sums)
                else -> error("Unexpected destination")
            }
        }))
        try { assertIs<GithubReleaseCheck.Available>(repo.check("1.0.0")); assertEquals(3, urls.size) } finally { repo.close() }
        assertTrue(GithubReleasePolicy.isChecksumRedirect("https://objects.githubusercontent.com/asset?id=fixture", "v1.1.0"))
    }

    @Test
    fun checksumRedirectToUntrustedHostAndRedirectLoopsStopBeforeFollowing() = runTest {
        val forbidden = listOf("https://evil.invalid/file", "http://objects.githubusercontent.com/file", "https://objects.githubusercontent.com.evil.invalid/file", "https://user:secret@objects.githubusercontent.com/file", "https://objects.githubusercontent.com:443/file", "/relative", sumsUrl)
        for (target in forbidden) {
            var requests = 0
            val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
                requests++
                if (request.url.host == "api.github.com") respond(release()) else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, target))
            }))
            try { assertIssue(repo.check("1.0.0"), ReleaseIssue.REDIRECT_BLOCKED); assertEquals(2, requests) } finally { repo.close() }
        }
    }

    @Test
    fun redirectCountAndBothResponseBodiesAreBoundedWithoutContentLength() = runTest {
        var redirects = 0
        val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
            if (request.url.host == "api.github.com") respond(release()) else {
                redirects++
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://objects.githubusercontent.com/file$redirects"))
            }
        }))
        try { assertIssue(repo.check("1.0.0"), ReleaseIssue.REDIRECT_BLOCKED); assertEquals(4, redirects) } finally { repo.close() }
        val bigJson = GithubReleaseRepository(HttpClient(MockEngine { respond("x".repeat(GithubReleasePolicy.MAX_JSON_BYTES + 1)) }))
        try { assertIssue(bigJson.check("1.0.0"), ReleaseIssue.RESPONSE_TOO_LARGE) } finally { bigJson.close() }
        val bigSums = GithubReleaseRepository(HttpClient(MockEngine { request ->
            if (request.url.host == "api.github.com") respond(release()) else respond("x".repeat(GithubReleasePolicy.MAX_CHECKSUM_BYTES + 1))
        }))
        try { assertIssue(bigSums.check("1.0.0"), ReleaseIssue.RESPONSE_TOO_LARGE) } finally { bigSums.close() }
    }

    @Test
    fun apiRedirectHttpFailureAndExceptionsNeverEchoBodyUrlOrSecret() = runTest {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests, HttpStatusCode.InternalServerError, HttpStatusCode.Found)) {
            var requests = 0
            val repo = GithubReleaseRepository(HttpClient(MockEngine { requests++; respond("fixture-secret", status, headersOf(HttpHeaders.Location, "https://evil.invalid/fixture-secret")) }))
            try {
                val result = assertIs<GithubReleaseCheck.Failed>(repo.check("1.0.0"))
                assertEquals(ReleaseIssue.HTTP, result.reason); assertEquals(status.value, result.httpStatus)
                assertFalse(result.toString().contains("fixture-secret")); assertEquals(1, requests)
            } finally { repo.close() }
        }
        val network = GithubReleaseRepository(HttpClient(MockEngine { throw IllegalStateException("fixture-secret https://evil.invalid") }))
        try { assertIssue(network.check("1.0.0"), ReleaseIssue.NETWORK) } finally { network.close() }
        val timeout = GithubReleaseRepository(HttpClient(MockEngine { throw HttpRequestTimeoutException("https://fixture.invalid/?secret=fixture-secret", 100) }))
        try { assertIssue(timeout.check("1.0.0"), ReleaseIssue.TIMEOUT) } finally { timeout.close() }
    }
}
