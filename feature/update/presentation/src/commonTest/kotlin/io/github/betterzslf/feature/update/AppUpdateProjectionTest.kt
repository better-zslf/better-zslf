package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.ApkUpdateSpec
import io.github.betterzslf.platform.AppUpdatePhase
import io.github.betterzslf.platform.AppUpdateState
import io.github.betterzslf.platform.NativeAppUpdater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateProjectionTest {
    private val policy = ProductUpdatePolicy.Standard
    private val candidate = GithubReleaseCandidate("1.2.3", "Plain release notes", ApkUpdateSpec("1.2.3",
        "${policy.assetUrlPrefix}v1.2.3/better-zslf-1.2.3.apk", "a".repeat(64), 1024))

    private class Updater : NativeAppUpdater {
        override val supportsApkInstall = true
        override val state = MutableStateFlow(AppUpdateState())
        var downloads = 0
        var installs = 0
        var closes = 0
        var downloaded: ApkUpdateSpec? = null
        override fun download(spec: ApkUpdateSpec) { downloads++; downloaded = spec; state.value = AppUpdateState(AppUpdatePhase.DOWNLOADING) }
        override fun cancel() { state.value = AppUpdateState(AppUpdatePhase.CANCELLED) }
        override fun install() { installs++; state.value = AppUpdateState(AppUpdatePhase.INSTALLER_OPENED) }
        override fun close() { closes++ }
    }

    @Test
    fun stateContainsOnlyDisplayProjectionAndKeepsConfirmationSpecInsideController() = runTest {
        val updater = Updater()
        val source = object : GithubReleaseSource {
            override suspend fun check(currentVersionName: String) = GithubReleaseCheck.Available(candidate)
        }
        val controller = AppUpdateController("1.0.0-rc1", source, updater, this, policy)
        try {
            assertEquals("1.0.0-rc1", controller.state.value.currentVersionName)
            assertNull(controller.state.value.result)
            controller.check(); runCurrent()
            assertEquals(AppUpdateResultUi.Available("1.2.3", "Plain release notes", 1024), controller.state.value.result)
            controller.requestDownload()
            assertTrue(controller.state.value.downloadConfirmation)
            val snapshot = controller.state.value.toString()
            assertFalse(snapshot.contains(candidate.apk.downloadUrl))
            assertFalse(snapshot.contains(candidate.apk.sha256))
            assertFalse(snapshot.contains("ApkUpdateSpec"))
            assertFalse(snapshot.contains("ProductUpdatePolicy"))
            controller.confirmDownload()
            assertEquals(candidate.apk, updater.downloaded)
            assertEquals(1, updater.downloads)
        } finally { controller.close() }
    }

    @Test
    fun forgedOrStaleDisplaySnapshotCannotAuthorizeAControllerConfirmation() = runTest {
        val updater = Updater()
        val source = object : GithubReleaseSource {
            override suspend fun check(currentVersionName: String) = GithubReleaseCheck.Available(candidate)
        }
        val controller = AppUpdateController("1.0.0", source, updater, this, policy)
        try {
            controller.check(); runCurrent()
            val forged = controller.state.value.copy(downloadConfirmation = true, installConfirmation = true, canInstall = true)
            assertNotNull(forged.eventFor(AppUpdateEvent.ConfirmDownload))
            controller.onEvent(AppUpdateEvent.ConfirmDownload)
            controller.onEvent(AppUpdateEvent.ConfirmInstall)
            assertEquals(0, updater.downloads); assertEquals(0, updater.installs)
            controller.requestDownload()
            val stale = controller.state.value
            updater.state.value = AppUpdateState(AppUpdatePhase.VERIFYING)
            assertNotNull(stale.eventFor(AppUpdateEvent.ConfirmDownload))
            controller.onEvent(AppUpdateEvent.ConfirmDownload) // Rechecks the live native port, not the stale snapshot.
            assertEquals(0, updater.downloads)
            updater.state.value = AppUpdateState(AppUpdatePhase.IDLE)
            controller.onEvent(AppUpdateEvent.ConfirmDownload) // Previous invalid confirmation was consumed.
            assertEquals(0, updater.downloads)
        } finally { controller.close() }
    }

    @Test
    fun nativeProjectionUpdatesOnlyDisplayAndCloseStopsObserverWithoutClosingNativeOwner() = runTest {
        val updater = Updater()
        var checks = 0
        val source = object : GithubReleaseSource {
            override suspend fun check(currentVersionName: String): GithubReleaseCheck { checks++; return GithubReleaseCheck.NotPublished }
        }
        val controller = AppUpdateController("1.0.0", source, updater, this)
        updater.state.value = AppUpdateState(AppUpdatePhase.DOWNLOADING, 7, 100, "fixed status")
        runCurrent()
        assertEquals(AppUpdatePhase.DOWNLOADING, controller.state.value.nativePhase)
        assertEquals(7L, controller.state.value.downloadedBytes)
        assertEquals(100L, controller.state.value.totalBytes)
        assertTrue(controller.state.value.canCancel)
        assertFalse(controller.state.value.canCheck)
        updater.state.value = AppUpdateState(AppUpdatePhase.READY_TO_INSTALL)
        runCurrent()
        assertTrue(controller.state.value.canInstall)
        assertEquals(0, updater.installs); assertEquals(0, checks)
        controller.close()
        val closed = controller.state.value
        assertFalse(closed.canCheck); assertFalse(closed.canInstall); assertFalse(closed.canCancel)
        updater.state.value = AppUpdateState(AppUpdatePhase.INSTALLER_OPENED)
        runCurrent()
        assertEquals(closed, controller.state.value)
        assertEquals(0, updater.closes)
    }

    @Test
    fun secondExplicitCheckInvalidatesPriorPendingSpecAndOnlyTheNewCandidateMayDownload() = runTest {
        val updater = Updater()
        val second = candidate.copy(versionName = "1.2.4", apk = candidate.apk.copy(versionName = "1.2.4",
            downloadUrl = "${policy.assetUrlPrefix}v1.2.4/better-zslf-1.2.4.apk"))
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val source = object : GithubReleaseSource {
            override suspend fun check(currentVersionName: String): GithubReleaseCheck {
                calls++
                if (calls == 1) return GithubReleaseCheck.Available(candidate)
                gate.await(); return GithubReleaseCheck.Available(second)
            }
        }
        val controller = AppUpdateController("1.0.0", source, updater, this)
        try {
            controller.check(); runCurrent(); controller.requestDownload()
            assertTrue(controller.state.value.downloadConfirmation)
            controller.check(); runCurrent()
            assertFalse(controller.state.value.downloadConfirmation)
            controller.confirmDownload(); assertEquals(0, updater.downloads)
            gate.complete(Unit); runCurrent()
            assertEquals("1.2.4", assertIs<AppUpdateResultUi.Available>(controller.state.value.result).versionName)
            controller.confirmDownload(); assertEquals(0, updater.downloads)
            controller.requestDownload(); controller.confirmDownload()
            assertEquals(second.apk, updater.downloaded)
        } finally { controller.close() }
    }
}
