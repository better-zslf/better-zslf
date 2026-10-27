package io.github.betterzslf.feature.update

import io.github.betterzslf.edition.BuildEdition
import io.github.betterzslf.platform.ApkUpdateSpec
import io.github.betterzslf.platform.AppUpdatePhase
import io.github.betterzslf.platform.AppUpdateState
import io.github.betterzslf.platform.NativeAppUpdater
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/** Independent RC fixtures run against the selected Standard OR Pro BuildEdition; old invalid cases stay intact. */
@OptIn(ExperimentalCoroutinesApi::class)
class InstalledRcUpdateTest {
    private val suffix = BuildEdition.versionNameSuffix
    private fun installed(value: String) = assertNotNull(StableVersion.parseInstalled("$value$suffix"))
    private fun stable(value: String) = assertNotNull(StableVersion.parse("$value$suffix"))

    @Test
    fun installedRcHasItsExactDisplayNameButRemoteParserStaysStableOnly() {
        for (number in listOf("1", "2", "10", "999999")) {
            val name = "1.0.0-rc$number$suffix"
            assertEquals(name, assertNotNull(StableVersion.parseInstalled(name)).versionName)
            assertNull(StableVersion.parse(name))
        }
        assertEquals("1.0.0$suffix", assertNotNull(StableVersion.parseInstalled("1.0.0$suffix")).versionName)
        // Existing numeric iOS marketing-version compatibility is not removed for Pro.
        assertNotNull(StableVersion.parseInstalled("1.0.0"))
    }

    @Test
    fun stableBeatsSameBaseRcButBaseComponentsAndRcNumbersKeepTheirOwnPrecedence() {
        assertTrue(stable("1.0.0") > installed("1.0.0-rc999999"))
        assertTrue(installed("1.0.0-rc10") > installed("1.0.0-rc2"))
        assertTrue(installed("1.0.0-rc2") > installed("1.0.0-rc1"))
        assertTrue(installed("1.0.1-rc1") > stable("1.0.0"))
        assertTrue(installed("2.0.0-rc1") > stable("1.999999999999999999999999.999"))
        assertEquals(0, installed("1.0.0-rc1").compareTo(installed("1.0.0-rc1")))
        assertTrue(installed("1.0.0-rc1") < stable("1.0.0"))
    }

    @Test
    fun installedRcComparisonDoesNotOverflowAnyNumericComponent() {
        val high = "9".repeat(30)
        val low = "8".repeat(30)
        assertTrue(installed("$high.0.0-rc1") > stable("$low.999.999"))
        assertTrue(installed("1.$high.0-rc1") > stable("1.$low.999"))
        assertTrue(installed("1.1.$high-rc1") > stable("1.1.$low"))
        assertTrue(stable("$high.$high.$high") > installed("$high.$high.$high-rc999999"))
    }

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
            if (BuildEdition.isPro) "1.0.0-rc1" else "1.0.0-rc1-pro",
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

    @Test
    fun acceptingAnInstalledRcDoesNotAdmitRcInstallSpecsOrAssetUrls() {
        val fixture = fixture("1.0.0-rc1$suffix")
        assertNotNull(StableVersion.parseInstalled(fixture.version))
        assertNull(StableVersion.parse(fixture.version))
        assertFalse(GithubReleasePolicy.isInstallSpec(fixture.candidate.apk))
        assertFalse(GithubReleasePolicy.isAssetUrl(fixture.apkUrl, "v${fixture.version}", "better-zslf-${fixture.version}.apk"))
    }

    @Test
    fun rcControllerStillDoesNoAutomaticWorkAndRequiresSeparateSingleShotConfirmations() = runTest {
        val fixture = fixture()
        var checks = 0
        val current = "1.0.0-rc1$suffix"
        val source = object : GithubReleaseSource {
            override suspend fun check(currentVersionName: String): GithubReleaseCheck {
                assertEquals(current, currentVersionName)
                checks++
                return GithubReleaseCheck.Available(fixture.candidate)
            }
        }
        val updater = CountingUpdater()
        val controller = AppUpdateController(current, source, updater, this)
        try {
            runCurrent()
            assertEquals(0, checks)
            assertNull(controller.state.value.result)
            controller.confirmDownload(); controller.confirmInstall()
            assertEquals(0, updater.downloads); assertEquals(0, updater.installs)
            controller.check(); controller.check(); runCurrent()
            assertEquals(1, checks)
            assertTrue(controller.canDownload())
            assertEquals(0, updater.downloads)
            controller.confirmDownload()
            assertEquals(0, updater.downloads)
            controller.requestDownload()
            assertEquals(fixture.candidate.apk, controller.state.value.downloadConfirmation)
            assertEquals(0, updater.downloads)
            controller.confirmDownload(); controller.confirmDownload()
            assertEquals(1, updater.downloads)
            assertEquals(fixture.candidate.apk, updater.spec)
            updater.state.value = AppUpdateState(AppUpdatePhase.READY_TO_INSTALL)
            controller.confirmInstall()
            assertEquals(0, updater.installs)
            controller.requestInstall()
            assertTrue(controller.state.value.installConfirmation)
            controller.confirmInstall(); controller.confirmInstall()
            assertEquals(1, updater.installs)
            assertEquals(AppUpdatePhase.INSTALLER_OPENED, updater.state.value.phase)
        } finally { controller.close() }
        assertEquals(0, updater.closes)
    }

    private class CountingUpdater : NativeAppUpdater {
        override val supportsApkInstall = true
        override val state = MutableStateFlow(AppUpdateState(AppUpdatePhase.IDLE))
        var downloads = 0
        var installs = 0
        var closes = 0
        var spec: ApkUpdateSpec? = null
        override fun download(spec: ApkUpdateSpec) {
            this.spec = spec
            downloads++
            state.value = AppUpdateState(AppUpdatePhase.DOWNLOADING, totalBytes = spec.sizeBytes)
        }
        override fun cancel() { state.value = AppUpdateState(AppUpdatePhase.CANCELLED) }
        override fun install() { installs++; state.value = AppUpdateState(AppUpdatePhase.INSTALLER_OPENED) }
        override fun close() { closes++ }
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
