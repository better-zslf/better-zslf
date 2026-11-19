package io.github.betterzslf.platform

import io.github.betterzslf.feature.update.GithubReleasePolicy
import io.github.betterzslf.feature.update.ProductUpdatePolicy
import kotlin.test.*

/** No Android Context, DownloadManager, permission, package archive or installer is invoked. */
class AndroidProductUpdatePolicyTest {
    private val standard = ProductUpdatePolicy.Standard
    private val pro = ProductUpdatePolicy(
        repository = "better-zslf/pro-ci",
        apkAssetPrefix = "better-zslf-",
        androidApplicationId = "io.github.betterzslf.pro",
        versionNameSuffix = "-pro",
    )

    @Test
    fun androidPolicyUsesExactInjectedProductWithoutChangingGlobalDefaults() {
        val publicSpec = spec(standard)
        val privateSpec = spec(pro)
        assertTrue(AndroidUpdatePolicy.valid(publicSpec))
        assertTrue(AndroidUpdatePolicy.valid(publicSpec, standard))
        assertTrue(AndroidUpdatePolicy.valid(privateSpec, pro))
        assertFalse(AndroidUpdatePolicy.valid(privateSpec))
        assertFalse(AndroidUpdatePolicy.valid(privateSpec, standard))
        assertFalse(AndroidUpdatePolicy.valid(publicSpec, pro))
        assertEquals(GithubReleasePolicy.MAX_APK_BYTES, AndroidUpdatePolicy.MAX_BYTES)
        assertTrue(AndroidUpdatePolicy.valid(publicSpec)) // Constructing Pro did not switch the default.
    }

    @Test
    fun invalidCrossProductDownloadsFailBeforeAnyAndroidContextOrDownloadRequest() {
        for ((policy, wrongSpec) in listOf(standard to spec(pro), pro to spec(standard))) {
            val updater = newAndroidAppUpdater(policy) { error("Invalid spec must not access the application context") }
            try {
                assertTrue(updater.supportsApkInstall)
                assertEquals(AppUpdatePhase.IDLE, updater.state.value.phase)
                updater.download(wrongSpec) // Invalid-spec branch short-circuits before context.packageName.
                assertEquals(AppUpdatePhase.FAILED, updater.state.value.phase)
                assertEquals(0L, updater.state.value.downloadedBytes)
                updater.install() // FAILED has no install capability or pending transfer.
                assertEquals(AppUpdatePhase.FAILED, updater.state.value.phase)
            } finally { updater.close(); updater.close() }
        }
    }

    @Test
    fun everyPolicyKeepsStableOnlyShaSizeAndExactAssetUrlGates() {
        for (policy in listOf(standard, pro)) {
            val own = spec(policy)
            assertFalse(AndroidUpdatePolicy.valid(own.copy(versionName = "1.2.3-rc1${policy.versionNameSuffix}"), policy))
            assertFalse(AndroidUpdatePolicy.valid(own.copy(versionName = "v${own.versionName}"), policy))
            assertFalse(AndroidUpdatePolicy.valid(own.copy(sha256 = "a".repeat(63)), policy))
            assertFalse(AndroidUpdatePolicy.valid(own.copy(sizeBytes = 0), policy))
            assertFalse(AndroidUpdatePolicy.valid(own.copy(sizeBytes = AndroidUpdatePolicy.MAX_BYTES + 1), policy))
            for (url in listOf(own.downloadUrl + "?token=fixture", own.downloadUrl + "#fragment",
                own.downloadUrl.replace("github.com", "github.com.evil.invalid"),
                own.downloadUrl.replace("github.com", "fixture:secret@github.com"),
                own.downloadUrl.replace("https:", "http:"))) {
                assertFalse(AndroidUpdatePolicy.valid(own.copy(downloadUrl = url), policy))
            }
        }
    }

    private fun spec(policy: ProductUpdatePolicy): ApkUpdateSpec {
        val version = "1.2.3${policy.versionNameSuffix}"
        return ApkUpdateSpec(version, "${policy.assetUrlPrefix}v$version/${policy.apkAssetName(version)}", "a".repeat(64), 1024)
    }
}
