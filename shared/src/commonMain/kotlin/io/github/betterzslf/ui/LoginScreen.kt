package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
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
    Screen(AppText.Auth.WELCOME_TITLE) { padding ->
        PageList(padding) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("better-zslf", style = MiuixTheme.textStyles.title1)
                    Text(AppText.Auth.LIGHTER_CAMPUS_LIFE_TAGLINE, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            item { TextField(value = username, onValueChange = { username = it }, label = AppText.Auth.STUDENT_NUMBER_FIELD, singleLine = true,
                enabled = !loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("username")) }
            item { TextField(value = password, onValueChange = { password = it }, label = AppText.Auth.PASSWORD_FIELD, singleLine = true,
                enabled = !loading, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("password")) }
            item { SwitchPreference(title = AppText.Auth.SHOW_PASSWORD, checked = visible, onCheckedChange = { visible = it }) }
            graph.storageNotice?.let { notice -> item { SectionCard(AppText.App.LOCAL_DATA_NOTICE, notice) } }
            if (message.isNotBlank()) item { SectionCard(AppText.Auth.LOGIN_NOTICE_TITLE, message) }
            item {
                Button(modifier = Modifier.fillMaxWidth().testTag("loginButton"), enabled = !loading && username.isNotBlank() && password.isNotEmpty(), onClick = {
                    loading = true
                    message = ""
                    scope.launch {
                        try {
                            when (val result = graph.repositories.auth.login(username.trim(), password)) {
                                is ApiResult.Success -> { password = ""; graph.clearStorageNotice() }
                                is ApiResult.Failure -> { message = result.error.message }
                            }
                        } finally { loading = false }
                    }
                }) { if (loading) CircularProgressIndicator(Modifier.size(20.dp)) else Text(AppText.Auth.LOG_IN) }
            }
            item { Text(AppText.Auth.SESSION_STORAGE_WITHOUT_PASSWORD_HINT, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            onResetPassword?.let { openReset -> item { TextButton(AppText.Recovery.RECOVER_PASSWORD_TITLE, onClick = openReset, modifier = Modifier.fillMaxWidth()) } }
            if (EditionAccess.waterEnabled) onWater?.let { openWater -> item { graph.editionFeatures.LoginEntry(openWater) } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(AppText.App.USER_AGREEMENT_DISPLAY_LABEL, onClick = { onDocument("用户协议") })
                TextButton(AppText.App.PRIVACY_POLICY_DISPLAY_LABEL, onClick = { onDocument("隐私政策") })
            } }
        }
    }
}
