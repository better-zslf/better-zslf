package io.github.betterzslf.ui.accountrecovery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.data.PasswordResetLogout
import io.github.betterzslf.feature.accountrecovery.*
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun AccountRecoveryPage(graph: AppGraph, onBack: () -> Unit) {
    val session by graph.sessions.session.collectAsState()
    // Only a non-sensitive outcome enum may outlive our own successful logout, never inputs/identity.
    var finalReceipt by remember(graph) { mutableStateOf<PasswordResetLogout?>(null) }
    LaunchedEffect(session) { if (session != null) finalReceipt = null }
    val receipt = finalReceipt
    if (session == null && receipt != null) {
        Screen("学校密码重置回执", onBack) { padding ->
            Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionCard(if (receipt == PasswordResetLogout.CLEAR_FAILED) "服务已成功 · 本机清理提示" else "服务已确认重置成功",
                    if (receipt == PasswordResetLogout.CLEAR_FAILED)
                        "学校登录密码已重置，内存会话已退出；持久会话记录删除失败，请处理本机会话记录。不要再次重置。"
                    else "当前会话已退出，请使用新学校登录密码登录；本页不会重复提交。")
                TextButton("返回登录", onClick = onBack)
            }
        }
    } else key(session) {
        val controller = remember(graph, session) {
            AccountRecoveryController(RepositoryAccountRecoverySource.capture(graph.repositories.auth), onAccepted = {
                if (graph.sessions.session.value == null) finalReceipt = it
            })
        }
        DisposableEffect(controller) { onDispose { controller.close() } }
        AccountRecoveryBody(controller, onBack)
    }
}

@Composable
private fun AccountRecoveryBody(controller: AccountRecoveryController, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var phone by remember(controller) { mutableStateOf("") }
    var code by remember(controller) { mutableStateOf("") }
    var password by remember(controller) { mutableStateOf("") }
    var confirmation by remember(controller) { mutableStateOf("") }
    var sendDialog by remember(controller) { mutableStateOf(false) }
    var resetDialog by remember(controller) { mutableStateOf(false) }
    var backNotice by remember(controller) { mutableStateOf(false) }
    val currentBack by rememberUpdatedState(onBack)
    val guardedBack: () -> Unit = {
        recoveryGuardedBack({ controller.state.value }, { currentBack() }, { backNotice = true })
    }
    var acknowledged by remember(state.preview?.id) { mutableStateOf(false) }
    val editable = state.phase == AccountRecoveryPhase.EDITING && !state.busy
    val sendEnabled = editable && state.cooldownSeconds == 0 && recoveryPhoneValidation(phone) == null

    LaunchedEffect(state.inputRevision) {
        phone = ""; code = ""; password = ""; confirmation = ""; sendDialog = false; resetDialog = false
    }
    LaunchedEffect(state.phase) {
        if (state.terminal) {
            phone = ""; code = ""; password = ""; confirmation = ""; sendDialog = false; resetDialog = false
        }
    }
    LaunchedEffect(state.verified) {
        if (!state.verified) { password = ""; confirmation = ""; resetDialog = false }
    }
    LaunchedEffect(controller, state.cooldownSeconds > 0) {
        while (controller.state.value.cooldownSeconds > 0) { delay(1000); controller.tickCooldown() }
    }
    LaunchedEffect(state.sending, state.phase) {
        if (!recoveryWriteInFlight(state)) backNotice = false
    }
    // Stay registered before a gesture starts; commit uses the current controller value even across frames.
    PredictiveBackHandler(
        enabled = true,
        onProgress = { events -> events.collect { } },
        onCommit = guardedBack,
        onCancel = {},
    )
    Screen("找回学校登录密码", guardedBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("scope") { SectionCard("重置学校登录密码", "不是运营商宽带密码。当前三个接口保留有效登录会话前置；匿名找回支持待核实。进入页面不会发送短信或修改密码。") }
            state.message?.let { item("message") { SectionCard(when (state.phase) {
                AccountRecoveryPhase.GUEST -> "未登录 · 操作保持禁用"
                AccountRecoveryPhase.UNCERTAIN -> "结果尚未确认 · 已停止"
                AccountRecoveryPhase.COMPLETED -> "服务回执"
                AccountRecoveryPhase.CLEANUP_WARNING -> "服务已成功 · 本机清理提示"
                AccountRecoveryPhase.FAILED -> "本次已停止"
                AccountRecoveryPhase.SESSION_CHANGED -> "会话已变化"
                else -> "操作提示"
            }, it) } }
            item("phone") { TextField(phone, {
                phone = it; code = ""; password = ""; confirmation = ""; sendDialog = false; resetDialog = false
                controller.phoneChanged()
            }, label = "手动输入手机号", singleLine = true, enabled = editable,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth()) }
            item("send") { Button(enabled = sendEnabled, modifier = Modifier.fillMaxWidth(), onClick = { sendDialog = true }) {
                Text(if (state.cooldownSeconds > 0) "本地等待 ${state.cooldownSeconds} 秒" else "请求验证码（会发送短信）")
            } }
            if (state.cooldownSeconds > 0) item("countdown-note") { Text("倒计时仅为本地界面提示，不保证短信送达，也不是服务端验证码有效期。") }
            item("code") { TextField(code, {
                code = it; password = ""; confirmation = ""; resetDialog = false; controller.codeChanged()
            }, label = "短信验证码", singleLine = true, enabled = editable && state.codeRequested,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth()) }
            item("verify") { Button(enabled = editable && !state.verified && state.codeRequested && code.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
                val phoneSnapshot = phone; val codeSnapshot = code
                scope.launch { controller.verify(phoneSnapshot, codeSnapshot) }
            }) { Text(if (state.verified) "已完成当前核验" else "核验当前手机号与验证码") } }
            if (state.verified) {
                item("identity") { SectionCard("已核验手机号：${state.maskedPhone.orEmpty()}", "目标只来自本次验证码核验响应的 user.id，不使用当前登录 userId 或手填身份替代。编辑手机号或验证码即作废。") }
                item("password") { TextField(password, { password = it }, label = "新的学校登录密码", enabled = editable,
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
                item("confirmation") { TextField(confirmation, { confirmation = it }, label = "重复新的学校登录密码", enabled = editable,
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
                item("policy") { Text("至少 8 位，包含字母、数字和符号，且不含空白。密码仅在本次页面内存使用，不预填、不保存。") }
                item("prepare") { Button(enabled = editable, modifier = Modifier.fillMaxWidth(), onClick = {
                    controller.prepare(phone, code, password, confirmation)
                }) { Text("核对学校密码重置预览") } }
            }
            state.preview?.let { preview -> item("preview") {
                SectionCard("重置预览", "已核验手机号：${preview.maskedPhone}\n目标：该次短信核验返回的学校账号\n动作：重置学校登录密码并退出\n密码不展示；未知结果时停止，不自动重试。")
                if (state.phase == AccountRecoveryPhase.PREVIEW) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("返回编辑", modifier = Modifier.weight(1f), onClick = { controller.editPreview() })
                    TextButton("继续最终确认", modifier = Modifier.weight(1f), onClick = { resetDialog = true })
                }
            } }
            if (state.busy) item("busy") { CircularProgressIndicator() }
        }
        OverlayDialog(show = backNotice && recoveryWriteInFlight(state), title = "正在等待服务回执",
            summary = RECOVERY_WRITE_BACK_NOTICE, onDismissRequest = { backNotice = false }) {
            TextButton("继续等待", modifier = Modifier.fillMaxWidth(), onClick = { backNotice = false })
        }
        OverlayDialog(show = sendDialog && sendEnabled, title = "确认发送短信？",
            summary = "将向 ${if (phone.length == 11) phone.take(3) + "••••" + phone.takeLast(2) else "当前手机号"} 请求发送验证码。只有服务业务成功后启动本地 60 秒倒计时；这不保证送达。",
            onDismissRequest = { sendDialog = false }) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("取消", modifier = Modifier.weight(1f), onClick = { sendDialog = false })
                TextButton("确认请求短信", modifier = Modifier.weight(1f), onClick = {
                    val phoneSnapshot = phone; sendDialog = false
                    code = ""; password = ""; confirmation = ""
                    scope.launch { controller.sendCode(phoneSnapshot, confirmed = true) }
                })
            }
        }
        OverlayDialog(show = resetDialog && state.phase == AccountRecoveryPhase.PREVIEW,
            title = "将重置学校登录密码并退出",
            summary = "已核验手机号：${state.preview?.maskedPhone.orEmpty()}。这是学校账号登录密码，不是运营商密码。最终确认后只发送一次。",
            onDismissRequest = { resetDialog = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(if (acknowledged) ToggleableState.On else ToggleableState.Off, onClick = { acknowledged = !acknowledged })
                    Text("我已核对预览，确认重置学校登录密码并退出", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("返回预览", modifier = Modifier.weight(1f), onClick = { resetDialog = false })
                    TextButton("最终确认重置", enabled = acknowledged, modifier = Modifier.weight(1f), onClick = {
                        val id = state.preview?.id ?: return@TextButton
                        val agreed = acknowledged; resetDialog = false
                        scope.launch { controller.confirm(id, agreed) }
                    })
                }
            }
        }
    }
}
