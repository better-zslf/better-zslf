package io.github.betterzslf.feature.update

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.platform.ApkUpdateSpec
import io.github.betterzslf.platform.AppUpdatePhase
import io.github.betterzslf.platform.NativeAppUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Page-owned only. Construction observes state but performs no check, download or system action. */
class AppUpdateController(
    private val currentVersionName: String,
    private val repository: GithubReleaseSource,
    private val updater: NativeAppUpdater,
    private val scope: CoroutineScope,
    private val policy: ProductUpdatePolicy = ProductUpdatePolicy.Standard,
) {
    private val mutable = MutableStateFlow(AppUpdateUiState())
    val state: StateFlow<AppUpdateUiState> = mutable.asStateFlow()
    private var result: GithubReleaseCheck? = null
    private var pendingDownload: ApkUpdateSpec? = null
    private var checking = false
    private var installConfirmation = false
    private var actionMessage = ""
    private var checkJob: Job? = null
    private var generation = 0L
    private var closed = false
    private val nativeObserver: Job

    init {
        publish()
        nativeObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            updater.state.collect { if (!closed) publish() }
        }
    }

    fun canCheck(): Boolean = !closed && !checking && updater.state.value.phase !in busyPhases + installPhases
    fun canDownload(): Boolean = !closed && !checking && updater.supportsApkInstall &&
        result is GithubReleaseCheck.Available && updater.state.value.phase in downloadPhases
    fun canInstall(): Boolean = !closed && updater.supportsApkInstall && updater.state.value.phase in installPhases
    private fun canCancel(): Boolean = !closed && updater.supportsApkInstall && updater.state.value.phase in busyPhases + installPhases

    /** Every action rechecks the live port state; a stale UI snapshot is never an authorization. */
    fun onEvent(event: AppUpdateEvent) {
        when (event) {
            AppUpdateEvent.Check -> check()
            AppUpdateEvent.CancelCheck -> cancelCheck()
            AppUpdateEvent.RequestDownload -> requestDownload()
            AppUpdateEvent.ConfirmDownload -> confirmDownload()
            AppUpdateEvent.DismissDownload -> dismissDownload()
            AppUpdateEvent.RequestInstall -> requestInstall()
            AppUpdateEvent.ConfirmInstall -> confirmInstall()
            AppUpdateEvent.DismissInstall -> dismissInstall()
            AppUpdateEvent.CancelDownload -> cancelDownload()
            AppUpdateEvent.OpenRelease, AppUpdateEvent.Back -> Unit // Product binder owns navigation.
        }
    }

    fun check() {
        if (!canCheck()) return
        val ticket = ++generation
        result = null; pendingDownload = null; installConfirmation = false; actionMessage = ""; checking = true
        publish()
        checkJob = scope.launch {
            try {
                val checked = repository.check(currentVersionName)
                if (!closed && ticket == generation) {
                    result = checked; checking = false; publish()
                }
            } catch (cancelled: CancellationException) {
                if (!closed && ticket == generation) {
                    result = GithubReleaseCheck.Failed(ReleaseIssue.CANCELLED); checking = false; publish()
                }
                throw cancelled
            } catch (_: Exception) {
                if (!closed && ticket == generation) {
                    result = GithubReleaseCheck.Failed(ReleaseIssue.NETWORK); checking = false; publish()
                }
            } finally { if (ticket == generation) checkJob = null }
        }
    }

    fun cancelCheck() {
        if (closed || !checking) return
        ++generation; checkJob?.cancel(); checkJob = null
        checking = false; result = GithubReleaseCheck.Failed(ReleaseIssue.CANCELLED)
        publish()
    }

    fun requestDownload() {
        if (!canDownload()) return
        val candidate = (result as GithubReleaseCheck.Available).candidate
        if (!GithubReleasePolicy.isInstallSpec(candidate.apk, policy)) {
            pendingDownload = null; actionMessage = releaseIssueMessage(ReleaseIssue.INVALID_ASSET); publish(); return
        }
        pendingDownload = candidate.apk; actionMessage = ""; publish()
    }

    fun dismissDownload() {
        if (closed) return
        pendingDownload = null; publish()
    }

    fun confirmDownload() {
        if (closed) return
        val spec = pendingDownload ?: return
        pendingDownload = null
        if (!canDownload() || !GithubReleasePolicy.isInstallSpec(spec, policy) ||
            (result as? GithubReleaseCheck.Available)?.candidate?.apk != spec) { publish(); return }
        try { updater.download(spec) }
        catch (_: Exception) { actionMessage = AppText.Updates.DOWNLOAD_NOT_STARTED_RECONFIRM_LATER }
        publish()
    }

    fun requestInstall() {
        if (!canInstall()) return
        installConfirmation = true; actionMessage = ""; publish()
    }

    fun dismissInstall() {
        if (closed) return
        installConfirmation = false; publish()
    }

    fun confirmInstall() {
        if (closed || !installConfirmation) return
        installConfirmation = false
        if (!canInstall()) { publish(); return }
        try { updater.install() }
        catch (_: Exception) { actionMessage = AppText.Updates.INSTALL_CONFIRMATION_NOT_OPENED_RECONFIRM_LATER }
        publish()
    }

    fun cancelDownload() {
        if (!canCancel()) return
        pendingDownload = null; installConfirmation = false
        try { updater.cancel() }
        catch (_: Exception) { actionMessage = AppText.Updates.CANCELLATION_INCOMPLETE_REVIEW_UPDATE_STATUS_LATER }
        publish()
    }

    fun browserFailed() {
        if (closed) return
        actionMessage = AppText.Updates.RELEASE_PAGE_NOT_OPENED_RETRY_LATER; publish()
    }

    /** Cancel this page's check, observer and dialogs only; the app-owned native transfer survives. */
    fun close() {
        if (closed) return
        closed = true; ++generation; checkJob?.cancel(); checkJob = null; nativeObserver.cancel()
        checking = false; pendingDownload = null; installConfirmation = false
        publish()
    }

    private fun publish() {
        val native = updater.state.value
        val safeResult = when (val checked = result) {
            null -> null
            GithubReleaseCheck.NotPublished -> AppUpdateResultUi.NotPublished
            is GithubReleaseCheck.UpToDate -> AppUpdateResultUi.UpToDate(checked.latestVersionName, checked.installedAhead)
            is GithubReleaseCheck.Failed -> AppUpdateResultUi.Failed(checked.reason)
            is GithubReleaseCheck.Available -> AppUpdateResultUi.Available(checked.candidate.versionName, checked.candidate.notes, checked.candidate.apk.sizeBytes)
        }
        mutable.value = AppUpdateUiState(
            currentVersionName = StableVersion.parseInstalled(currentVersionName, policy)
                ?.takeIf { it.versionName == currentVersionName }?.versionName ?: AppText.Updates.UNKNOWN_VERSION,
            checking = checking, result = safeResult, supportsApkInstall = updater.supportsApkInstall,
            nativePhase = native.phase, downloadedBytes = native.downloadedBytes, totalBytes = native.totalBytes,
            nativeMessage = native.message, canCheck = canCheck(), canDownload = canDownload(),
            canInstall = canInstall(), canCancel = canCancel(), downloadConfirmation = pendingDownload != null,
            installConfirmation = installConfirmation, actionMessage = actionMessage,
        )
    }

    companion object {
        val busyPhases = setOf(AppUpdatePhase.DOWNLOADING, AppUpdatePhase.VERIFYING)
        private val downloadPhases = setOf(AppUpdatePhase.IDLE, AppUpdatePhase.CANCELLED, AppUpdatePhase.FAILED, AppUpdatePhase.INSTALLER_OPENED)
        private val installPhases = setOf(AppUpdatePhase.READY_TO_INSTALL, AppUpdatePhase.INSTALL_PERMISSION_REQUIRED, AppUpdatePhase.INSTALLER_OPENED)
    }
}
