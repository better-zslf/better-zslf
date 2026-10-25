package io.github.betterzslf.ui.campusnet

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.feature.campusnet.*
import io.github.betterzslf.ui.EmptyCard
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Lead wires this entry into campusnet; creating the page never checks a phone or changes a password. */
@Composable
fun CampusNetworkFeaturePage(graph: AppGraph, onBack: () -> Unit) {
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
private fun CampusNetworkPage(controller: CampusNetworkController, onBack: () -> Unit) {
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
        PullToRefresh(state.loading, { scope.launch { controller.refresh() } },
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("intro") { SectionCard(AppText.CampusNetwork.PROVISIONED_BROADBAND_ACCOUNTS_TITLE, AppText.CampusNetwork.MASKED_SESSION_ACCOUNT_QUERY_HINT) }
                if (state.loading) item("loading") { CircularProgressIndicator() }
                state.queryMessage?.let { message -> item("query-error") {
                    SectionCard(AppText.CampusNetwork.ACCOUNT_QUERY_NOTICE, message + AppText.CampusNetwork.PREVIOUS_ACCOUNTS_RETAINED_REFRESH_SUFFIX)
                } }
                if (!state.loading && state.queryMessage == null && state.accounts.isEmpty() && state.phase != CampusPasswordPhase.SESSION_CHANGED)
                    item("empty") { EmptyCard(AppText.CampusNetwork.NO_PROVISIONED_BROADBAND_ACCOUNTS) }
                items(state.accounts, key = { it.key }) { account ->
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Text(AppText.CampusNetwork.accountLine((account.maskedAccount)))
                        if (account.typeStatus.isNotBlank()) Text(AppText.CampusNetwork.typeStatus((account.typeStatus)))
                        if (!account.canChangePassword) Text(AppText.CampusNetwork.INVALID_ACCOUNT_IDS_READ_ONLY_HINT)
                        if (state.expanded) TextButton(
                            if (state.selectedAccountKey == account.key) AppText.CampusNetwork.ACCOUNT_SELECTED else AppText.CampusNetwork.SELECT_THIS_ACCOUNT,
                            enabled = selectable && account.canChangePassword,
                            onClick = { controller.selectAccount(account.key) })
                    }
                }
                item("operation-toggle") {
                    SectionCard(AppText.CampusNetwork.CARRIER_PASSWORD_ACTIONS_TITLE, if (state.expanded) AppText.CampusNetwork.COLLAPSE_AND_CLEAR_PASSWORD_INPUT else AppText.CampusNetwork.MANUAL_EXPANSION_REQUIRED_HINT,
                        onClick = { controller.setExpanded(!state.expanded) })
                }
                if (state.expanded && !state.terminal) {
                    item("warning") { SectionCard(AppText.CampusNetwork.NOT_SCHOOL_LOGIN_PASSWORD_TITLE, AppText.CampusNetwork.CARRIER_ACCOUNT_PASSWORD_SCOPE_HINT) }
                    if (selected == null) item("choose") { SectionCard(AppText.CampusNetwork.SELECT_ACCOUNT_FIRST_TITLE, AppText.CampusNetwork.QUERIED_VALID_ACCOUNT_REQUIRED_HINT) }
                    item("phone") { TextField(phone, { phone = it; password = ""; confirmation = ""; controller.forgetPhoneCheck() },
                        label = AppText.Common.MANUAL_PHONE_NUMBER_FIELD, singleLine = true, enabled = editable && selected?.canChangePassword == true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth()) }
                    item("check-phone") { Button(enabled = editable && !state.phoneChecked && selected?.canChangePassword == true && campusPhoneValidation(phone) == null,
                        modifier = Modifier.fillMaxWidth(), onClick = { scope.launch { controller.checkPhone(phone) } }) {
                        Text(if (state.checkingPhone) AppText.Common.CHECKING else if (state.phoneChecked) AppText.CampusNetwork.PHONE_CHECK_PASSED else AppText.CampusNetwork.CHECK_PHONE_MAY_SEND_SMS)
                    } }
                    if (state.phoneChecked) {
                        item("new-password") { TextField(password, { password = it }, label = AppText.CampusNetwork.NEW_BROADBAND_PASSWORD_FIELD, singleLine = true,
                            enabled = editable, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
                        item("repeat-password") { TextField(confirmation, { confirmation = it }, label = AppText.CampusNetwork.CONFIRM_BROADBAND_PASSWORD_FIELD, singleLine = true,
                            enabled = editable, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
                        item("prepare") { Button(enabled = editable, modifier = Modifier.fillMaxWidth(),
                            onClick = { controller.prepare(phone, password, confirmation) }) { Text(AppText.CampusNetwork.REVIEW_AND_PREPARE_PREVIEW) } }
                    }
                }
                state.preview?.let { preview ->
                    item("preview") { SectionCard(AppText.CampusNetwork.CURRENT_OPERATION_PREVIEW_TITLE, AppText.CampusNetwork.passwordPreview((preview.maskedAccount), (preview.maskedPhone))) }
                    if (state.phase == CampusPasswordPhase.PREVIEW) item("final-actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(AppText.Common.BACK_TO_EDIT, modifier = Modifier.weight(1f), onClick = { controller.editPreview() })
                            TextButton(AppText.Common.CONTINUE_TO_FINAL_CONFIRMATION, modifier = Modifier.weight(1f), onClick = { finalDialog = true })
                        }
                    }
                }
                if (state.checkingPhone || state.phase == CampusPasswordPhase.SUBMITTING) item("operation-progress") { CircularProgressIndicator() }
                state.formMessage?.let { message -> item("form-message") {
                    SectionCard(when (state.phase) {
                        CampusPasswordPhase.COMPLETED -> AppText.Common.SERVICE_RECEIPT_TITLE
                        CampusPasswordPhase.UNCERTAIN -> AppText.Common.RESULT_UNCONFIRMED_OPERATION_STOPPED
                        CampusPasswordPhase.FAILED -> AppText.CampusNetwork.CURRENT_OPERATION_STOPPED
                        CampusPasswordPhase.SESSION_CHANGED -> AppText.CampusNetwork.LOGIN_SESSION_CHANGED
                        else -> AppText.Common.OPERATION_NOTICE_TITLE
                    }, message)
                } }
                if (state.terminal) item("terminal") { Text(AppText.CampusNetwork.REFRESH_ONLY_NO_PASSWORD_RETRY_HINT) }
            }
        }
        OverlayDialog(show = finalDialog && state.phase == CampusPasswordPhase.PREVIEW,
            title = AppText.CampusNetwork.CARRIER_PASSWORD_FINAL_CONFIRM_TITLE,
            summary = AppText.CampusNetwork.passwordTarget((state.preview?.maskedAccount.orEmpty())),
            onDismissRequest = { finalDialog = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(if (acknowledged) ToggleableState.On else ToggleableState.Off, onClick = { acknowledged = !acknowledged })
                    Text(AppText.CampusNetwork.SINGLE_SUBMISSION_ACKNOWLEDGEMENT, Modifier.weight(1f))
                }
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
