package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
fun LoginScreen(graph: AppGraph, onDocument: (String) -> Unit, onWater: (() -> Unit)? = null,
                onResetPassword: (() -> Unit)? = null, onEnter: () -> Unit = {}) {
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
            campusBound = graph.sessions.session.collectAsState().value != null,
            academicBound = graph.schedule.state.collectAsState().value.boundAccount != null,
            onCampus = { stage = LoginStage.Campus },
            onAcademic = { stage = LoginStage.Academic },
            onDorm = { stage = LoginStage.Dorm },
            onEnter = onEnter,
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
        LoginStage.Academic -> io.github.betterzslf.ui.schedule.ScheduleBindingPage(
            runtime = graph.schedule,
            updatingPassword = false,
            onBack = { stage = LoginStage.Chooser },
            // Returning to the chooser is deliberate: the chooser is where the account list lives,
            // and it now shows an explicit way in once at least one account is bound.
            onBound = { stage = LoginStage.Chooser },
        )
        LoginStage.Dorm -> DormNetworkPage(
            graph = graph,
            onBack = { stage = LoginStage.Chooser },
        )
    }
}

/** Which surface the login screen is showing. Kept saveable so a rotation does not lose the step. */
private enum class LoginStage { Chooser, Campus, Academic, Dorm }

@Composable
private fun LoginChooser(
    showDormLink: Boolean,
    campusBound: Boolean,
    academicBound: Boolean,
    onCampus: () -> Unit,
    onAcademic: () -> Unit,
    onDorm: () -> Unit,
    onEnter: () -> Unit,
    onDocument: (String) -> Unit,
) {
    val canEnter = campusBound || academicBound
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
                    bound = campusBound,
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
                    bound = academicBound,
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
            if (canEnter) item {
                // Only appears once something is actually usable, so the chooser never offers a way
                // into an app that would have nothing to show.
                PrimaryAction(
                    text = AppText.Auth.ENTER_APP,
                    modifier = Modifier.fillMaxWidth().padding(top = AppUiTokens.SectionGap).testTag("enterApp"),
                    onClick = onEnter,
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton(AppText.App.USER_AGREEMENT_DISPLAY_LABEL, onClick = { onDocument(AppText.Document.USER_AGREEMENT) })
                    TextButton(AppText.App.PRIVACY_POLICY_DISPLAY_LABEL, onClick = { onDocument(AppText.Document.PRIVACY_POLICY) })
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
    bound: Boolean,
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
                Text(
                    if (bound) subtitle + AppText.Auth.ENTRY_BOUND_SUFFIX else subtitle,
                    fontSize = 13.sp,
                    color = if (bound) AppUiTokens.Positive else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (bound) {
                // A filled dot replaces the chevron so the bound state reads at a glance, not only
                // through the subtitle text.
                Box(Modifier.size(10.dp).clip(CircleShape).background(AppUiTokens.Positive))
            } else {
                Icon(BackChevron, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp).rotate(180f))
            }
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

/**
 * N2, the optional dorm-network sign-in page (design task book §5.3).
 *
 * Three deliberate limits:
 *  - the broadband password box is always empty and nothing here is persisted;
 *  - there is no "remember me" and no automatic sign-in;
 *  - the primary button is disabled and explains why, because the gateway's negotiated cipher
 *    (`algo_id`) has to be captured on campus first. A button that posted to `:7001` today would
 *    fail every time, so it is not offered.
 *
 * The phone-source card has all three states from the design. State B (nothing taken from 掌上莲峰)
 * is the *normal* case, not an error: users can reach this page without ever signing in there.
 */
@Composable
private fun DormNetworkPage(graph: AppGraph, onBack: () -> Unit) {
    val session by graph.sessions.session.collectAsState()
    // Only a signed-in 掌上莲峰 can supply the broadband phone; nothing is fetched before that.
    val campusBound = session != null
    var manualPhone by remember(boundSessionKey(session)) { mutableStateOf("") }
    var taken by remember(boundSessionKey(session)) { mutableStateOf<String?>(null) }
    var queryFailed by remember(boundSessionKey(session)) { mutableStateOf(false) }
    val editable = taken == null

    // The only request this page makes, and only when 掌上莲峰 is signed in. It is a read: the
    // account list endpoint takes no parameters and changes nothing.
    LaunchedEffect(boundSessionKey(session), campusBound) {
        if (!campusBound) { taken = null; queryFailed = false; return@LaunchedEffect }
        val bound = graph.repositories.campusNetwork.boundToCurrentSession()
        if (bound == null) { taken = null; queryFailed = true; return@LaunchedEffect }
        when (val response = bound.accounts()) {
            is ApiResult.Success -> {
                val rows = (response.data as? kotlinx.serialization.json.JsonObject)
                    ?.get("operatorAccount") as? kotlinx.serialization.json.JsonArray
                taken = rows?.firstNotNullOfOrNull { row ->
                    ((row as? kotlinx.serialization.json.JsonObject)
                        ?.get("operatorAccount") as? kotlinx.serialization.json.JsonPrimitive)
                        ?.let { if (it.isString || it.content.isNotEmpty()) it.content else null }
                        ?.let(::dormPhonePrefixOf)
                }
                queryFailed = false
            }
            is ApiResult.Failure -> { taken = null; queryFailed = true }
        }
    }

    Scaffold(containerColor = MiuixTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = AppUiTokens.PagePadding, end = AppUiTokens.PagePadding,
                top = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap),
        ) {
            item { BackToChooser(onBack) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(AppText.Auth.ENTRY_DORM_LINK, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(AppText.Auth.DORM_SUBTITLE, fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                        TextField(
                            value = taken?.let(::maskDormPhone) ?: manualPhone,
                            onValueChange = { if (editable) manualPhone = it },
                            label = AppText.CampusNetwork.DORM_PHONE_FIELD,
                            singleLine = true,
                            enabled = editable,
                            cornerRadius = AppUiTokens.FieldRadius,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth().testTag("dormPhone"),
                        )
                        // Always empty and never restored: the design forbids pre-filling this.
                        TextField(
                            value = "",
                            onValueChange = { },
                            label = AppText.CampusNetwork.DORM_BROADBAND_PASSWORD_FIELD,
                            singleLine = true,
                            enabled = false,
                            cornerRadius = AppUiTokens.FieldRadius,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("dormPassword"),
                        )
                    }
                }
            }
            item {
                // The phone-source card, all three states from the design.
                val tone = when {
                    taken != null -> UiTone.Positive
                    // "Took something, but it was not a phone" is a real, distinct third state.
                    campusBound && queryFailed -> UiTone.Negative
                    else -> UiTone.Warning
                }
                val message = when {
                    taken != null -> AppText.CampusNetwork.dormPhoneSourceReady(maskDormPhone(taken!!))
                    campusBound && queryFailed -> AppText.CampusNetwork.DORM_PHONE_SOURCE_MALFORMED
                    else -> AppText.CampusNetwork.DORM_PHONE_SOURCE_MISSING
                }
                InfoBanner(message, title = AppText.CampusNetwork.DORM_PHONE_SOURCE_TITLE, tone = tone)
            }
            item {
                InfoBanner(AppText.CampusNetwork.DORM_DIALER_UNAVAILABLE_HINT,
                    title = AppText.CampusNetwork.DORM_DIALER_UNAVAILABLE_TITLE, tone = UiTone.Warning)
            }
            item {
                Text(AppText.CampusNetwork.NOT_SCHOOL_LOGIN_PASSWORD_TITLE, fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

/** A session change must not carry a typed phone across owners. */
private fun boundSessionKey(session: io.github.betterzslf.core.model.Session?): String =
    session?.let { "${it.userId}:${it.token.hashCode()}" }.orEmpty()

/**
 * The phone prefix of a broadband account.
 *
 * 掌上莲峰 returns accounts shaped `13800138000@163.gd`; the dorm dialer wants only the part before
 * `@`. Anything that is not an 11-digit mobile number yields `null`, which sends the page to its
 * manual-entry state instead of dialling with a value we cannot trust.
 */
internal fun dormPhonePrefixOf(account: String): String? {
    val prefix = account.substringBefore('@').trim()
    return prefix.takeIf { it.length == 11 && it.all { c -> c in '0'..'9' } }
}

/** `138****8000`, the same masking the rest of the app uses. Never shows the middle digits. */
internal fun maskDormPhone(phone: String): String =
    if (phone.length == 11) phone.take(3) + "****" + phone.takeLast(4) else phone

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
                    TextButton(AppText.App.USER_AGREEMENT_DISPLAY_LABEL, onClick = { onDocument(AppText.Document.USER_AGREEMENT) })
                    TextButton(AppText.App.PRIVACY_POLICY_DISPLAY_LABEL, onClick = { onDocument(AppText.Document.PRIVACY_POLICY) })
                }
            }
        }
    }
}
