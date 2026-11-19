package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.ApkUpdateSpec
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class ProductUpdatePolicyTransportTest {
    private val standard = ProductUpdatePolicy.Standard
    // Explicit test-only product composition. There is no built-in global Pro policy to select.
    private val pro = ProductUpdatePolicy(
        repository = "better-zslf/pro-ci",
        apkAssetPrefix = "better-zslf-",
        androidApplicationId = "io.github.betterzslf.pro",
        versionNameSuffix = "-pro",
    )
    private val other = ProductUpdatePolicy(
        repository = "fixture-owner/other-product",
        apkAssetPrefix = "other-product-",
        androidApplicationId = "io.fixture.other",
        versionNameSuffix = "-other",
    )
    private val sha = "a".repeat(64)

    @Test
    fun checksumRedirectAllowlistRemainsStrictForEveryProductAndTag() {
        for (policy in listOf(standard, pro, other)) {
            val version = "1.2.3${policy.versionNameSuffix}"
            val tag = "v$version"
            assertTrue(GithubChecksumPolicy.isChecksumRedirect("${policy.assetUrlPrefix}$tag/SHA256SUMS", tag, policy))
            assertTrue(GithubChecksumPolicy.isChecksumRedirect("https://objects.githubusercontent.com/asset?id=fixture", tag, policy))
            assertTrue(GithubChecksumPolicy.isChecksumRedirect("https://release-assets.githubusercontent.com/asset?sig=fixture", tag, policy))
            for (url in listOf("https://github.com/other/repository/releases/download/$tag/SHA256SUMS",
                "http://objects.githubusercontent.com/asset", "https://objects.githubusercontent.com.evil.invalid/asset",
                "https://user:secret@objects.githubusercontent.com/asset", "https://objects.githubusercontent.com:443/asset",
                "https://objects.githubusercontent.com/%2e%2e/asset", "https://objects.githubusercontent.com/a/%2fasset",
                "https://objects.githubusercontent.com/a/../asset", "https://objects.githubusercontent.com/asset?x=%0a",
                "https://objects.githubusercontent.com/asset#fragment", "/relative")) {
                assertFalse(GithubChecksumPolicy.isChecksumRedirect(url, tag, policy), url)
            }
            assertFalse(GithubChecksumPolicy.isChecksumRedirect("https://objects.githubusercontent.com/asset", "v1.2.3-rc1${policy.versionNameSuffix}", policy))
        }
        assertFalse(GithubChecksumPolicy.isChecksumRedirect("https://objects.githubusercontent.com/asset", "v1.2.3", pro))
        assertFalse(GithubChecksumPolicy.isChecksumRedirect("https://objects.githubusercontent.com/asset", "v1.2.3-pro", standard))
    }

    @Test
    fun separateRepositoriesUseOnlyTheirInjectedPolicyWithoutCredentialsOrConstructionWork() = runTest {
        val urls = mutableListOf<String>()
        val standardFixture = fixture(standard)
        val proFixture = fixture(pro)
        fun repository(policy: ProductUpdatePolicy, fixture: Fixture): GithubReleaseRepository {
            val owner = HttpClient(MockEngine { request ->
                urls += request.url.toString()
                assertNull(request.headers[HttpHeaders.Authorization])
                assertNull(request.headers[HttpHeaders.Cookie])
                when (request.url.toString()) {
                    policy.latestApiUrl -> respond(fixture.body)
                    fixture.sumsUrl -> respond(fixture.sums)
                    else -> error("Only this product's latest metadata and checksum are permitted")
                }
            }) { defaultRequest { headers.append(HttpHeaders.Authorization, "fixture-owner-secret"); headers.append(HttpHeaders.Cookie, "fixture-owner-cookie") } }
            return GithubReleaseRepository(owner, policy)
        }
        val first = repository(standard, standardFixture)
        val second = repository(pro, proFixture)
        try {
            assertSame(standard, first.policy)
            assertSame(pro, second.policy)
            assertTrue(urls.isEmpty())
            assertEquals(proFixture.candidate.apk, assertIs<GithubReleaseCheck.Available>(second.check("1.2.3-rc1-pro")).candidate.apk)
            assertEquals(standardFixture.candidate.apk, assertIs<GithubReleaseCheck.Available>(first.check("1.2.3-rc1")).candidate.apk)
            assertEquals(GithubReleaseCheck.UpToDate("1.2.3-pro", true), second.check("1.2.4-rc1-pro"))
            assertEquals(listOf(pro.latestApiUrl, proFixture.sumsUrl, standard.latestApiUrl, standardFixture.sumsUrl, pro.latestApiUrl), urls)
            assertEquals(GithubReleasePolicy.LATEST_API_URL, standard.latestApiUrl)
        } finally { first.close(); second.close() }
    }

    @Test
    fun foreignInstalledIdentityIsRejectedBeforeAnyRequestAndNumericInstalledProIsNotRemotePermission() = runTest {
        for ((policy, wrong) in listOf(standard to "1.0.0-pro", standard to "1.0.0-rc1-pro",
            pro to "1.0.0-other", pro to "1.0.0-rc1", pro to "v1.0.0-rc1-pro")) {
            var requests = 0
            val repo = GithubReleaseRepository(HttpClient(MockEngine { requests++; error("Must stay local") }), policy)
            try {
                assertEquals(ReleaseIssue.UNKNOWN_CURRENT_VERSION, assertIs<GithubReleaseCheck.Failed>(repo.check(wrong)).reason)
                assertEquals(0, requests)
            } finally { repo.close() }
        }
        val own = fixture(pro)
        val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
            if (request.url.toString() == pro.latestApiUrl) respond(own.body) else respond(own.sums)
        }), pro)
        try { assertEquals(own.candidate.apk, assertIs<GithubReleaseCheck.Available>(repo.check("1.0.0")).candidate.apk) }
        finally { repo.close() }
    }

    @Test
    fun responseFieldsNeverReplaceTheProductPolicyAndForeignReleasesStopBeforeChecksum() = runTest {
        for ((policy, foreign) in listOf(standard to pro, pro to standard, pro to other)) {
            val body = fixture(foreign).body
            var requests = 0
            val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
                requests++
                assertEquals(policy.latestApiUrl, request.url.toString())
                respond(body)
            }), policy)
            try {
                val current = "1.0.0${policy.versionNameSuffix}"
                assertEquals(ReleaseIssue.INVALID_RELEASE_VERSION, assertIs<GithubReleaseCheck.Failed>(repo.check(current)).reason)
                assertEquals(1, requests)
                assertSame(policy, repo.policy)
            } finally { repo.close() }
        }
        for (policy in listOf(standard, pro, other)) {
            val own = fixture(policy)
            val wrongRepoUrl = own.candidate.apk.downloadUrl.replace(policy.repository, "foreign/repository")
            val wrongAsset = asset(policy.apkAssetName(own.version), wrongRepoUrl, 1024)
            val wrongName = "foreign-${own.version}.apk"
            val badBodies = listOf(
                fixture(policy, extraApk = wrongAsset).body,
                fixture(policy, extraApk = asset(wrongName, "${policy.assetUrlPrefix}v${own.version}/$wrongName", 1024)).body,
                fixture(policy, version = "1.2.3-rc1${policy.versionNameSuffix}").body,
                fixture(policy, prerelease = true).body,
            )
            val issues = listOf(ReleaseIssue.INVALID_ASSET, ReleaseIssue.INVALID_ASSET, ReleaseIssue.INVALID_RELEASE_VERSION, ReleaseIssue.PRERELEASE)
            for ((body, issue) in badBodies.zip(issues)) {
                var requests = 0
                val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
                    requests++; assertEquals(policy.latestApiUrl, request.url.toString()); respond(body)
                }), policy)
                try {
                    assertEquals(issue, assertIs<GithubReleaseCheck.Failed>(repo.check("1.0.0${policy.versionNameSuffix}")).reason)
                    assertEquals(1, requests)
                } finally { repo.close() }
            }
        }
    }

    @Test
    fun proChecksRetainBodyLimitsAndChecksumRedirectGuards() = runTest {
        val own = fixture(pro)
        for ((body, expected) in listOf("x".repeat(GithubReleasePolicy.MAX_JSON_BYTES + 1) to ReleaseIssue.RESPONSE_TOO_LARGE,
            own.body to ReleaseIssue.REDIRECT_BLOCKED)) {
            var requests = 0
            val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
                requests++
                if (request.url.toString() == pro.latestApiUrl) respond(body)
                else respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.invalid/SHA256SUMS"))
            }), pro)
            try {
                assertEquals(expected, assertIs<GithubReleaseCheck.Failed>(repo.check("1.0.0-pro")).reason)
                assertEquals(if (expected == ReleaseIssue.RESPONSE_TOO_LARGE) 1 else 2, requests)
            } finally { repo.close() }
        }
    }

    @Test
    fun proChecksumMustMatchExactBoundedAdvertisedContentAndSingleEntry() = runTest {
        val own = fixture(pro)
        val filename = pro.apkAssetName(own.version)
        val cases = listOf(
            own.sums + own.sums to ReleaseIssue.INVALID_CHECKSUM,
            "${"a".repeat(63)}  $filename\n" to ReleaseIssue.INVALID_CHECKSUM,
            "$sha  other-product-${own.version}.apk\n" to ReleaseIssue.INVALID_CHECKSUM,
            "$sha  ../$filename\n" to ReleaseIssue.INVALID_CHECKSUM,
            "x".repeat(GithubReleasePolicy.MAX_CHECKSUM_BYTES + 1) to ReleaseIssue.RESPONSE_TOO_LARGE,
        )
        for ((text, issue) in cases) {
            var requests = 0
            val body = buildJsonObject {
                put("tag_name", "v${own.version}"); put("draft", false); put("prerelease", false)
                put("assets", JsonArray(listOf(asset(filename, own.candidate.apk.downloadUrl, 1024),
                    asset("SHA256SUMS", own.sumsUrl, text.encodeToByteArray().size.toLong().coerceAtMost(GithubReleasePolicy.MAX_CHECKSUM_BYTES.toLong())))))
            }.toString()
            val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
                requests++
                when (request.url.toString()) {
                    pro.latestApiUrl -> respond(body)
                    own.sumsUrl -> respond(text)
                    else -> error("A foreign checksum or APK must not be fetched")
                }
            }), pro)
            try {
                assertEquals(issue, assertIs<GithubReleaseCheck.Failed>(repo.check("1.0.0-pro")).reason)
                assertEquals(2, requests)
            } finally { repo.close() }
        }
        val wrongSizeBody = buildJsonObject {
            put("tag_name", "v${own.version}"); put("draft", false); put("prerelease", false)
            put("assets", JsonArray(listOf(asset(filename, own.candidate.apk.downloadUrl, 1024),
                asset("SHA256SUMS", own.sumsUrl, own.sums.encodeToByteArray().size.toLong() + 1))))
        }.toString()
        val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
            if (request.url.toString() == pro.latestApiUrl) respond(wrongSizeBody) else respond(own.sums)
        }), pro)
        try { assertEquals(ReleaseIssue.INVALID_CHECKSUM, assertIs<GithubReleaseCheck.Failed>(repo.check("1.0.0-pro")).reason) }
        finally { repo.close() }
    }

    private fun spec(policy: ProductUpdatePolicy, version: String = "1.2.3${policy.versionNameSuffix}") = ApkUpdateSpec(
        versionName = version,
        downloadUrl = "${policy.assetUrlPrefix}v$version/${policy.apkAssetName(version)}",
        sha256 = sha,
        sizeBytes = 1024,
    )

    private data class Fixture(val version: String, val candidate: GithubReleaseCandidate, val sumsUrl: String, val sums: String, val body: String)

    private fun asset(name: String, url: String, size: Long) = buildJsonObject {
        put("name", name); put("browser_download_url", url); put("size", size)
    }

    private fun fixture(
        policy: ProductUpdatePolicy,
        version: String = "1.2.3${policy.versionNameSuffix}",
        extraApk: JsonObject? = null,
        prerelease: Boolean = false,
    ): Fixture {
        val apk = spec(policy, version)
        val filename = policy.apkAssetName(version)
        val sumsUrl = "${policy.assetUrlPrefix}v$version/SHA256SUMS"
        val sums = "$sha  $filename\n"
        val body = buildJsonObject {
            put("tag_name", "v$version"); put("draft", false); put("prerelease", prerelease)
            put("body", "fixture notes")
            // Untrusted extra fields cannot replace any part of the composition-root policy.
            put("repository", "foreign/repository"); put("androidApplicationId", "io.foreign.app")
            put("versionNameSuffix", "-foreign"); put("channel", "rc")
            put("assets", JsonArray(listOf(extraApk ?: asset(filename, apk.downloadUrl, apk.sizeBytes),
                asset("SHA256SUMS", sumsUrl, sums.encodeToByteArray().size.toLong()))))
        }.toString()
        return Fixture(version, GithubReleaseCandidate(version, "fixture notes", apk), sumsUrl, sums, body)
    }
}
