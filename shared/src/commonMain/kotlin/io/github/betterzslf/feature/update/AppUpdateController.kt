package io.github.betterzslf.feature.update

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.platform.ApkUpdateSpec
import io.github.betterzslf.platform.AppUpdatePhase
import io.github.betterzslf.platform.NativeAppUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AppUpdateUiState(
    val checking: Boolean = false,
    val result: GithubReleaseCheck? = null,
    val downloadConfirmation: ApkUpdateSpec? = null,
    val installConfirmation: Boolean = false,
    val actionMessage: String = "",
)

/** Page-owned only: no init/resume work and no ownership of the repository/native service. */
class AppUpdateController(
    private val currentVersionName: String,
    private val repository: GithubReleaseSource,
    private val updater: NativeAppUpdater,
    private val scope: CoroutineScope,
) {
    private val mutable = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = mutable.asStateFlow()
    private var checkJob: Job? = null
    private var generation = 0L
    private var closed = false

    fun canCheck(): Boolean = !closed && !mutable.value.checking && updater.state.value.phase !in busyPhases + installPhases

    fun check() {
        if (!canCheck()) return
        val ticket = ++generation
        mutable.value = AppUpdateUiState(checking = true)
        checkJob = scope.launch {
            try {
                val result = repository.check(currentVersionName)
                if (!closed && ticket == generation) mutable.value = AppUpdateUiState(result = result)
            } catch (cancelled: CancellationException) {
                if (!closed && ticket == generation) mutable.value = AppUpdateUiState(result = GithubReleaseCheck.Failed(ReleaseIssue.CANCELLED))
                throw cancelled
            } catch (_: Exception) {
                if (!closed && ticket == generation) mutable.value = AppUpdateUiState(result = GithubReleaseCheck.Failed(ReleaseIssue.NETWORK))
            } finally { if (ticket == generation) checkJob = null }
        }
    }

    fun cancelCheck() {
        if (closed || !mutable.value.checking) return
        ++generation
        checkJob?.cancel(); checkJob = null
        mutable.value = AppUpdateUiState(result = GithubReleaseCheck.Failed(ReleaseIssue.CANCELLED))
    }

    fun canDownload(): Boolean = !closed && !mutable.value.checking && updater.supportsApkInstall &&
        mutable.value.result is GithubReleaseCheck.Available && updater.state.value.phase in downloadPhases

    fun requestDownload() {
        if (!canDownload()) return
        val candidate = (mutable.value.result as GithubReleaseCheck.Available).candidate
        if (!GithubReleasePolicy.isInstallSpec(candidate.apk)) {
            mutable.value = mutable.value.copy(actionMessage = releaseIssueMessage(ReleaseIssue.INVALID_ASSET)); return
        }
        mutable.value = mutable.value.copy(downloadConfirmation = candidate.apk, actionMessage = "")
    }
    fun dismissDownload() { mutable.value = mutable.value.copy(downloadConfirmation = null) }
    fun confirmDownload() {
        if (closed) return
        val spec = mutable.value.downloadConfirmation ?: return
        mutable.value = mutable.value.copy(downloadConfirmation = null)
        if (!canDownload() || !GithubReleasePolicy.isInstallSpec(spec) ||
            (mutable.value.result as? GithubReleaseCheck.Available)?.candidate?.apk != spec) return
        try { updater.download(spec) }
        catch (_: Exception) { mutable.value = mutable.value.copy(actionMessage = AppText.Updates.DOWNLOAD_NOT_STARTED_RECONFIRM_LATER) }
    }

    fun canInstall(): Boolean = !closed && updater.supportsApkInstall && updater.state.value.phase in installPhases
    fun requestInstall() {
        if (canInstall()) mutable.value = mutable.value.copy(installConfirmation = true, actionMessage = "")
    }
    fun dismissInstall() { mutable.value = mutable.value.copy(installConfirmation = false) }
    fun confirmInstall() {
        if (closed || !mutable.value.installConfirmation) return
        mutable.value = mutable.value.copy(installConfirmation = false)
        if (!canInstall()) return
        try { updater.install() }
        catch (_: Exception) { mutable.value = mutable.value.copy(actionMessage = AppText.Updates.INSTALL_CONFIRMATION_NOT_OPENED_RECONFIRM_LATER) }
    }
    fun cancelDownload() {
        if (closed || !updater.supportsApkInstall || updater.state.value.phase !in busyPhases + installPhases) return
        mutable.value = mutable.value.copy(downloadConfirmation = null, installConfirmation = false)
        try { updater.cancel() }
        catch (_: Exception) { mutable.value = mutable.value.copy(actionMessage = AppText.Updates.CANCELLATION_INCOMPLETE_REVIEW_UPDATE_STATUS_LATER) }
    }
    fun browserFailed() { mutable.value = mutable.value.copy(actionMessage = AppText.Updates.RELEASE_PAGE_NOT_OPENED_RETRY_LATER) }

    /** Cancel this page's check/dialogs only; an explicitly started app-owned download survives. */
    fun close() {
        if (closed) return
        closed = true; ++generation; checkJob?.cancel(); checkJob = null
        mutable.value = mutable.value.copy(checking = false, downloadConfirmation = null, installConfirmation = false)
    }

    companion object {
        val busyPhases = setOf(AppUpdatePhase.DOWNLOADING, AppUpdatePhase.VERIFYING)
        private val downloadPhases = setOf(AppUpdatePhase.IDLE, AppUpdatePhase.CANCELLED, AppUpdatePhase.FAILED, AppUpdatePhase.INSTALLER_OPENED)
        private val installPhases = setOf(AppUpdatePhase.READY_TO_INSTALL, AppUpdatePhase.INSTALL_PERMISSION_REQUIRED, AppUpdatePhase.INSTALLER_OPENED)
    }
}

fun appUpdatePhaseMessage(phase: AppUpdatePhase): String = when (phase) {
    AppUpdatePhase.IDLE -> AppText.Updates.DOWNLOAD_NOT_STARTED_STATUS
    AppUpdatePhase.DOWNLOADING -> AppText.Updates.DOWNLOADING_CONFIRMED_APK_STATUS
    AppUpdatePhase.VERIFYING -> AppText.Updates.VERIFYING_FILE_VERSION_AND_SIGNATURE_STATUS
    AppUpdatePhase.READY_TO_INSTALL -> AppText.Updates.VERIFIED_AWAITING_EXPLICIT_SYSTEM_INSTALL_CONFIRMATION
    AppUpdatePhase.INSTALL_PERMISSION_REQUIRED -> AppText.Updates.ALLOW_INSTALL_IN_SYSTEM_SETTINGS_AND_RECONFIRM_HINT
    AppUpdatePhase.INSTALLER_OPENED -> AppText.Updates.SYSTEM_INSTALL_CONFIRMATION_REQUESTED_INSTALL_UNCONFIRMED_HINT
    AppUpdatePhase.CANCELLED -> AppText.Updates.CURRENT_DOWNLOAD_CANCELLED_NOTICE
    AppUpdatePhase.FAILED -> AppText.Updates.DOWNLOAD_OR_VERIFICATION_INCOMPLETE_NO_AUTO_RETRY
    AppUpdatePhase.UNAVAILABLE -> AppText.Updates.APK_DOWNLOAD_AND_INSTALL_UNAVAILABLE_OPEN_RELEASE_PAGE
}
