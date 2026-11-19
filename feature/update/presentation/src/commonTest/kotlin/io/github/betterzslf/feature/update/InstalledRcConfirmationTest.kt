package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class InstalledRcConfirmationTest {
    private val suffix = ProductUpdatePolicy.Standard.versionNameSuffix
    private fun installed(value: String) = assertNotNull(StableVersion.parseInstalled("$value$suffix"))
    private fun stable(value: String) = assertNotNull(StableVersion.parse("$value$suffix"))

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
            assertTrue(controller.state.value.downloadConfirmation)
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

    private data class ReleaseFixture(val version: String, val apkUrl: String) {
        val candidate get() = GithubReleaseCandidate(version, "fixture notes", ApkUpdateSpec(version, apkUrl, "a".repeat(64), 1024))
    }
    private fun fixture(version: String = "1.0.0$suffix"): ReleaseFixture = ReleaseFixture(version,
        "${GithubReleasePolicy.ASSET_PREFIX}v$version/better-zslf-$version.apk")
}
