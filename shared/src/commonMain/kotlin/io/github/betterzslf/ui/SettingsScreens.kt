package io.github.betterzslf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun SettingsScreen(graph: AppGraph, open: (Route) -> Unit, back: () -> Unit) {
    val settings by graph.settings.settings.collectAsState()
    val storageError by graph.settings.error.collectAsState()
    var clear by remember { mutableStateOf(false) }
    Screen("设置", back) { padding ->
        PageList(padding) {
            storageError?.let { error -> item { SectionCard("本机数据提示", error) } }
            item { Text("外观") }
            item { Card {
                val names = mapOf("System" to "跟随系统", "Light" to "浅色", "Dark" to "深色")
                ArrowPreference("深色模式", summary = names[settings.theme] ?: "跟随系统", onClick = {
                    val modes = listOf("System", "Light", "Dark")
                    val next = modes[(modes.indexOf(settings.theme).coerceAtLeast(0) + 1) % modes.size]
                    graph.settings.update { it.copy(theme = next) }
                })
                SwitchPreference(title = "动态取色", summary = "使用系统壁纸配色", checked = settings.dynamicColor,
                    onCheckedChange = { checked -> graph.settings.update { it.copy(dynamicColor = checked) } })
            } }
            item { Text("校园跑") }
            item { Card {
                ArrowPreference("跑步默认模式", summary = if (settings.preferredRunMode == "ask") "进入时询问（仍默认真实跑步）" else "真实跑步", onClick = {
                    graph.settings.update { it.copy(preferredRunMode = if (it.preferredRunMode == "real") "ask" else "real") }
                })
                ArrowPreference("定位采样间隔", summary = "${settings.samplingSeconds} 秒", onClick = {
                    val values = listOf(10, 15, 30, 60)
                    val next = values[(values.indexOf(settings.samplingSeconds).coerceAtLeast(0) + 1) % values.size]
                    graph.settings.update { it.copy(samplingSeconds = next) }
                })
                SwitchPreference(title = "公制单位", summary = "米、千米；关闭后显示英里", checked = settings.metricUnits,
                    onCheckedChange = { checked -> graph.settings.update { it.copy(metricUnits = checked) } })
            } }
            item { Text("本地数据") }
            graph.storageNotice?.let { notice -> item { SectionCard("本机数据提示", notice) } }
            item { SectionCard(if (graph.clearingLocalData) "正在清理本机数据" else "清除本机数据", "会先停止运动记录与后续上传，再退出登录并恢复设置；学校记录保持原样。", { if (!graph.clearingLocalData) clear = true }) }
            item { Text("关于与版本") }
            item { SectionCard("better-zslf", graph.platform.versionName, { open(Route.Page("about", title = "关于应用")) }) }
            item { SectionCard("检查更新", onClick = { open(Route.Page("updates", title = "版本更新")) }) }
        }
        ConfirmDialog(clear, "清除本机数据？", "会移除本机的会话、设置和离线数据。请先确认没有尚未同步的运动记录。", { clear = false }) {
            graph.clearLocalData()
            clear = false
        }
    }
}

fun passwordValidation(password: String, confirmation: String): String? = when {
    password.length < 8 -> "新密码至少 8 位"
    !io.github.betterzslf.data.AuthRepository.isValidNewPassword(password) -> "新密码需含字母、数字和符号，且不含空白字符"
    password != confirmation -> "两次输入的新密码不一致"
    else -> null
}

@Composable
fun PasswordScreen(graph: AppGraph, back: () -> Unit) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Screen("修改密码", back) { padding ->
        PageList(padding) {
            item { SectionCard("修改账号密码", "此操作会实际修改学校账号密码，成功后需要重新登录。") }
            item { TextField(old, { old = it }, label = "原密码", singleLine = true, enabled = !submitting,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
            item { TextField(new, { new = it }, label = "新密码", singleLine = true, enabled = !submitting,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
            item { TextField(confirmation, { confirmation = it }, label = "确认新密码", singleLine = true, enabled = !submitting,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
            if (message.isNotEmpty()) item { SectionCard("提示", message) }
            item { Button(enabled = !submitting && old.isNotBlank(), onClick = {
                val error = passwordValidation(new, confirmation)
                if (error != null) message = error else confirming = true
            }) { Text(if (submitting) "提交中…" else "修改密码") } }
        }
        ConfirmDialog(confirming, "确认修改学校账号密码？", "这不是测试操作，服务端会立即修改密码。", { confirming = false }) {
            confirming = false
            submitting = true
            scope.launch {
                try {
                    when (val result = graph.repositories.auth.modifyPassword(old, new)) {
                        is ApiResult.Failure -> message = result.error.message
                        is ApiResult.Success -> { old = ""; new = ""; confirmation = "" }
                    }
                } finally { submitting = false }
            }
        }
    }
}
