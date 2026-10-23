package io.github.betterzslf.ui.update

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.feature.update.*
import io.github.betterzslf.platform.AppUpdatePhase
import io.github.betterzslf.platform.NativeAppUpdater
import io.github.betterzslf.ui.ConfirmDialog
import io.github.betterzslf.ui.PageList
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import top.yukonga.miuix.kmp.basic.*

/** No auto-check on composition/resume; repository and updater remain AppGraph-owned. */
@Composable
fun AppUpdatePage(
    currentVersionName: String,
    repository: GithubReleaseRepository,
    updater: NativeAppUpdater,
    onOpenRelease: (String) -> Unit,
    onBack: () -> Unit,
) {
    val displayedVersion = StableVersion.parse(currentVersionName)?.takeIf { it.versionName == currentVersionName }?.versionName ?: AppText.Updates.UNKNOWN_VERSION
    val scope = rememberCoroutineScope()
    val controller = remember(currentVersionName, repository, updater, scope) { AppUpdateController(currentVersionName, repository, updater, scope) }
    val state by controller.state.collectAsState()
    val native by updater.state.collectAsState()
    DisposableEffect(controller) { onDispose { controller.close() } }
    Screen(AppText.Updates.APP_UPDATE_TITLE, onBack) { padding ->
        PageList(padding) {
            item { SectionCard(AppText.Updates.CURRENT_APP_TITLE, AppText.Updates.installedSummary((displayedVersion))) }
            item {
                Button(enabled = controller.canCheck(),
                    onClick = controller::check, modifier = Modifier.fillMaxWidth()) { Text(if (state.checking) AppText.Common.CHECKING else AppText.Updates.CHECK_FOR_UPDATES) }
                if (state.checking) {
                    Spacer(Modifier.height(8.dp)); CircularProgressIndicator()
                    TextButton(AppText.Updates.CANCEL_UPDATE_CHECK, onClick = controller::cancelCheck)
                }
            }
            when (val result = state.result) {
                null -> if (!state.checking) item { SectionCard(AppText.Updates.NOT_CHECKED, AppText.Updates.MANUAL_PUBLIC_RELEASE_CHECK_HINT) }
                GithubReleaseCheck.NotPublished -> item { SectionCard(AppText.Updates.NO_RELEASE_YET, AppText.Updates.NO_LATEST_STABLE_RELEASE_HINT) }
                is GithubReleaseCheck.UpToDate -> item { SectionCard(if (result.installedAhead) AppText.Updates.CURRENT_VERSION_AHEAD_OF_STABLE else AppText.Updates.LATEST_STABLE_VERSION,
                    AppText.Updates.latestStableVersion((result.latestVersionName)) + if (result.installedAhead) AppText.Updates.NO_DOWNGRADE_SUFFIX else "。") }
                is GithubReleaseCheck.Failed -> item { SectionCard(AppText.Updates.UPDATE_CHECK_NOTICE, releaseIssueMessage(result.reason)) }
                is GithubReleaseCheck.Available -> {
                    val candidate = result.candidate
                    item { SectionCard(AppText.Updates.availableVersion((candidate.versionName)), AppText.Updates.verifiedApkSummary((size(candidate.apk.sizeBytes)))) }
                    item { SectionCard(AppText.Updates.RELEASE_NOTES_TITLE, candidate.notes.ifBlank { AppText.Updates.RELEASE_NOTES_MISSING }) }
                    if (updater.supportsApkInstall) item {
                        Button(enabled = controller.canDownload(), onClick = controller::requestDownload, modifier = Modifier.fillMaxWidth()) { Text(AppText.Updates.DOWNLOAD_UPDATE_ACTION) }
                    }
                }
            }
            if (!updater.supportsApkInstall) item { SectionCard(AppText.Updates.PLATFORM_UPDATE_METHOD_TITLE, AppText.Updates.RELEASE_PAGE_ONLY_PLATFORM_HINT) }
            else if (native.phase != AppUpdatePhase.IDLE) {
                item { SectionCard(AppText.Updates.NATIVE_UPDATE_STATUS_TITLE, appUpdatePhaseMessage(native.phase)) }
                if (native.phase == AppUpdatePhase.DOWNLOADING) item {
                    val total = native.totalBytes?.takeIf { it > 0 }
                    val bytes = native.downloadedBytes.coerceAtLeast(0)
                    val progress = total?.let { (bytes.toDouble() / it * 100).coerceIn(0.0, 100.0).toInt() }
                    Text(AppText.Updates.downloadedBytes((size(bytes))) + if (total == null) "" else AppText.Updates.downloadProgress((size(total)), (progress)))
                    CircularProgressIndicator()
                }
                if (native.phase in AppUpdateController.busyPhases || controller.canInstall()) item {
                    TextButton(AppText.Updates.CANCEL_CURRENT_UPDATE, onClick = controller::cancelDownload)
                }
                if (controller.canInstall()) item {
                    Button(onClick = controller::requestInstall, modifier = Modifier.fillMaxWidth()) {
                        Text(if (native.phase == AppUpdatePhase.INSTALL_PERMISSION_REQUIRED) AppText.Updates.OPEN_SETTINGS_OR_RECONFIRM_INSTALL else AppText.Updates.OPEN_SYSTEM_INSTALL_CONFIRMATION)
                    }
                }
            }
            if (state.actionMessage.isNotBlank()) item { SectionCard(AppText.Common.OPERATION_NOTICE_TITLE, state.actionMessage) }
            item { TextButton(AppText.Updates.OPEN_GITHUB_RELEASE_PAGE, onClick = {
                try { onOpenRelease(GithubReleasePolicy.RELEASES_URL) } catch (_: Exception) { controller.browserFailed() }
            }, modifier = Modifier.fillMaxWidth()) }
        }
        // Kept inside this Scaffold content so Miuix's overlay belongs to the visible screen.
        ConfirmDialog(state.downloadConfirmation != null, AppText.Updates.DOWNLOAD_UPDATE_CONFIRM_TITLE,
            AppText.Updates.VERIFIED_APK_DOWNLOAD_HINT,
            controller::dismissDownload, controller::confirmDownload)
        ConfirmDialog(state.installConfirmation, AppText.Updates.SYSTEM_INSTALL_CONFIRM_TITLE,
            AppText.Updates.SYSTEM_INSTALL_REVALIDATION_HINT,
            controller::dismissInstall, controller::confirmInstall)
    }
}

private fun size(bytes: Long): String = AppText.Updates.byteSize((bytes.coerceAtLeast(0) / (1024 * 1024)), (bytes.coerceAtLeast(0)))
