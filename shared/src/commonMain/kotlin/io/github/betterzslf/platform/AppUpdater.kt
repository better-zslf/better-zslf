package io.github.betterzslf.platform

import kotlinx.coroutines.flow.StateFlow

/** A public release APK only. Never contains school credentials or an arbitrary output path. */
data class ApkUpdateSpec(
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    override fun toString(): String = "ApkUpdateSpec(redacted)"
}

enum class AppUpdatePhase {
    IDLE, DOWNLOADING, VERIFYING, READY_TO_INSTALL, INSTALL_PERMISSION_REQUIRED,
    INSTALLER_OPENED, CANCELLED, FAILED, UNAVAILABLE,
}

/** INSTALLER_OPENED means only that the system confirmation UI was opened, never installed. */
data class AppUpdateState(
    val phase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String = "",
)

/** Application-owned. No download, permission request, or installer action during construction. */
interface NativeAppUpdater {
    val state: StateFlow<AppUpdateState>
    val supportsApkInstall: Boolean
    fun download(spec: ApkUpdateSpec)
    fun cancel()
    fun install()
    fun close()
}

expect fun createNativeAppUpdater(): NativeAppUpdater
