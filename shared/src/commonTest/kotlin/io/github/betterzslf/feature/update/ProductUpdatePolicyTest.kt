package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.*
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
    fun nativeFactoriesAcceptExplicitPoliciesWithoutSharedOwnersOrConstructionEffects() {
        val first = createNativeAppUpdater(pro)
        val second = createNativeAppUpdater(standard)
        try {
            assertNotSame(first, second)
            assertNotSame(first.state, second.state)
            assertEquals(if (first.supportsApkInstall) AppUpdatePhase.IDLE else AppUpdatePhase.UNAVAILABLE, first.state.value.phase)
            val original = second.state.value
            first.close()
            assertEquals(original, second.state.value)
            // On unsupported platforms, even a valid other-product spec remains a local no-op.
            if (!second.supportsApkInstall) {
                second.download(spec(pro)); second.install(); second.cancel()
                assertEquals(original, second.state.value)
            }
        } finally { first.close(); second.close() }
    }

    private fun spec(policy: ProductUpdatePolicy, version: String = "1.2.3${policy.versionNameSuffix}") = ApkUpdateSpec(
        versionName = version,
        downloadUrl = "${policy.assetUrlPrefix}v$version/${policy.apkAssetName(version)}",
        sha256 = sha,
        sizeBytes = 1024,
    )

}
