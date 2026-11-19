package io.github.betterzslf.platform

import io.github.betterzslf.feature.update.ProductUpdatePolicy

/** Product-root adapter; the host target has no APK installation capability. */
actual fun createNativeAppUpdater(policy: ProductUpdatePolicy): NativeAppUpdater = newUnavailableAppUpdater()
