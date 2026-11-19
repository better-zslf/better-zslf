package io.github.betterzslf.platform

import io.github.betterzslf.feature.update.ProductUpdatePolicy

/** Product-root adapter; iOS never downloads or installs an APK. */
actual fun createNativeAppUpdater(policy: ProductUpdatePolicy): NativeAppUpdater = newUnavailableAppUpdater()
