package io.github.betterzslf.ui.campusnet

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.github.betterzslf.AppGraph
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.campusnet.*
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.PasswordVisibilityIcon
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.TextField

/** The independently owned carrier route is not keyed by the campus session epoch. */
@Composable
fun DormNetworkFeaturePage(graph: AppGraph, onBack: () -> Unit) {
    io.github.betterzslf.ui.DormNetworkPage(graph, onBack)
}

/** Product binding only: fixed epoch sources/controllers, raw input slots, effects and platform actions. */
@Composable
fun CampusNetworkFeaturePage(
    graph: AppGraph,
    onBack: (() -> Unit)?,
    onOpenDormSignIn: (() -> Unit)? = null,
) {
    val session by graph.sessions.session.collectAsState()
    val epoch by graph.sessions.revision.collectAsState()
    key(graph, epoch) {
        val source = remember(graph, epoch) { RepositoryCampusNetworkSource.capture(graph.repositories.campusNetwork) }
        val controller = remember(graph, source) { CampusNetworkController(source) }
        val portalSource = remember(graph, epoch) { RepositoryCampusPortalSource.capture(graph.repositories.campusPortal) }
        val probe = remember(graph) { CampusPortalProbeSource { graph.portalProbe.probe(it) } }
        val portal = remember(graph, portalSource) {
            CampusPortalController(portalSource, probe) { kotlin.time.Clock.System.now().toEpochMilliseconds() }
        }
        var mounted by remember(controller, portal) { mutableStateOf(true) }
        val accountLive = remember(graph, epoch, source, controller) {
            { mounted && graph.sessions.revision.value == epoch && source?.isCurrentSession() == true }
        }
        val portalLive = remember(graph, epoch, portalSource, portal) {
            { mounted && graph.sessions.revision.value == epoch && portalSource?.isCurrentSession() == true }
        }
        val gate = remember(controller) { CampusPasswordConfirmationGate(accountLive, controller::captureConfirmationId) }
        DisposableEffect(controller, portal, gate) {
            onDispose { mounted = false; gate.retire(); controller.close(); portal.close() }
        }
        val state by controller.state.collectAsState()
        val portalState by portal.state.collectAsState()
        val scope = rememberCoroutineScope()
        // These controlled field slots are deliberately not saveable and never cross UI props.
        var phone by remember(controller) { mutableStateOf("") }
        var password by remember(controller) { mutableStateOf("") }
        var confirmation by remember(controller) { mutableStateOf("") }
        var passwordVisible by remember(controller) { mutableStateOf(false) }
        var openFailed by remember(portal) { mutableStateOf(false) }
        val transformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation()
        val reveal: @Composable () -> Unit = { PasswordVisibilityIcon(passwordVisible, { passwordVisible = !passwordVisible }) }
        val editable = state.expanded && !state.busy && !state.terminal && state.phase == CampusPasswordPhase.EDITING
        val selectable = state.expanded && !state.busy && !state.terminal && state.queryMessage == null
        val selected = state.accounts.firstOrNull { it.key == state.selectedAccountKey }

        LaunchedEffect(controller) { controller.refresh() }
        // Entry is read-only: authentication is an explicit CONNECT intent, never a mount effect.
        LaunchedEffect(portal) { portal.refresh() }
        LaunchedEffect(state.inputRevision) {
            phone = ""; password = ""; confirmation = ""; passwordVisible = false; gate.dismiss()
        }
        LaunchedEffect(state.phase, state.preview) {
            gate.reconcile()
            if (state.phase in setOf(CampusPasswordPhase.SUBMITTING, CampusPasswordPhase.COMPLETED,
                    CampusPasswordPhase.FAILED, CampusPasswordPhase.UNCERTAIN, CampusPasswordPhase.SESSION_CHANGED)) {
                password = ""; confirmation = ""; passwordVisible = false; gate.dismiss()
                if (state.terminal) phone = ""
            }
        }
        // Capture the preview that this render actually displayed, not a later controller value.
        val displayedPreview = state.preview
        val controls = CampusNetworkContentControls(
            sessionBound = session != null,
            canGoBack = onBack != null,
            canOpenDorm = onOpenDormSignIn != null,
            editable = editable,
            selectable = selectable,
            canCheckPhone = editable && !state.phoneChecked && selected?.canChangePassword == true && campusPhoneValidation(phone) == null,
            canPrepare = editable && state.phoneChecked,
            finalDialogOpen = gate.isOpen,
            finalMaskedAccount = gate.maskedAccount,
            acknowledged = gate.acknowledged,
        )
        val display = CampusPortalDisplay(
            phase = portalState.phase,
            detail = portalState.detail,
            elapsedMillis = portalState.connectedAtMillis?.let { (kotlin.time.Clock.System.now().toEpochMilliseconds() - it).coerceAtLeast(0) },
            maskedPhone = portalState.maskedPhone,
            hasManualAddress = portalState.portalAddress != null,
            manualPortalOpened = portalState.manualPortalOpened,
            openFailed = openFailed,
            canConnect = portalState.canConnect,
            canDisconnect = portalState.canDisconnect,
            canRetry = portalState.canRetry,
        )
        CampusNetworkContent(state, display, controls,
            onEvent = { event ->
                when (event) {
                    CampusNetworkUiEvent.BACK -> onBack?.invoke()
                    CampusNetworkUiEvent.OPEN_DORM -> onOpenDormSignIn?.invoke()
                    CampusNetworkUiEvent.REFRESH -> if (accountLive()) scope.launch { controller.refresh() }
                    CampusNetworkUiEvent.CONNECT, CampusNetworkUiEvent.RETRY_CONNECTION ->
                        if (portalLive()) scope.launch { portal.connect() }
                    CampusNetworkUiEvent.DISCONNECT -> if (portalLive()) scope.launch { portal.disconnect() }
                    CampusNetworkUiEvent.OPEN_PORTAL -> if (portalLive()) {
                        // Only this fixed controller's school-supplied address reaches platform policy.
                        portal.state.value.portalAddress?.let { address ->
                            portal.markManualPortalOpened()
                            openFailed = !graph.platform.openPortalGateway(address)
                        }
                    }
                    CampusNetworkUiEvent.TOGGLE_EXPANDED -> if (accountLive()) {
                        gate.dismiss(); controller.setExpanded(!controller.state.value.expanded)
                    }
                    CampusNetworkUiEvent.CHECK_PHONE -> if (accountLive()) scope.launch { controller.checkPhone(phone) }
                    CampusNetworkUiEvent.PREPARE -> if (accountLive()) {
                        gate.dismiss(); controller.prepare(phone, password, confirmation)
                    }
                    CampusNetworkUiEvent.EDIT_PREVIEW -> if (accountLive()) { gate.dismiss(); controller.editPreview() }
                    CampusNetworkUiEvent.OPEN_CONFIRMATION -> if (accountLive()) {
                        // The gate captures the private id exactly once, with the displayed target.
                        if (displayedPreview != null && controller.state.value.preview === displayedPreview) {
                            gate.open(displayedPreview.maskedAccount)
                        } else gate.dismiss()
                    }
                    CampusNetworkUiEvent.DISMISS_CONFIRMATION -> gate.dismiss()
                    CampusNetworkUiEvent.TOGGLE_ACKNOWLEDGEMENT -> gate.toggleAcknowledgement()
                    CampusNetworkUiEvent.CONFIRM -> {
                        // Consume the opened version, never substitute currentPreview's newer id.
                        val id = gate.consume()
                        if (id != null && accountLive()) scope.launch { controller.confirm(id, true) }
                    }
                }
            },
            onSelectAccount = { key -> if (accountLive()) { gate.dismiss(); controller.selectAccount(key) } },
            phoneField = { enabled ->
                TextField(phone, { value -> if (accountLive()) {
                    phone = value; password = ""; confirmation = ""; gate.dismiss(); controller.forgetPhoneCheck()
                } }, label = AppText.Common.MANUAL_PHONE_NUMBER_FIELD, singleLine = true,
                    enabled = enabled, cornerRadius = AppUiTokens.FieldRadius,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            },
            passwordFields = { enabled ->
                TextField(password, { if (accountLive()) password = it }, label = AppText.CampusNetwork.NEW_BROADBAND_PASSWORD_FIELD,
                    singleLine = true, enabled = enabled, cornerRadius = AppUiTokens.FieldRadius,
                    visualTransformation = transformation, trailingIcon = reveal,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                TextField(confirmation, { if (accountLive()) confirmation = it }, label = AppText.CampusNetwork.CONFIRM_BROADBAND_PASSWORD_FIELD,
                    singleLine = true, enabled = enabled, cornerRadius = AppUiTokens.FieldRadius,
                    visualTransformation = transformation, trailingIcon = reveal,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            })
    }
}
