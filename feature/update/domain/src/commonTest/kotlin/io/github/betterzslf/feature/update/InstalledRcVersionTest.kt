package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.ApkUpdateSpec
import kotlin.test.*

class InstalledRcVersionTest {
    private val suffix = ProductUpdatePolicy.Standard.versionNameSuffix
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
        // Installed numeric stable versions remain supported by the Standard default.
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
    fun acceptingAnInstalledRcDoesNotAdmitRcInstallSpecsOrAssetUrls() {
        val fixture = fixture("1.0.0-rc1$suffix")
        assertNotNull(StableVersion.parseInstalled(fixture.version))
        assertNull(StableVersion.parse(fixture.version))
        assertFalse(GithubReleasePolicy.isInstallSpec(fixture.candidate.apk))
        assertFalse(GithubReleasePolicy.isAssetUrl(fixture.apkUrl, "v${fixture.version}", "better-zslf-${fixture.version}.apk"))
    }

    private data class ReleaseFixture(val version: String, val apkUrl: String) {
        val candidate get() = GithubReleaseCandidate(version, "fixture notes", ApkUpdateSpec(version, apkUrl, "a".repeat(64), 1024))
    }
    private fun fixture(version: String = "1.0.0$suffix"): ReleaseFixture = ReleaseFixture(version,
        "${GithubReleasePolicy.ASSET_PREFIX}v$version/better-zslf-$version.apk")
}
