package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProductUpdatePolicyPresentationTest {
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
    fun controllersValidateTheirOwnPolicyAndKeepIndependentExplicitConfirmations() = runTest {
        val own = candidate(pro)
        val foreign = candidate(standard)
        val proUpdater = CountingUpdater()
        val standardUpdater = CountingUpdater()
        fun source(candidate: GithubReleaseCandidate) = object : GithubReleaseSource {
            override suspend fun check(currentVersionName: String) = GithubReleaseCheck.Available(candidate)
        }
        val proController = AppUpdateController("1.0.0-pro", source(own), proUpdater, this, pro)
        val wrongProController = AppUpdateController("1.0.0-pro", source(foreign), proUpdater, this, pro)
        val defaultController = AppUpdateController("1.0.0", source(own), standardUpdater, this)
        try {
            runCurrent(); assertEquals(0, proUpdater.downloads)
            proController.check(); wrongProController.check(); defaultController.check(); runCurrent()
            wrongProController.requestDownload(); wrongProController.confirmDownload()
            defaultController.requestDownload(); defaultController.confirmDownload()
            assertFalse(wrongProController.state.value.downloadConfirmation)
            assertFalse(defaultController.state.value.downloadConfirmation)
            assertEquals(releaseIssueMessage(ReleaseIssue.INVALID_ASSET), wrongProController.state.value.actionMessage)
            assertEquals(0, proUpdater.downloads); assertEquals(0, standardUpdater.downloads)
            proController.confirmDownload(); assertEquals(0, proUpdater.downloads)
            proController.requestDownload(); assertTrue(proController.state.value.downloadConfirmation)
            proController.confirmDownload(); proController.confirmDownload()
            assertEquals(1, proUpdater.downloads); assertEquals(own.apk, proUpdater.spec)
            assertEquals(0, proUpdater.installs)
            proUpdater.state.value = AppUpdateState(AppUpdatePhase.READY_TO_INSTALL)
            proController.confirmInstall(); assertEquals(0, proUpdater.installs)
            proController.requestInstall(); proController.confirmInstall(); proController.confirmInstall()
            assertEquals(1, proUpdater.installs)
        } finally { proController.close(); wrongProController.close(); defaultController.close() }
    }

    private class CountingUpdater : NativeAppUpdater {
        override val supportsApkInstall = true
        override val state = MutableStateFlow(AppUpdateState())
        var downloads = 0
        var installs = 0
        var spec: ApkUpdateSpec? = null
        override fun download(spec: ApkUpdateSpec) { this.spec = spec; downloads++; state.value = AppUpdateState(AppUpdatePhase.DOWNLOADING) }
        override fun cancel() { state.value = AppUpdateState(AppUpdatePhase.CANCELLED) }
        override fun install() { installs++; state.value = AppUpdateState(AppUpdatePhase.INSTALLER_OPENED) }
        override fun close() = Unit
    }

    private fun spec(policy: ProductUpdatePolicy, version: String = "1.2.3${policy.versionNameSuffix}") = ApkUpdateSpec(
        versionName = version,
        downloadUrl = "${policy.assetUrlPrefix}v$version/${policy.apkAssetName(version)}",
        sha256 = sha,
        sizeBytes = 1024,
    )

    private fun candidate(policy: ProductUpdatePolicy): GithubReleaseCandidate =
        GithubReleaseCandidate("1.2.3${policy.versionNameSuffix}", "fixture notes", spec(policy))
}
