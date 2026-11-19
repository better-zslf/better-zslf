package io.github.betterzslf.ui.accountrecovery

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.betterzslf.AppGraph
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.accountrecovery.*
import io.github.betterzslf.feature.accountrecovery.ui.AccountRecoveryContent
import io.github.betterzslf.feature.accountrecovery.ui.RecoveryContentControls
import io.github.betterzslf.feature.accountrecovery.ui.RecoveryReceiptContent
import io.github.betterzslf.feature.accountrecovery.ui.RecoveryUiEvent
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.PasswordVisibilityIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler

/** Product binder: only this layer can see the graph, raw field values and confirmation tickets. */
@Composable
fun AccountRecoveryPage(graph: AppGraph, onBack: () -> Unit) {
    val session by graph.sessions.session.collectAsState()
    val epoch by graph.sessions.revision.collectAsState()
    // Deliberately outside the epoch subtree: our own acknowledged logout must not erase its receipt.
    var finalReceipt by remember(graph) { mutableStateOf<RecoveryLogout?>(null) }
    LaunchedEffect(session) { if (session != null) finalReceipt = null }
    val receipt = finalReceipt
    if (session == null && receipt != null) {
        RecoveryReceiptContent(cleanupWarning = receipt == RecoveryLogout.CLEAR_FAILED, onBack = onBack)
    } else key(graph, epoch) {
        val controller = remember(graph, epoch) {
            AccountRecoveryController(ApiAccountRecoverySource.capture(graph.api, graph.sessions), onAccepted = {
                // Not an ordinary read publication: a successful reset intentionally retired this epoch.
                if (it != RecoveryLogout.NEW_SESSION_PRESERVED && graph.sessions.session.value == null) finalReceipt = it
            })
        }
        DisposableEffect(controller) { onDispose { controller.close() } }
        AccountRecoveryBinder(graph, epoch, controller, onBack)
    }
}

@Composable
private fun AccountRecoveryBinder(graph: AppGraph, epoch: Long, controller: AccountRecoveryController, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val formCurrent: () -> Boolean = {
        scope.isActive && graph.applicationScope.isActive && !graph.clearingLocalData && graph.sessions.revision.value == epoch
    }
    var phone by remember(controller) { mutableStateOf("") }
    var code by remember(controller) { mutableStateOf("") }
    var password by remember(controller) { mutableStateOf("") }
    var confirmation by remember(controller) { mutableStateOf("") }
    var passwordVisible by remember(controller) { mutableStateOf(false) }
    val transformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation()
    val reveal: @Composable () -> Unit = {
        PasswordVisibilityIcon(passwordVisible, { if (formCurrent()) passwordVisible = !passwordVisible })
    }
    val dialogs = remember(controller) { RecoveryDialogSession(formCurrent, controller::isResetTicketCurrent) }
    var dialogRevision by remember(controller) { mutableIntStateOf(0) }
    DisposableEffect(dialogs) { onDispose { dialogs.close() } }
    fun invalidateDialogs() { dialogs.inputsChanged(); dialogRevision++ }
    fun clearInputs() {
        invalidateDialogs()
        phone = ""; code = ""; password = ""; confirmation = ""; passwordVisible = false
    }
    var backNotice by remember(controller) { mutableStateOf(false) }
    val currentBack by rememberUpdatedState(onBack)
    val guardedBack: () -> Unit = {
        // Arrow and predictive gesture commit read live state, never the frame at gesture start.
        if (recoveryBackDecision(controller.state.value) == RecoveryBackDecision.WAIT_FOR_RECEIPT) backNotice = true
        else currentBack()
    }

    LaunchedEffect(state.inputRevision) { clearInputs() }
    LaunchedEffect(state.phase) { if (state.terminal) clearInputs() }
    LaunchedEffect(state.verified) {
        if (!state.verified) { invalidateDialogs(); password = ""; confirmation = "" }
    }
    LaunchedEffect(controller, state.cooldownSeconds > 0) {
        while (controller.state.value.cooldownSeconds > 0) { delay(1000); controller.tickCooldown() }
    }
    LaunchedEffect(state.sending, state.phase) {
        if (recoveryBackDecision(state) == RecoveryBackDecision.ALLOW) backNotice = false
    }
    PredictiveBackHandler(enabled = true, onProgress = { events -> events.collect { } },
        onCommit = guardedBack, onCancel = {})

    // The holder remains pure; its revision explicitly invalidates the rendered event generation.
    val renderedGeneration = remember(dialogs, dialogRevision) { dialogs.inputGeneration }
    val displayedPhone = phone
    val displayedPreview = state.preview
    val controls = RecoveryContentControls(
        editable = formCurrent() && state.editable,
        canSend = formCurrent() && state.editable && state.cooldownSeconds == 0 && recoveryPhoneValidation(phone) == null,
        canVerify = formCurrent() && state.editable && !state.verified && state.codeRequested && code.isNotBlank(),
        canPrepare = formCurrent() && state.editable && state.verified,
        smsDialogOpen = dialogs.smsVisible(state), smsMaskedPhone = dialogs.smsMaskedPhone(state),
        resetDialogOpen = dialogs.resetVisible(state), resetMaskedPhone = dialogs.resetMaskedPhone(state),
        acknowledged = dialogs.acknowledged && dialogs.resetVisible(state), backNotice = backNotice,
    )
    AccountRecoveryContent(state, controls, onEvent = dispatch@{ event ->
        // A stale frame's zero-payload confirmation must not select a newer dialog or its ticket.
        if (event != RecoveryUiEvent.BACK && event != RecoveryUiEvent.DISMISS_BACK_NOTICE &&
            !dialogs.acceptsEventsFrom(renderedGeneration)) return@dispatch
        // Content emits no sensitive payload. Every irreversible operation is checked again here.
        when (event) {
            RecoveryUiEvent.BACK -> guardedBack()
            RecoveryUiEvent.DISMISS_BACK_NOTICE -> backNotice = false
            RecoveryUiEvent.OPEN_SMS_CONFIRMATION -> {
                if (formCurrent()) dialogs.openSms(displayedPhone, controller.state.value)
                dialogRevision++
            }
            RecoveryUiEvent.DISMISS_SMS_CONFIRMATION -> { dialogs.dismissSms(); dialogRevision++ }
            RecoveryUiEvent.CONFIRM_SMS -> {
                val captured = dialogs.takeSms(controller.state.value)
                dialogRevision++
                if (captured != null) {
                    code = ""; password = ""; confirmation = ""
                    scope.launch {
                        if (dialogs.smsCurrent(captured, controller.state.value)) controller.sendCode(captured.phone, confirmed = true)
                    }
                }
            }
            RecoveryUiEvent.VERIFY -> if (formCurrent() && controller.state.value.editable) {
                val phoneSnapshot = phone
                val codeSnapshot = code
                val inputGeneration = dialogs.inputGeneration
                scope.launch {
                    if (formCurrent() && dialogs.inputGeneration == inputGeneration) controller.verify(phoneSnapshot, codeSnapshot)
                }
            }
            RecoveryUiEvent.PREPARE_RESET -> if (formCurrent()) {
                dialogs.dismissReset(); dialogRevision++
                controller.prepare(phone, code, password, confirmation)
            }
            RecoveryUiEvent.EDIT_PREVIEW -> if (formCurrent()) {
                invalidateDialogs(); controller.editPreview()
            }
            RecoveryUiEvent.OPEN_RESET_CONFIRMATION -> if (formCurrent()) {
                dialogs.openReset(displayedPreview, { controller.state.value }, controller::captureResetTicket)
                dialogRevision++
            }
            RecoveryUiEvent.DISMISS_RESET_CONFIRMATION -> { dialogs.dismissReset(); dialogRevision++ }
            RecoveryUiEvent.TOGGLE_ACKNOWLEDGEMENT -> { dialogs.toggleAcknowledgement(controller.state.value); dialogRevision++ }
            RecoveryUiEvent.CONFIRM_RESET -> {
                val captured = dialogs.takeReset(controller.state.value)
                dialogRevision++
                if (captured != null) scope.launch {
                    if (dialogs.resetCurrent(captured, controller.state.value)) controller.confirm(captured.ticket, acknowledged = true)
                }
            }
        }
    }, phoneField = { enabled ->
        TextField(phone, { value ->
            if (formCurrent() && controller.state.value.editable) {
                invalidateDialogs(); phone = value; code = ""; password = ""; confirmation = ""
                controller.phoneChanged()
            }
        }, label = AppText.Common.MANUAL_PHONE_NUMBER_FIELD, singleLine = true, enabled = enabled,
            cornerRadius = AppUiTokens.FieldRadius, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth())
    }, codeField = { enabled ->
        TextField(code, { value ->
            if (formCurrent() && controller.state.value.editable && controller.state.value.codeRequested) {
                invalidateDialogs(); code = value; password = ""; confirmation = ""; controller.codeChanged()
            }
        }, label = AppText.Recovery.SMS_VERIFICATION_CODE_FIELD, singleLine = true, enabled = enabled,
            cornerRadius = AppUiTokens.FieldRadius, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth())
    }, passwordFields = { enabled ->
        TextField(password, { value ->
            if (formCurrent() && controller.state.value.editable) { invalidateDialogs(); password = value }
        }, label = AppText.Recovery.NEW_SCHOOL_LOGIN_PASSWORD_FIELD, enabled = enabled, singleLine = true,
            cornerRadius = AppUiTokens.FieldRadius, visualTransformation = transformation, trailingIcon = reveal,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        TextField(confirmation, { value ->
            if (formCurrent() && controller.state.value.editable) { invalidateDialogs(); confirmation = value }
        }, label = AppText.Recovery.REPEAT_SCHOOL_LOGIN_PASSWORD_FIELD, enabled = enabled, singleLine = true,
            cornerRadius = AppUiTokens.FieldRadius, visualTransformation = transformation, trailingIcon = reveal,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
    })
}
