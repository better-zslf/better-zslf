package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Each factory call owns a fresh, inert lifecycle. iOS never downloads or installs an APK. */
actual fun createNativeAppUpdater(): NativeAppUpdater = object : NativeAppUpdater {
    private val mutableState = MutableStateFlow(AppUpdateState(
        phase = AppUpdatePhase.UNAVAILABLE,
        message = AppText.Updates.PLATFORM_RELEASE_PAGE_ONLY_NO_ANDROID_PACKAGE_DOWNLOAD,
    ))
    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    override val supportsApkInstall: Boolean = false
    override fun download(spec: ApkUpdateSpec) = Unit
    override fun cancel() = Unit
    override fun install() = Unit
    override fun close() = Unit
}
