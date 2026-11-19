package io.github.betterzslf.platform

import io.github.betterzslf.feature.update.ProductUpdatePolicy

/** Product-owned platform adapter. Construction performs no IO, permission or installer work. */
expect fun createNativeAppUpdater(policy: ProductUpdatePolicy = ProductUpdatePolicy.Standard): NativeAppUpdater
