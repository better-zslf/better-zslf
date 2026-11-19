package io.github.betterzslf.platform

import android.content.Context
import io.github.betterzslf.feature.update.ProductUpdatePolicy
import kotlin.test.*

/** Throwing suppliers prove these branches never obtain Context or touch Android services. */
class AndroidUpdateOwnerTest {
    private val policy = ProductUpdatePolicy.Standard
    private val invalid = ApkUpdateSpec("1.2.3", "https://fixture.invalid/not-a-release", "a".repeat(64), 1)

    @Test
    fun constructionIdleInstallCancelAndCloseDoNotObtainContext() {
        var contextReads = 0
        val supplier: () -> Context = { contextReads++; error("No explicit valid download was requested") }
        val updater = newAndroidAppUpdater(policy, supplier)
        try {
            assertTrue(updater.supportsApkInstall)
            assertEquals(AppUpdatePhase.IDLE, updater.state.value.phase)
            updater.install() // IDLE has no transfer and no install capability.
            updater.cancel()
            updater.close(); updater.close()
            val closed = updater.state.value
            updater.install()
            updater.download(invalid)
            updater.cancel()
            assertEquals(closed, updater.state.value)
            assertEquals(0, contextReads)
        } finally { updater.close(); updater.close() }
    }

    @Test
    fun invalidSpecificationFailsBeforeInvokingContextSupplier() {
        var contextReads = 0
        val updater = newAndroidAppUpdater(policy) { contextReads++; error("Invalid spec must fail first") }
        try {
            updater.download(invalid)
            assertEquals(AppUpdatePhase.FAILED, updater.state.value.phase)
            assertEquals(0L, updater.state.value.downloadedBytes)
            updater.install()
            assertEquals(AppUpdatePhase.FAILED, updater.state.value.phase)
            assertEquals(0, contextReads)
        } finally { updater.close(); updater.close() }
        assertEquals(0, contextReads)
    }

    @Test
    fun factoryReturnsFreshOwnersAndFlowsWithoutAnInstalledGlobalContext() {
        var contextReads = 0
        val supplier: () -> Context = { contextReads++; error("Factory ownership test must remain offline") }
        val first = newAndroidAppUpdater(policy, supplier)
        val second = newAndroidAppUpdater(policy, supplier)
        try {
            assertNotSame(first, second)
            assertNotSame(first.state, second.state)
            first.download(invalid)
            assertEquals(AppUpdatePhase.FAILED, first.state.value.phase)
            assertEquals(AppUpdatePhase.IDLE, second.state.value.phase)
            first.close()
            assertEquals(AppUpdatePhase.IDLE, second.state.value.phase)
            val replacement = newAndroidAppUpdater(policy, supplier)
            try {
                assertNotSame(first, replacement)
                assertNotSame(second, replacement)
                assertEquals(AppUpdatePhase.IDLE, replacement.state.value.phase)
            } finally { replacement.close() }
        } finally { first.close(); second.close() }
        assertEquals(0, contextReads)
    }
}
