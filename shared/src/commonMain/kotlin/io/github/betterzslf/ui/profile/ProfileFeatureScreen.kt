package io.github.betterzslf.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.*
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.feature.profile.*
import io.github.betterzslf.ui.ConfirmDialog
import io.github.betterzslf.ui.PageList
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared Android/iOS/JVM Miuix profile. Avatar decoding never receives a network URL. */
@Composable
fun ProfileFeatureScreen(graph: AppGraph, open: (Route) -> Unit) {
    val session by graph.sessions.session.collectAsState()
    if (session == null) {
        Screen("我的") { padding -> PageList(padding) { item { SectionCard("尚未登录", "登录后读取个人资料。") } } }
    } else key(session) {
        ProfileOwnerScreen(graph, open)
    }
}

@Composable
private fun ProfileOwnerScreen(graph: AppGraph, open: (Route) -> Unit) {
    val context = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    // New PUBLIC client owned by this page/session, never graph.api or its engine.
    val avatars = remember(graph) { AvatarByteRepository(graph.platform.httpClient()) }
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(4L * 1024 * 1024).build() }
            .diskCache(null)
            .build()
    }
    val resources = remember(imageLoader, avatars) { ProfilePageResources(imageLoader, avatars) }
    val controller = remember(graph, avatars, scope) { ProfileController(graph.repositories.profile, graph.sessions.session, avatars, scope) }
    val observed by controller.state.collectAsState()
    val state = if (observed.hasSession) controller.visibleState() else observed
    var logout by remember { mutableStateOf(false) }
    DisposableEffect(controller, resources) {
        onDispose { controller.close(); resources.close() }
    }
    // Explicit page read, once on entry; no per-resume reload and no automatic image retry.
    LaunchedEffect(controller) { controller.refresh() }
    Screen("我的", actions = {
        TextButton("刷新", enabled = !state.loading, onClick = controller::refresh)
    }) { padding ->
        PageList(padding) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProfileAvatar(state.avatar, imageLoader, controller)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.fields?.name ?: "姓名未提供", style = MiuixTheme.textStyles.title4)
                        Text(avatarSummary(state.avatar))
                        state.photoIssue?.let { Text("头像资料：${profileReadMessage(it)}") }
                    }
                }
            }
            if (state.loading) item {
                CircularProgressIndicator()
                TextButton("取消读取", onClick = controller::cancel)
            }
            if (state.cancelled) item { SectionCard("读取已取消", "点按刷新可重新读取，不会自动重试。") }
            state.fieldsIssue?.let { issue -> item { SectionCard("个人资料", profileReadMessage(issue)) } }
            item {
                val fields = state.fields
                SectionCard("个人资料", "姓名：${fields?.name ?: "未提供"}\n学校：${fields?.schoolName ?: "未提供"}\n学号：${fields?.studentNumber ?: "未提供"}\n账号：${fields?.account ?: "未提供"}\n身份：${fields?.identity ?: "未提供"}\n性别：${fields?.gender ?: "未提供"}\n院系：暂无信息")
            }
            item {
                val stats = state.statistics
                SectionCard("我的足迹", state.statisticsIssue?.let(::profileReadMessage)
                    ?: "收藏 ${stats?.collected?.toString() ?: "未提供"} · 评论 ${stats?.comments?.toString() ?: "未提供"} · 浏览 ${stats?.history?.toString() ?: "未提供"}")
            }
            item { Text("账号") }
            item { SectionCard("修改密码", onClick = { open(Route.Page("password", title = "修改密码")) }) }
            item { SectionCard("短信找回密码", "通过短信核验后重置学校登录密码", { open(Route.Page("resetpassword", title = "找回密码")) }) }
            item { SectionCard("设置", "主题、跑步偏好与本地数据", { open(Route.Page("settings", title = "设置")) }) }
            item { Text("关于") }
            item { SectionCard("版本更新", "better-zslf ${graph.platform.versionName}", { open(Route.Page("updates", title = "版本更新")) }) }
            item { SectionCard("关于应用", onClick = { open(Route.Page("about", title = "关于应用")) }) }
            item { Text("协议与帮助") }
            listOf("用户协议", "隐私政策", "使用帮助").forEach { title ->
                item { SectionCard(title, onClick = { open(Route.Page("document", title, title)) }) }
            }
            item { TextButton("退出登录", onClick = { logout = true }, modifier = Modifier.fillMaxWidth()) }
        }
        ConfirmDialog(logout, "退出登录？", "会清除本机保存的会话，账号和学校数据保持原样。", { logout = false }) {
            controller.close(); resources.close()
            logout = false; graph.logout()
        }
    }
}

/** Idempotent even when logout is followed by composition disposal; never clears a global cache. */
private class ProfilePageResources(private val loader: ImageLoader, private val avatars: AvatarByteRepository) {
    private var closed = false
    fun close() {
        if (closed) return
        closed = true
        runCatching { loader.shutdown() }
        runCatching { loader.memoryCache?.clear() }
        runCatching { avatars.close() }
    }
}

@Composable
private fun ProfileAvatar(state: ProfileAvatarState, loader: ImageLoader, controller: ProfileController) {
    val context = LocalPlatformContext.current
    val image = (state as? ProfileAvatarState.BytesReady)?.image
    var decoded by remember(image) { mutableStateOf(false) }
    Box(Modifier.size(96.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        if (image == null) Text("头像") else {
            val request = remember(image, context) {
                ImageRequest.Builder(context).data(image.encoded).size(256, 256)
                    .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.ENABLED).build()
            }
            AsyncImage(model = request, imageLoader = loader, contentDescription = "个人头像", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(), onSuccess = { decoded = true },
                onError = { loader.memoryCache?.clear(); controller.avatarDecodeFailed(image) })
        }
        if (state == ProfileAvatarState.Loading || image != null && !decoded) CircularProgressIndicator()
    }
}

private fun avatarSummary(state: ProfileAvatarState): String = when (state) {
    ProfileAvatarState.Unrequested -> "尚未取得头像资料"
    ProfileAvatarState.Loading -> "正在读取头像资料"
    ProfileAvatarState.Empty -> "暂无头像"
    ProfileAvatarState.Missing -> "服务端未提供头像资料"
    is ProfileAvatarState.Failed -> avatarIssueMessage(state.issue)
    is ProfileAvatarState.BytesReady -> "个人头像 · 仅在本页内存中处理"
}
