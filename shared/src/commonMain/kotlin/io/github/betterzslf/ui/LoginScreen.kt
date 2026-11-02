package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.edition.EditionAccess
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LoginScreen(graph: AppGraph, onDocument: (String) -> Unit, onWater: (() -> Unit)? = null, onResetPassword: (() -> Unit)? = null) {
    // The login surface is a two-step flow: first choose which account to sign in with, then the
    // form for that choice. Dormitory network is a secondary option, so it stays a small link
    // rather than a third card. Anything that signs in ends up in the same session gate.
    var stage by rememberSaveable { mutableStateOf(LoginStage.Chooser) }
    var username by rememberSaveable { mutableStateOf("") }
    // Password intentionally never participates in saved instance state or storage.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    when (stage) {
        LoginStage.Chooser -> LoginChooser(
            showDormLink = true,
            onCampus = { stage = LoginStage.Campus },
            onAcademic = { stage = LoginStage.Academic },
            onDorm = { stage = LoginStage.Dorm },
            onDocument = onDocument,
        )
        LoginStage.Campus -> LoginForm(
            graph = graph,
            title = AppText.Auth.ENTRY_CAMPUS_TITLE,
            tagline = AppText.Auth.LIGHTER_CAMPUS_LIFE_TAGLINE,
            accountLabel = AppText.Auth.STUDENT_NUMBER_FIELD,
            numericAccount = true,
            username = username,
            onUsername = { username = it },
            password = password,
            onPassword = { password = it },
            visible = visible,
            onVisible = { visible = it },
            loading = loading,
            notice = message.ifBlank { graph.storageNotice.orEmpty() },
            noticeTitle = if (message.isNotBlank()) AppText.Auth.LOGIN_NOTICE_TITLE else AppText.App.LOCAL_DATA_NOTICE,
            onBack = { message = ""; stage = LoginStage.Chooser },
            onResetPassword = onResetPassword,
            onWater = onWater,
            onDocument = onDocument,
            onSubmit = submit@{
                if (loading || username.isBlank() || password.isEmpty()) return@submit
                loading = true
                message = ""
                scope.launch {
                    try {
                        when (val result = graph.repositories.auth.login(username.trim(), password)) {
                            is ApiResult.Success -> { password = ""; graph.clearStorageNotice() }
                            is ApiResult.Failure -> message = result.error.message
                        }
                    } finally { loading = false }
                }
            },
            submitEnabled = username.isNotBlank() && password.isNotEmpty(),
        )
        LoginStage.Academic -> LoginUnavailable(
            title = AppText.Auth.ENTRY_ACADEMIC_TITLE,
            onBack = { stage = LoginStage.Chooser },
            onDocument = onDocument,
        )
        LoginStage.Dorm -> LoginUnavailable(
            title = AppText.Auth.ENTRY_DORM_LINK,
            onBack = { stage = LoginStage.Chooser },
            onDocument = onDocument,
        )
    }
}

/** Which surface the login screen is showing. Kept saveable so a rotation does not lose the step. */
private enum class LoginStage { Chooser, Campus, Academic, Dorm }

@Composable
private fun LoginChooser(
    showDormLink: Boolean,
    onCampus: () -> Unit,
    onAcademic: () -> Unit,
    onDorm: () -> Unit,
    onDocument: (String) -> Unit,
) {
    Scaffold(containerColor = MiuixTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 44.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppBrandMark()
                    Text(AppText.Auth.WELCOME_TITLE, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(AppText.Auth.ENTRY_ENTRY_HINT, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center)
                }
            }
            item {
                LoginEntryCard(
                    title = AppText.Auth.ENTRY_CAMPUS_TITLE,
                    subtitle = AppText.Auth.ENTRY_CAMPUS_SUBTITLE,
                    accent = AppUiTokens.Primary,
                    icon = MiuixIcons.Contacts,
                    testTag = "entryCampus",
                    onClick = onCampus,
                )
            }
            item {
                LoginEntryCard(
                    title = AppText.Auth.ENTRY_ACADEMIC_TITLE,
                    subtitle = AppText.Auth.ENTRY_ACADEMIC_SUBTITLE,
                    accent = AppUiTokens.Positive,
                    icon = MiuixIcons.Notes,
                    testTag = "entryAcademic",
                    onClick = onAcademic,
                )
            }
            if (showDormLink) item {
                // Deliberately a plain text link, not a card: dorm network is optional and must
                // read as secondary to the two account cards above it. Miuix's TextButton paints a
                // filled container, which made this look like a third card, so the tap target is a
                // bare clickable text instead.
                Text(
                    AppText.Auth.ENTRY_DORM_LINK,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppUiTokens.FieldRadius))
                        .clickable(onClick = onDorm)
                        .padding(vertical = 12.dp)
                        .testTag("entryDorm"),
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton(AppText.App.USER_AGREEMENT_DISPLAY_LABEL, onClick = { onDocument("用户协议") })
                    TextButton(AppText.App.PRIVACY_POLICY_DISPLAY_LABEL, onClick = { onDocument("隐私政策") })
                }
            }
        }
    }
}

@Composable
private fun LoginEntryCard(
    title: String,
    subtitle: String,
    accent: Color,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(72.dp).testTag(testTag),
        cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding),
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Icon(BackChevron, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.size(20.dp).rotate(180f))
        }
    }
}

/** A stage that is not wired to a backend yet keeps the user oriented instead of dead-ending. */
@Composable
private fun LoginUnavailable(title: String, onBack: () -> Unit, onDocument: (String) -> Unit) {
    Scaffold(containerColor = MiuixTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { BackToChooser(onBack) }
            item { Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            item { InfoBanner(AppText.Auth.STAGE_NOT_READY_HINT, tone = UiTone.Info) }
        }
    }
}

@Composable
private fun BackToChooser(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("loginBack")) {
            Icon(BackChevron, contentDescription = AppText.Auth.ENTRY_BACK_CONTENT_DESCRIPTION,
                tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun LoginForm(
    graph: AppGraph,
    title: String,
    tagline: String,
    accountLabel: String,
    numericAccount: Boolean,
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    visible: Boolean,
    onVisible: (Boolean) -> Unit,
    loading: Boolean,
    notice: String,
    noticeTitle: String,
    onBack: () -> Unit,
    onResetPassword: (() -> Unit)?,
    onWater: (() -> Unit)?,
    onDocument: (String) -> Unit,
    onSubmit: () -> Unit,
    submitEnabled: Boolean,
) {
    Scaffold(containerColor = MiuixTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { BackToChooser(onBack) }
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppBrandMark()
                    Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(tagline, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center)
                }
            }
            item {
                TextField(value = username, onValueChange = onUsername, label = accountLabel,
                    singleLine = true, cornerRadius = AppUiTokens.FieldRadius, enabled = !loading,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (numericAccount) KeyboardType.Number else KeyboardType.Text,
                        imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().heightIn(min = AppUiTokens.ButtonHeight).testTag("username"))
            }
            item {
                TextField(value = password, onValueChange = onPassword, label = AppText.Auth.PASSWORD_FIELD,
                    singleLine = true, cornerRadius = AppUiTokens.FieldRadius, enabled = !loading,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().heightIn(min = AppUiTokens.ButtonHeight).testTag("password"))
            }
            item {
                SwitchPreference(title = AppText.Auth.SHOW_PASSWORD, checked = visible,
                    enabled = !loading, onCheckedChange = onVisible)
            }
            if (notice.isNotBlank()) item { InfoBanner(notice, title = noticeTitle, tone = UiTone.Warning) }
            item {
                PrimaryAction(
                    text = if (loading) AppText.Auth.LOGGING_IN else AppText.Auth.LOG_IN,
                    modifier = Modifier.testTag("loginButton"),
                    enabled = !loading && submitEnabled,
                    onClick = onSubmit,
                )
            }
            onResetPassword?.let { openReset -> item {
                TextButton(AppText.Recovery.RECOVER_PASSWORD_TITLE, onClick = openReset, modifier = Modifier.fillMaxWidth())
            } }
            if (EditionAccess.waterEnabled) onWater?.let { openWater -> item { graph.editionFeatures.LoginEntry(openWater) } }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(AppText.App.USER_AGREEMENT_DISPLAY_LABEL, onClick = { onDocument("用户协议") })
                    TextButton(AppText.App.PRIVACY_POLICY_DISPLAY_LABEL, onClick = { onDocument("隐私政策") })
                }
            }
        }
    }
}
