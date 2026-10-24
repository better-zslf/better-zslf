package io.github.betterzslf.ui.update

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
    val displayedVersion = StableVersion.parse(currentVersionName)?.takeIf { it.versionName == currentVersionName }?.versionName ?: "未知版本"
    val scope = rememberCoroutineScope()
    val controller = remember(currentVersionName, repository, updater, scope) { AppUpdateController(currentVersionName, repository, updater, scope) }
    val state by controller.state.collectAsState()
    val native by updater.state.collectAsState()
    DisposableEffect(controller) { onDispose { controller.close() } }
    Screen("应用更新", onBack) { padding ->
        PageList(padding) {
            item { SectionCard("当前应用", "better-zslf · $displayedVersion\n这里只检查本项目的 GitHub 发布，不使用学校应用版本接口。") }
            item {
                Button(enabled = controller.canCheck(),
                    onClick = controller::check, modifier = Modifier.fillMaxWidth()) { Text(if (state.checking) "正在检查…" else "检查更新") }
                if (state.checking) {
                    Spacer(Modifier.height(8.dp)); CircularProgressIndicator()
                    TextButton("取消检查", onClick = controller::cancelCheck)
                }
            }
            when (val result = state.result) {
                null -> if (!state.checking) item { SectionCard("尚未检查", "仅在点按检查后读取公共 GitHub 发布信息。") }
                GithubReleaseCheck.NotPublished -> item { SectionCard("尚未发布", "本项目目前尚无可用的最新正式发布。") }
                is GithubReleaseCheck.UpToDate -> item { SectionCard(if (result.installedAhead) "当前版本高于公开稳定版" else "已是最新稳定版",
                    "GitHub 最新稳定版 ${result.latestVersionName}" + if (result.installedAhead) "；不会降级安装。" else "。") }
                is GithubReleaseCheck.Failed -> item { SectionCard("更新检查提示", releaseIssueMessage(result.reason)) }
                is GithubReleaseCheck.Available -> {
                    val candidate = result.candidate
                    item { SectionCard("发现新版本 ${candidate.versionName}", "APK ${size(candidate.apk.sizeBytes)} · 已取得同一发布的 SHA-256 清单；下载后仍须原生校验签名与版本。") }
                    item { SectionCard("发布说明", candidate.notes.ifBlank { "此发布没有附加说明。" }) }
                    if (updater.supportsApkInstall) item {
                        Button(enabled = controller.canDownload(), onClick = controller::requestDownload, modifier = Modifier.fillMaxWidth()) { Text("下载更新…") }
                    }
                }
            }
            if (!updater.supportsApkInstall) item { SectionCard("本平台更新方式", "此平台只提供 GitHub 发布页面入口，不下载 Android APK，也不请求安装权限。") }
            else if (native.phase != AppUpdatePhase.IDLE) {
                item { SectionCard("本次原生更新状态", appUpdatePhaseMessage(native.phase)) }
                if (native.phase == AppUpdatePhase.DOWNLOADING) item {
                    val total = native.totalBytes?.takeIf { it > 0 }
                    val bytes = native.downloadedBytes.coerceAtLeast(0)
                    val progress = total?.let { (bytes.toDouble() / it * 100).coerceIn(0.0, 100.0).toInt() }
                    Text("已下载 ${size(bytes)}" + if (total == null) "" else " / ${size(total)} · $progress%")
                    CircularProgressIndicator()
                }
                if (native.phase in AppUpdateController.busyPhases || controller.canInstall()) item {
                    TextButton("取消本次更新", onClick = controller::cancelDownload)
                }
                if (controller.canInstall()) item {
                    Button(onClick = controller::requestInstall, modifier = Modifier.fillMaxWidth()) {
                        Text(if (native.phase == AppUpdatePhase.INSTALL_PERMISSION_REQUIRED) "前往设置 / 再次确认安装…" else "打开系统安装确认页…")
                    }
                }
            }
            if (state.actionMessage.isNotBlank()) item { SectionCard("操作提示", state.actionMessage) }
            item { TextButton("打开 GitHub 发布页面", onClick = {
                try { onOpenRelease(GithubReleasePolicy.RELEASES_URL) } catch (_: Exception) { controller.browserFailed() }
            }, modifier = Modifier.fillMaxWidth()) }
        }
        // Kept inside this Scaffold content so Miuix's overlay belongs to the visible screen.
        ConfirmDialog(state.downloadConfirmation != null, "下载应用更新？",
            "仅下载本项目已核对的公开发布 APK；下载后校验 SHA-256、包名、版本与签名，不会自动安装。",
            controller::dismissDownload, controller::confirmDownload)
        ConfirmDialog(state.installConfirmation, "打开系统安装确认？",
            "将重新校验文件，再向系统请求本应用的安装设置或安装确认；仍由你在系统界面决定。返回设置后不会自动安装。",
            controller::dismissInstall, controller::confirmInstall)
    }
}

private fun size(bytes: Long): String = "${bytes.coerceAtLeast(0) / (1024 * 1024)} MiB (${bytes.coerceAtLeast(0)} 字节)"
