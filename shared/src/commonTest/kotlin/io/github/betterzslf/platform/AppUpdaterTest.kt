package io.github.betterzslf.platform

import kotlin.test.*

/** Pure lifecycle checks; no valid Android download or installation is invoked by these tests. */
class AppUpdaterTest {
    private val fixture = ApkUpdateSpec("1.2.3", "https://fixture.invalid/not-a-release", "a".repeat(64), 1)

    @Test fun specDiagnosticsDoNotExposeUrlChecksumOrVersion() {
        assertEquals("ApkUpdateSpec(redacted)", fixture.toString())
        assertFalse(fixture.toString().contains(fixture.downloadUrl))
        assertFalse(fixture.toString().contains(fixture.sha256))
        assertFalse(fixture.toString().contains(fixture.versionName))
    }

    @Test fun defaultStateIsIdleNotDownloadedOrInstalled() {
        val state = AppUpdateState()
        assertEquals(AppUpdatePhase.IDLE, state.phase)
        assertEquals(0L, state.downloadedBytes)
        assertNull(state.totalBytes)
        assertEquals("", state.message)
        assertFalse(AppUpdatePhase.entries.any { it.name == "INSTALLED" || it.name == "INSTALL_SUCCESS" })
    }

    @Test fun factoriesReturnIndependentOwnersAndStateFlows() {
        val first = createNativeAppUpdater()
        val second = createNativeAppUpdater()
        try {
            assertNotSame(first, second)
            assertNotSame(first.state, second.state)
            val original = second.state.value
            first.close()
            assertEquals(original, second.state.value)
        } finally { first.close(); second.close() }
    }

    @Test fun closingTwiceDoesNotPermanentlyCloseTheFactory() {
        val old = createNativeAppUpdater()
        old.close(); old.close()
        val fresh = createNativeAppUpdater()
        try {
            assertNotSame(old, fresh)
            assertEquals(if (fresh.supportsApkInstall) AppUpdatePhase.IDLE else AppUpdatePhase.UNAVAILABLE, fresh.state.value.phase)
            old.install() // Closed owner: no context, permission or installer action.
        } finally { fresh.close() }
    }

    @Test fun unsupportedOwnersRemainUnavailableForEveryOperation() {
        val updater = createNativeAppUpdater()
        try {
            if (updater.supportsApkInstall) return // Android native APIs are intentionally not exercised here.
            val original = updater.state.value
            updater.download(fixture)
            updater.install()
            updater.cancel()
            updater.close(); updater.close()
            assertEquals(original, updater.state.value)
            assertEquals(AppUpdatePhase.UNAVAILABLE, updater.state.value.phase)
            assertEquals(0L, updater.state.value.downloadedBytes)
        } finally { updater.close() }
    }
}
