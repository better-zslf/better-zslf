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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.edition.EditionAccess
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LoginScreen(graph: AppGraph, onDocument: (String) -> Unit, onWater: (() -> Unit)? = null, onResetPassword: (() -> Unit)? = null) {
    var username by rememberSaveable { mutableStateOf("") }
    // Password intentionally never participates in saved instance state or storage.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Scaffold(containerColor = MiuixTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 44.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppBrandMark()
                    Text(AppText.Auth.WELCOME_TITLE, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(AppText.Auth.LIGHTER_CAMPUS_LIFE_TAGLINE, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center)
                }
            }
            item {
                TextField(value = username, onValueChange = { username = it }, label = AppText.Auth.STUDENT_NUMBER_FIELD,
                    singleLine = true, cornerRadius = AppUiTokens.FieldRadius, enabled = !loading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().heightIn(min = AppUiTokens.ButtonHeight).testTag("username"))
            }
            item {
                TextField(value = password, onValueChange = { password = it }, label = AppText.Auth.PASSWORD_FIELD,
                    singleLine = true, cornerRadius = AppUiTokens.FieldRadius, enabled = !loading,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().heightIn(min = AppUiTokens.ButtonHeight).testTag("password"))
            }
            item {
                SwitchPreference(title = AppText.Auth.SHOW_PASSWORD, checked = visible,
                    enabled = !loading, onCheckedChange = { visible = it })
            }
            graph.storageNotice?.let { notice -> item { InfoBanner(notice, title = AppText.App.LOCAL_DATA_NOTICE, tone = UiTone.Warning) } }
            if (message.isNotBlank()) item { InfoBanner(message, title = AppText.Auth.LOGIN_NOTICE_TITLE, tone = UiTone.Warning) }
            item {
                PrimaryAction(
                    text = if (loading) AppText.Auth.LOGGING_IN else AppText.Auth.LOG_IN,
                    modifier = Modifier.testTag("loginButton"),
                    enabled = !loading && username.isNotBlank() && password.isNotEmpty(),
                    onClick = login@{
                        if (loading) return@login
                        loading = true
                        message = ""
                        scope.launch {
                            try {
                                when (val result = graph.repositories.auth.login(username.trim(), password)) {
                                    is ApiResult.Success -> { password = ""; graph.clearStorageNotice() }
                                    is ApiResult.Failure -> message = result.error.message
                                }
                            } finally { loading = false }
                        }
                    },
                )
            }
            onResetPassword?.let { openReset -> item {
                TextButton(AppText.Recovery.RECOVER_PASSWORD_TITLE, onClick = openReset, modifier = Modifier.fillMaxWidth())
            } }
            if (EditionAccess.waterEnabled) onWater?.let { openWater -> item { graph.editionFeatures.LoginEntry(openWater) } }
            item {
                Text(AppText.Auth.SESSION_STORAGE_WITHOUT_PASSWORD_HINT, fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    TextButton(AppText.App.USER_AGREEMENT_DISPLAY_LABEL, onClick = { onDocument("用户协议") })
                    TextButton(AppText.App.PRIVACY_POLICY_DISPLAY_LABEL, onClick = { onDocument("隐私政策") })
                }
            }
        }
    }
}
