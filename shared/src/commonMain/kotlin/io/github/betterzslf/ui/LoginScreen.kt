package io.github.betterzslf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun LoginScreen(graph: AppGraph, onDocument: (String) -> Unit, onWater: (() -> Unit)? = null) {
    var username by rememberSaveable { mutableStateOf("") }
    // Password intentionally never participates in saved instance state or storage.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Screen("欢迎使用") { padding ->
        PageList(padding) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("better-zslf", style = MiuixTheme.textStyles.title1)
                    Text("轻一点的校园生活", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            item { TextField(value = username, onValueChange = { username = it }, label = "学号", singleLine = true,
                enabled = !loading, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("username")) }
            item { TextField(value = password, onValueChange = { password = it }, label = "密码", singleLine = true,
                enabled = !loading, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("password")) }
            item { SwitchPreference(title = "显示密码", checked = visible, onCheckedChange = { visible = it }) }
            graph.storageNotice?.let { notice -> item { SectionCard("本机数据提示", notice) } }
            if (message.isNotBlank()) item { SectionCard("登录提示", message) }
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
                }) { if (loading) CircularProgressIndicator(Modifier.size(20.dp)) else Text("登录") }
            }
            item { Text("账号仅用于直连学校服务；本机保存登录会话，不保存密码。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            onWater?.let { openWater -> item { TextButton("离线热水器", onClick = openWater, modifier = Modifier.fillMaxWidth()) } }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton("用户协议", onClick = { onDocument("用户协议") })
                TextButton("隐私政策", onClick = { onDocument("隐私政策") })
            } }
        }
    }
}
