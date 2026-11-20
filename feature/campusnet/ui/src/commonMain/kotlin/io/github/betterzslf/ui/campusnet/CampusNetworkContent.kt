package io.github.betterzslf.ui.campusnet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.campusnet.CampusNetworkState
import io.github.betterzslf.feature.campusnet.CampusPasswordPhase
import io.github.betterzslf.feature.campusnet.CampusPortalPhase
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.ConfirmationCheckRow
import io.github.betterzslf.ui.EmptyCard
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionTitle
import io.github.betterzslf.ui.UiTone
import io.github.betterzslf.ui.primaryTextActionColors
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.betterzslf.ui.AppCard

internal enum class CampusConnectionState { NotSignedIn, Offline, Connecting, Online, Failed, NotOnCampus }

private fun campusConnectionState(state: CampusPortalDisplay, sessionBound: Boolean): CampusConnectionState =
    if (!sessionBound) CampusConnectionState.NotSignedIn else when (state.phase) {
        CampusPortalPhase.NotSignedIn -> CampusConnectionState.NotSignedIn
        CampusPortalPhase.Offline -> CampusConnectionState.Offline
        CampusPortalPhase.Connecting -> CampusConnectionState.Connecting
        CampusPortalPhase.Online -> CampusConnectionState.Online
        CampusPortalPhase.Failed -> CampusConnectionState.Failed
        CampusPortalPhase.NotOnCampus -> CampusConnectionState.NotOnCampus
    }

private fun campusConnectionDetail(state: CampusPortalDisplay, connection: CampusConnectionState): String {
    if (state.detail.isNotBlank()) return state.detail
    if (connection != CampusConnectionState.Online) return ""
    val elapsed = state.elapsedMillis ?: return ""
    val duration = campusDurationLabel(elapsed)
    return if (state.maskedPhone.isNotBlank()) AppText.CampusNetwork.statusOnlineHint(duration, state.maskedPhone)
    else AppText.CampusNetwork.usedFor(duration)
}

/** Locally derived, so no locale formatting call and no platform API is involved. */
internal fun campusDurationLabel(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${AppText.CampusNetwork.durationHours(hours)}${AppText.CampusNetwork.durationMinutes(minutes)}"
    else AppText.CampusNetwork.durationMinutes(minutes)
}

/** Complete campus account/portal layout. Controlled field slots contain all raw input handling. */
@Composable
fun CampusNetworkContent(
    state: CampusNetworkState,
    portal: CampusPortalDisplay,
    controls: CampusNetworkContentControls,
    onEvent: (CampusNetworkUiEvent) -> Unit,
    onSelectAccount: (String) -> Unit,
    phoneField: @Composable (enabled: Boolean) -> Unit,
    passwordFields: @Composable (enabled: Boolean) -> Unit,
) {
    val selected = state.accounts.firstOrNull { it.key == state.selectedAccountKey }
    Screen(AppText.CampusNetwork.CAMPUS_NETWORK_TITLE,
        onBack = if (controls.canGoBack) ({ onEvent(CampusNetworkUiEvent.BACK) }) else null,
        actions = {
            TextButton(AppText.Common.REFRESH, enabled = !state.busy && state.phase != CampusPasswordPhase.SESSION_CHANGED,
                onClick = { onEvent(CampusNetworkUiEvent.REFRESH) })
        }) { padding ->
        val listPadding = io.github.betterzslf.ui.pageContentPadding(padding, io.github.betterzslf.ui.LocalNavigationBottomInset.current,
            androidx.compose.ui.platform.LocalLayoutDirection.current, AppUiTokens.FormPadding)
        PullToRefresh(state.loading, { onEvent(CampusNetworkUiEvent.REFRESH) },
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding), contentPadding = padding,
            refreshTexts = io.github.betterzslf.ui.appRefreshTexts()) {
            LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                item("connection") {
                    val connection = campusConnectionState(portal, controls.sessionBound)
                    CampusConnectionCard(connection, campusConnectionDetail(portal, connection),
                        onSignIn = if (controls.canOpenDorm && connection == CampusConnectionState.NotSignedIn)
                            ({ onEvent(CampusNetworkUiEvent.OPEN_DORM) }) else null,
                        primary = when {
                            portal.canConnect -> AppText.CampusNetwork.ACTION_CONNECT
                            portal.canDisconnect -> AppText.CampusNetwork.ACTION_DISCONNECT
                            portal.canRetry -> AppText.CampusNetwork.ACTION_RETRY
                            else -> null
                        },
                        onPrimary = when {
                            portal.canConnect -> ({ onEvent(CampusNetworkUiEvent.CONNECT) })
                            portal.canDisconnect -> ({ onEvent(CampusNetworkUiEvent.DISCONNECT) })
                            portal.canRetry -> ({ onEvent(CampusNetworkUiEvent.RETRY_CONNECTION) })
                            else -> null
                        })
                }
                if (portal.hasManualAddress) item("manual-portal") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(AppText.CampusNetwork.OPEN_PORTAL_ACTION, modifier = Modifier.fillMaxWidth(),
                            minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                            onClick = { onEvent(CampusNetworkUiEvent.OPEN_PORTAL) })
                        if (portal.manualPortalOpened) Text(
                            if (portal.openFailed) AppText.CampusNetwork.PORTAL_MANUAL_OPEN_FAILED_HINT
                            else AppText.CampusNetwork.PORTAL_MANUAL_OPENED_HINT,
                            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
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
                    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(AppUiTokens.CardPadding),
                        colors = CardDefaults.defaultColors(color = if (state.selectedAccountKey == account.key)
                            MiuixTheme.colorScheme.primary.copy(alpha = 0.10f) else MiuixTheme.colorScheme.surfaceContainer)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(account.maskedAccount, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (account.typeStatusLabel.isNotBlank()) Text(account.typeStatusLabel, fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (!account.canChangePassword) InfoBanner(AppText.CampusNetwork.INVALID_ACCOUNT_IDS_READ_ONLY_HINT, tone = UiTone.Neutral)
                            if (state.expanded) TextButton(
                                if (state.selectedAccountKey == account.key) AppText.CampusNetwork.ACCOUNT_SELECTED else AppText.CampusNetwork.SELECT_THIS_ACCOUNT,
                                enabled = controls.selectable && account.canChangePassword, modifier = Modifier.fillMaxWidth(),
                                minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                                onClick = { onSelectAccount(account.key) })
                        }
                    }
                }
                item("operation-toggle") {
                    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius) {
                        ArrowPreference(AppText.CampusNetwork.CARRIER_PASSWORD_ACTIONS_TITLE,
                            summary = if (state.expanded) AppText.CampusNetwork.COLLAPSE_AND_CLEAR_PASSWORD_INPUT else AppText.CampusNetwork.MANUAL_EXPANSION_REQUIRED_HINT,
                            enabled = !state.busy && !state.terminal, holdDownState = state.expanded,
                            onClick = { onEvent(CampusNetworkUiEvent.TOGGLE_EXPANDED) })
                    }
                }
                if (state.expanded && !state.terminal) {
                    if (selected == null) item("choose") {
                        InfoBanner(AppText.CampusNetwork.QUERIED_VALID_ACCOUNT_REQUIRED_HINT,
                            title = AppText.CampusNetwork.SELECT_ACCOUNT_FIRST_TITLE, tone = UiTone.Info)
                    }
                    item("password-form") {
                        AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                            insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                            Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                                phoneField(controls.editable && selected?.canChangePassword == true)
                                TextButton(if (state.checkingPhone) AppText.Common.CHECKING else if (state.phoneChecked)
                                    AppText.CampusNetwork.PHONE_CHECK_PASSED else AppText.CampusNetwork.CHECK_PHONE_MAY_SEND_SMS,
                                    enabled = controls.canCheckPhone, modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                                    cornerRadius = AppUiTokens.ButtonRadius, onClick = { onEvent(CampusNetworkUiEvent.CHECK_PHONE) })
                                Text(AppText.CampusNetwork.CARRIER_ACCOUNT_PASSWORD_SCOPE_HINT, fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                if (state.phoneChecked) InfoBanner(AppText.CampusNetwork.PHONE_CHECK_PASSED_NOT_SMS_VERIFICATION_HINT, tone = UiTone.Positive)
                                passwordFields(controls.editable && state.phoneChecked)
                            }
                        }
                    }
                    item("prepare") {
                        TextButton(AppText.CampusNetwork.REVIEW_AND_PREPARE_PREVIEW, enabled = controls.canPrepare,
                            modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                            cornerRadius = AppUiTokens.ButtonRadius, colors = primaryTextActionColors(),
                            onClick = { onEvent(CampusNetworkUiEvent.PREPARE) })
                    }
                }
                state.preview?.let { preview -> item("preview") {
                    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                            SectionTitle(AppText.CampusNetwork.CURRENT_OPERATION_PREVIEW_TITLE)
                            Text(AppText.CampusNetwork.passwordPreview(preview.maskedAccount, preview.maskedPhone))
                            if (state.phase == CampusPasswordPhase.PREVIEW) {
                                TextButton(AppText.Common.CONTINUE_TO_FINAL_CONFIRMATION, modifier = Modifier.fillMaxWidth(),
                                    minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                                    colors = primaryTextActionColors(), onClick = { onEvent(CampusNetworkUiEvent.OPEN_CONFIRMATION) })
                                TextButton(AppText.Common.BACK_TO_EDIT, modifier = Modifier.fillMaxWidth(),
                                    onClick = { onEvent(CampusNetworkUiEvent.EDIT_PREVIEW) })
                            }
                        }
                    }
                } }
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
        OverlayDialog(show = controls.finalDialogOpen && state.phase == CampusPasswordPhase.PREVIEW,
            title = AppText.CampusNetwork.CARRIER_PASSWORD_FINAL_CONFIRM_TITLE,
            summary = AppText.CampusNetwork.passwordTarget(controls.finalMaskedAccount),
            onDismissRequest = { onEvent(CampusNetworkUiEvent.DISMISS_CONFIRMATION) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfirmationCheckRow(checked = controls.acknowledged, label = AppText.CampusNetwork.SINGLE_SUBMISSION_ACKNOWLEDGEMENT,
                    onCheckedChange = { onEvent(CampusNetworkUiEvent.TOGGLE_ACKNOWLEDGEMENT) })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(AppText.Common.BACK_TO_PREVIEW, modifier = Modifier.weight(1f),
                        onClick = { onEvent(CampusNetworkUiEvent.DISMISS_CONFIRMATION) })
                    TextButton(AppText.CampusNetwork.CONFIRM_PASSWORD_CHANGE, enabled = controls.acknowledged,
                        modifier = Modifier.weight(1f), onClick = { onEvent(CampusNetworkUiEvent.CONFIRM) })
                }
            }
        }
    }
}

@Composable
private fun CampusConnectionCard(
    state: CampusConnectionState,
    detail: String = "",
    onSignIn: (() -> Unit)? = null,
    primary: String? = null,
    onPrimary: (() -> Unit)? = null,
) {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val accent = when (state) {
        CampusConnectionState.NotSignedIn, CampusConnectionState.Offline -> AppUiTokens.NeutralDot
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
        CampusConnectionState.Online, CampusConnectionState.Failed -> ""
        CampusConnectionState.NotOnCampus -> AppText.CampusNetwork.STATUS_NOT_ON_CAMPUS_HINT
    }
    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state == CampusConnectionState.Connecting) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            else Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val hint = detail.ifBlank { defaultHint }
                if (hint.isNotBlank()) Text(hint, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            onSignIn?.let { open -> TextButton(text = AppText.CampusNetwork.ACTION_SIGN_IN, onClick = open,
                minHeight = 36.dp, cornerRadius = AppUiTokens.ButtonRadius) }
            if (primary != null && onPrimary != null) TextButton(text = primary, onClick = onPrimary,
                minHeight = 36.dp, cornerRadius = AppUiTokens.ButtonRadius)
        }
    }
}
