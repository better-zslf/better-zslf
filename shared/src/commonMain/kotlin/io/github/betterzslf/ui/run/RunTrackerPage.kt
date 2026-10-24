package io.github.betterzslf.ui.run

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.feature.runui.*
import io.github.betterzslf.platform.rememberLocationPermissionRequester
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** No DisposableEffect closes runtime: navigation leaves app-level real recording intact. */
@Composable
internal fun RunTrackerPage(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit, runtime: RunRuntime) {
    val currentSession by graph.sessions.session.collectAsState()
    val runtimeState by runtime.state.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    val model = if (runtime.isCurrentSession() && runtimeState.ownerId == currentSession?.userId) runtimeState else RunRuntimeState(ownerId = currentSession?.userId, requiresRestore = true)
    val local = model.session
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    val selection = RunTaskSelection.decode(page.id) ?: model.taskSelection
    val query = if (selection == null) RunPageQuery() else rememberRunQuery(graph, selection, refresh) { it.detail(selection.errandId, it.boundOwnerId.orEmpty(), selection.errandScheduleId) }
    val detail = remember(query.data, selection) { query.data?.let(RunDataDecoder::detail)?.let { data -> selection?.let(data::withQueryContext) ?: data } }
    var permissionGranted by remember { mutableStateOf(false) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val permission = rememberLocationPermissionRequester { granted ->
        permissionGranted = granted
        permissionMessage = if (granted) "精确定位已允许；仍需明确按开始或继续" else "精确定位尚未允许，请在系统设置检查后重试"
    }
    var action by remember { mutableStateOf<String?>(null) }
    var captureConfirmed by remember(action) { mutableStateOf(false) }
    var uploadConfirmed by remember { mutableStateOf(false) }
    var uploadPreview by remember { mutableStateOf<List<RunQueuedBatch>?>(null) }
    var sampleWindow by remember { mutableIntStateOf(20) }
    val busy = model.busy || model.closed
    val isCapturing = local.phase == RunSessionPhase.RECORDING
    val canResume = local.phase in listOf(RunSessionPhase.PAUSED, RunSessionPhase.RECOVERED) && !local.finishRequested
    val canEnd = local.metadata != null && local.phase in listOf(RunSessionPhase.RECORDING, RunSessionPhase.PAUSED, RunSessionPhase.RECOVERED, RunSessionPhase.FINISHING)
    val pending = model.queue.filter { it.status != RunBatchStatus.ACKNOWLEDGED }

    Screen(page.title.ifBlank { "真实跑步记录" }, back, actions = { TextButton("本机记录", enabled = !busy, onClick = { scope.launch { runtime.loadLocalSessions() } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("native-only") { SectionCard("真实定位 · 原始 WGS84", "仅原生定位器可以向本记录加入采样点。地图事件、导入路线与生成预览均不作为 GPS。离开本页不会结束 app 级记录。") }
            if (model.requiresRestore) item("restore-required") { SectionCard("需要明确选择本地记录", "账号或登录状态已变化；不会自动重新开启定位或发送旧任务。") }
            model.message?.let { message -> item("runtime-message") { SectionCard("本地操作与队列状态", message) } }
            if (currentSession?.userId.isNullOrBlank()) item("login") { SectionCard("请先登录", "真实记录归属于当前账号；现有其他账号数据保持隔离。") }
            item("metrics") { RunMetrics(local, settings.metricUnits) }
            item("native-state") { SectionCard("定位服务", "${local.nativeLocation.phase}\n${local.nativeLocation.message.orEmpty()}\n当前采样设置 ${settings.samplingSeconds} 秒；已开始的会话使用开始时的设置，不会中途悄悄改时间。") }
            permissionMessage?.let { message -> item("permission-result") { SectionCard("系统定位权限", message) } }
            item("permission") { TextButton("检查 / 申请精确定位权限", enabled = !busy, onClick = permission::launch, modifier = Modifier.fillMaxWidth()) }
            if (local.metadata == null || local.phase in listOf(RunSessionPhase.FINISHED, RunSessionPhase.CANCELLED)) {
                runFeedback(query, { refresh++ })
                if (detail != null) {
                    item("task") { SectionCard("待开始任务 · ${detail.task.title}", "任务：${detail.task.errandId ?: "未返回"}\n排期：${detail.task.scheduleId ?: "未返回"}\n明确开始后按认证 account + 当前时间生成并保存本轮会话 trid") }
                    runWarnings(detail.warnings)
                    item("start") { TextButton("开始真实记录", enabled = !busy && permissionGranted && detail.canStart && !currentSession?.userId.isNullOrBlank(), onClick = { action = "start" }, modifier = Modifier.fillMaxWidth()) }
                    if (!detail.canStart) item("start-blocked") { SectionCard("任务缺少开始所需字段", "待补齐：${detail.missingFields.joinToString()}。当前只展示，不会编造任务、计划、账号或坐标。") }
                } else item("choose-task") { SectionCard("请先选择任务", "开始新记录需要服务端任务详情。已有本机记录可用顶部按钮读取，再明确恢复。", { open(Route.Page("runs", title = "校园跑")) }) }
            }
            if (local.metadata != null) {
                item("selected") { SectionCard("当前本机记录", "trid：${local.metadata.trid}\n${runPhase(local.phase)}\n${runUploadPhase(local.uploadPhase)}") }
                item("native-map") { RunMapPreview(graph, listOf("原生 WGS84 实测轨迹" to local.acceptedSamples.map { it.point }), coordinateSystem = io.github.betterzslf.platform.RunMapCoordinateSystem.WGS84, role = RunMapRole.ACTUAL) }
                if (isCapturing) item("pause") { TextButton("暂停采样", enabled = !busy, onClick = { action = "pause" }, modifier = Modifier.fillMaxWidth()) }
                if (canResume) item("resume") { TextButton("明确继续采样", enabled = !busy && permissionGranted, onClick = { action = "resume" }, modifier = Modifier.fillMaxWidth()) }
                if (canEnd) item("end-actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(if (local.phase == RunSessionPhase.FINISHING) "完成本地结束保存" else "结束并保留轨迹", enabled = !busy, onClick = { action = "finish" }, modifier = Modifier.weight(1f))
                        if (local.phase != RunSessionPhase.FINISHING) TextButton("取消记录", enabled = !busy, onClick = { action = "cancel" }, modifier = Modifier.weight(1f))
                    }
                }
                if (local.hasUnpersistedSample) item("retry-disk") { SectionCard("有一个真实点尚未保存", "请先重试本地持久化；不会替换为新坐标或伪造时间。", { scope.launch { runtime.retryPersistence() } }) }
                if (local.acceptedSampleCount <= 1 && local.phase == RunSessionPhase.FINISHED) item("short") { SectionCard("零点 / 单点记录已保留", "没有补造轨迹或 finishType=1 假点；此记录只保存在本机。") }
                item("queue-refresh") { TextButton("刷新本地上传队列", enabled = !busy, onClick = { scope.launch { runtime.refreshQueue() } }) }
                itemsIndexed(model.queue, key = { _, row -> "batch:${row.batch.batchId}" }) { _, row ->
                    SectionCard("批次 ${row.batch.index + 1} · ${row.status}", "${row.batch.batchId}\nfinishType=${row.batch.payload.finishType} · 已尝试 ${row.attempts} 次\n${row.failures.lastOrNull()?.message.orEmpty()}")
                }
                if (model.queue.any { it.status == RunBatchStatus.UNCERTAIN || it.status == RunBatchStatus.IN_FLIGHT }) item("uncertain") { SectionCard("结果待核对", "已发请求可能已被接收。保持 UNCERTAIN / IN_FLIGHT，不猜测失败、不自动重发，也不冒充成绩成功。") }
                if (local.phase == RunSessionPhase.FINISHED && pending.isNotEmpty()) item("upload") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton("预览并最终确认上传", enabled = !busy && model.upload != RunUploadUiPhase.SENDING && model.transportAvailable,
                            onClick = { uploadConfirmed = false; uploadPreview = pending.toList() }, modifier = Modifier.fillMaxWidth())
                        if (!model.transportAvailable) Text("轨迹已保存在本机，当前版本尚未启用上传。")
                    }
                }
                if (model.upload == RunUploadUiPhase.SENDING) item("stop-upload") { TextButton("停止后续发送", onClick = { scope.launch { runtime.stopUpload() } }) }
                if (local.finishRequested) item("server-record") { TextButton("只读查询服务端成绩 / 轨迹", onClick = { open(Route.Page("runrecord", local.metadata.trid, "服务端成绩")) }) }
                itemsIndexed(local.acceptedSamples.takeLast(sampleWindow).reversed(), key = { index, row -> "native:${row.timestampMillis}:$index" }) { index, sample ->
                    SectionCard("真实点 ${local.acceptedSampleCount - index}", "${coordinate(sample.point)}\n原始时间戳 ${sample.timestampMillis} ms · 精度 ${RunDisplay.decimal(sample.accuracyMeters)} 米\n原生速度 ${sample.speedMetersPerSecond?.let { "${RunDisplay.decimal(it)} m/s" } ?: "未提供"}")
                }
                if (sampleWindow < local.acceptedSampleCount) item("more-samples") { TextButton("展开更多真实点", onClick = { sampleWindow += 20 }) }
            }
            item("history-read") { TextButton("读取当前账号的本机记录", enabled = !busy, onClick = { scope.launch { runtime.loadLocalSessions() } }) }
            itemsIndexed(model.localSessions, key = { index, row -> "local:${row.metadata.trid}:$index" }) { _, row ->
                SectionCard("本机记录 · ${row.metadata.trid}", "${runPhase(row.phase)} · ${row.acceptedSampleCount} 个真实点\n点按恢复查看，不开启定位、不发送尾块。", { scope.launch { runtime.restore(row.metadata.trid) } })
            }
            if (selection != null) item("import-mode") { TextButton("仅本次切换为路线导入预览", onClick = { open(Route.Page("runimport", selection.encode(), "路线导入预览")) }) }
        }
    }

    OverlayDialog(show = action != null, title = when (action) { "start" -> "开始真实定位记录？"; "pause" -> "暂停采样？"; "resume" -> "继续真实定位记录？"; "finish" -> "结束并保存本机记录？"; else -> "取消本次记录？" },
        summary = when (action) {
            "start" -> "开始时使用 ${settings.samplingSeconds} 秒原生采样。记录归当前账号，仅保存真实位置；本动作不会发送网络请求。"
            "pause" -> "暂停后不接收新的采样点；已保存点不改变。"
            "resume" -> "需要再次确认本次真实轨迹；不会修改旧点时间戳或补造暂停段位置。"
            "finish" -> "停止采样并保留零点、单点或完整轨迹。末块只生成一次；上传仍是独立最终确认。"
            else -> "停止采样并暂停未来未发送批次，原始点仍保留。已发请求的接收结果保持待核对。"
        }, onDismissRequest = { action = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (action == "start" || action == "resume") RunCheck(captureConfirmed, "我确认当前账号本次真实轨迹的上传用途；实际发送还需最终确认") { captureConfirmed = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("返回", onClick = { action = null }, modifier = Modifier.weight(1f))
                TextButton("确认", enabled = !busy && (!(action == "start" || action == "resume") || captureConfirmed && permissionGranted), onClick = {
                    val confirmedAction = action
                    val confirmedCapture = captureConfirmed
                    action = null
                    scope.launch {
                        when (confirmedAction) {
                            "start" -> detail?.let { runtime.start(it, confirmedCapture, settings.samplingSeconds) }
                            "pause" -> runtime.pause()
                            "resume" -> runtime.resume(confirmedCapture)
                            "finish" -> runtime.finish(true)
                            "cancel" -> runtime.cancel(true)
                        }
                    }
                }, modifier = Modifier.weight(1f))
            }
        }
    }

    val preview = uploadPreview
    OverlayDialog(show = preview != null, title = "最终确认上传真实轨迹", summary = "核对以下精确批次；数据变化后本确认自动失效。接收确认不等于服务端成绩通过。", onDismissRequest = { uploadPreview = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(preview.orEmpty(), key = { _, row -> row.batch.batchId }) { _, row ->
                    Text("${row.batch.batchId}\n归属 ${row.batch.payload.userId} · 末块 ${row.batch.payload.finishType}\n${row.status}")
                }
            }
            RunCheck(uploadConfirmed, "我已核对当前账号、真实轨迹与全部待发送批次，确认现在发送") { uploadConfirmed = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("保留本机", onClick = { uploadPreview = null }, modifier = Modifier.weight(1f))
                TextButton("现在发送", enabled = uploadConfirmed && model.transportAvailable && model.upload != RunUploadUiPhase.SENDING, onClick = {
                    val expected = preview.orEmpty().map { it.batch.batchId }
                    uploadPreview = null
                    scope.launch { runtime.upload(expected, true) }
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}
