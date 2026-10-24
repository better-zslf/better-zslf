package io.github.betterzslf.feature.update

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
        catch (_: Exception) { mutable.value = mutable.value.copy(actionMessage = "下载未开始，请稍后重新确认。") }
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
        catch (_: Exception) { mutable.value = mutable.value.copy(actionMessage = "安装确认页未打开，请稍后重新确认。") }
    }
    fun cancelDownload() {
        if (closed || !updater.supportsApkInstall || updater.state.value.phase !in busyPhases + installPhases) return
        mutable.value = mutable.value.copy(downloadConfirmation = null, installConfirmation = false)
        try { updater.cancel() }
        catch (_: Exception) { mutable.value = mutable.value.copy(actionMessage = "取消未完成，请稍后核对更新状态。") }
    }
    fun browserFailed() { mutable.value = mutable.value.copy(actionMessage = "发布页面未打开，请稍后重试。") }

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
    AppUpdatePhase.IDLE -> "尚未开始下载。"
    AppUpdatePhase.DOWNLOADING -> "正在下载已确认的 APK。"
    AppUpdatePhase.VERIFYING -> "正在校验文件、版本与应用签名。"
    AppUpdatePhase.READY_TO_INSTALL -> "校验完成，等待你明确打开系统安装确认页。"
    AppUpdatePhase.INSTALL_PERMISSION_REQUIRED -> "请在本应用的系统设置中允许安装，返回后再次点按并确认安装；不会自动继续。"
    AppUpdatePhase.INSTALLER_OPENED -> "已向系统请求打开安装确认；尚未确认安装。请在系统界面核对，之后重新打开应用确认版本。"
    AppUpdatePhase.CANCELLED -> "已取消本次下载。"
    AppUpdatePhase.FAILED -> "下载或校验未完成，未自动重试。"
    AppUpdatePhase.UNAVAILABLE -> "此平台不提供 APK 下载或安装，请查看发布页面。"
}
