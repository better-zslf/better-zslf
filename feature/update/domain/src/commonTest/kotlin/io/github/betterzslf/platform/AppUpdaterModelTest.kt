package io.github.betterzslf.platform

import kotlin.test.*

class AppUpdaterModelTest {
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

}
