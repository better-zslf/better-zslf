package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.AppUpdatePhase

/** Display-only result: no install spec, download URL, hash, credentials or product policy. */
sealed interface AppUpdateResultUi {
    data object NotPublished : AppUpdateResultUi
    data class UpToDate(val latestVersionName: String, val installedAhead: Boolean = false) : AppUpdateResultUi
    data class Available(val versionName: String, val notes: String, val sizeBytes: Long) : AppUpdateResultUi
    data class Failed(val reason: ReleaseIssue) : AppUpdateResultUi
}

/** A snapshot, not an updater capability. Confirmation payloads remain private to the controller. */
data class AppUpdateUiState(
    val currentVersionName: String = "",
    val checking: Boolean = false,
    val result: AppUpdateResultUi? = null,
    val supportsApkInstall: Boolean = false,
    val nativePhase: AppUpdatePhase = AppUpdatePhase.IDLE,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val nativeMessage: String = "",
    val canCheck: Boolean = false,
    val canDownload: Boolean = false,
    val canInstall: Boolean = false,
    val canCancel: Boolean = false,
    val downloadConfirmation: Boolean = false,
    val installConfirmation: Boolean = false,
    val actionMessage: String = "",
) {
    /** Rendering cannot dispatch disabled intents or forge an install confirmation. */
    fun eventFor(event: AppUpdateEvent): AppUpdateEvent? = when (event) {
        AppUpdateEvent.Check -> event.takeIf { canCheck }
        AppUpdateEvent.CancelCheck -> event.takeIf { checking }
        AppUpdateEvent.RequestDownload -> event.takeIf { canDownload }
        AppUpdateEvent.ConfirmDownload -> event.takeIf { canDownload && downloadConfirmation }
        AppUpdateEvent.DismissDownload -> event.takeIf { downloadConfirmation }
        AppUpdateEvent.RequestInstall -> event.takeIf { canInstall }
        AppUpdateEvent.ConfirmInstall -> event.takeIf { canInstall && installConfirmation }
        AppUpdateEvent.DismissInstall -> event.takeIf { installConfirmation }
        AppUpdateEvent.CancelDownload -> event.takeIf { canCancel }
        AppUpdateEvent.OpenRelease, AppUpdateEvent.Back -> event
    }
}

/** Zero-payload user intents. In particular there is no URL, policy, APK or arbitrary action input. */
enum class AppUpdateEvent {
    Check, CancelCheck, RequestDownload, ConfirmDownload, DismissDownload,
    RequestInstall, ConfirmInstall, DismissInstall, CancelDownload, OpenRelease, Back,
}
