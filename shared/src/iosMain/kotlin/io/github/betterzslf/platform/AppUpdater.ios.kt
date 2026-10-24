package io.github.betterzslf.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Each factory call owns a fresh, inert lifecycle. iOS never downloads or installs an APK. */
actual fun createNativeAppUpdater(): NativeAppUpdater = object : NativeAppUpdater {
    private val mutableState = MutableStateFlow(AppUpdateState(
        phase = AppUpdatePhase.UNAVAILABLE,
        message = "此平台请在发布页面查看更新，不下载 Android 安装包。",
    ))
    override val state: StateFlow<AppUpdateState> = mutableState.asStateFlow()
    override val supportsApkInstall: Boolean = false
    override fun download(spec: ApkUpdateSpec) = Unit
    override fun cancel() = Unit
    override fun install() = Unit
    override fun close() = Unit
}
