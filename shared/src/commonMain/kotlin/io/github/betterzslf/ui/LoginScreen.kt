package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.composition.ContributedOfflineEntry
import io.github.betterzslf.ui.auth.AuthChooserContent
import io.github.betterzslf.ui.auth.AuthChooserDisplay
import io.github.betterzslf.ui.auth.AuthLoginUiEvent
import io.github.betterzslf.ui.auth.CampusLoginBinder
import top.yukonga.miuix.kmp.nav.core.NavKey
import io.github.betterzslf.feature.campusnet.CampusDiagnosisController
import io.github.betterzslf.feature.campusnet.CampusDiagnosisControllerHolder
import io.github.betterzslf.feature.campusnet.CampusDiagnosisHandshake
import io.github.betterzslf.feature.campusnet.CampusDiagnosisProbe
import io.github.betterzslf.feature.campusnet.DORM_ANDROID_USER_AGENT
import io.github.betterzslf.feature.campusnet.DormSignInController
import io.github.betterzslf.feature.campusnet.DormSignInPhase
import io.github.betterzslf.feature.campusnet.DormXmlFields
import io.github.betterzslf.feature.campusnet.campusAlgoStatusText
import io.github.betterzslf.feature.campusnet.newDormClientId
import io.github.betterzslf.feature.campusnet.recordDormSchoolIdentity
import io.github.betterzslf.feature.campusnet.CampusOutcome
import io.github.betterzslf.feature.campusnet.RepositoryCampusPortalSource
import io.github.betterzslf.feature.campusnet.RepositoryCampusProvisionedPhoneSource
import io.github.betterzslf.feature.campusnet.campusProvisionedPhonePrefix
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LoginScreen(graph: AppGraph, onDocument: (String) -> Unit,
                extraEntry: ContributedOfflineEntry? = null, onExtraEntry: (NavKey) -> Unit = {},
                onResetPassword: (() -> Unit)? = null, onEnter: () -> Unit = {}) {
    // The login surface is a two-step flow: first choose which account to sign in with, then the
    // form for that choice. Dormitory network is a secondary option, so it stays a small link
    // rather than a third card. Anything that signs in ends up in the same session gate.
    //
    // This root binder keeps only what never crosses into the feature content: the saveable
    // stage, the raw credential drafts and the reveal flag (all mounted outside the stage
    // switch, exactly as before), and the chooser's page routing. The campus form's controller,
    // job and banner now live in CampusLoginBinder; the chooser and campus layouts are the
    // feature content's, which reports typed intents back here.
    val epoch by graph.sessions.revision.collectAsState()
    var stage by rememberSaveable { mutableStateOf(LoginStage.Chooser) }
    var username by rememberSaveable(graph, epoch) { mutableStateOf("") }
    // Do not key the chooser/Dorm subtree: its independent carrier session must stay mounted.
    var password by remember(graph, epoch) { mutableStateOf("") }
    var visible by remember(graph, epoch) { mutableStateOf(false) }
    when (stage) {
        LoginStage.Chooser -> AuthChooserContent(
            display = AuthChooserDisplay(
                showDormLink = true,
                campusBound = graph.sessions.session.collectAsState().value != null,
                academicBound = graph.schedule.state.collectAsState().value.boundAccount != null,
            ),
            onEvent = chooser@{ event -> when (event) {
                AuthLoginUiEvent.CHOOSE_CAMPUS -> stage = LoginStage.Campus
                AuthLoginUiEvent.CHOOSE_ACADEMIC -> stage = LoginStage.Academic
                AuthLoginUiEvent.CHOOSE_DORM -> stage = LoginStage.Dorm
                AuthLoginUiEvent.ENTER_APP -> onEnter()
                AuthLoginUiEvent.USER_AGREEMENT -> onDocument(AppText.Document.USER_AGREEMENT)
                AuthLoginUiEvent.PRIVACY_POLICY -> onDocument(AppText.Document.PRIVACY_POLICY)
                else -> {}
            } },
        )
        LoginStage.Campus -> CampusLoginBinder(
            graph = graph,
            mountEpoch = epoch,
            username = username,
            onUsername = { username = it },
            password = password,
            onPassword = { password = it },
            visible = visible,
            onVisible = { visible = it },
            extraEntry = extraEntry,
            onExtraEntry = onExtraEntry,
            onResetPassword = onResetPassword,
            onDocument = onDocument,
            onBack = { stage = LoginStage.Chooser },
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
/**
 * N2, the dorm-network sign-in page.
 *
 * Reachable from two places on purpose: the login chooser (before entering the app) and the campus
 * tab's "去登录" action (after). The design draws it as a login-time step, but §5.4 also gives the
 * campus page a button into it, so it cannot live only inside the login flow.
 */
@Composable
internal fun DormNetworkPage(graph: AppGraph, onBack: () -> Unit) {
    val session by graph.sessions.session.collectAsState()
    val epoch by graph.sessions.revision.collectAsState()
    // Campus phone provenance changes with its epoch; carrier credentials/session do not.
    val campusBound = session != null
    var manualPhone by remember(graph, epoch) { mutableStateOf("") }
    // Independent dorm credentials never participate in campus login keys or saved state.
    var broadbandPassword by remember(graph) { mutableStateOf("") }
    var passwordVisible by remember(graph) { mutableStateOf(false) }
    val dormScope = rememberCoroutineScope()
    // The portal addresses and school identity are only obtainable on campus, and the diagnosis run
    // is what captures them. Sharing one state here means the sign-in button uses exactly what the
    // probe observed instead of asking the user to copy it.
    val sharedPortal = remember(graph) { CampusDiagnosisControllerHolder() }
    val dorm = remember(graph) {
        DormSignInController(
            transport = graph.dormTransport,
            fields = {
                // A fresh client id per attempt: it identifies this attempt, not the user.
                DormXmlFields(
                    userAgent = DORM_ANDROID_USER_AGENT,
                    clientId = newDormClientId(),
                    localTime = "",
                    ipv4 = "",
                    gwip = "",
                )
            },
            portalAddress = { sharedPortal.observed },
            schoolIdentity = { sharedPortal.identity },
            logoutStore = graph.dormLogoutStore,
            probeHasRun = { sharedPortal.probed },
        )
    }
    DisposableEffect(dorm) { onDispose { dorm.close() } }
    val dormState by dorm.state.collectAsState()

    // If a previous run left a session behind, sign it out before the user can start another. The
    // gateway refuses a second login from the same account as "already signed in elsewhere", so
    // skipping this makes the first attempt fail for a reason the user cannot act on.
    LaunchedEffect(dorm) { dorm.signOutPreviousSession() }

    // Without this the session looks connected and then silently lapses: the gateway drops a
    // client that stops heartbeating, and the user would see "connected" while offline. The
    // interval comes from the gateway (keep-retry), so it is read fresh on every pass rather than
    // captured once.
    LaunchedEffect(dorm) {
        snapshotFlow { dorm.state.value.phase }
            .collectLatest { phase ->
                if (phase != DormSignInPhase.Connected) return@collectLatest
                while (true) {
                    delay(dorm.keepAliveMillis.takeIf { it > 0 } ?: 300_000L)
                    if (dorm.state.value.phase != DormSignInPhase.Connected) break
                    dorm.heartbeat()
                }
            }
    }
    var taken by remember(graph, epoch) { mutableStateOf<String?>(null) }
    var queryFailed by remember(graph, epoch) { mutableStateOf(false) }
    val editable = taken == null
    val campusRead = remember(graph, epoch) { SessionEpochReadFence(epoch, { graph.sessions.revision.value }) }
    DisposableEffect(campusRead) { onDispose { campusRead.close() } }

    // A campus read never owns the independent carrier controller or its heartbeat timer.
    LaunchedEffect(graph, epoch, campusBound) {
        if (!campusBound) {
            taken = null
            queryFailed = false
            return@LaunchedEffect
        }
        val bound = RepositoryCampusProvisionedPhoneSource.capture(graph.repositories.campusNetwork)
        if (bound == null) {
            taken = null
            queryFailed = true
            return@LaunchedEffect
        }
        val response = campusRead.readCurrent { bound.readProvisionedPhone() }
        if (!campusRead.isCurrent() || !bound.isCurrentSession()) return@LaunchedEffect
        when (response) {
            is CampusOutcome.Success -> {
                // A successful empty projection keeps manual entry available; it is not a read failure.
                taken = response.data
                queryFailed = false
            }
            is CampusOutcome.Failure -> {
                taken = null
                queryFailed = true
            }
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
                AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
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
                        // Never pre-filled and never restored: the design forbids remembering it.
                        // S01: Miuix 0.9.4 TextField does have a trailingIcon slot, so the reveal
                        // control lives inside the field instead of taking a whole extra row.
                        TextField(
                            value = broadbandPassword,
                            onValueChange = { broadbandPassword = it },
                            label = AppText.CampusNetwork.DORM_BROADBAND_PASSWORD_FIELD,
                            singleLine = true,
                            // Editable only while idle: once an attempt is running or a session is
                            // up, changing it mid-flight would desynchronise the UI from reality.
                            enabled = dormState.phase != DormSignInPhase.Running &&
                                dormState.phase != DormSignInPhase.Connected,
                            cornerRadius = AppUiTokens.FieldRadius,
                            visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                            trailingIcon = {
                                PasswordVisibilityIcon(passwordVisible, { passwordVisible = !passwordVisible },
                                    modifier = Modifier.testTag("dormPasswordVisibility"))
                            },
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
                // The dialer is wired now, so the primary action is real. It still says plainly
                // when it cannot work (no campus network, unsupported cipher) instead of failing
                // silently on a rate-limited gateway.
                val phase = dormState.phase
                val running = phase == DormSignInPhase.Running
                val connected = phase == DormSignInPhase.Connected
                TextButton(
                    text = when {
                        running -> AppText.CampusNetwork.DORM_SIGNING_IN
                        connected -> AppText.CampusNetwork.DORM_SIGN_OUT_ACTION
                        else -> AppText.CampusNetwork.DORM_SIGN_IN_ACTION
                    },
                    enabled = !running && (connected || (taken ?: manualPhone).length == 11),
                    modifier = Modifier.fillMaxWidth().testTag("dormSignIn"),
                    minHeight = AppUiTokens.ButtonHeight,
                    cornerRadius = AppUiTokens.ButtonRadius,
                    colors = primaryTextActionColors(),
                    onClick = {
                        // A stale campus-derived phone is never admitted, but connected dorm logout
                        // remains independent of the campus token.
                        if (!connected && !campusRead.isCurrent()) return@TextButton
                        val phone = taken ?: manualPhone
                        dormScope.launch {
                            if (connected) dorm.signOut()
                            else if (campusRead.isCurrent()) dorm.signIn(phone, broadbandPassword)
                        }
                    },
                )
                if (dormState.detail.isNotBlank()) {
                    InfoBanner(dormState.detail, tone = when (phase) {
                        DormSignInPhase.Connected -> UiTone.Positive
                        DormSignInPhase.Failed, DormSignInPhase.Unsupported -> UiTone.Negative
                        else -> UiTone.Warning
                    })
                }
            }
            item { CampusDiagnosisPanel(graph, sharedPortal, onOpenUrl = graph.platform::openPortalGateway) }
            item {
                Text(AppText.CampusNetwork.NOT_SCHOOL_LOGIN_PASSWORD_TITLE, fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

/**
 * On-campus diagnosis panel.
 *
 * The carrier dialer is blocked on one value the gateway only issues on campus (`algo_id`), plus the
 * school identity it demands alongside it. Asking the user to run `curl` would be a poor trade when
 * the app is already on the network, so this panel does the two reads and shows the raw result to
 * copy. It is explicitly read-only: no account, no password, no retry.
 */
@Composable
private fun CampusDiagnosisPanel(
    graph: AppGraph,
    shared: CampusDiagnosisControllerHolder,
    onOpenUrl: (String) -> Boolean,
) {
    val epoch by graph.sessions.revision.collectAsState()
    key(graph, epoch) { CampusDiagnosisBody(graph, epoch, shared, onOpenUrl) }
}

@Composable
private fun CampusDiagnosisBody(
    graph: AppGraph,
    epoch: Long,
    shared: CampusDiagnosisControllerHolder,
    onOpenUrl: (String) -> Boolean,
) {
    val scope = rememberCoroutineScope()
    val bound = remember(graph, epoch) { RepositoryCampusPortalSource.capture(graph.repositories.campusPortal) }
    val fence = remember(graph, epoch, bound) { SessionEpochReadFence(epoch, { graph.sessions.revision.value },
        { bound?.isCurrentSession() == true }) }
    // The legacy diagnosis controller has no close method; retire its external capabilities and
    // composition scope instead, without touching the independent dorm controller/holder.
    DisposableEffect(fence) { onDispose { fence.close() } }
    val controller = remember(graph, epoch, fence) {
        CampusDiagnosisController(
            portalAddress = {
                if (bound == null) null else fence.readCurrent {
                    when (val response = bound.redirectTarget()) {
                        is CampusOutcome.Success -> response.data.address
                        is CampusOutcome.Failure -> null
                    }
                }
            },
            probe = CampusDiagnosisProbe { target -> fence.readCurrent { graph.portalProbe.probe(target) } },
            handshake = CampusDiagnosisHandshake { server, address, schoolId, domain, area ->
                fence.readCurrent { graph.dormHandshake.negotiate(server, address, schoolId, domain, area) }
            },
        )
    }
    val state by controller.state.collectAsState()
    val clipboard = graph.platform
    var copied by remember(controller) { mutableStateOf(false) }
    var copyAttempted by remember(controller) { mutableStateOf(false) }

    // Publish what the probe observed so the sign-in button can use it without a second probe.
    LaunchedEffect(state.diagnosis.probedUrl, state.diagnosis.portalAddress) {
        val d = state.diagnosis
        if (fence.isCurrent() && d.probedUrl.isNotBlank()) {
            shared.record(d.probedUrl, d.schoolId, d.domain, d.area)
            recordDormSchoolIdentity(d.schoolId, d.domain, d.area)
        }
    }

    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
            Text(AppText.CampusNetwork.DIAGNOSIS_TITLE, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(AppText.CampusNetwork.DIAGNOSIS_HINT, fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextButton(
                text = if (state.running) AppText.CampusNetwork.DIAGNOSIS_RUNNING
                else AppText.CampusNetwork.DIAGNOSIS_RUN,
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth().testTag("diagnosisRun"),
                minHeight = AppUiTokens.ButtonHeight,
                cornerRadius = AppUiTokens.ButtonRadius,
                onClick = { if (fence.isCurrent()) { copied = false; scope.launch {
                    if (fence.isCurrent()) controller.run()
                } } },
            )
            val diagnosis = state.diagnosis
            if (state.hasResult) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    DiagnosisRow(AppText.CampusNetwork.DIAGNOSIS_PORTAL_LABEL, diagnosis.portalAddress)
                    DiagnosisRow(AppText.CampusNetwork.DIAGNOSIS_PROBED_LABEL, diagnosis.probedUrl)
                    DiagnosisRow(AppText.CampusNetwork.DIAGNOSIS_SCHOOL_LABEL, diagnosis.schoolId)
                    DiagnosisRow(AppText.CampusNetwork.DIAGNOSIS_ALGO_LABEL, diagnosis.algoId)
                    // The gateway's raw code, only when there is one to show.
                    DiagnosisRow(AppText.CampusNetwork.DIAGNOSIS_ERROR_LABEL,
                        diagnosis.errorCode.takeIf { it.isNotBlank() && it != "-" }.orEmpty())
                }
            }
            if (state.message.isNotBlank()) {
                InfoBanner(state.message, tone = UiTone.Warning)
            }
            if (state.hasResult) {
                // Only when there is something to conclude. "Not run" belongs to the message banner,
                // which explains why (no campus portal, no identity, and so on).
                val status = campusAlgoStatusText(diagnosis)
                if (diagnosis.hasAlgoId || diagnosis.errorCode.isNotBlank()) {
                    Text(status, fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                // Nothing was read, so there is nothing worth copying: hide the whole report block
                // instead of offering an empty string to paste back.
                if (diagnosis.hasAnyReading()) {
                    Text(diagnosis.reportLine(), fontSize = 11.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.testTag("diagnosisReport"))
                    TextButton(
                        text = if (copied) AppText.CampusNetwork.DIAGNOSIS_COPIED
                        else AppText.CampusNetwork.DIAGNOSIS_COPY,
                        modifier = Modifier.fillMaxWidth(),
                        minHeight = 40.dp, cornerRadius = AppUiTokens.ButtonRadius,
                        onClick = { if (fence.isCurrent()) {
                            copyAttempted = true; copied = clipboard.copyToClipboard(diagnosis.reportLine())
                        } },
                    )
                    // Only after an attempt, so it cannot appear before the user has tried.
                    if (copyAttempted && !copied) {
                        Text(AppText.CampusNetwork.DIAGNOSIS_COPY_FAILED, fontSize = 11.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            // The portal itself is the fallback when the identity headers are missing.
            diagnosis.portalAddress.takeIf { it.isNotBlank() }?.let { address ->
                TextButton(AppText.CampusNetwork.OPEN_PORTAL_ACTION, modifier = Modifier.fillMaxWidth(),
                    minHeight = 40.dp, cornerRadius = AppUiTokens.ButtonRadius,
                    onClick = { if (fence.isCurrent()) onOpenUrl(address) })
            }
        }
    }
}

/** The label/value line the diagnosis report is built from. */
@Composable
private fun DiagnosisRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 11.sp, maxLines = 2,
            modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The phone prefix of a broadband account.
 *
 * 掌上莲峰 returns accounts shaped `13800138000@163.gd`; the dorm dialer wants only the part before
 * `@`. Anything that is not an 11-digit mobile number yields `null`, which sends the page to its
 * manual-entry state instead of dialling with a value we cannot trust.
 */
internal fun dormPhonePrefixOf(account: String): String? = campusProvisionedPhonePrefix(account)

/** `138****8000`, the same masking the rest of the app uses. Never shows the middle digits. */
internal fun maskDormPhone(phone: String): String =
    if (phone.length == 11) phone.take(3) + "****" + phone.takeLast(4) else phone

