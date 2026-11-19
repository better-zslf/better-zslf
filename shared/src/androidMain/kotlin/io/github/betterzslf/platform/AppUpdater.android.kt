package io.github.betterzslf.platform

import io.github.betterzslf.feature.update.ProductUpdatePolicy

/** Composition-root adapter; the implementation never depends on the shared Android host. */
actual fun createNativeAppUpdater(policy: ProductUpdatePolicy): NativeAppUpdater =
    newAndroidAppUpdater(policy) { AndroidPlatform.context }
