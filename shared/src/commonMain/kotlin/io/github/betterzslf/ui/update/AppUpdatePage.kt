package io.github.betterzslf.ui.update

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.feature.update.*
import io.github.betterzslf.platform.AppUpdatePhase
import io.github.betterzslf.platform.NativeAppUpdater
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.ConfirmDialog
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.PageList
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionTitle
import io.github.betterzslf.ui.UiTone
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    val latestVersion = when (val result = state.result) {
        is GithubReleaseCheck.Available -> result.candidate.versionName
        is GithubReleaseCheck.UpToDate -> result.latestVersionName
        GithubReleaseCheck.NotPublished -> AppText.Updates.NO_RELEASE_YET
        is GithubReleaseCheck.Failed -> AppText.Common.NOT_PROVIDED
        null -> if (state.checking) AppText.Common.CHECKING else AppText.Updates.NOT_CHECKED_VALUE
    }
    Screen(AppText.Updates.APP_UPDATE_TITLE, onBack) { padding ->
        PageList(padding) {
            item("versions") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        UpdateVersionRow(AppText.Updates.CURRENT_VERSION_LABEL, displayedVersion)
                        UpdateVersionRow(AppText.Updates.LATEST_VERSION_LABEL, latestVersion)
                    }
                }
            }
            item("check") {
                TextButton(if (state.checking) AppText.Common.CHECKING else AppText.Updates.CHECK_FOR_UPDATES,
                    enabled = controller.canCheck(), onClick = controller::check, modifier = Modifier.fillMaxWidth(),
                    minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                    colors = ButtonDefaults.textButtonColorsPrimary())
                if (state.checking) {
                    Spacer(Modifier.height(AppUiTokens.Gap))
                    LinearProgressIndicator()
                    TextButton(AppText.Updates.CANCEL_UPDATE_CHECK, onClick = controller::cancelCheck, modifier = Modifier.fillMaxWidth())
                }
            }
            when (val result = state.result) {
                null -> if (!state.checking) item("manual-check-note") {
                    InfoBanner(AppText.Updates.MANUAL_PUBLIC_RELEASE_CHECK_HINT, tone = UiTone.Neutral)
                }
                GithubReleaseCheck.NotPublished -> item("unpublished") {
                    InfoBanner(AppText.Updates.NO_LATEST_STABLE_RELEASE_HINT, title = AppText.Updates.NO_RELEASE_YET, tone = UiTone.Neutral)
                }
                is GithubReleaseCheck.UpToDate -> item("up-to-date") {
                    InfoBanner(AppText.Updates.latestStableVersion(result.latestVersionName) +
                        if (result.installedAhead) AppText.Updates.NO_DOWNGRADE_SUFFIX else "",
                        title = if (result.installedAhead) AppText.Updates.CURRENT_VERSION_AHEAD_OF_STABLE else AppText.Updates.LATEST_STABLE_VERSION,
                        tone = UiTone.Positive)
                }
                is GithubReleaseCheck.Failed -> item("check-error") {
                    InfoBanner(releaseIssueMessage(result.reason), title = AppText.Updates.UPDATE_CHECK_NOTICE,
                        tone = if (result.reason == ReleaseIssue.CANCELLED) UiTone.Neutral else UiTone.Warning)
                }
                is GithubReleaseCheck.Available -> {
                    val candidate = result.candidate
                    item("release") {
                        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                            insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                            Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                                Text(AppText.Updates.availableVersion(candidate.versionName), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                if (updater.supportsApkInstall) {
                                    Text(AppText.Updates.verifiedApkSummary(size(candidate.apk.sizeBytes)), fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                SectionTitle(AppText.Updates.RELEASE_NOTES_TITLE)
                                Text(candidate.notes.ifBlank { AppText.Updates.RELEASE_NOTES_MISSING })
                            }
                        }
                    }
                    if (updater.supportsApkInstall) item("download") {
                        TextButton(AppText.Updates.DOWNLOAD_UPDATE_ACTION, enabled = controller.canDownload(),
                            onClick = controller::requestDownload, modifier = Modifier.fillMaxWidth(),
                            minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                            colors = ButtonDefaults.textButtonColorsPrimary())
                    }
                }
            }
            if (!updater.supportsApkInstall) item("platform-method") {
                InfoBanner(AppText.Updates.RELEASE_PAGE_ONLY_PLATFORM_HINT,
                    title = AppText.Updates.PLATFORM_UPDATE_METHOD_TITLE, tone = UiTone.Info)
            } else if (native.phase != AppUpdatePhase.IDLE) {
                item("native-status") {
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                            SectionTitle(AppText.Updates.NATIVE_UPDATE_STATUS_TITLE)
                            InfoBanner(appUpdatePhaseMessage(native.phase), tone = when (native.phase) {
                                AppUpdatePhase.READY_TO_INSTALL -> UiTone.Positive
                                AppUpdatePhase.INSTALL_PERMISSION_REQUIRED -> UiTone.Warning
                                AppUpdatePhase.FAILED -> UiTone.Negative
                                AppUpdatePhase.CANCELLED -> UiTone.Neutral
                                else -> UiTone.Info
                            })
                            if (native.phase == AppUpdatePhase.DOWNLOADING) {
                                val total = native.totalBytes?.takeIf { it > 0 }
                                val bytes = native.downloadedBytes.coerceAtLeast(0)
                                val fraction = total?.let { (bytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
                                LinearProgressIndicator(progress = fraction)
                                Text(AppText.Updates.downloadedBytes(size(bytes)) + if (total == null) "" else
                                    AppText.Updates.downloadProgress(size(total), fraction?.let { (it * 100).toInt() }),
                                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            } else if (native.phase == AppUpdatePhase.VERIFYING) {
                                LinearProgressIndicator()
                            }
                            if (native.message.isNotBlank() && native.message != appUpdatePhaseMessage(native.phase)) {
                                Text(native.message, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            if (native.phase in AppUpdateController.busyPhases || controller.canInstall()) {
                                TextButton(AppText.Updates.CANCEL_CURRENT_UPDATE, onClick = controller::cancelDownload,
                                    modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                                    cornerRadius = AppUiTokens.ButtonRadius)
                            }
                        }
                    }
                }
                if (controller.canInstall()) item("install") {
                    TextButton(if (native.phase == AppUpdatePhase.INSTALL_PERMISSION_REQUIRED)
                        AppText.Updates.OPEN_SETTINGS_OR_RECONFIRM_INSTALL else AppText.Updates.OPEN_SYSTEM_INSTALL_CONFIRMATION,
                        onClick = controller::requestInstall, modifier = Modifier.fillMaxWidth(),
                        minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                        colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
            if (state.actionMessage.isNotBlank()) item("action-message") {
                InfoBanner(state.actionMessage, title = AppText.Common.OPERATION_NOTICE_TITLE, tone = UiTone.Warning)
            }
            item("release-page") {
                TextButton(AppText.Updates.OPEN_GITHUB_RELEASE_PAGE, onClick = {
                    try { onOpenRelease(GithubReleasePolicy.RELEASES_URL) } catch (_: Exception) { controller.browserFailed() }
                }, modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius)
            }
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

@Composable
private fun UpdateVersionRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun size(bytes: Long): String = AppText.Updates.byteSize((bytes.coerceAtLeast(0) / (1024 * 1024)), (bytes.coerceAtLeast(0)))
