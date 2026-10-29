package io.github.betterzslf.ui.profile

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import coil3.request.*
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.feature.profile.*
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.ConfirmDialog
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.MetricStrip
import io.github.betterzslf.ui.PageList
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import io.github.betterzslf.ui.SectionTitle
import io.github.betterzslf.ui.UiMetric
import io.github.betterzslf.ui.UiTone
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared Android/iOS/JVM Miuix profile. Avatar decoding never receives a network URL. */
@Composable
fun ProfileFeatureScreen(graph: AppGraph, open: (Route) -> Unit) {
    val session by graph.sessions.session.collectAsState()
    if (session == null) {
        Screen(AppText.Navigation.PROFILE) { padding -> PageList(padding) { item { SectionCard(AppText.Auth.NOT_LOGGED_IN, AppText.Profile.LOG_IN_TO_LOAD_PROFILE_HINT) } } }
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
    var detailsExpanded by remember { mutableStateOf(false) }
    DisposableEffect(controller, resources) {
        onDispose { controller.close(); resources.close() }
    }
    // Explicit page read, once on entry; no per-resume reload and no automatic image retry.
    LaunchedEffect(controller) { controller.refresh() }
    Screen(AppText.Navigation.PROFILE, actions = {
        TextButton(AppText.Common.REFRESH, enabled = !state.loading, onClick = controller::refresh)
    }) { padding ->
        PageList(padding) {
            item("identity") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProfileAvatar(state.avatar, imageLoader, controller)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(state.fields?.name ?: AppText.Profile.NAME_NOT_PROVIDED,
                                fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(state.fields?.studentNumber, state.fields?.schoolName).joinToString(" · ")
                                .ifBlank { AppText.Common.NOT_PROVIDED }, fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (state.avatar !is ProfileAvatarState.BytesReady) {
                                Text(avatarSummary(state.avatar), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }
            item("statistics") {
                val stats = state.statistics
                MetricStrip(listOf(
                    UiMetric(stats?.history?.toString() ?: AppText.Common.NOT_PROVIDED, AppText.Profile.BROWSING_HISTORY_LABEL),
                ), onClick = { _ -> open(Route.Page("history", title = AppText.Content.BROWSING_HISTORY_TITLE)) })
            }
            if (state.loading) item("loading") {
                LinearProgressIndicator()
                TextButton(AppText.Profile.CANCEL_PROFILE_READ, onClick = controller::cancel, modifier = Modifier.fillMaxWidth())
            }
            if (state.cancelled) item("cancelled") {
                InfoBanner(AppText.Profile.REFRESH_TO_READ_AGAIN_NO_AUTO_RETRY_HINT,
                    title = AppText.Profile.PROFILE_READ_CANCELLED, tone = UiTone.Neutral)
            }
            state.fieldsIssue?.let { issue -> item("fields-issue") {
                InfoBanner(profileReadMessage(issue), title = AppText.Profile.PERSONAL_PROFILE_TITLE, tone = UiTone.Warning)
            } }
            state.statisticsIssue?.let { issue -> item("statistics-issue") {
                InfoBanner(profileReadMessage(issue), title = AppText.Profile.MY_FOOTPRINTS_TITLE, tone = UiTone.Warning)
            } }
            state.photoIssue?.let { issue -> item("photo-issue") {
                InfoBanner(AppText.Profile.avatarReadIssue(profileReadMessage(issue)), tone = UiTone.Neutral)
            } }
            // Keep the two frequently used app destinations above the longer account/help groups.
            // They stay reachable on a fresh 390dp-wide profile without scrolling under the pill.
            item("app-heading") { SectionTitle(AppText.App.ABOUT_TITLE) }
            item("app-actions") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius) {
                    ArrowPreference(AppText.App.ABOUT_APP_TITLE,
                        onClick = { open(Route.Page("about", title = AppText.App.ABOUT_APP_TITLE)) })
                    ArrowPreference(AppText.Updates.VERSION_UPDATE_TITLE, summary = graph.platform.versionName,
                        onClick = { open(Route.Page("updates", title = AppText.Updates.VERSION_UPDATE_TITLE)) })
                }
            }
            item("account-heading") { SectionTitle(AppText.Profile.ACCOUNT_SECTION_TITLE) }
            item("account-actions") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius) {
                    ArrowPreference(AppText.Settings.SETTINGS_TITLE,
                        onClick = { open(Route.Page("settings", title = AppText.Settings.SETTINGS_TITLE)) })
                    ArrowPreference(AppText.Auth.CHANGE_PASSWORD_TITLE,
                        onClick = { open(Route.Page("password", title = AppText.Auth.CHANGE_PASSWORD_TITLE)) })
                    ArrowPreference(AppText.Recovery.SMS_PASSWORD_RECOVERY_TITLE,
                        summary = AppText.Recovery.VERIFY_SMS_THEN_RESET_SCHOOL_PASSWORD_SUMMARY,
                        onClick = { open(Route.Page("resetpassword", title = AppText.Recovery.RECOVER_PASSWORD_TITLE)) })
                    ArrowPreference(AppText.Profile.PERSONAL_PROFILE_TITLE,
                        holdDownState = detailsExpanded, onClick = { detailsExpanded = !detailsExpanded })
                }
            }
            if (detailsExpanded) item("profile-details") {
                val fields = state.fields
                InfoBanner(AppText.Profile.personalFields(fields?.name ?: AppText.Common.NOT_PROVIDED,
                    fields?.schoolName ?: AppText.Common.NOT_PROVIDED, fields?.studentNumber ?: AppText.Common.NOT_PROVIDED,
                    fields?.account ?: AppText.Common.NOT_PROVIDED, fields?.identity ?: AppText.Common.NOT_PROVIDED,
                    fields?.gender ?: AppText.Common.NOT_PROVIDED), title = AppText.Profile.PERSONAL_PROFILE_TITLE, tone = UiTone.Neutral)
            }
            item("help-heading") { SectionTitle(AppText.App.AGREEMENTS_AND_HELP_SECTION_TITLE) }
            item("help-actions") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius) {
                    listOf("用户协议" to AppText.App.USER_AGREEMENT_DISPLAY_LABEL, "隐私政策" to AppText.App.PRIVACY_POLICY_DISPLAY_LABEL, "使用帮助" to AppText.App.USER_GUIDE_DISPLAY_LABEL).forEach { (documentId, title) ->
                        ArrowPreference(title, onClick = { open(Route.Page("document", documentId, title)) })
                    }
                }
            }
            item("logout") {
                TextButton(AppText.Auth.LOG_OUT, onClick = { logout = true }, modifier = Modifier.fillMaxWidth(),
                    minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                    colors = ButtonDefaults.textButtonColors(textColor = MiuixTheme.colorScheme.error))
            }
        }
        ConfirmDialog(logout, AppText.Auth.LOG_OUT_CONFIRM_TITLE, AppText.Auth.LOG_OUT_LOCAL_SESSION_ONLY_HINT, { logout = false }) {
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
    Box(Modifier.size(72.dp).clip(CircleShape).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
        if (image == null) Text(AppText.Profile.AVATAR_PLACEHOLDER_LABEL) else {
            val request = remember(image, context) {
                ImageRequest.Builder(context).data(image.encoded).size(256, 256)
                    .diskCachePolicy(CachePolicy.DISABLED).memoryCachePolicy(CachePolicy.ENABLED).build()
            }
            AsyncImage(model = request, imageLoader = loader, contentDescription = AppText.Profile.AVATAR_CONTENT_DESCRIPTION, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(), onSuccess = { decoded = true },
                onError = { loader.memoryCache?.clear(); controller.avatarDecodeFailed(image) })
        }
        if (state == ProfileAvatarState.Loading || image != null && !decoded) CircularProgressIndicator()
    }
}

private fun avatarSummary(state: ProfileAvatarState): String = when (state) {
    ProfileAvatarState.Unrequested -> AppText.Profile.AVATAR_NOT_REQUESTED
    ProfileAvatarState.Loading -> AppText.Profile.AVATAR_LOADING
    ProfileAvatarState.Empty -> AppText.Profile.NO_AVATAR
    ProfileAvatarState.Missing -> AppText.Profile.SERVER_AVATAR_DATA_MISSING
    is ProfileAvatarState.Failed -> avatarIssueMessage(state.issue)
    is ProfileAvatarState.BytesReady -> AppText.Profile.AVATAR_PAGE_MEMORY_ONLY_SUMMARY
}
