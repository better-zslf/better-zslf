package io.github.betterzslf.ui.campusnet

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

    Screen("校园网", onBack, actions = {
        TextButton("刷新", enabled = !state.busy && state.phase != CampusPasswordPhase.SESSION_CHANGED,
            onClick = { scope.launch { controller.refresh() } })
    }) { padding ->
        PullToRefresh(state.loading, { scope.launch { controller.refresh() } },
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("intro") { SectionCard("已办理的运营商宽带账号", "只查询当前登录会话的账号。账号已遮盖；不会预填手机号或密码。") }
                if (state.loading) item("loading") { CircularProgressIndicator() }
                state.queryMessage?.let { message -> item("query-error") {
                    SectionCard("账号查询提示", message + "；原有列表保留，需成功刷新后再选操作账号。")
                } }
                if (!state.loading && state.queryMessage == null && state.accounts.isEmpty() && state.phase != CampusPasswordPhase.SESSION_CHANGED)
                    item("empty") { EmptyCard("暂无已办理的宽带账号") }
                items(state.accounts, key = { it.key }) { account ->
                    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
                        Text("宽带账号：${account.maskedAccount}")
                        if (account.typeStatus.isNotBlank()) Text("类型状态：${account.typeStatus}")
                        if (!account.canChangePassword) Text("缺少或存在重复的账号标识，当前只读，不生成修改目标。")
                        if (state.expanded) TextButton(
                            if (state.selectedAccountKey == account.key) "已选择此账号" else "选择此账号",
                            enabled = selectable && account.canChangePassword,
                            onClick = { controller.selectAccount(account.key) })
                    }
                }
                item("operation-toggle") {
                    SectionCard("运营商密码操作", if (state.expanded) "点按收起并清除输入" else "默认隐藏；手动展开后才进入操作流程",
                        onClick = { controller.setExpanded(!state.expanded) })
                }
                if (state.expanded && !state.terminal) {
                    item("warning") { SectionCard("这里不是学校登录密码", "仅针对上方主动选中的运营商宽带账号。手机号检查可能发送短信，不等于短信验证码核验；本页不会自动检查手机号或修改密码，也不执行一键联网。") }
                    if (selected == null) item("choose") { SectionCard("先选择账号", "只接受本次成功查询返回的有效账号标识；资料不足时保持只读。") }
                    item("phone") { TextField(phone, { phone = it; password = ""; confirmation = ""; controller.forgetPhoneCheck() },
                        label = "手动输入手机号", singleLine = true, enabled = editable && selected?.canChangePassword == true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth()) }
                    item("check-phone") { Button(enabled = editable && !state.phoneChecked && selected?.canChangePassword == true && campusPhoneValidation(phone) == null,
                        modifier = Modifier.fillMaxWidth(), onClick = { scope.launch { controller.checkPhone(phone) } }) {
                        Text(if (state.checkingPhone) "正在检查…" else if (state.phoneChecked) "手机号检查已通过" else "检查手机号（可能发送短信）")
                    } }
                    if (state.phoneChecked) {
                        item("new-password") { TextField(password, { password = it }, label = "新的运营商宽带密码", singleLine = true,
                            enabled = editable, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
                        item("repeat-password") { TextField(confirmation, { confirmation = it }, label = "确认运营商宽带密码", singleLine = true,
                            enabled = editable, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()) }
                        item("prepare") { Button(enabled = editable, modifier = Modifier.fillMaxWidth(),
                            onClick = { controller.prepare(phone, password, confirmation) }) { Text("核对并准备预览") } }
                    }
                }
                state.preview?.let { preview ->
                    item("preview") { SectionCard("本次操作预览", "运营商账号：${preview.maskedAccount}\n手机号：${preview.maskedPhone}\n动作：修改运营商宽带密码\n新密码不展示、不保存；提交后本页不重复发送。") }
                    if (state.phase == CampusPasswordPhase.PREVIEW) item("final-actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton("返回编辑", modifier = Modifier.weight(1f), onClick = { controller.editPreview() })
                            TextButton("继续最终确认", modifier = Modifier.weight(1f), onClick = { finalDialog = true })
                        }
                    }
                }
                if (state.checkingPhone || state.phase == CampusPasswordPhase.SUBMITTING) item("operation-progress") { CircularProgressIndicator() }
                state.formMessage?.let { message -> item("form-message") {
                    SectionCard(when (state.phase) {
                        CampusPasswordPhase.COMPLETED -> "服务回执"
                        CampusPasswordPhase.UNCERTAIN -> "结果尚未确认 · 已停止"
                        CampusPasswordPhase.FAILED -> "本次操作已停止"
                        CampusPasswordPhase.SESSION_CHANGED -> "登录会话已变化"
                        else -> "操作提示"
                    }, message)
                } }
                if (state.terminal) item("terminal") { Text("仅可刷新账号查询；本页不会重试密码修改。需要进一步操作时，请先核对实际结果。") }
            }
        }
        OverlayDialog(show = finalDialog && state.phase == CampusPasswordPhase.PREVIEW,
            title = "最终确认修改运营商密码？",
            summary = "目标账号：${state.preview?.maskedAccount.orEmpty()}。这会真实修改运营商宽带密码，不是学校账号登录密码。请先核对上方完整预览。",
            onDismissRequest = { finalDialog = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(if (acknowledged) ToggleableState.On else ToggleableState.Off, onClick = { acknowledged = !acknowledged })
                    Text("我已核对目标与操作，确认只提交这一次", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton("返回预览", modifier = Modifier.weight(1f), onClick = { finalDialog = false })
                    TextButton("确认修改", enabled = acknowledged, modifier = Modifier.weight(1f), onClick = {
                        val id = state.preview?.id ?: return@TextButton
                        finalDialog = false
                        scope.launch { controller.confirm(id, acknowledged) }
                    })
                }
            }
        }
    }
}
