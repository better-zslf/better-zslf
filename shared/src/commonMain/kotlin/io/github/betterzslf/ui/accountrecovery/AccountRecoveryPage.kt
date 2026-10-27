package io.github.betterzslf.ui.accountrecovery

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.data.PasswordResetLogout
import io.github.betterzslf.feature.accountrecovery.*
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.ConfirmationCheckRow
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionTitle
import io.github.betterzslf.ui.UiTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AccountRecoveryPage(graph: AppGraph, onBack: () -> Unit) {
    val session by graph.sessions.session.collectAsState()
    // Only a non-sensitive outcome enum may outlive our own successful logout, never inputs/identity.
    var finalReceipt by remember(graph) { mutableStateOf<PasswordResetLogout?>(null) }
    LaunchedEffect(session) { if (session != null) finalReceipt = null }
    val receipt = finalReceipt
    if (session == null && receipt != null) {
        Screen(AppText.Recovery.SCHOOL_PASSWORD_RESET_RECEIPT_TITLE, onBack) { padding ->
            Column(Modifier.padding(padding).padding(AppUiTokens.FormPadding), verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                InfoBanner(title = if (receipt == PasswordResetLogout.CLEAR_FAILED) AppText.Recovery.SERVICE_SUCCEEDED_LOCAL_CLEANUP_NOTICE else AppText.Recovery.PASSWORD_RESET_CONFIRMED,
                    message = if (receipt == PasswordResetLogout.CLEAR_FAILED)
                        AppText.Recovery.PASSWORD_RESET_SUCCEEDED_SESSION_CLEANUP_FAILED_HINT
                    else AppText.Recovery.SESSION_EXITED_LOG_IN_WITH_NEW_PASSWORD_HINT,
                    tone = if (receipt == PasswordResetLogout.CLEAR_FAILED) UiTone.Warning else UiTone.Positive)
                TextButton(AppText.Auth.BACK_TO_LOGIN, onClick = onBack, modifier = Modifier.fillMaxWidth(),
                    minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    } else key(session) {
        val controller = remember(graph, session) {
            AccountRecoveryController(RepositoryAccountRecoverySource.capture(graph.repositories.auth), onAccepted = {
                if (graph.sessions.session.value == null) finalReceipt = it
            })
        }
        DisposableEffect(controller) { onDispose { controller.close() } }
        AccountRecoveryBody(controller, onBack)
    }
}

@Composable
private fun AccountRecoveryBody(controller: AccountRecoveryController, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var phone by remember(controller) { mutableStateOf("") }
    var code by remember(controller) { mutableStateOf("") }
    var password by remember(controller) { mutableStateOf("") }
    var confirmation by remember(controller) { mutableStateOf("") }
    var sendDialog by remember(controller) { mutableStateOf(false) }
    var resetDialog by remember(controller) { mutableStateOf(false) }
    var backNotice by remember(controller) { mutableStateOf(false) }
    val currentBack by rememberUpdatedState(onBack)
    val guardedBack: () -> Unit = {
        recoveryGuardedBack({ controller.state.value }, { currentBack() }, { backNotice = true })
    }
    var acknowledged by remember(state.preview?.id) { mutableStateOf(false) }
    val editable = state.phase == AccountRecoveryPhase.EDITING && !state.busy
    val sendEnabled = editable && state.cooldownSeconds == 0 && recoveryPhoneValidation(phone) == null

    LaunchedEffect(state.inputRevision) {
        phone = ""; code = ""; password = ""; confirmation = ""; sendDialog = false; resetDialog = false
    }
    LaunchedEffect(state.phase) {
        if (state.terminal) {
            phone = ""; code = ""; password = ""; confirmation = ""; sendDialog = false; resetDialog = false
        }
    }
    LaunchedEffect(state.verified) {
        if (!state.verified) { password = ""; confirmation = ""; resetDialog = false }
    }
    LaunchedEffect(controller, state.cooldownSeconds > 0) {
        while (controller.state.value.cooldownSeconds > 0) { delay(1000); controller.tickCooldown() }
    }
    LaunchedEffect(state.sending, state.phase) {
        if (!recoveryWriteInFlight(state)) backNotice = false
    }
    // Stay registered before a gesture starts; commit uses the current controller value even across frames.
    PredictiveBackHandler(
        enabled = true,
        onProgress = { events -> events.collect { } },
        onCommit = guardedBack,
        onCancel = {},
    )
    Screen(AppText.Recovery.RECOVER_SCHOOL_LOGIN_PASSWORD_TITLE, guardedBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = AppUiTokens.FormPadding, end = AppUiTokens.FormPadding,
                top = padding.calculateTopPadding() + AppUiTokens.FormPadding,
                bottom = padding.calculateBottomPadding() + AppUiTokens.FormPadding),
            verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
            item("scope") {
                InfoBanner(AppText.Recovery.SCHOOL_PASSWORD_RECOVERY_SCOPE_HINT,
                    tone = if (state.phase == AccountRecoveryPhase.GUEST) UiTone.Warning else UiTone.Info)
            }
            state.message?.let { message -> item("message") {
                InfoBanner(message, title = when (state.phase) {
                    AccountRecoveryPhase.GUEST -> AppText.Recovery.GUEST_OPERATIONS_DISABLED
                    AccountRecoveryPhase.UNCERTAIN -> AppText.Common.RESULT_UNCONFIRMED_OPERATION_STOPPED
                    AccountRecoveryPhase.COMPLETED -> AppText.Common.SERVICE_RECEIPT_TITLE
                    AccountRecoveryPhase.CLEANUP_WARNING -> AppText.Recovery.SERVICE_SUCCEEDED_LOCAL_CLEANUP_NOTICE
                    AccountRecoveryPhase.FAILED -> AppText.Recovery.CURRENT_ATTEMPT_STOPPED
                    AccountRecoveryPhase.SESSION_CHANGED -> AppText.Common.SESSION_CHANGED
                    else -> AppText.Common.OPERATION_NOTICE_TITLE
                }, tone = when (state.phase) {
                    AccountRecoveryPhase.COMPLETED -> UiTone.Positive
                    AccountRecoveryPhase.FAILED -> UiTone.Negative
                    AccountRecoveryPhase.GUEST, AccountRecoveryPhase.UNCERTAIN,
                    AccountRecoveryPhase.CLEANUP_WARNING, AccountRecoveryPhase.SESSION_CHANGED -> UiTone.Warning
                    else -> UiTone.Info
                })
            } }
            item("verification-heading") { SectionTitle(AppText.Recovery.PHONE_AND_CODE_SECTION_TITLE) }
            item("verification-form") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                        TextField(phone, {
                            phone = it; code = ""; password = ""; confirmation = ""; sendDialog = false; resetDialog = false
                            controller.phoneChanged()
                        }, label = AppText.Common.MANUAL_PHONE_NUMBER_FIELD, singleLine = true, enabled = editable,
                            cornerRadius = AppUiTokens.FieldRadius, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth())
                        TextField(code, {
                            code = it; password = ""; confirmation = ""; resetDialog = false; controller.codeChanged()
                        }, label = AppText.Recovery.SMS_VERIFICATION_CODE_FIELD, singleLine = true,
                            enabled = editable && state.codeRequested, cornerRadius = AppUiTokens.FieldRadius,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        TextButton(if (state.cooldownSeconds > 0) AppText.Recovery.cooldown(state.cooldownSeconds) else AppText.Recovery.REQUEST_SMS_CODE,
                            enabled = sendEnabled, modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                            cornerRadius = AppUiTokens.ButtonRadius, colors = ButtonDefaults.textButtonColorsPrimary(),
                            onClick = { sendDialog = true })
                        TextButton(if (state.verified) AppText.Recovery.CURRENT_VERIFICATION_COMPLETED else AppText.Recovery.VERIFY_CURRENT_PHONE_AND_CODE,
                            enabled = editable && !state.verified && state.codeRequested && code.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                            cornerRadius = AppUiTokens.ButtonRadius, onClick = {
                                val phoneSnapshot = phone; val codeSnapshot = code
                                scope.launch { controller.verify(phoneSnapshot, codeSnapshot) }
                            })
                    }
                }
            }
            if (state.cooldownSeconds > 0) item("countdown-note") {
                InfoBanner(AppText.Recovery.LOCAL_COUNTDOWN_NOT_DELIVERY_OR_EXPIRY_HINT, tone = UiTone.Neutral)
            }
            if (state.verified) {
                item("identity") {
                    InfoBanner(AppText.Recovery.VERIFIED_RESPONSE_IDENTITY_SCOPE_HINT,
                        title = AppText.Recovery.verifiedPhone(state.maskedPhone.orEmpty()), tone = UiTone.Positive)
                }
                item("password-heading") { SectionTitle(AppText.Recovery.NEW_PASSWORD_SECTION_TITLE) }
                item("password-form") {
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                            TextField(password, { password = it }, label = AppText.Recovery.NEW_SCHOOL_LOGIN_PASSWORD_FIELD,
                                enabled = editable, singleLine = true, cornerRadius = AppUiTokens.FieldRadius,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                            TextField(confirmation, { confirmation = it }, label = AppText.Recovery.REPEAT_SCHOOL_LOGIN_PASSWORD_FIELD,
                                enabled = editable, singleLine = true, cornerRadius = AppUiTokens.FieldRadius,
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                            Text(AppText.Recovery.PASSWORD_RULES_AND_MEMORY_ONLY_HINT,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
                item("prepare") {
                    TextButton(AppText.Recovery.REVIEW_SCHOOL_PASSWORD_RESET_PREVIEW, enabled = editable,
                        modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                        cornerRadius = AppUiTokens.ButtonRadius, colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = { controller.prepare(phone, code, password, confirmation) })
                }
            }
            state.preview?.let { preview -> item("preview") {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                        SectionTitle(AppText.Recovery.RESET_PREVIEW_TITLE)
                        Text(AppText.Recovery.resetPreview(preview.maskedPhone))
                        if (state.phase == AccountRecoveryPhase.PREVIEW) {
                            TextButton(AppText.Common.CONTINUE_TO_FINAL_CONFIRMATION, modifier = Modifier.fillMaxWidth(),
                                minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                                colors = ButtonDefaults.textButtonColorsPrimary(), onClick = { resetDialog = true })
                            TextButton(AppText.Common.BACK_TO_EDIT, modifier = Modifier.fillMaxWidth(), onClick = { controller.editPreview() })
                        }
                    }
                }
            } }
            if (state.busy) item("busy") { LinearProgressIndicator() }
        }
        OverlayDialog(show = backNotice && recoveryWriteInFlight(state), title = AppText.Recovery.AWAITING_SERVICE_RECEIPT,
            summary = RECOVERY_WRITE_BACK_NOTICE, onDismissRequest = { backNotice = false }) {
            TextButton(AppText.Common.CONTINUE_WAITING, modifier = Modifier.fillMaxWidth(), onClick = { backNotice = false })
        }
        OverlayDialog(show = sendDialog && sendEnabled, title = AppText.Recovery.SEND_SMS_CONFIRM_TITLE,
            summary = AppText.Recovery.sendCodeConfirmation((if (phone.length == 11) phone.take(3) + "••••" + phone.takeLast(2) else AppText.Recovery.CURRENT_PHONE_FALLBACK_LABEL)),
            onDismissRequest = { sendDialog = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(AppText.Common.CANCEL, modifier = Modifier.weight(1f), onClick = { sendDialog = false })
                TextButton(AppText.Recovery.CONFIRM_SMS_REQUEST, modifier = Modifier.weight(1f), onClick = {
                    val phoneSnapshot = phone; sendDialog = false
                    code = ""; password = ""; confirmation = ""
                    scope.launch { controller.sendCode(phoneSnapshot, confirmed = true) }
                })
            }
        }
        OverlayDialog(show = resetDialog && state.phase == AccountRecoveryPhase.PREVIEW,
            title = AppText.Recovery.RESET_PASSWORD_AND_LOG_OUT_CONFIRM_TITLE,
            summary = AppText.Recovery.resetConfirmation((state.preview?.maskedPhone.orEmpty())),
            onDismissRequest = { resetDialog = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfirmationCheckRow(checked = acknowledged, label = AppText.Recovery.RESET_PASSWORD_AND_LOG_OUT_ACKNOWLEDGEMENT,
                    onCheckedChange = { acknowledged = it })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(AppText.Common.BACK_TO_PREVIEW, modifier = Modifier.weight(1f), onClick = { resetDialog = false })
                    TextButton(AppText.Recovery.FINAL_CONFIRM_PASSWORD_RESET, enabled = acknowledged, modifier = Modifier.weight(1f), onClick = {
                        val id = state.preview?.id ?: return@TextButton
                        val agreed = acknowledged; resetDialog = false
                        scope.launch { controller.confirm(id, agreed) }
                    })
                }
            }
        }
    }
}
