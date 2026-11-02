package io.github.betterzslf.ui.campusnet

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.feature.campusnet.*
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.ConfirmationCheckRow
import io.github.betterzslf.ui.EmptyCard
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionTitle
import io.github.betterzslf.ui.UiTone
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Dorm-network link state as the user sees it. Absent wiring is surfaced, never faked. */
internal enum class CampusConnectionState { NotSignedIn, Offline, Connecting, Online, Failed, NotOnCampus }

/**
 * The connection card that sits above the broadband-account section.
 *
 * Dorm network sign-in is optional in this app's scope, so this card owns its own honest state:
 * today only the sign-in provider can be absent, and the card says so instead of showing a
 * disabled-looking "connect" button that would do nothing.
 */
@Composable
private fun CampusConnectionCard(
    state: CampusConnectionState,
    detail: String = "",
    onSignIn: (() -> Unit)? = null,
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val accent = when (state) {
        CampusConnectionState.NotSignedIn -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        CampusConnectionState.Offline -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        CampusConnectionState.Connecting -> MiuixTheme.colorScheme.primary
        CampusConnectionState.Online -> if (dark) AppUiTokens.PositiveDark else AppUiTokens.Positive
        CampusConnectionState.Failed -> if (dark) AppUiTokens.NegativeDark else AppUiTokens.Negative
        CampusConnectionState.NotOnCampus -> if (dark) AppUiTokens.WarningDark else AppUiTokens.Warning
    }
    val title = when (state) {
        CampusConnectionState.NotSignedIn -> AppText.CampusNetwork.STATUS_NOT_SIGNED_IN
        CampusConnectionState.Offline -> AppText.CampusNetwork.STATUS_OFFLINE
        CampusConnectionState.Connecting -> AppText.CampusNetwork.STATUS_CONNECTING
        CampusConnectionState.Online -> AppText.CampusNetwork.STATUS_ONLINE
        CampusConnectionState.Failed -> AppText.CampusNetwork.STATUS_FAILED
        CampusConnectionState.NotOnCampus -> AppText.CampusNetwork.STATUS_NOT_ON_CAMPUS
    }
    val defaultHint = when (state) {
        CampusConnectionState.NotSignedIn -> AppText.CampusNetwork.STATUS_NOT_SIGNED_IN_HINT
        CampusConnectionState.Offline -> AppText.CampusNetwork.STATUS_OFFLINE_HINT
        CampusConnectionState.Connecting -> AppText.CampusNetwork.STATUS_CONNECTING_HINT
        CampusConnectionState.Online -> ""
        CampusConnectionState.Failed -> ""
        CampusConnectionState.NotOnCampus -> AppText.CampusNetwork.STATUS_NOT_ON_CAMPUS_HINT
    }
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state == CampusConnectionState.Connecting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val hint = detail.ifBlank { defaultHint }
                if (hint.isNotBlank()) {
                    Text(hint, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            onSignIn?.let { open ->
                TextButton(text = AppText.CampusNetwork.ACTION_SIGN_IN, onClick = open,
                    minHeight = 36.dp, cornerRadius = AppUiTokens.ButtonRadius)
            }
        }
    }
}

/** Lead wires this entry into campusnet; creating the page never checks a phone or changes a password. */
@Composable
fun CampusNetworkFeaturePage(graph: AppGraph, onBack: (() -> Unit)?) {
    val session by graph.sessions.session.collectAsState()
    // A different owner OR token gets entirely new, unsaved inputs and an opaque request capability.
    key(session) {
        val source = remember(graph, session) { RepositoryCampusNetworkSource.capture(graph.repositories.campusNetwork) }
        val controller = remember(graph, source) { CampusNetworkController(source) }
        DisposableEffect(controller) { onDispose { controller.close() } }
        CampusNetworkPage(controller, onBack)
    }
}

@Composable
private fun CampusNetworkPage(controller: CampusNetworkController, onBack: (() -> Unit)?) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Deliberately not saveable: no phone, account, or password is prefilled or persisted.
    var phone by remember(controller) { mutableStateOf("") }
    var password by remember(controller) { mutableStateOf("") }
    var confirmation by remember(controller) { mutableStateOf("") }
    var finalDialog by remember(controller) { mutableStateOf(false) }
    var acknowledged by remember(state.preview?.id) { mutableStateOf(false) }
    val editable = state.expanded && !state.busy && !state.terminal && state.phase == CampusPasswordPhase.EDITING
    val selectable = state.expanded && !state.busy && !state.terminal && state.queryMessage == null
    val selected = state.accounts.firstOrNull { it.key == state.selectedAccountKey }

    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(state.inputRevision) { phone = ""; password = ""; confirmation = ""; finalDialog = false }
    LaunchedEffect(state.phase) {
        if (state.phase in setOf(CampusPasswordPhase.SUBMITTING, CampusPasswordPhase.COMPLETED,
                CampusPasswordPhase.FAILED, CampusPasswordPhase.UNCERTAIN, CampusPasswordPhase.SESSION_CHANGED)) {
            password = ""; confirmation = ""; finalDialog = false
            if (state.terminal) phone = ""
        }
    }

    Screen(AppText.CampusNetwork.CAMPUS_NETWORK_TITLE, onBack, actions = {
        TextButton(AppText.Common.REFRESH, enabled = !state.busy && state.phase != CampusPasswordPhase.SESSION_CHANGED,
            onClick = { scope.launch { controller.refresh() } })
    }) { padding ->
        val listPadding = io.github.betterzslf.ui.pageContentPadding(padding, io.github.betterzslf.ui.LocalNavigationBottomInset.current,
            androidx.compose.ui.platform.LocalLayoutDirection.current, AppUiTokens.FormPadding)
        PullToRefresh(state.loading, { scope.launch { controller.refresh() } },
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding), contentPadding = padding,
            refreshTexts = io.github.betterzslf.ui.appRefreshTexts()) {
            LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                item("connection") {
                    // The dorm-network sign-in provider is not wired yet (see the campus-network
                    // research notes). The card states that plainly rather than offering a button
                    // that cannot succeed.
                    CampusConnectionCard(CampusConnectionState.NotSignedIn)
                }
                item("scope") { InfoBanner(AppText.CampusNetwork.NOT_SCHOOL_LOGIN_PASSWORD_TITLE, tone = UiTone.Warning) }
                item("account-heading") { SectionTitle(AppText.CampusNetwork.PROVISIONED_BROADBAND_ACCOUNTS_TITLE) }
                item("account-scope") {
                    Text(AppText.CampusNetwork.MASKED_SESSION_ACCOUNT_QUERY_HINT,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
                }
                if (state.loading) item("loading") { LinearProgressIndicator() }
                state.queryMessage?.let { message -> item("query-error") {
                    InfoBanner(message + AppText.CampusNetwork.PREVIOUS_ACCOUNTS_RETAINED_REFRESH_SUFFIX,
                        title = AppText.CampusNetwork.ACCOUNT_QUERY_NOTICE, tone = UiTone.Warning)
                } }
                if (!state.loading && state.queryMessage == null && state.accounts.isEmpty() && state.phase != CampusPasswordPhase.SESSION_CHANGED)
                    item("empty") { EmptyCard(AppText.CampusNetwork.NO_PROVISIONED_BROADBAND_ACCOUNTS) }
                items(state.accounts, key = { it.key }) { account ->
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(AppUiTokens.CardPadding),
                        colors = CardDefaults.defaultColors(color = if (state.selectedAccountKey == account.key)
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.10f) else MiuixTheme.colorScheme.surfaceContainer)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(account.maskedAccount, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (account.typeStatusLabel.isNotBlank()) {
                                Text(account.typeStatusLabel, fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            if (!account.canChangePassword) {
                                InfoBanner(AppText.CampusNetwork.INVALID_ACCOUNT_IDS_READ_ONLY_HINT, tone = UiTone.Neutral)
                            }
                            if (state.expanded) TextButton(
                                if (state.selectedAccountKey == account.key) AppText.CampusNetwork.ACCOUNT_SELECTED else AppText.CampusNetwork.SELECT_THIS_ACCOUNT,
                                enabled = selectable && account.canChangePassword, modifier = Modifier.fillMaxWidth(),
                                minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                                onClick = { controller.selectAccount(account.key) })
                        }
                    }
                }
                item("operation-toggle") {
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius) {
                        ArrowPreference(AppText.CampusNetwork.CARRIER_PASSWORD_ACTIONS_TITLE,
                            summary = if (state.expanded) AppText.CampusNetwork.COLLAPSE_AND_CLEAR_PASSWORD_INPUT else AppText.CampusNetwork.MANUAL_EXPANSION_REQUIRED_HINT,
                            enabled = !state.busy && !state.terminal, holdDownState = state.expanded,
                            onClick = { controller.setExpanded(!state.expanded) })
                    }
                }
                if (state.expanded && !state.terminal) {
                    if (selected == null) item("choose") {
                        InfoBanner(AppText.CampusNetwork.QUERIED_VALID_ACCOUNT_REQUIRED_HINT,
                            title = AppText.CampusNetwork.SELECT_ACCOUNT_FIRST_TITLE, tone = UiTone.Info)
                    }
                    item("password-form") {
                        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                            insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                            Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                                TextField(phone, {
                                    phone = it; password = ""; confirmation = ""; finalDialog = false; controller.forgetPhoneCheck()
                                }, label = AppText.Common.MANUAL_PHONE_NUMBER_FIELD, singleLine = true,
                                    enabled = editable && selected?.canChangePassword == true, cornerRadius = AppUiTokens.FieldRadius,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                                TextButton(if (state.checkingPhone) AppText.Common.CHECKING else if (state.phoneChecked)
                                    AppText.CampusNetwork.PHONE_CHECK_PASSED else AppText.CampusNetwork.CHECK_PHONE_MAY_SEND_SMS,
                                    enabled = editable && !state.phoneChecked && selected?.canChangePassword == true && campusPhoneValidation(phone) == null,
                                    modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                                    cornerRadius = AppUiTokens.ButtonRadius,
                                    onClick = { scope.launch { controller.checkPhone(phone) } })
                                Text(AppText.CampusNetwork.CARRIER_ACCOUNT_PASSWORD_SCOPE_HINT, fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                if (state.phoneChecked) InfoBanner(AppText.CampusNetwork.PHONE_CHECK_PASSED_NOT_SMS_VERIFICATION_HINT, tone = UiTone.Positive)
                                // The next step is visible, but stays disabled until this exact phone check passes.
                                TextField(password, { password = it }, label = AppText.CampusNetwork.NEW_BROADBAND_PASSWORD_FIELD,
                                    singleLine = true, enabled = editable && state.phoneChecked, cornerRadius = AppUiTokens.FieldRadius,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                                TextField(confirmation, { confirmation = it }, label = AppText.CampusNetwork.CONFIRM_BROADBAND_PASSWORD_FIELD,
                                    singleLine = true, enabled = editable && state.phoneChecked, cornerRadius = AppUiTokens.FieldRadius,
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    item("prepare") {
                        TextButton(AppText.CampusNetwork.REVIEW_AND_PREPARE_PREVIEW, enabled = editable && state.phoneChecked,
                            modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                            cornerRadius = AppUiTokens.ButtonRadius, colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { controller.prepare(phone, password, confirmation) })
                    }
                }
                state.preview?.let { preview ->
                    item("preview") {
                        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                            insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                            Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                                SectionTitle(AppText.CampusNetwork.CURRENT_OPERATION_PREVIEW_TITLE)
                                Text(AppText.CampusNetwork.passwordPreview(preview.maskedAccount, preview.maskedPhone))
                                if (state.phase == CampusPasswordPhase.PREVIEW) {
                                    TextButton(AppText.Common.CONTINUE_TO_FINAL_CONFIRMATION, modifier = Modifier.fillMaxWidth(),
                                        minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                                        colors = ButtonDefaults.textButtonColorsPrimary(), onClick = { finalDialog = true })
                                    TextButton(AppText.Common.BACK_TO_EDIT, modifier = Modifier.fillMaxWidth(), onClick = { controller.editPreview() })
                                }
                            }
                        }
                    }
                }
                if (state.checkingPhone || state.phase == CampusPasswordPhase.SUBMITTING) item("operation-progress") { LinearProgressIndicator() }
                state.formMessage?.let { message -> item("form-message") {
                    InfoBanner(message, title = when (state.phase) {
                        CampusPasswordPhase.COMPLETED -> AppText.Common.SERVICE_RECEIPT_TITLE
                        CampusPasswordPhase.UNCERTAIN -> AppText.Common.RESULT_UNCONFIRMED_OPERATION_STOPPED
                        CampusPasswordPhase.FAILED -> AppText.CampusNetwork.CURRENT_OPERATION_STOPPED
                        CampusPasswordPhase.SESSION_CHANGED -> AppText.CampusNetwork.LOGIN_SESSION_CHANGED
                        else -> AppText.Common.OPERATION_NOTICE_TITLE
                    }, tone = when (state.phase) {
                        CampusPasswordPhase.COMPLETED -> UiTone.Positive
                        CampusPasswordPhase.FAILED -> UiTone.Negative
                        CampusPasswordPhase.UNCERTAIN, CampusPasswordPhase.SESSION_CHANGED -> UiTone.Warning
                        else -> UiTone.Info
                    })
                } }
                if (state.terminal) item("terminal") {
                    InfoBanner(AppText.CampusNetwork.REFRESH_ONLY_NO_PASSWORD_RETRY_HINT, tone = UiTone.Neutral)
                }
            }
        }
        OverlayDialog(show = finalDialog && state.phase == CampusPasswordPhase.PREVIEW,
            title = AppText.CampusNetwork.CARRIER_PASSWORD_FINAL_CONFIRM_TITLE,
            summary = AppText.CampusNetwork.passwordTarget((state.preview?.maskedAccount.orEmpty())),
            onDismissRequest = { finalDialog = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfirmationCheckRow(checked = acknowledged, label = AppText.CampusNetwork.SINGLE_SUBMISSION_ACKNOWLEDGEMENT,
                    onCheckedChange = { acknowledged = it })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(AppText.Common.BACK_TO_PREVIEW, modifier = Modifier.weight(1f), onClick = { finalDialog = false })
                    TextButton(AppText.CampusNetwork.CONFIRM_PASSWORD_CHANGE, enabled = acknowledged, modifier = Modifier.weight(1f), onClick = {
                        val id = state.preview?.id ?: return@TextButton
                        finalDialog = false
                        scope.launch { controller.confirm(id, acknowledged) }
                    })
                }
            }
        }
    }
}
