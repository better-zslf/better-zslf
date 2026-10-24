package io.github.betterzslf.ui.run

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.feature.runui.*
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*

/** The app owns runtime. Route composition never disposes a tracker, coordinator, or upload queue. */
@Composable
fun RunFeaturePage(graph: AppGraph, page: Route.Page, onOpen: (Route) -> Unit, onBack: () -> Unit, runtime: RunRuntime) {
    val session by graph.sessions.session.collectAsState()
    key(graph, session?.userId, session?.token, page) {
        when (page.kind) {
            "runs" -> RunTaskList(graph, onOpen, onBack)
            "rundetail" -> RunDetailPage(graph, page, onOpen, onBack)
            "runrecords" -> RunRecordsPage(graph, page, onOpen, onBack, runtime)
            "runrecord" -> RunRecordPage(graph, page, onBack)
            "runtracker" -> RunTrackerPage(graph, page, onOpen, onBack, runtime)
            "runimport" -> RunImportPage(graph, page, onOpen, onBack)
            else -> Screen(page.title.ifBlank { "校园跑" }, onBack) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                    item { SectionCard("页面类型未识别", "请从任务、真实记录或本机路线预览入口重新进入。") }
                }
            }
        }
    }
}

@Composable
fun RunTaskList(graph: AppGraph, onOpen: (Route) -> Unit, onBack: (() -> Unit)? = null) {
    val session by graph.sessions.session.collectAsState()
    key(graph, session?.userId, session?.token) {
        val owner = session?.userId.orEmpty()
        val bound = remember(graph, session?.userId, session?.token) { graph.repositories.run.boundToCurrentSession() }
        val controller = remember(bound, owner) { RunTaskController(RunTaskPageSource { requestedOwner, offset, limit, filter ->
            if (owner.isBlank() || bound == null || bound.boundOwnerId != requestedOwner) ApiResult.Failure(ApiError.Authentication())
            else bound.tasks(requestedOwner, offset, limit, filter)
        }, owner) }
        val state by controller.state.collectAsState()
        val scope = rememberCoroutineScope()
        val list = rememberLazyListState()
        LaunchedEffect(controller) { if (owner.isNotBlank()) controller.refresh() }
        LaunchedEffect(controller, list, state.items.size, state.filter, state.end, state.loading, state.moreError, state.error) {
            if (!state.loading && !state.end && state.error == null && state.moreError == null && state.items.isNotEmpty()) snapshotFlow {
                val layout = list.layoutInfo
                layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
            }.distinctUntilChanged().collect { if (it) controller.more() }
        }
        Screen("校园跑", onBack, actions = { TextButton("成绩", onClick = { onOpen(Route.Page("runrecords", title = "我的跑步记录")) }) }) { padding ->
            PullToRefresh(state.loading, { scope.launch { controller.refresh() } }, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton("真实记录 / 恢复", onClick = { onOpen(Route.Page("runtracker", title = "真实跑步记录")) })
                            TextButton("本机路线预览", onClick = { onOpen(Route.Page("runimport", title = "路线导入预览")) })
                        }
                    }
                    item("groups") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(RunTaskFilter.ALL to "全部", RunTaskFilter.UNFINISHED to "未完成", RunTaskFilter.FINISHED to "已完成").forEach { (filter, label) ->
                                TextButton(if (filter == state.filter) "· $label ·" else label, enabled = !state.loading,
                                    onClick = { scope.launch { controller.select(filter); list.scrollToItem(0) } })
                            }
                        }
                    }
                    if (owner.isBlank()) item("login") { SectionCard("请先登录", "本页只读取当前账号的任务。") }
                    if (state.loading) item("loading") { CircularProgressIndicator() }
                    state.error?.let { error -> item("error") { SectionCard("任务读取失败", error + "\n点按刷新", { scope.launch { controller.refresh() } }) } }
                    runWarnings(state.warnings)
                    if (!state.loading && state.error == null && state.items.isEmpty() && owner.isNotBlank()) item("empty") { SectionCard("暂无跑步任务", "服务端尚未返回可展示任务；本机恢复和路线预览仍可使用。") }
                    itemsIndexed(state.items, key = { index, row -> "task:${row.identityKey}:$index" }) { _, task ->
                        val selection = RunTaskSelection.from(task)
                        SectionCard(task.title, listOfNotNull(task.startText?.let { "开始：$it" }, task.endText?.let { "结束：$it" }, task.isFinish?.let { "服务端完成标记：$it" },
                            if (selection == null) "缺少真实任务或排期标识，仅展示" else "会话标识仅在明确开始记录时生成").joinToString("\n"),
                            selection?.let { selected -> ({ onOpen(Route.Page("rundetail", selected.encode(), task.title)) }) })
                    }
                    state.moreError?.let { error -> item("more-error") { SectionCard("追加读取失败，已保留前页", error, { scope.launch { controller.more() } }) } }
                    if (!state.end && state.items.isNotEmpty()) item("more") { TextButton("读取下一页", enabled = !state.loading, onClick = { scope.launch { controller.more() } }) }
                    if (state.end && state.items.isNotEmpty()) item("end") { Text("已到服务端分页末尾 · ${state.items.size} 条去重结果") }
                }
            }
        }
    }
}

@Composable
private fun RunDetailPage(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val selection = remember(page.id) { RunTaskSelection.decode(page.id) }
    val query = rememberRunQuery(graph, selection, refresh) { if (selection == null) missingRunSelection() else it.detail(selection.errandId, it.boundOwnerId.orEmpty(), selection.errandScheduleId) }
    val detail = remember(query.data, selection) { query.data?.let(RunDataDecoder::detail)?.let { data -> selection?.let(data::withQueryContext) ?: data } }
    val settings by graph.settings.settings.collectAsState()
    Screen(page.title.ifBlank { "任务详情" }, back, actions = { TextButton("刷新", enabled = !query.loading, onClick = { refresh++ }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            runFeedback(query, { refresh++ })
            if (detail != null) {
                item("task") { SectionCard(detail.task.title, "最低距离：${RunDisplay.distance(detail.minimumDistanceMeters, settings.metricUnits)}\n任务：${detail.task.errandId ?: "未返回"}\n排期：${detail.task.scheduleId ?: "未返回"}\n跑步会话 trid 将在明确开始时按账号+时间生成并保存") }
                runWarnings(detail.warnings)
                item("map") { RunMapPreview(graph, detail.walkPaths.mapIndexed { index, points -> "推荐路径 ${index + 1}" to points }, detail.checkpoints, role = RunMapRole.RECOMMENDED) }
                itemsIndexed(detail.checkpoints, key = { index, _ -> "checkpoint:$index" }) { index, point -> SectionCard("顺序点 ${index + 1}", coordinate(point.point)) }
                item("record") { TextButton("进入真实定位记录", enabled = detail.canStart && selection != null, onClick = { selection?.let { open(Route.Page("runtracker", it.encode(), detail.task.title)) } }, modifier = Modifier.fillMaxWidth()) }
                if (!detail.canStart) item("not-ready") { SectionCard("当前只展示详情", "待补齐：${detail.missingFields.joinToString()}。不会编造任务、排期、账号或坐标。") }
                item("records") { TextButton("本排期成绩 / 历史记录", enabled = selection != null, onClick = { selection?.let { open(Route.Page("runrecords", it.encode(), "本排期跑步记录")) } }, modifier = Modifier.fillMaxWidth()) }
                item("import") { TextButton("导入路线 · 仅本机预览", onClick = { open(Route.Page("runimport", page.id, "路线导入预览")) }, modifier = Modifier.fillMaxWidth()) }
                runRawFields(detail.task.raw)
            } else if (!query.loading && query.error == null) item("shape") { SectionCard("任务详情结构尚未识别", "响应没有可解码的 errandDetail，暂不开放定位开始或写操作。") }
        }
    }
}

@Composable
private fun RunRecordsPage(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit, runtime: RunRuntime) {
    val session by graph.sessions.session.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    val current by runtime.state.collectAsState()
    val owner = session?.userId.orEmpty()
    val selection = RunTaskSelection.decode(page.id) ?: current.taskSelection.takeIf { runtime.isCurrentSession() && current.ownerId == owner }
    if (selection == null) {
        Screen(page.title.ifBlank { "跑步成绩" }, back) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SectionCard("请先选择任务排期", "历史接口按 errandScheduleId 真实分页，不猜测全部排期值；进入任务详情后可查看本排期成绩。") }
                item { TextButton("从我的任务选择", onClick = { open(Route.Page("runs", title = "选择跑步任务")) }) }
            }
        }
        return
    }
    val bound = remember(graph, session?.userId, session?.token) { graph.repositories.run.boundToCurrentSession() }
    val controller = remember(bound, owner, selection) { RunRecordController(RunRecordPageSource { requestedOwner, plan, offset, limit ->
        if (bound == null || bound.boundOwnerId != requestedOwner) ApiResult.Failure(ApiError.Authentication())
        else bound.records(requestedOwner, plan, offset, limit)
    }, owner, selection.errandScheduleId) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(controller, list, state.items.size, state.loading, state.end, state.moreError, state.error) {
        if (!state.loading && !state.end && state.error == null && state.moreError == null && state.items.isNotEmpty()) snapshotFlow {
            val layout = list.layoutInfo
            layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { if (it) controller.more() }
    }
    Screen(page.title.ifBlank { "本排期跑步记录" }, back, actions = { TextButton("刷新", enabled = !state.loading, onClick = { scope.launch { controller.refresh() } }) }) { padding ->
        PullToRefresh(state.loading, { scope.launch { controller.refresh() } }, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("plan") { SectionCard("当前排期", selection.errandScheduleId + "\n按已证实 userId / errandScheduleId / offset / limit 查询，不发送 errandId 到记录接口。") }
                if (state.loading) item("loading") { CircularProgressIndicator() }
                state.error?.let { error -> item("error") { SectionCard("记录读取失败，已有内容保留", error, { scope.launch { controller.refresh() } }) } }
                runWarnings(state.warnings)
                if (!state.loading && state.error == null && state.items.isEmpty()) item("empty") { SectionCard("本排期暂无记录", "服务端返回的记录列表为空。") }
                itemsIndexed(state.items, key = { index, row -> "record:${row.trid}:$index" }) { _, row ->
                    SectionCard(row.title, "距离：${RunDisplay.distance(row.distanceMeters, settings.metricUnits)}\n用时：${RunDisplay.duration(row.elapsedMillis)}\n服务端状态：${row.status ?: "未返回"}" + if (row.trid.isNullOrBlank()) "\n缺少 trid，仅展示" else "",
                        if (row.trid.isNullOrBlank()) null else ({ open(Route.Page("runrecord", row.trid, row.title)) }))
                }
                state.moreError?.let { error -> item("more-error") { SectionCard("追加读取失败，前页保留", error, { scope.launch { controller.more() } }) } }
                if (!state.end && state.items.isNotEmpty()) item("more") { TextButton("读取下一页", enabled = !state.loading, onClick = { scope.launch { controller.more() } }) }
                if (state.end && state.items.isNotEmpty()) item("end") { Text("已到此排期记录末尾 · ${state.items.size} 条去重记录") }
            }
        }
    }
}

@Composable
private fun RunRecordPage(graph: AppGraph, page: Route.Page, back: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val settings by graph.settings.settings.collectAsState()
    val recordQuery = rememberRunQuery(graph, "record:${page.id}", refresh) { if (page.id.isBlank()) missingRunId() else it.record(page.id) }
    val trackQuery = rememberRunQuery(graph, "track:${page.id}", refresh) { if (page.id.isBlank()) missingRunId() else it.track(page.id) }
    val record = remember(recordQuery.data) { recordQuery.data?.let(RunDataDecoder::record) }
    val points = remember(trackQuery.data, record) { trackQuery.data?.let(RunDataDecoder::track).orEmpty().ifEmpty { record?.points.orEmpty() } }
    Screen(page.title.ifBlank { "跑步成绩与轨迹" }, back, actions = { TextButton("重新查询", enabled = !recordQuery.loading && !trackQuery.loading, onClick = { refresh++ }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("truth") { SectionCard("服务端成绩查询", "本页只展示返回字段。上传批次被接收不等于成绩已通过，缺少状态时不会推定成功。") }
            runFeedback(recordQuery, { refresh++ }, "record")
            runFeedback(trackQuery, { refresh++ }, "track")
            if (record != null) {
                item("summary") { SectionCard(record.title, "服务端状态：${record.status ?: "未返回"}\n距离：${RunDisplay.distance(record.distanceMeters, settings.metricUnits)}\n用时：${RunDisplay.duration(record.elapsedMillis)}") }
                runWarnings(record.warnings)
                runRawFields(record.raw)
            } else if (!recordQuery.loading && recordQuery.error == null) item("no-record") { SectionCard("暂无可解码成绩", "服务端没有返回可识别的 recordMap；这不代表提交成功或失败。") }
            item("map") { RunMapPreview(graph, listOf("服务端轨迹" to points), role = RunMapRole.ACTUAL) }
            if (points.isEmpty() && !trackQuery.loading) item("no-track") { SectionCard("暂无可解码轨迹", "服务端可能尚未生成轨迹，或坐标字段尚待验证。") }
            itemsIndexed(points.take(100), key = { index, _ -> "point:$index" }) { index, point -> SectionCard("服务端坐标 ${index + 1}", coordinate(point)) }
            if (points.size > 100) item("bounded") { Text("轨迹共 ${points.size} 点；此坐标明细仅展开前 100 点，地图渲染仍受平台点数上限约束。") }
        }
    }
}
