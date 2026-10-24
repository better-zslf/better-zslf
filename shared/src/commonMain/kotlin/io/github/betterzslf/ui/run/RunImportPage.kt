package io.github.betterzslf.ui.run

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.feature.runui.*
import io.github.betterzslf.platform.FilePickResult
import io.github.betterzslf.platform.rememberFilePicker
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import top.yukonga.miuix.kmp.basic.*

/** Entire draft is route-local and in-memory; this page never changes preferredRunMode/settings. */
@Composable
internal fun RunImportPage(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val settings by graph.settings.settings.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val selection = remember(page.id) { RunTaskSelection.decode(page.id) }
    val query = if (selection == null) RunPageQuery() else rememberRunQuery(graph, selection, refresh) { it.detail(selection.errandId, it.boundOwnerId.orEmpty(), selection.errandScheduleId) }
    val detail = remember(query.data, selection) { query.data?.let(RunDataDecoder::detail)?.let { data -> selection?.let(data::withQueryContext) ?: data } ?: localPreviewDetail() }
    val controller = remember(detail) { RunImportController(detail) { kotlin.time.Clock.System.now().toEpochMilliseconds() } }
    val model by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var target by remember(detail) { mutableStateOf(detail.minimumDistanceMeters?.toString() ?: "1000") }
    var minutes by remember { mutableStateOf("20") }
    var seed by remember { mutableStateOf("0") }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableIntStateOf(40) }
    val picker = rememberFilePicker { result ->
        when (result) {
            FilePickResult.Cancelled -> fileMessage = "已取消选择，原预览保持不变"
            is FilePickResult.Failed -> fileMessage = result.message
            is FilePickResult.Selected -> { fileMessage = null; scope.launch { controller.read(result.file) } }
        }
    }
    val distance = target.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val duration = minutes.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 1440 }?.let { (it * 60_000).toLong() }?.takeIf { it > 0 }
    val seedNumber = seed.trim().toIntOrNull()
    val parameterized = distance != null && duration != null
    val changed = model.validation != null && (model.targetDistanceMeters != distance || model.durationMillis != duration)
    val busy = model.busy || query.loading
    val frame = model.workingCoordinateSystem

    Screen(page.title.ifBlank { "路线导入 · 本机预览" }, back, actions = { TextButton("清空草稿", enabled = !busy, onClick = { scope.launch { controller.clear() } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("local-only") { SectionCard("仅本机路线预览", "导入原始路线、几何修整与生成预览分别保留；都不是真实 GPS，不进入真实记录队列，不自动上传。离开页面后草稿不持久化，本次切换不更改默认模式。") }
            runFeedback(query, { refresh++ })
            runWarnings(model.warnings)
            item("source-actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton("选择 GPX / KML", enabled = !busy, onClick = picker::launch, modifier = Modifier.weight(1f))
                    TextButton("使用推荐路径", enabled = !busy && detail.walkPaths.isNotEmpty(), onClick = { scope.launch { controller.useRecommended() } }, modifier = Modifier.weight(1f))
                }
            }
            item("size-limit") { Text("文件选择上限 8 MiB；XML 解析器另有字符/点数上限，不加载外部实体。GPX/KML 按标准标注 WGS84，未确认的服务端坐标不会被悄悄换系。") }
            fileMessage?.let { message -> item("file-message") { SectionCard("文件选择", message) } }
            model.error?.let { message -> item("error") { SectionCard("本机预览未完成", message) } }
            if (model.busy) item("busy") { CircularProgressIndicator() }
            if (model.source != null) {
                item("source") { SectionCard(model.fileName ?: "路线草稿", "来源 ${model.source}\n原始 ${model.originalPoints.size} 点 / ${model.originalSegments.size} 段 · 工作副本 ${model.workingPoints.size} 点\n${if (model.normalized) "已显式抽稀/加密" else "未作密度修整"} · ${if (model.detoured) "已作本机补距" else "未补距"}") }
                item("original-map") { RunMapPreview(graph, model.originalSegments.mapIndexed { index, points -> "原始段 ${index + 1}" to points }, coordinateSystem = model.originalCoordinateSystem) }
            }
            item("target") { TextField(target, { target = it }, enabled = !busy, label = "本机目标距离（米）", modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("duration") { TextField(minutes, { minutes = it }, enabled = !busy, label = "目标用时（分钟，最多 1440）", modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("seed") { TextField(seed, { seed = it }, enabled = !busy, label = "可复现生成种子（整数）", modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("requirements") { SectionCard("校验参数", "服务端最低距离：${RunDisplay.distance(detail.minimumDistanceMeters, settings.metricUnits)}\n本机输入始终以米 / 分钟计，显示单位随设置。生成间隔使用当前 ${settings.samplingSeconds} 秒设置。" + if (detail.minimumDistanceMeters == null) "\n服务端未明确最低距离单位，仅校验本机目标，不推定已满足学校规则。" else "") }
            if (!parameterized) item("bad-input") { Text("请输入正数米值和 0 至 1440 分钟之间的目标用时。") }
            if (changed) item("stale-report") { SectionCard("目标参数已变化", "下方报告仍对应此前明确记录的参数，请重新校验或生成。") }
            item("geometry-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton("显式抽稀 / 加密工作副本", enabled = !busy && model.originalSegments.isNotEmpty(), onClick = { scope.launch { controller.normalize() } }, modifier = Modifier.fillMaxWidth())
                    TextButton("校验覆盖 / 距离 / 速度 / 密度", enabled = !busy && parameterized && model.workingPoints.isNotEmpty(), onClick = { scope.launch { controller.validate(distance!!, duration!!) } }, modifier = Modifier.fillMaxWidth())
                    TextButton("沿推荐路径尝试折返补距", enabled = !busy && parameterized && detail.walkPaths.isNotEmpty() && model.workingPoints.isNotEmpty(), onClick = { scope.launch { controller.supplement(distance!!, duration!!) } }, modifier = Modifier.fillMaxWidth())
                }
            }
            model.validation?.let { report ->
                item("report-parameters") { SectionCard("本次几何报告参数", "本机目标 ${RunDisplay.distance(model.targetDistanceMeters, settings.metricUnits)} · 用时 ${RunDisplay.duration(model.durationMillis)}\n工作副本长 ${RunDisplay.distance(report.distanceMeters, settings.metricUnits)} · 均速 ${RunDisplay.decimal(report.averageSpeedMetersPerSecond, 2)} m/s") }
                routeReportItems(report, detail, "working")
            }
            if (model.normalized || model.detoured) item("working-map") { RunMapPreview(graph, listOf("显式处理后的工作副本" to model.workingPoints), coordinateSystem = frame) }
            item("generate") { TextButton("生成本机时序预览（不上传）", enabled = !busy && parameterized && seedNumber != null && model.workingPoints.size >= 2,
                onClick = { scope.launch { controller.generate(distance!!, duration!!, seedNumber!!, settings.samplingSeconds) } }, modifier = Modifier.fillMaxWidth()) }
            val generated = model.generated
            if (generated != null) {
                item("generated") { SectionCard("生成预览 · 非 GPS", "${generated.points.size} 个生成点 · ${RunDisplay.distance(generated.distanceMeters, settings.metricUnits)}\n${RunDisplay.duration(generated.endTimeMillis - generated.startTimeMillis)} · 设置间隔 ${model.generationSamplingSeconds} 秒\n逐点时间/距离/总均速一致性已校验，但不代表学校已接受或已完成任务。") }
                item("generated-map") { RunMapPreview(graph, listOf("生成预览，非实测" to generated.points.map { it.point }), coordinateSystem = frame) }
                model.generatedValidation?.let { routeReportItems(it, detail, "generated") }
                itemsIndexed(generated.points.take(visible), key = { index, _ -> "generated:$index" }) { index, point ->
                    SectionCard("生成点 ${index + 1} · 非真实定位", "${coordinate(point.point)}\n时间 ${point.locatetime} ms · 本段 ${RunDisplay.distance(point.dis, settings.metricUnits)} · 累计秒 ${RunDisplay.decimal(point.count)}")
                }
                if (visible < generated.points.size) item("more-generated") { TextButton("展开更多生成点", onClick = { visible += 40 }) }
            } else {
                itemsIndexed(model.originalPoints.take(visible), key = { index, _ -> "original:$index" }) { index, point -> SectionCard("原始导入坐标 ${index + 1}", coordinate(point)) }
                if (visible < model.originalPoints.size) item("more-original") { TextButton("展开更多原始坐标", onClick = { visible += 40 }) }
            }
            if (page.id.isNotBlank()) item("real-mode") { TextButton("仅本次切回真实记录", onClick = { open(Route.Page("runtracker", page.id, "真实跑步记录")) }) }
        }
    }
}

private fun localPreviewDetail() = RunTaskDetail(
    RunTaskItem(null, null, "未选择服务端任务", null, null, null, "local-preview-only", JsonObject(emptyMap())),
    emptyList(), emptyList(), null, listOf("未选择可识别任务：顺序打卡点与学校最低距离待核实；当前只作本机几何预览"),
)

private fun LazyListScope.routeReportItems(report: RouteValidationReport, detail: RunTaskDetail, prefix: String) {
    val groups = listOf(
        "顺序打卡覆盖" to setOf(RouteIssueCode.MISSING_CHECKPOINT, RouteIssueCode.CHECKPOINT_ORDER),
        "距离要求" to setOf(RouteIssueCode.DISTANCE_TOO_SHORT, RouteIssueCode.DISTANCE_TOO_LONG, RouteIssueCode.TOO_FEW_POINTS),
        "目标速度 ≤ 5 m/s" to setOf(RouteIssueCode.SPEED),
        "点间密度 0.5 至 45 米" to setOf(RouteIssueCode.SPARSE, RouteIssueCode.DENSE),
    )
    groups.forEachIndexed { index, (label, codes) ->
        val issues = report.issues.filter { it.code in codes }
        val unknown = index == 0 && (detail.checkpoints.isEmpty() || detail.checkpoints.size != detail.checkpointSourceCount)
        val text = when {
            unknown -> "任务打卡点缺失或损坏，本项未完整核实"
            issues.isNotEmpty() -> issues.joinToString("\n") { it.message + (it.index?.let { position -> "（索引 $position）" } ?: "") }
            index == 1 && detail.minimumDistanceMeters == null -> "本机目标通过；学校最低距离未明确，尚未核实"
            else -> "本地几何校验通过，不代表服务端接受"
        }
        item("$prefix:report:$index") { SectionCard(label, text) }
    }
}
