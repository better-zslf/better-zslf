package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
import kotlin.test.*

class UnavailableNativeUpdateOwnerTest {
    private val fixture = ApkUpdateSpec("1.2.3", "https://fixture.invalid/not-a-release", "a".repeat(64), 1)

    @Test
    fun unavailableFactoryReturnsIndependentOwnersAndFlows() {
        val first = newUnavailableAppUpdater()
        val second = newUnavailableAppUpdater()
        try {
            assertNotSame(first, second)
            assertNotSame(first.state, second.state)
            assertFalse(first.supportsApkInstall)
            assertFalse(second.supportsApkInstall)
            assertEquals(AppUpdatePhase.UNAVAILABLE, first.state.value.phase)
            assertEquals(AppText.Updates.PLATFORM_RELEASE_PAGE_ONLY_NO_ANDROID_PACKAGE_DOWNLOAD, first.state.value.message)
            val original = second.state.value
            first.close()
            assertEquals(original, second.state.value)
        } finally { first.close(); second.close() }
    }

    @Test
    fun operationsAndRepeatedCloseLeaveUnavailableStateUnchanged() {
        val updater = newUnavailableAppUpdater()
        val original = updater.state.value
        updater.download(fixture)
        updater.install()
        updater.cancel()
        updater.close(); updater.close()
        updater.download(fixture)
        updater.install()
        assertEquals(original, updater.state.value)
        assertEquals(0L, updater.state.value.downloadedBytes)
        assertNull(updater.state.value.totalBytes)
    }

    @Test
    fun closingAnOwnerDoesNotCloseFutureFactoryResults() {
        val previous = newUnavailableAppUpdater()
        previous.close(); previous.close()
        val fresh = newUnavailableAppUpdater()
        try {
            assertNotSame(previous, fresh)
            assertNotSame(previous.state, fresh.state)
            assertEquals(AppUpdatePhase.UNAVAILABLE, fresh.state.value.phase)
            assertFalse(fresh.supportsApkInstall)
        } finally { fresh.close() }
    }
}
