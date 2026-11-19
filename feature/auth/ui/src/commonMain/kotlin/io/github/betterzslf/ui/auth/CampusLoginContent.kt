package io.github.betterzslf.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.ui.AppBrandMark
import io.github.betterzslf.ui.BackToChooser
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.PrimaryAction
import io.github.betterzslf.ui.UiTone
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The campus credential form, migrated whole from the original campus-stage LoginForm. Brand,
 * spacing and test tags are unchanged; the two field rows are root-provided slots, so the raw
 * username/password strings, the reveal control and their saved-state placement stay in the
 * trusted root binder. The notice banner carries whitelisted AppText only; the extra entry is a
 * local label plus an opaque local key the root resolves.
 *
 * This content owns no graph, session, repository, API client, NavKey, route or credential
 * string: it reports the typed intents and the opaque key, nothing else.
 */
@Composable
fun CampusLoginContent(
    display: CampusLoginDisplay,
    controls: CampusLoginControls,
    onEvent: (AuthLoginUiEvent) -> Unit,
    onExtraEntry: (String) -> Unit,
    accountField: @Composable () -> Unit,
    passwordField: @Composable () -> Unit,
) {
    val dispatch: (AuthLoginUiEvent) -> Unit = { onEvent(it) }
    Scaffold(containerColor = MiuixTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item { BackToChooser(onBack = { dispatch(AuthLoginUiEvent.BACK) }) }
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppBrandMark()
                    Text(AppText.Auth.ENTRY_CAMPUS_TITLE, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(AppText.Auth.LIGHTER_CAMPUS_LIFE_TAGLINE, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center)
                }
            }
            item { accountField() }
            item { passwordField() }
            display.notice?.let { notice -> item {
                InfoBanner(notice.message, title = notice.title, tone = UiTone.Warning)
            } }
            item {
                PrimaryAction(
                    text = if (display.busy) AppText.Auth.LOGGING_IN else AppText.Auth.LOG_IN,
                    modifier = Modifier.testTag("loginButton"),
                    enabled = !display.busy && controls.submitEnabled,
                    onClick = { dispatch(AuthLoginUiEvent.SUBMIT) },
                )
            }
            if (controls.recoveryAvailable) item {
                TextButton(AppText.Recovery.RECOVER_PASSWORD_TITLE, onClick = { dispatch(AuthLoginUiEvent.RECOVER_PASSWORD) },
                    modifier = Modifier.fillMaxWidth())
            }
            display.extraLabel?.let { label -> item {
                TextButton(label, onClick = { onExtraEntry(display.extraKey) }, modifier = Modifier.fillMaxWidth())
            } }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(AppText.App.USER_AGREEMENT_DISPLAY_LABEL, onClick = { dispatch(AuthLoginUiEvent.USER_AGREEMENT) })
                    TextButton(AppText.App.PRIVACY_POLICY_DISPLAY_LABEL, onClick = { dispatch(AuthLoginUiEvent.PRIVACY_POLICY) })
                }
            }
        }
    }
}
