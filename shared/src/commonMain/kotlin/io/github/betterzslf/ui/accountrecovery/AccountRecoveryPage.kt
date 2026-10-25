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
import io.github.betterzslf.ui.ConfirmationCheckRow
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun AccountRecoveryPage(graph: AppGraph, onBack: () -> Unit) {
    val session by graph.sessions.session.collectAsState()
    // Only a non-sensitive outcome enum may outlive our own successful logout, never inputs/identity.
    var finalReceipt by remember(graph) { mutableStateOf<PasswordResetLogout?>(null) }
    LaunchedEffect(session) { if (session != null) finalReceipt = null }
    val receipt = finalReceipt
    if (session == null && receipt != null) {
        Screen(AppText.Recovery.SCHOOL_PASSWORD_RESET_RECEIPT_TITLE, onBack) { padding ->
            Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard(if (receipt == PasswordResetLogout.CLEAR_FAILED) AppText.Recovery.SERVICE_SUCCEEDED_LOCAL_CLEANUP_NOTICE else AppText.Recovery.PASSWORD_RESET_CONFIRMED,
                    if (receipt == PasswordResetLogout.CLEAR_FAILED)
                        AppText.Recovery.PASSWORD_RESET_SUCCEEDED_SESSION_CLEANUP_FAILED_HINT
                    else AppText.Recovery.SESSION_EXITED_LOG_IN_WITH_NEW_PASSWORD_HINT)
                TextButton(AppText.Auth.BACK_TO_LOGIN, onClick = onBack)
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
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("scope") { SectionCard(AppText.Recovery.RESET_SCHOOL_LOGIN_PASSWORD_TITLE, AppText.Recovery.SCHOOL_PASSWORD_RECOVERY_SCOPE_HINT) }
            state.message?.let { item("message") { SectionCard(when (state.phase) {
                AccountRecoveryPhase.GUEST -> AppText.Recovery.GUEST_OPERATIONS_DISABLED
                AccountRecoveryPhase.UNCERTAIN -> AppText.Common.RESULT_UNCONFIRMED_OPERATION_STOPPED
                AccountRecoveryPhase.COMPLETED -> AppText.Common.SERVICE_RECEIPT_TITLE
                AccountRecoveryPhase.CLEANUP_WARNING -> AppText.Recovery.SERVICE_SUCCEEDED_LOCAL_CLEANUP_NOTICE
                AccountRecoveryPhase.FAILED -> AppText.Recovery.CURRENT_ATTEMPT_STOPPED
                AccountRecoveryPhase.SESSION_CHANGED -> AppText.Common.SESSION_CHANGED
                else -> AppText.Common.OPERATION_NOTICE_TITLE
            }, it) } }
            item("phone") { TextField(phone, {
                phone = it; code = ""; password = ""; confirmation = ""; sendDialog = false; resetDialog = false
                controller.phoneChanged()
            }, label = AppText.Common.MANUAL_PHONE_NUMBER_FIELD, singleLine = true, enabled = editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth()) }
            item("send") { Button(enabled = sendEnabled, modifier = Modifier.fillMaxWidth(), onClick = { sendDialog = true }) {
                Text(if (state.cooldownSeconds > 0) AppText.Recovery.cooldown((state.cooldownSeconds)) else AppText.Recovery.REQUEST_SMS_CODE)
            } }
            if (state.cooldownSeconds > 0) item("countdown-note") { Text(AppText.Recovery.LOCAL_COUNTDOWN_NOT_DELIVERY_OR_EXPIRY_HINT) }
            item("code") { TextField(code, {
                code = it; password = ""; confirmation = ""; resetDialog = false; controller.codeChanged()
            }, label = AppText.Recovery.SMS_VERIFICATION_CODE_FIELD, singleLine = true, enabled = editable && state.codeRequested,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()) }
            item("verify") { Button(enabled = editable && !state.verified && state.codeRequested && code.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                val phoneSnapshot = phone; val codeSnapshot = code
                scope.launch { controller.verify(phoneSnapshot, codeSnapshot) }
            }) { Text(if (state.verified) AppText.Recovery.CURRENT_VERIFICATION_COMPLETED else AppText.Recovery.VERIFY_CURRENT_PHONE_AND_CODE) } }
            if (state.verified) {
                item("identity") { SectionCard(AppText.Recovery.verifiedPhone((state.maskedPhone.orEmpty())), AppText.Recovery.VERIFIED_RESPONSE_IDENTITY_SCOPE_HINT) }
                item("password") { TextField(password, { password = it }, label = AppText.Recovery.NEW_SCHOOL_LOGIN_PASSWORD_FIELD, enabled = editable,
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
                item("confirmation") { TextField(confirmation, { confirmation = it }, label = AppText.Recovery.REPEAT_SCHOOL_LOGIN_PASSWORD_FIELD, enabled = editable,
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
                item("policy") { Text(AppText.Recovery.PASSWORD_RULES_AND_MEMORY_ONLY_HINT) }
                item("prepare") { Button(enabled = editable, modifier = Modifier.fillMaxWidth(), onClick = {
                    controller.prepare(phone, code, password, confirmation)
                }) { Text(AppText.Recovery.REVIEW_SCHOOL_PASSWORD_RESET_PREVIEW) } }
            }
            state.preview?.let { preview -> item("preview") {
                SectionCard(AppText.Recovery.RESET_PREVIEW_TITLE, AppText.Recovery.resetPreview((preview.maskedPhone)))
                if (state.phase == AccountRecoveryPhase.PREVIEW) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(AppText.Common.BACK_TO_EDIT, modifier = Modifier.weight(1f), onClick = { controller.editPreview() })
                    TextButton(AppText.Common.CONTINUE_TO_FINAL_CONFIRMATION, modifier = Modifier.weight(1f), onClick = { resetDialog = true })
                }
            } }
            if (state.busy) item("busy") { CircularProgressIndicator() }
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
