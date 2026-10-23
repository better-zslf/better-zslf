package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateControllerTest {
    private val candidate = GithubReleaseCandidate("1.1.0", "Plain notes", ApkUpdateSpec("1.1.0",
        GithubReleasePolicy.ASSET_PREFIX + "v1.1.0/better-zslf-1.1.0.apk", "a".repeat(64), 1024))
    private class FakeUpdater(override val supportsApkInstall: Boolean = true) : NativeAppUpdater {
        override val state = MutableStateFlow(AppUpdateState(if (supportsApkInstall) AppUpdatePhase.IDLE else AppUpdatePhase.UNAVAILABLE))
        var downloads = 0; var installs = 0; var cancels = 0; var closes = 0
        override fun download(spec: ApkUpdateSpec) { downloads++; state.value = AppUpdateState(AppUpdatePhase.DOWNLOADING, totalBytes = spec.sizeBytes) }
        override fun cancel() { cancels++; state.value = AppUpdateState(AppUpdatePhase.CANCELLED) }
        override fun install() { installs++; if (state.value.phase != AppUpdatePhase.INSTALL_PERMISSION_REQUIRED) state.value = AppUpdateState(AppUpdatePhase.INSTALLER_OPENED) }
        override fun close() { closes++ }
    }
    private fun available() = object : GithubReleaseSource {
        override suspend fun check(currentVersionName: String) = GithubReleaseCheck.Available(candidate)
    }

    @Test
    fun creationDoesNoCheckAndDoubleCheckIsCoalescedUntilExplicitCancel() = runTest {
        var requests = 0
        val gate = CompletableDeferred<Unit>()
        val source = object : GithubReleaseSource {
            override suspend fun check(currentVersionName: String): GithubReleaseCheck {
                requests++; gate.await(); return GithubReleaseCheck.Available(candidate)
            }
        }
        val updater = FakeUpdater()
        val controller = AppUpdateController("1.0.0", source, updater, this)
        assertEquals(0, requests); assertNull(controller.state.value.result)
        controller.check(); controller.check(); runCurrent()
        assertEquals(1, requests); assertTrue(controller.state.value.checking)
        controller.cancelCheck(); runCurrent()
        assertFalse(controller.state.value.checking)
        assertEquals(ReleaseIssue.CANCELLED, assertIs<GithubReleaseCheck.Failed>(controller.state.value.result).reason)
        gate.complete(Unit); runCurrent()
        assertIs<GithubReleaseCheck.Failed>(controller.state.value.result)
        assertEquals(0, updater.downloads); assertEquals(0, updater.installs)
        controller.close()
    }

    @Test
    fun lateCancelledCheckNeverReplacesNewerExplicitResult() = runTest {
        val late = CompletableDeferred<Unit>()
        var requests = 0
        val source = object : GithubReleaseSource {
            override suspend fun check(currentVersionName: String): GithubReleaseCheck {
                requests++
                if (requests == 1) return withContext(NonCancellable) { late.await(); GithubReleaseCheck.Available(candidate) }
                return GithubReleaseCheck.NotPublished
            }
        }
        val controller = AppUpdateController("1.0.0", source, FakeUpdater(), this)
        controller.check(); runCurrent(); controller.cancelCheck()
        controller.check(); runCurrent()
        assertEquals(GithubReleaseCheck.NotPublished, controller.state.value.result)
        late.complete(Unit); runCurrent()
        assertEquals(GithubReleaseCheck.NotPublished, controller.state.value.result)
        assertFalse(controller.state.value.checking)
        controller.close()
    }

    @Test
    fun downloadAndInstallNeedSeparateConfirmationAndDoubleConfirmationIsSingleShot() = runTest {
        val updater = FakeUpdater()
        val controller = AppUpdateController("1.0.0", available(), updater, this)
        controller.check(); runCurrent()
        assertTrue(controller.canDownload())
        controller.confirmDownload(); assertEquals(0, updater.downloads)
        controller.requestDownload(); controller.requestDownload()
        assertNotNull(controller.state.value.downloadConfirmation)
        assertEquals(0, updater.downloads)
        controller.confirmDownload(); controller.confirmDownload()
        assertEquals(1, updater.downloads); assertFalse(controller.canDownload())
        assertEquals(AppUpdatePhase.DOWNLOADING, updater.state.value.phase)
        updater.state.value = AppUpdateState(AppUpdatePhase.READY_TO_INSTALL)
        controller.confirmInstall(); assertEquals(0, updater.installs)
        controller.requestInstall(); assertTrue(controller.state.value.installConfirmation)
        controller.confirmInstall(); controller.confirmInstall()
        assertEquals(1, updater.installs)
        assertEquals(AppUpdatePhase.INSTALLER_OPENED, updater.state.value.phase)
        assertTrue(appUpdatePhaseMessage(updater.state.value.phase).contains("尚未确认安装"))
        controller.close(); assertEquals(0, updater.closes); assertEquals(0, updater.cancels)
    }

    @Test
    fun permissionSettingsAndReturnRequireAnotherExplicitInstallConfirmation() = runTest {
        val updater = FakeUpdater()
        updater.state.value = AppUpdateState(AppUpdatePhase.INSTALL_PERMISSION_REQUIRED)
        val controller = AppUpdateController("1.0.0", available(), updater, this)
        controller.requestInstall(); controller.confirmInstall(); runCurrent()
        assertEquals(1, updater.installs)
        updater.state.value = AppUpdateState(AppUpdatePhase.READY_TO_INSTALL)
        runCurrent(); assertEquals(1, updater.installs) // No resume or state collector installs.
        controller.confirmInstall(); assertEquals(1, updater.installs)
        controller.requestInstall(); controller.confirmInstall(); assertEquals(2, updater.installs)
        controller.close()
    }

    @Test
    fun cancelledDownloadDoesNotAutoRetryAndPageCloseDoesNotCloseAppOwners() = runTest {
        val updater = FakeUpdater()
        val controller = AppUpdateController("1.0.0", available(), updater, this)
        controller.check(); runCurrent(); controller.requestDownload(); controller.confirmDownload()
        controller.cancelDownload(); runCurrent()
        assertEquals(1, updater.downloads); assertEquals(1, updater.cancels)
        assertEquals(AppUpdatePhase.CANCELLED, updater.state.value.phase)
        controller.requestDownload(); controller.dismissDownload(); controller.confirmDownload()
        assertEquals(1, updater.downloads)
        controller.requestDownload(); controller.confirmDownload(); assertEquals(2, updater.downloads)
        controller.close(); controller.close()
        assertEquals(0, updater.closes); assertEquals(1, updater.cancels)
        assertEquals(AppUpdatePhase.DOWNLOADING, updater.state.value.phase)
        controller.check(); controller.requestInstall(); controller.confirmInstall(); controller.cancelDownload()
        assertEquals(2, updater.downloads); assertEquals(0, updater.installs); assertEquals(1, updater.cancels)
    }

    @Test
    fun unsupportedPlatformNeverDownloadsCancelsOrInstallsAnApk() = runTest {
        val updater = FakeUpdater(false)
        val controller = AppUpdateController("1.0.0", available(), updater, this)
        controller.check(); runCurrent()
        assertIs<GithubReleaseCheck.Available>(controller.state.value.result)
        controller.requestDownload(); controller.confirmDownload(); controller.requestInstall(); controller.confirmInstall(); controller.cancelDownload()
        assertFalse(controller.canDownload()); assertFalse(controller.canInstall())
        assertEquals(0, updater.downloads); assertEquals(0, updater.installs); assertEquals(0, updater.cancels)
        val repository = if (io.github.betterzslf.edition.BuildEdition.isPro) "better-zslf/pro-ci" else "better-zslf/better-zslf"
        assertEquals("https://github.com/$repository/releases", GithubReleasePolicy.RELEASES_URL)
        controller.close(); assertEquals(0, updater.closes)
    }

    @Test
    fun resumedPageWithVerifiedNativeFileDoesNotMixItWithANewReleaseCheck() = runTest {
        var checks = 0
        val updater = FakeUpdater()
        updater.state.value = AppUpdateState(AppUpdatePhase.READY_TO_INSTALL)
        val source = object : GithubReleaseSource {
            override suspend fun check(currentVersionName: String): GithubReleaseCheck { checks++; return GithubReleaseCheck.NotPublished }
        }
        val controller = AppUpdateController("1.0.0", source, updater, this)
        assertFalse(controller.canCheck()); assertTrue(controller.canInstall())
        controller.check(); runCurrent(); assertEquals(0, checks)
        controller.cancelDownload(); assertEquals(1, updater.cancels)
        assertTrue(controller.canCheck()); controller.check(); runCurrent()
        assertEquals(1, checks); assertEquals(GithubReleaseCheck.NotPublished, controller.state.value.result)
        controller.close(); assertEquals(0, updater.closes)
    }

    @Test
    fun sourceExceptionsAreOpaqueAndDismissedOrInvalidCandidatesNeverDownload() = runTest {
        val updater = FakeUpdater()
        val source = object : GithubReleaseSource { override suspend fun check(currentVersionName: String): GithubReleaseCheck = error("fixture-secret https://evil.invalid") }
        val controller = AppUpdateController("1.0.0", source, updater, this)
        controller.check(); runCurrent()
        val error = assertIs<GithubReleaseCheck.Failed>(controller.state.value.result)
        assertEquals(ReleaseIssue.NETWORK, error.reason); assertFalse(controller.state.value.toString().contains("fixture-secret"))
        controller.close()
        val invalidSource = object : GithubReleaseSource {
            override suspend fun check(currentVersionName: String) = GithubReleaseCheck.Available(candidate.copy(apk = candidate.apk.copy(downloadUrl = "https://evil.invalid/file.apk")))
        }
        val invalid = AppUpdateController("1.0.0", invalidSource, updater, this)
        invalid.check(); runCurrent(); invalid.requestDownload(); invalid.confirmDownload()
        assertNull(invalid.state.value.downloadConfirmation)
        assertEquals(0, updater.downloads)
        assertEquals(releaseIssueMessage(ReleaseIssue.INVALID_ASSET), invalid.state.value.actionMessage)
        invalid.close()
    }
}
