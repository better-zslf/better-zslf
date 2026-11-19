package io.github.betterzslf.feature.update.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.feature.update.*
import io.github.betterzslf.platform.AppUpdatePhase
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.PageList
import io.github.betterzslf.ui.primaryTextActionColors
import io.github.betterzslf.ui.primaryActionColors
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionTitle
import io.github.betterzslf.ui.UiTone
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Pure content: immutable display state and zero-payload intents only. No effects or owners. */
@Composable
fun AppUpdateContent(state: AppUpdateUiState, onEvent: (AppUpdateEvent) -> Unit) {
    fun emit(event: AppUpdateEvent) { state.eventFor(event)?.let(onEvent) }
    val latestVersion = when (val result = state.result) {
        is AppUpdateResultUi.Available -> result.versionName
        is AppUpdateResultUi.UpToDate -> result.latestVersionName
        AppUpdateResultUi.NotPublished -> AppText.Updates.NO_RELEASE_YET
        is AppUpdateResultUi.Failed -> AppText.Common.NOT_PROVIDED
        null -> if (state.checking) AppText.Common.CHECKING else AppText.Updates.NOT_CHECKED_VALUE
    }
    Screen(AppText.Updates.APP_UPDATE_TITLE, { emit(AppUpdateEvent.Back) }) { padding ->
        PageList(padding) {
            item("versions") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        UpdateVersionRow(AppText.Updates.CURRENT_VERSION_LABEL, state.currentVersionName)
                        UpdateVersionRow(AppText.Updates.LATEST_VERSION_LABEL, latestVersion)
                    }
                }
            }
            item("check") {
                TextButton(if (state.checking) AppText.Common.CHECKING else AppText.Updates.CHECK_FOR_UPDATES,
                    enabled = state.canCheck, onClick = { emit(AppUpdateEvent.Check) }, modifier = Modifier.fillMaxWidth(),
                    minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                    colors = primaryTextActionColors())
                if (state.checking) {
                    Spacer(Modifier.height(AppUiTokens.Gap))
                    LinearProgressIndicator()
                    TextButton(AppText.Updates.CANCEL_UPDATE_CHECK, onClick = { emit(AppUpdateEvent.CancelCheck) }, modifier = Modifier.fillMaxWidth())
                }
            }
            when (val result = state.result) {
                null -> if (!state.checking) item("manual-check-note") {
                    InfoBanner(AppText.Updates.MANUAL_PUBLIC_RELEASE_CHECK_HINT, tone = UiTone.Neutral)
                }
                AppUpdateResultUi.NotPublished -> item("unpublished") {
                    InfoBanner(AppText.Updates.NO_LATEST_STABLE_RELEASE_HINT, title = AppText.Updates.NO_RELEASE_YET, tone = UiTone.Neutral)
                }
                is AppUpdateResultUi.UpToDate -> item("up-to-date") {
                    InfoBanner(AppText.Updates.latestStableVersion(result.latestVersionName) +
                        if (result.installedAhead) AppText.Updates.NO_DOWNGRADE_SUFFIX else "",
                        title = if (result.installedAhead) AppText.Updates.CURRENT_VERSION_AHEAD_OF_STABLE else AppText.Updates.LATEST_STABLE_VERSION,
                        tone = UiTone.Positive)
                }
                is AppUpdateResultUi.Failed -> item("check-error") {
                    InfoBanner(releaseIssueMessage(result.reason), title = AppText.Updates.UPDATE_CHECK_NOTICE,
                        tone = if (result.reason == ReleaseIssue.CANCELLED) UiTone.Neutral else UiTone.Warning)
                }
                is AppUpdateResultUi.Available -> {
                    val candidate = result
                    item("release") {
                        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                            insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                            Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                                Text(AppText.Updates.availableVersion(candidate.versionName), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                if (state.supportsApkInstall) {
                                    Text(AppText.Updates.verifiedApkSummary(size(candidate.sizeBytes)), fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                SectionTitle(AppText.Updates.RELEASE_NOTES_TITLE)
                                Text(candidate.notes.ifBlank { AppText.Updates.RELEASE_NOTES_MISSING })
                            }
                        }
                    }
                    if (state.supportsApkInstall) item("download") {
                        TextButton(AppText.Updates.DOWNLOAD_UPDATE_ACTION, enabled = state.canDownload,
                            onClick = { emit(AppUpdateEvent.RequestDownload) }, modifier = Modifier.fillMaxWidth(),
                            minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                            colors = primaryTextActionColors())
                    }
                }
            }
            if (!state.supportsApkInstall) item("platform-method") {
                InfoBanner(AppText.Updates.RELEASE_PAGE_ONLY_PLATFORM_HINT,
                    title = AppText.Updates.PLATFORM_UPDATE_METHOD_TITLE, tone = UiTone.Info)
            } else if (state.nativePhase != AppUpdatePhase.IDLE) {
                item("native-status") {
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                            SectionTitle(AppText.Updates.NATIVE_UPDATE_STATUS_TITLE)
                            InfoBanner(appUpdatePhaseMessage(state.nativePhase), tone = when (state.nativePhase) {
                                AppUpdatePhase.READY_TO_INSTALL -> UiTone.Positive
                                AppUpdatePhase.INSTALL_PERMISSION_REQUIRED -> UiTone.Warning
                                AppUpdatePhase.FAILED -> UiTone.Negative
                                AppUpdatePhase.CANCELLED -> UiTone.Neutral
                                else -> UiTone.Info
                            })
                            if (state.nativePhase == AppUpdatePhase.DOWNLOADING) {
                                val total = state.totalBytes?.takeIf { it > 0 }
                                val bytes = state.downloadedBytes.coerceAtLeast(0)
                                val fraction = total?.let { (bytes.toDouble() / it.toDouble()).coerceIn(0.0, 1.0).toFloat() }
                                LinearProgressIndicator(progress = fraction)
                                Text(AppText.Updates.downloadedBytes(size(bytes)) + if (total == null) "" else
                                    AppText.Updates.downloadProgress(size(total), fraction?.let { (it * 100).toInt() }),
                                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            } else if (state.nativePhase == AppUpdatePhase.VERIFYING) {
                                LinearProgressIndicator()
                            }
                            if (state.nativeMessage.isNotBlank() && state.nativeMessage != appUpdatePhaseMessage(state.nativePhase)) {
                                Text(state.nativeMessage, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            if (state.canCancel) {
                                TextButton(AppText.Updates.CANCEL_CURRENT_UPDATE, onClick = { emit(AppUpdateEvent.CancelDownload) },
                                    modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                                    cornerRadius = AppUiTokens.ButtonRadius)
                            }
                        }
                    }
                }
                if (state.canInstall) item("install") {
                    TextButton(if (state.nativePhase == AppUpdatePhase.INSTALL_PERMISSION_REQUIRED)
                        AppText.Updates.OPEN_SETTINGS_OR_RECONFIRM_INSTALL else AppText.Updates.OPEN_SYSTEM_INSTALL_CONFIRMATION,
                        onClick = { emit(AppUpdateEvent.RequestInstall) }, modifier = Modifier.fillMaxWidth(),
                        minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                        colors = primaryTextActionColors())
                }
            }
            if (state.actionMessage.isNotBlank()) item("action-message") {
                InfoBanner(state.actionMessage, title = AppText.Common.OPERATION_NOTICE_TITLE, tone = UiTone.Warning)
            }
            item("release-page") {
                TextButton(AppText.Updates.OPEN_GITHUB_RELEASE_PAGE, onClick = { emit(AppUpdateEvent.OpenRelease) }, modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius)
            }
        }
        // Kept inside this Scaffold content so Miuix's overlay belongs to the visible screen.
        ConfirmDialog(state.downloadConfirmation, AppText.Updates.DOWNLOAD_UPDATE_CONFIRM_TITLE,
            AppText.Updates.VERIFIED_APK_DOWNLOAD_HINT,
            { emit(AppUpdateEvent.DismissDownload) }, { emit(AppUpdateEvent.ConfirmDownload) })
        ConfirmDialog(state.installConfirmation, AppText.Updates.SYSTEM_INSTALL_CONFIRM_TITLE,
            AppText.Updates.SYSTEM_INSTALL_REVALIDATION_HINT,
            { emit(AppUpdateEvent.DismissInstall) }, { emit(AppUpdateEvent.ConfirmInstall) })
    }
}

/** Local rendering only; confirmation still dispatches a zero-payload intent to the controller. */
@Composable
private fun ConfirmDialog(show: Boolean, title: String, summary: String, dismiss: () -> Unit, confirm: () -> Unit) {
    OverlayDialog(show = show, title = title, summary = summary, onDismissRequest = dismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = dismiss, modifier = Modifier.weight(1f), minHeight = 48.dp,
                cornerRadius = AppUiTokens.ButtonRadius) { Text(AppText.Common.CANCEL) }
            Button(onClick = confirm, modifier = Modifier.weight(1f), minHeight = 48.dp,
                cornerRadius = AppUiTokens.ButtonRadius, colors = primaryActionColors()) { Text(AppText.Common.CONFIRM) }
        }
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
