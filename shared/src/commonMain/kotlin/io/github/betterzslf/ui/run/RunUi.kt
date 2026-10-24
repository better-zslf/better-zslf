package io.github.betterzslf.ui.run

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.RunRepository
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.feature.runui.RunDisplay
import io.github.betterzslf.platform.*
import io.github.betterzslf.ui.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class RunPageQuery(val loading: Boolean = false, val data: JsonElement? = null, val error: String? = null, val message: String? = null)

/** Each request captures an atomic repository binding; late results stay in the old composition key. */
@Composable
internal fun rememberRunQuery(graph: AppGraph, key: Any?, refresh: Int, load: suspend (RunRepository) -> ApiResult<JsonElement>): RunPageQuery {
    val loader by rememberUpdatedState(load)
    val session by graph.sessions.session.collectAsState()
    val result by produceState(RunPageQuery(loading = true), graph, session?.userId, session?.token, key, refresh) {
        val previous = value.data
        value = RunPageQuery(loading = true, data = previous)
        val bound = graph.repositories.run.boundToCurrentSession()
        if (bound == null) value = RunPageQuery(data = previous, error = "请先登录，再查看当前账号的校园跑数据")
        else try {
            value = when (val response = loader(bound)) {
                is ApiResult.Success -> RunPageQuery(data = response.data, message = response.message.takeIf { it.isNotBlank() })
                is ApiResult.Failure -> RunPageQuery(data = previous, error = response.error.message)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = RunPageQuery(data = previous, error = "校园跑读取失败，可手动刷新；不会重试写操作") }
    }
    return result
}

internal fun missingRunId() = ApiResult.Failure(ApiError.Validation(listOf("trid"), "缺少跑步记录标识，请重新选择记录"))
internal fun missingRunSelection() = ApiResult.Failure(ApiError.Validation(listOf("errandId", "errandScheduleId"), "任务信息不完整，请返回任务列表重新选择"))

internal fun LazyListScope.runFeedback(query: RunPageQuery, retry: () -> Unit, keyPrefix: String = "read") {
    if (query.loading) item("$keyPrefix:loading") { Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
    query.error?.let { error -> item("$keyPrefix:error") { SectionCard(if (query.data == null) "读取失败" else "刷新失败，保留上次结果", error + "\n点按重新查询", retry) } }
    query.message?.let { text -> item("$keyPrefix:message") { SectionCard("服务端提示", text) } }
}

internal fun LazyListScope.runWarnings(warnings: List<String>, keyPrefix: String = "warning") {
    itemsIndexed(warnings.distinct(), key = { index, _ -> "$keyPrefix:$index" }) { _, warning -> SectionCard("信息与限制", warning) }
}

@Composable
internal fun RunCheck(checked: Boolean, text: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, onClick = { onChange(!checked) }, enabled = enabled)
        Text(text, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun RunMetrics(session: RunSessionState, metric: Boolean) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Text("真实记录 · ${runPhase(session.phase)}", style = MiuixTheme.textStyles.title4)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { Text("距离"); Text(RunDisplay.distance(session.distanceMeters, metric), style = MiuixTheme.textStyles.title4) }
            Column(Modifier.weight(1f)) { Text("采样用时"); Text(RunDisplay.duration(session.elapsedMillis), style = MiuixTheme.textStyles.title4) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { Text("平均配速"); Text(RunDisplay.pace(session.distanceMeters, session.elapsedMillis, metric)) }
            Column(Modifier.weight(1f)) { Text("距下一点"); Text(RunDisplay.distance(session.distanceToNextCheckpointMeters, metric)) }
        }
        Spacer(Modifier.height(12.dp))
        Text("已保存 ${session.acceptedSampleCount} 个真实点 · 过滤 ${session.rejectedSampleCount} 个点\n用时按首末真实时间戳计算，含暂停间隔；暂停路段按前后真实点直线估距。", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

internal fun runPhase(phase: RunSessionPhase): String = when (phase) {
    RunSessionPhase.IDLE -> "尚未开始"
    RunSessionPhase.RECORDING -> "正在定位记录"
    RunSessionPhase.PAUSED -> "已暂停采样"
    RunSessionPhase.RECOVERED -> "本机恢复预览 · 未开定位"
    RunSessionPhase.FINISHING -> "结束状态待保存"
    RunSessionPhase.FINISHED -> "本机记录已结束"
    RunSessionPhase.CANCELLED -> "已取消，原始点保留"
    RunSessionPhase.OWNER_MISMATCH -> "账号归属不一致"
    RunSessionPhase.CLOSED -> "已关闭"
}

internal fun runUploadPhase(phase: RunSessionUploadPhase): String = when (phase) {
    RunSessionUploadPhase.NONE -> "尚无可上传块"
    RunSessionUploadPhase.INSUFFICIENT_SAMPLES -> "零点或单点，仅保留本机"
    RunSessionUploadPhase.STAGED -> "待确认，未发送"
    RunSessionUploadPhase.CONFIRMED -> "已准备，仍需最终明确上传"
    RunSessionUploadPhase.IN_FLIGHT -> "正在等待接收结果"
    RunSessionUploadPhase.ACKNOWLEDGED -> "批次已确认接收，成绩仍需查询"
    RunSessionUploadPhase.FAILED -> "已失败，未自动重试"
    RunSessionUploadPhase.UNCERTAIN -> "结果待核对，不自动重发"
}

internal enum class RunMapRole { ACTUAL, RECOMMENDED, IMPORTED }

/** No concatenated polyline is drawn across separate source segments. Select a segment explicitly. */
@Composable
internal fun RunMapPreview(
    graph: AppGraph,
    paths: List<Pair<String, List<GeoPoint>>>,
    checkpoints: List<RunCheckpoint> = emptyList(),
    coordinateSystem: RunMapCoordinateSystem = RunMapCoordinateSystem.GCJ02,
    role: RunMapRole = RunMapRole.IMPORTED,
) {
    val useful = paths.filter { it.second.isNotEmpty() }
    val session by graph.sessions.session.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    var selected by remember(paths) { mutableIntStateOf(0) }
    var feedback by remember(paths, coordinateSystem) { mutableStateOf<String?>(null) }
    val path = useful.getOrNull(selected.coerceIn(0, (useful.size - 1).coerceAtLeast(0)))
    val line = RunMapPath(coordinateSystem = coordinateSystem, segments = useful.map { it.second })
    val dark = when (settings.theme) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    // Verified against the original client by Lead; unknown values have no guessed/key fallback.
    val provider = runMapProviderFor(session?.mapWebType)
    val model = RunMapModel(provider = provider, apiKey = session?.mapToken,
        actualPath = if (role == RunMapRole.ACTUAL) line else RunMapPath(),
        recommendedPath = if (role == RunMapRole.RECOMMENDED) line else RunMapPath(),
        importedPath = if (role == RunMapRole.IMPORTED) line else RunMapPath(),
        checkpoints = checkpoints.mapIndexed { index, point -> RunMapCheckpoint(point.id, point.point, "顺序点 ${index + 1}", RunMapCoordinateSystem.GCJ02) },
        currentLocation = if (role == RunMapRole.ACTUAL) path?.second?.lastOrNull() else null,
        currentCoordinateSystem = coordinateSystem, dark = dark, editMode = false)
    if (useful.isEmpty() && checkpoints.isEmpty()) {
        SectionCard("暂无可展示坐标", "尚未取得真实位置或路线；页面没有模拟 GPS 或自动打卡。")
    } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        RunMapPanel(model, { event ->
            feedback = when (event) {
                RunMapEvent.Ready -> "地图已就绪；选择标记只查看，不会发打卡请求"
                is RunMapEvent.Failed -> "地图降级：${event.reason}，下方坐标预览仍可查看"
                is RunMapEvent.CheckpointSelected -> "已选顺序点 ${checkpoints.indexOfFirst { it.id == event.id }.takeIf { it >= 0 }?.plus(1) ?: "未知"}；未执行打卡"
                is RunMapEvent.DrawPoint -> "此页仅预览，地图绘制事件未加入任何真实定位记录"
            }
        }, Modifier.fillMaxWidth().height(280.dp))
        if (useful.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton("上一段", enabled = selected > 0, onClick = { selected-- })
            Text("${selected + 1} / ${useful.size} 段")
            TextButton("下一段", enabled = selected + 1 < useful.size, onClick = { selected++ })
        }
        feedback?.let { Text(it) }
        SectionCard("原始坐标预览 · $coordinateSystem", (path?.let { (name, points) ->
            "$name：${points.size} 点\n首点 ${coordinate(points.first())}\n末点 ${coordinate(points.last())}"
        } ?: "无可用路径") + "\n顺序点 ${checkpoints.size} 个；分段不会被画成假连线。")
    }
}

internal fun coordinate(point: GeoPoint): String = "${point.longitude},${point.latitude}"

internal fun runFields(raw: JsonObject): List<Pair<String, String>> = raw.entries.mapNotNull { (key, value) ->
    if (listOf("password", "token", "authorization", "cookie").any { key.contains(it, ignoreCase = true) }) null
    else (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { key to it.take(400) }
}

internal fun LazyListScope.runRawFields(raw: JsonObject, prefix: String = "raw") {
    itemsIndexed(runFields(raw), key = { index, _ -> "$prefix:$index" }) { _, field -> SectionCard(field.first, field.second) }
}
