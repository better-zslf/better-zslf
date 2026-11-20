package io.github.betterzslf.feature.accountrecovery.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.accountrecovery.AccountRecoveryPhase
import io.github.betterzslf.feature.accountrecovery.AccountRecoveryState
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.ConfirmationCheckRow
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionTitle
import io.github.betterzslf.ui.UiTone
import io.github.betterzslf.ui.primaryTextActionColors
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.betterzslf.ui.AppCard

/**
 * Complete recovery layout. The three controlled field slots receive only enabled flags; text,
 * field callbacks, password visibility and confirmation authority remain in the trusted binder.
 * This content creates no controller, coroutine, timer, repository or network side effect.
 */
@Composable
fun AccountRecoveryContent(
    state: AccountRecoveryState,
    controls: RecoveryContentControls,
    onEvent: (RecoveryUiEvent) -> Unit,
    phoneField: @Composable (enabled: Boolean) -> Unit,
    codeField: @Composable (enabled: Boolean) -> Unit,
    passwordFields: @Composable (enabled: Boolean) -> Unit,
) {
    val dispatch: (RecoveryUiEvent) -> Unit = { if (controls.allows(it)) onEvent(it) }
    Screen(AppText.Recovery.RECOVER_SCHOOL_LOGIN_PASSWORD_TITLE, { dispatch(RecoveryUiEvent.BACK) }) { padding ->
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
                AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                        phoneField(controls.editable)
                        codeField(controls.editable && state.codeRequested)
                        TextButton(if (state.cooldownSeconds > 0) AppText.Recovery.cooldown(state.cooldownSeconds) else AppText.Recovery.REQUEST_SMS_CODE,
                            enabled = controls.canSend, modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                            cornerRadius = AppUiTokens.ButtonRadius, colors = primaryTextActionColors(),
                            onClick = { dispatch(RecoveryUiEvent.OPEN_SMS_CONFIRMATION) })
                        TextButton(if (state.verified) AppText.Recovery.CURRENT_VERIFICATION_COMPLETED else AppText.Recovery.VERIFY_CURRENT_PHONE_AND_CODE,
                            enabled = controls.canVerify, modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                            cornerRadius = AppUiTokens.ButtonRadius, onClick = { dispatch(RecoveryUiEvent.VERIFY) })
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
                    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                            passwordFields(controls.editable)
                            Text(AppText.Recovery.PASSWORD_RULES_AND_MEMORY_ONLY_HINT,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                }
                item("prepare") {
                    TextButton(AppText.Recovery.REVIEW_SCHOOL_PASSWORD_RESET_PREVIEW, enabled = controls.canPrepare,
                        modifier = Modifier.fillMaxWidth(), minHeight = AppUiTokens.ButtonHeight,
                        cornerRadius = AppUiTokens.ButtonRadius, colors = primaryTextActionColors(),
                        onClick = { dispatch(RecoveryUiEvent.PREPARE_RESET) })
                }
            }
            state.preview?.let { preview -> item("preview") {
                AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                    insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                        SectionTitle(AppText.Recovery.RESET_PREVIEW_TITLE)
                        Text(AppText.Recovery.resetPreview(preview.maskedPhone))
                        if (state.phase == AccountRecoveryPhase.PREVIEW) {
                            TextButton(AppText.Common.CONTINUE_TO_FINAL_CONFIRMATION, modifier = Modifier.fillMaxWidth(),
                                minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                                colors = primaryTextActionColors(), onClick = { dispatch(RecoveryUiEvent.OPEN_RESET_CONFIRMATION) })
                            TextButton(AppText.Common.BACK_TO_EDIT, modifier = Modifier.fillMaxWidth(),
                                onClick = { dispatch(RecoveryUiEvent.EDIT_PREVIEW) })
                        }
                    }
                }
            } }
            if (state.busy) item("busy") { LinearProgressIndicator() }
        }
        OverlayDialog(show = controls.backNotice && (state.sending || state.phase == AccountRecoveryPhase.RESETTING),
            title = AppText.Recovery.AWAITING_SERVICE_RECEIPT,
            summary = AppText.Recovery.REQUEST_POSSIBLY_ACCEPTED_BACK_DOES_NOT_REVOKE_NOTICE,
            onDismissRequest = { dispatch(RecoveryUiEvent.DISMISS_BACK_NOTICE) }) {
            TextButton(AppText.Common.CONTINUE_WAITING, modifier = Modifier.fillMaxWidth(),
                onClick = { dispatch(RecoveryUiEvent.DISMISS_BACK_NOTICE) })
        }
        OverlayDialog(show = controls.smsDialogOpen && controls.canSend,
            title = AppText.Recovery.SEND_SMS_CONFIRM_TITLE,
            summary = AppText.Recovery.sendCodeConfirmation(controls.smsMaskedPhone.ifBlank { AppText.Recovery.CURRENT_PHONE_FALLBACK_LABEL }),
            onDismissRequest = { dispatch(RecoveryUiEvent.DISMISS_SMS_CONFIRMATION) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(AppText.Common.CANCEL, modifier = Modifier.weight(1f),
                    onClick = { dispatch(RecoveryUiEvent.DISMISS_SMS_CONFIRMATION) })
                TextButton(AppText.Recovery.CONFIRM_SMS_REQUEST, modifier = Modifier.weight(1f),
                    onClick = { dispatch(RecoveryUiEvent.CONFIRM_SMS) })
            }
        }
        OverlayDialog(show = controls.resetDialogOpen && state.phase == AccountRecoveryPhase.PREVIEW,
            title = AppText.Recovery.RESET_PASSWORD_AND_LOG_OUT_CONFIRM_TITLE,
            summary = AppText.Recovery.resetConfirmation(controls.resetMaskedPhone),
            onDismissRequest = { dispatch(RecoveryUiEvent.DISMISS_RESET_CONFIRMATION) }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ConfirmationCheckRow(checked = controls.acknowledged,
                    label = AppText.Recovery.RESET_PASSWORD_AND_LOG_OUT_ACKNOWLEDGEMENT,
                    onCheckedChange = { dispatch(RecoveryUiEvent.TOGGLE_ACKNOWLEDGEMENT) })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(AppText.Common.BACK_TO_PREVIEW, modifier = Modifier.weight(1f),
                        onClick = { dispatch(RecoveryUiEvent.DISMISS_RESET_CONFIRMATION) })
                    TextButton(AppText.Recovery.FINAL_CONFIRM_PASSWORD_RESET, enabled = controls.acknowledged,
                        modifier = Modifier.weight(1f), onClick = { dispatch(RecoveryUiEvent.CONFIRM_RESET) })
                }
            }
        }
    }
}

/** A non-sensitive accepted outcome survives the recovery page's own successful logout. */
@Composable
fun RecoveryReceiptContent(cleanupWarning: Boolean, onBack: () -> Unit) {
    Screen(AppText.Recovery.SCHOOL_PASSWORD_RESET_RECEIPT_TITLE, onBack) { padding ->
        Column(Modifier.padding(padding).padding(AppUiTokens.FormPadding), verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
            InfoBanner(title = if (cleanupWarning) AppText.Recovery.SERVICE_SUCCEEDED_LOCAL_CLEANUP_NOTICE else AppText.Recovery.PASSWORD_RESET_CONFIRMED,
                message = if (cleanupWarning) AppText.Recovery.PASSWORD_RESET_SUCCEEDED_SESSION_CLEANUP_FAILED_HINT
                else AppText.Recovery.SESSION_EXITED_LOG_IN_WITH_NEW_PASSWORD_HINT,
                tone = if (cleanupWarning) UiTone.Warning else UiTone.Positive)
            TextButton(AppText.Auth.BACK_TO_LOGIN, onClick = onBack, modifier = Modifier.fillMaxWidth(),
                minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
                colors = primaryTextActionColors())
        }
    }
}
