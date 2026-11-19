package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.ApkUpdateSpec
import kotlin.test.*

class ProductUpdatePolicyTest {
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
    fun standardDefaultApiAndUrlsDoNotChangeWhenOtherProductsExist() {
        assertEquals("better-zslf/better-zslf", standard.repository)
        assertEquals("better-zslf-", standard.apkAssetPrefix)
        assertEquals("io.github.betterzslf", standard.androidApplicationId)
        assertEquals("", standard.versionNameSuffix)
        assertEquals(ProductUpdateChannel.STABLE, standard.channel)
        assertEquals(listOf(ProductUpdateChannel.STABLE), ProductUpdateChannel.entries.toList())
        assertEquals(GithubReleasePolicy.RELEASES_URL, standard.releasesUrl)
        assertEquals(GithubReleasePolicy.LATEST_API_URL, standard.latestApiUrl)
        assertEquals(GithubReleasePolicy.ASSET_PREFIX, standard.assetUrlPrefix)
        assertEquals("https://github.com/better-zslf/pro-ci/releases", pro.releasesUrl)
        assertEquals("https://api.github.com/repos/better-zslf/pro-ci/releases/latest", pro.latestApiUrl)
        assertEquals("better-zslf-1.2.3-pro.apk", pro.apkAssetName("1.2.3-pro"))
        assertNotNull(StableVersion.parse("v1.2.3"))
        assertNull(StableVersion.parse("v1.2.3-pro"))
        assertTrue(GithubReleasePolicy.isInstallSpec(spec(standard)))
        assertFalse(GithubReleasePolicy.isInstallSpec(spec(pro)))
        assertEquals("ProductUpdatePolicy(redacted)", pro.toString())
    }

    @Test
    fun compositionRejectsUrlsCredentialsTraversalAndMalformedIdentityOrNaming() {
        fun policy(
            repository: String = "owner/repository",
            prefix: String = "fixture-",
            appId: String = "io.fixture.app",
            suffix: String = "-fixture",
        ) = ProductUpdatePolicy(repository, prefix, appId, suffix)
        for (repository in listOf("", "https://github.com/owner/repo", "owner/repo?token=fixture",
            "user:secret@github.com/repo", "owner/repo#main", "owner/../repo", "owner/.", "owner/..",
            "owner/repo/", "owner\\repo", "owner/repo\n", "owner/repo%2fother")) {
            assertFailsWith<IllegalArgumentException>(repository) { policy(repository = repository) }
        }
        for (prefix in listOf("", "../fixture-", "/tmp/fixture-", "fixture/", "fixture\\", "fixture?", "fixture\n-")) {
            assertFailsWith<IllegalArgumentException>(prefix) { policy(prefix = prefix) }
        }
        for (appId in listOf("", "single", "io..fixture", "io.fixture/other", "io.fixture\n")) {
            assertFailsWith<IllegalArgumentException>(appId) { policy(appId = appId) }
        }
        for (suffix in listOf("pro", "-PRO", "-pro-pro", "-rc", "-rc1", "-alpha", "-beta2", "-pro+meta", "-pro/")) {
            assertFailsWith<IllegalArgumentException>(suffix) { policy(suffix = suffix) }
        }
    }

    @Test
    fun productSuffixIsExactForRemoteButNumericIosCompatibilityIsInstalledOnly() {
        for (policy in listOf(standard, pro, other)) {
            val version = "1.2.3${policy.versionNameSuffix}"
            assertEquals(version, assertNotNull(StableVersion.parse("v$version", policy)).versionName)
            assertEquals(version, assertNotNull(StableVersion.parseInstalled(version, policy)).versionName)
            assertEquals("1.2.3", assertNotNull(StableVersion.parseInstalled("1.2.3", policy)).versionName)
            for (bad in listOf("1.2.3${policy.versionNameSuffix}-beta", "1.2.3${policy.versionNameSuffix}+build",
                "1.2.3${policy.versionNameSuffix}${policy.versionNameSuffix}-extra", "01.2.3${policy.versionNameSuffix}")) {
                assertNull(StableVersion.parse(bad, policy), bad)
            }
        }
        assertNull(StableVersion.parse("1.2.3", pro))
        assertNull(StableVersion.parse("1.2.3-other", pro))
        assertNull(StableVersion.parse("1.2.3-pro", other))
        assertNull(StableVersion.parseInstalled("1.2.3-pro", standard))
        assertNull(StableVersion.parseInstalled("1.2.3-other", pro))
    }

    @Test
    fun installedRcOrderingAndRemoteRcRejectionAreIndependentForEveryPolicy() {
        for (policy in listOf(standard, pro, other)) {
            val suffix = policy.versionNameSuffix
            val stable = assertNotNull(StableVersion.parse("1.2.3$suffix", policy))
            fun rc(number: String) = assertNotNull(StableVersion.parseInstalled("1.2.3-rc$number$suffix", policy))
            assertTrue(stable > rc("999999"))
            assertTrue(rc("10") > rc("2"))
            assertTrue(assertNotNull(StableVersion.parseInstalled("1.2.4-rc1$suffix", policy)) > stable)
            val high = "9".repeat(30)
            assertTrue(assertNotNull(StableVersion.parseInstalled("$high.0.0-rc1$suffix", policy)) > stable)
            assertEquals("1.2.3-rc1$suffix", rc("1").versionName)
            for (bad in listOf("1.2.3-rc0$suffix", "1.2.3-rc01$suffix", "1.2.3-rc1000000$suffix",
                "1.2.3-RC1$suffix", "1.2.3-rc1$suffix+build")) {
                assertNull(StableVersion.parseInstalled(bad, policy), bad)
            }
            val rcSpec = spec(policy, "1.2.3-rc1$suffix")
            assertNull(StableVersion.parse(rcSpec.versionName, policy))
            assertFalse(GithubReleasePolicy.isInstallSpec(rcSpec, policy))
            assertFalse(GithubReleasePolicy.isAssetUrl(rcSpec.downloadUrl, "v${rcSpec.versionName}", policy.apkAssetName(rcSpec.versionName), policy))
        }
        assertNull(StableVersion.parseInstalled("1.2.3-rc1", pro))
        assertNull(StableVersion.parseInstalled("1.2.3-pro-rc1", pro))
        assertNull(StableVersion.parseInstalled("1.2.3-rc1-pro-pro", pro))
        assertNull(StableVersion.parseInstalled("1.2.3-rc1-pro", standard))
    }

    @Test
    fun installSpecPinsRepositoryAssetNamingAndSuffixInsteadOfOnlyHttps() {
        for (policy in listOf(standard, pro, other)) {
            val own = spec(policy)
            assertTrue(GithubReleasePolicy.isInstallSpec(own, policy))
            val filename = policy.apkAssetName(own.versionName)
            assertTrue(GithubReleasePolicy.isAssetUrl(own.downloadUrl, "v${own.versionName}", filename, policy))
            for (foreign in listOf(standard, pro, other).filter { it !== policy }) {
                assertFalse(GithubReleasePolicy.isInstallSpec(spec(foreign), policy))
                assertFalse(GithubReleasePolicy.isInstallSpec(own.copy(downloadUrl = "${foreign.assetUrlPrefix}v${own.versionName}/$filename"), policy))
            }
            val wrongName = "wrong-product-${own.versionName}.apk"
            assertFalse(GithubReleasePolicy.isAssetUrl("${policy.assetUrlPrefix}v${own.versionName}/$wrongName", "v${own.versionName}", wrongName, policy))
            for (url in listOf(own.downloadUrl + "?token=fixture", own.downloadUrl + "#fragment",
                own.downloadUrl.replace("https:", "http:"), own.downloadUrl.replace("github.com", "github.com.evil.invalid"),
                own.downloadUrl.replace("github.com", "user:secret@github.com"), own.downloadUrl.replace("github.com", "github.com:443"),
                own.downloadUrl.replace("/v${own.versionName}/", "/v${own.versionName}/../"),
                own.downloadUrl.replace("/v${own.versionName}/", "/v${own.versionName}/%2e%2e/"))) {
                assertFalse(GithubReleasePolicy.isInstallSpec(own.copy(downloadUrl = url), policy), url)
            }
            assertFalse(GithubReleasePolicy.isInstallSpec(own.copy(versionName = "v${own.versionName}"), policy))
            assertFalse(GithubReleasePolicy.isInstallSpec(own.copy(sizeBytes = 0), policy))
            assertFalse(GithubReleasePolicy.isInstallSpec(own.copy(sizeBytes = GithubReleasePolicy.MAX_APK_BYTES + 1), policy))
            assertFalse(GithubReleasePolicy.isInstallSpec(own.copy(sha256 = "z".repeat(64)), policy))
        }
        // Same repo and suffix still do not authorize a different APK filename convention.
        val alternateNaming = ProductUpdatePolicy(pro.repository, "private-product-", pro.androidApplicationId, pro.versionNameSuffix)
        assertFalse(GithubReleasePolicy.isInstallSpec(spec(pro), alternateNaming))
        assertTrue(GithubReleasePolicy.isInstallSpec(spec(alternateNaming), alternateNaming))
        assertFalse(GithubReleasePolicy.isInstallSpec(spec(alternateNaming), pro))
    }

    private fun spec(policy: ProductUpdatePolicy, version: String = "1.2.3${policy.versionNameSuffix}") = ApkUpdateSpec(
        versionName = version,
        downloadUrl = "${policy.assetUrlPrefix}v$version/${policy.apkAssetName(version)}",
        sha256 = sha,
        sizeBytes = 1024,
    )

}
