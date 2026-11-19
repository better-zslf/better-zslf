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

class InstalledRcRepositoryTest {
    private val suffix = ProductUpdatePolicy.Standard.versionNameSuffix
    private fun installed(value: String) = assertNotNull(StableVersion.parseInstalled("$value$suffix"))
    private fun stable(value: String) = assertNotNull(StableVersion.parse("$value$suffix"))

    @Test
    fun invalidRcAndWrongEditionStayUnknownWithoutAnyRequest() = runTest {
        var requests = 0
        val repo = GithubReleaseRepository(HttpClient(MockEngine { requests++; error("Invalid installed version must remain local") }))
        val invalid = listOf(
            "1.0.0-rc0", "1.0.0-rc00", "1.0.0-rc01", "1.0.0-rc000001", "1.0.0-rc1000000",
            "1.0.0-rc", "1.0.0-RC1", "1.0.0-Rc1", "1.0.0-rc+1", "1.0.0-rc-1", "1.0.0-rc1.0",
            "1.0.0-rc1+build", "1.0.0-beta1", "01.0.0-rc1", "1.00.0-rc1", "1.0.00-rc1",
            "1.0-rc1", "1.0.0.0-rc1", "1.0.0-pro-rc1", "1.0.0-rc1-pro-pro", "1.0.0-rc1-PRO",
            " 1.0.0-rc1", "1.0.0-rc1 ", "1.0.0-rc1\n", "1.${"9".repeat(130)}.0-rc1",
        ).map { "$it$suffix" } + listOf(
            "1.0.0-rc1-pro",
            "1.0.0-rc1-standard",
        )
        try {
            for (name in invalid) {
                assertNull(StableVersion.parseInstalled(name), name)
                assertEquals(ReleaseIssue.UNKNOWN_CURRENT_VERSION, assertIs<GithubReleaseCheck.Failed>(repo.check(name)).reason, name)
            }
            // Normalized tag syntax is still not an exact installed version, as with the original stable guard.
            assertEquals(ReleaseIssue.UNKNOWN_CURRENT_VERSION,
                assertIs<GithubReleaseCheck.Failed>(repo.check("v1.0.0-rc1$suffix")).reason)
            assertEquals(0, requests)
        } finally { repo.close() }
    }

    @Test
    fun sameBaseStableIsAnUpdateFromInstalledRcAndFetchesOnlyItsOwnChecksum() = runTest {
        val fixture = fixture()
        val urls = mutableListOf<String>()
        val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
            urls += request.url.toString()
            assertNull(request.headers[HttpHeaders.Authorization])
            assertNull(request.headers[HttpHeaders.Cookie])
            when (request.url.toString()) {
                GithubReleasePolicy.LATEST_API_URL -> respond(fixture.body)
                fixture.sumsUrl -> respond(fixture.checksums)
                else -> error("Only latest metadata and its checksum may be read")
            }
        }))
        try {
            assertTrue(urls.isEmpty())
            val candidate = assertIs<GithubReleaseCheck.Available>(repo.check("1.0.0-rc1$suffix")).candidate
            assertEquals(fixture.version, candidate.versionName)
            assertEquals(fixture.candidate.apk, candidate.apk)
            assertTrue(GithubReleasePolicy.isInstallSpec(candidate.apk))
            assertEquals(listOf(GithubReleasePolicy.LATEST_API_URL, fixture.sumsUrl), urls)
        } finally { repo.close() }
    }

    @Test
    fun newerBaseInstalledRcIsAheadOfAnOlderStableWithoutChecksumOrApkReads() = runTest {
        val fixture = fixture()
        val urls = mutableListOf<String>()
        val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
            urls += request.url.toString()
            assertEquals(GithubReleasePolicy.LATEST_API_URL, request.url.toString())
            respond(fixture.body)
        }))
        try {
            assertEquals(GithubReleaseCheck.UpToDate(fixture.version, installedAhead = true), repo.check("1.0.1-rc1$suffix"))
            assertEquals(listOf(GithubReleasePolicy.LATEST_API_URL), urls)
        } finally { repo.close() }
    }

    @Test
    fun remoteRcIsRejectedEvenWhenTheApiClaimsItIsStable() = runTest {
        val cases = listOf(
            fixture("1.0.1-rc1$suffix", prerelease = false) to ReleaseIssue.INVALID_RELEASE_VERSION,
            fixture("1.0.1-rc1$suffix", prerelease = true) to ReleaseIssue.PRERELEASE,
            fixture("1.0.1$suffix", prerelease = true) to ReleaseIssue.PRERELEASE,
        )
        for ((fixture, issue) in cases) {
            val urls = mutableListOf<String>()
            val repo = GithubReleaseRepository(HttpClient(MockEngine { request ->
                urls += request.url.toString()
                assertEquals(GithubReleasePolicy.LATEST_API_URL, request.url.toString())
                respond(fixture.body)
            }))
            try {
                assertEquals(issue, assertIs<GithubReleaseCheck.Failed>(repo.check("1.0.0-rc1$suffix")).reason)
                assertEquals(listOf(GithubReleasePolicy.LATEST_API_URL), urls)
            } finally { repo.close() }
        }
    }

    private data class ReleaseFixture(
        val version: String,
        val apkUrl: String,
        val sumsUrl: String,
        val checksums: String,
        val body: String,
    ) {
        val candidate get() = GithubReleaseCandidate(version, "fixture notes", ApkUpdateSpec(version, apkUrl, "a".repeat(64), 1024))
    }

    private fun fixture(version: String = "1.0.0$suffix", prerelease: Boolean = false): ReleaseFixture {
        val filename = "better-zslf-$version.apk"
        val apkUrl = "${GithubReleasePolicy.ASSET_PREFIX}v$version/$filename"
        val sumsUrl = "${GithubReleasePolicy.ASSET_PREFIX}v$version/SHA256SUMS"
        val checksums = "${"a".repeat(64)}  $filename\n"
        val body = buildJsonObject {
            put("tag_name", "v$version")
            put("draft", false)
            put("prerelease", prerelease)
            put("body", "fixture notes")
            put("assets", JsonArray(listOf(
                buildJsonObject { put("name", filename); put("browser_download_url", apkUrl); put("size", 1024) },
                buildJsonObject { put("name", "SHA256SUMS"); put("browser_download_url", sumsUrl); put("size", checksums.encodeToByteArray().size) },
            )))
        }.toString()
        return ReleaseFixture(version, apkUrl, sumsUrl, checksums, body)
    }
}
