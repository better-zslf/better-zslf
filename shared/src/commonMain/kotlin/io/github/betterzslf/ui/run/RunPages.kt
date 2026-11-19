package io.github.betterzslf.ui.run

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.composition.ContributedAction
import io.github.betterzslf.feature.run.api.RunPreviewSelection
import top.yukonga.miuix.kmp.nav.core.NavKey
import io.github.betterzslf.feature.runui.*
import io.github.betterzslf.ui.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The app owns runtime. Route composition never disposes a tracker, coordinator, or upload queue. */
@Composable
fun RunFeaturePage(graph: AppGraph, page: Route.Page, onOpen: (NavKey) -> Unit, onBack: () -> Unit, runtime: RunRuntime,
                   contributedActions: (RunPreviewSelection?) -> List<ContributedAction> = { emptyList() }) {
    val session by graph.sessions.session.collectAsState()
    val epoch by graph.sessions.revision.collectAsState()
    key(graph, epoch, session?.userId, session?.token, page) {
        when (page.kind) {
            "runs" -> RunTaskList(graph, onOpen, onBack, contributedActions)
            "rundetail" -> RunDetailPage(graph, page, onOpen, onBack, contributedActions)
            "runrecords" -> RunRecordsPage(graph, page, onOpen, onBack, runtime)
            "runrecord" -> RunRecordPage(graph, page, onBack)
            "runtracker" -> RunTrackerPage(graph, page, onOpen, onBack, runtime, contributedActions)
            else -> Screen(page.title.ifBlank { AppText.Navigation.CAMPUS_RUN }, onBack) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).padding(AppUiTokens.PagePadding)) {
                    RunEmpty(AppText.Common.UNKNOWN_PAGE_TYPE, AppText.Run.REOPEN_FROM_RUN_ENTRY_HINT)
                }
            }
        }
    }
}

@Composable
fun RunTaskList(graph: AppGraph, onOpen: (NavKey) -> Unit, onBack: (() -> Unit)? = null,
                contributedActions: (RunPreviewSelection?) -> List<ContributedAction> = { emptyList() }) {
    val session by graph.sessions.session.collectAsState()
    val epoch by graph.sessions.revision.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    key(graph, epoch, session?.userId, session?.token) {
        val owner = session?.userId.orEmpty()
        val bound = remember(graph, epoch) { ApiRunReadSource.capture(graph.api) }
        val fence = remember(graph, epoch, bound) { SessionEpochReadFence(epoch, { graph.sessions.revision.value },
            { bound?.isCurrentSession() == true }) }
        val controller = remember(bound, owner, fence) { RunTaskController(boundRunTaskPages(bound, fence, owner), owner) }
        val state by controller.state.collectAsState()
        val scope = rememberCoroutineScope()
        val list = rememberLazyListState()
        var mounted by remember(controller, fence) { mutableStateOf(true) }
        val bindings = remember(controller, fence) { RunListBindings("task-row", RunTaskItem::identityKey,
            { mounted && graph.sessions.revision.value == epoch && (bound?.isCurrentSession() ?: true) },
            { controller.state.value.items }) }
        DisposableEffect(fence, bindings) { onDispose { mounted = false; bindings.retire(); fence.close() } }
        val render = bindings.render(state.items)
        val actions = remember(contributedActions) { contributedActions(null).mapIndexed { index, action -> index to action } }
        val latestActions by rememberUpdatedState(actions)
        val actionBindings = remember(controller, fence) { RunListBindings("contribution", { row: Pair<Int, ContributedAction> -> row.first.toString() },
            { mounted && graph.sessions.revision.value == epoch && (bound?.isCurrentSession() ?: true) }, { latestActions }) }
        DisposableEffect(actionBindings) { onDispose { actionBindings.retire() } }
        val actionRender = actionBindings.render(actions)
        val display = taskListDisplay(state, render, owner.isNotBlank(), settings.metricUnits,
            actionRender.rows.map { (key, row) -> RunListContributionDisplay(key, row.second.title) })
        val controls = RunListControls(onBack != null, !state.loading, !state.loading && !state.end)
        LaunchedEffect(controller) { if (owner.isNotBlank()) controller.refresh() }
        LaunchedEffect(controller, list, state.items.size, state.filter, state.end, state.loading, state.moreError, state.error) {
            if (!state.loading && !state.end && state.error == null && state.moreError == null && state.items.isNotEmpty()) snapshotFlow {
                val layout = list.layoutInfo
                layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
            }.distinctUntilChanged().collect { if (it && fence.isCurrent()) controller.more() }
        }
        RunTaskListContent(display, controls, list,
            onEvent = { event -> if (bindings.isCurrent(render)) {
                when (event) {
                    RunListUiEvent.BACK -> onBack?.invoke()
                    RunListUiEvent.REFRESH -> if (fence.isCurrent()) scope.launch { controller.refresh() }
                    RunListUiEvent.MORE -> if (fence.isCurrent()) scope.launch { controller.more() }
                    RunListUiEvent.LOCAL_TRACKER -> onOpen(Route.Page("runtracker", title = AppText.Run.REAL_RUN_RECORD_TITLE))
                    RunListUiEvent.SELECT_TASKS -> Unit
                }
            } },
            onFilter = { filter -> if (bindings.isCurrent(render) && !controller.state.value.loading) scope.launch {
                controller.select(filter); list.scrollToItem(0)
            } },
            onRowAction = { key, action -> bindings.lookup(render, key)?.let { task -> taskListDestination(task, action)?.let(onOpen) } },
            onContribution = { key -> if (bindings.isCurrent(render)) {
                actionBindings.lookup(actionRender, key)?.let { onOpen(it.second.target) }
            } })
    }
}

@Composable
private fun RunDetailPage(graph: AppGraph, page: Route.Page, open: (NavKey) -> Unit, back: () -> Unit,
                          contributedActions: (RunPreviewSelection?) -> List<ContributedAction>) {
    var refresh by remember { mutableIntStateOf(0) }
    val selection = remember(page.id) { RunTaskSelection.decode(page.id) }
    val query = rememberRunQuery(graph, selection, refresh) { if (selection == null) missingRunSelection() else it.detail(selection.errandId, it.boundOwnerId, selection.errandScheduleId) }
    val detail = remember(query.data, selection) { query.data?.value?.let { data -> selection?.let(data::withQueryContext) ?: data } }
    val settings by graph.settings.settings.collectAsState()
    Screen(AppText.Run.TASK_DETAIL_TITLE, back, actions = { TextButton(AppText.Common.REFRESH, enabled = !query.loading, onClick = { refresh++ }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(AppUiTokens.PagePadding), verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
            runFeedback(query, { refresh++ })
            if (detail != null) {
                item("map") { RunMapPreview(graph, detail.walkPaths.mapIndexed { index, points -> AppText.Run.recommendedPath(index + 1) to points }, detail.checkpoints, role = RunMapRole.RECOMMENDED, height = 200.dp) }
                item("task") { RunCard {
                    Text(detail.task.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    Text("${AppText.Run.DISTANCE_REQUIREMENT_TITLE} · ${RunDisplay.distance(detail.minimumDistanceMeters, settings.metricUnits)}", fontSize = 14.sp)
                    detail.task.endText?.let { Text(AppText.Run.endsAt(it), fontSize = 14.sp) }
                    Spacer(Modifier.height(12.dp))
                    RunStatus(AppText.Run.REAL_RUNNING_MODE_LABEL, UiTone.Info)
                } }
                if (detail.checkpoints.isNotEmpty()) item("checkpoints") { RunCard {
                    SectionTitle(AppText.Run.ORDERED_CHECKPOINT_COVERAGE_TITLE)
                    detail.checkpoints.forEachIndexed { index, point ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RunStatus((index + 1).toString(), UiTone.Info)
                            Text(detail.checkpointNames[point.id] ?: AppText.Run.checkpointOrdinal(index + 1), modifier = Modifier.weight(1f), fontSize = 15.sp)
                        }
                    }
                } }
                item("record") { RunAction(AppText.Run.START_REAL_RECORDING, primary = true, enabled = detail.canStart && selection != null, modifier = Modifier.fillMaxWidth(), onClick = {
                    selection?.let { open(Route.Page("runtracker", it.encode(), detail.task.title)) }
                }) }
                if (!detail.canStart) item("not-ready") { InfoBanner(AppText.Run.missingTaskFields(detail.missingFields.joinToString()), tone = UiTone.Warning) }
                item("records") { TextButton(AppText.Run.SCHEDULE_SCORES_AND_HISTORY, enabled = selection != null, onClick = {
                    selection?.let { open(Route.Page("runrecords", it.encode(), AppText.Run.SCHEDULE_RUN_RECORDS_TITLE)) }
                }, modifier = Modifier.fillMaxWidth()) }
                contributedActions(selection?.let { RunPreviewSelection(it.errandId, it.errandScheduleId) }).forEachIndexed { index, action ->
                    item("contribution:$index") { TextButton(action.title, onClick = { open(action.target) }, modifier = Modifier.fillMaxWidth()) }
                }
                runWarnings(detail.warnings)
                runDisplayFields(detail.task.safeDisplayFields)
            } else if (!query.loading && query.error == null) item("shape") { RunEmpty(AppText.Run.TASK_DETAIL_STRUCTURE_UNRECOGNIZED, AppText.Run.UNDECODABLE_TASK_DETAIL_NO_RECORDING_HINT) }
        }
    }
}

@Composable
private fun RunRecordsPage(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit, runtime: RunRuntime) {
    val session by graph.sessions.session.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    val current by runtime.state.collectAsState()
    val epoch by graph.sessions.revision.collectAsState()
    val owner = session?.userId.orEmpty()
    val selection = RunTaskSelection.decode(page.id) ?: current.taskSelection.takeIf { runtime.isCurrentSession() && current.ownerId == owner }
    val list = rememberLazyListState()
    var mounted by remember(graph, epoch) { mutableStateOf(true) }
    DisposableEffect(graph, epoch) { onDispose { mounted = false } }
    if (selection == null) {
        RunRecordsContent(RunRecordListDisplay(), RunListControls(canGoBack = true), list,
            onEvent = { event -> if (mounted && graph.sessions.revision.value == epoch) when (event) {
                RunListUiEvent.BACK -> back()
                RunListUiEvent.SELECT_TASKS -> open(Route.Page("runs", title = AppText.Run.SELECT_RUN_TASK))
                else -> Unit
            } }, onOpenRow = {})
        return
    }
    val bound = remember(graph, epoch) { ApiRunReadSource.capture(graph.api) }
    val fence = remember(graph, epoch, bound) { SessionEpochReadFence(epoch, { graph.sessions.revision.value },
        { bound?.isCurrentSession() == true }) }
    val controller = remember(bound, owner, selection, fence) {
        RunRecordController(boundRunRecordPages(bound, fence, owner), owner, selection.errandScheduleId)
    }
    val state by controller.state.collectAsState()
    val bindings = remember(controller, fence) { RunListBindings("record-row", RunRecordItem::identityKey,
        { mounted && graph.sessions.revision.value == epoch && (bound?.isCurrentSession() ?: true) },
        { controller.state.value.items }) }
    DisposableEffect(fence, bindings) { onDispose { bindings.retire(); fence.close() } }
    val render = bindings.render(state.items)
    var detailRefresh by remember { mutableIntStateOf(0) }
    val planQuery = rememberRunQuery(graph, selection, detailRefresh) { it.detail(selection.errandId, it.boundOwnerId, selection.errandScheduleId) }
    val minimum = remember(planQuery.data, selection) {
        planQuery.data?.value?.withQueryContext(selection)
            ?.takeIf { it.task.errandId == selection.errandId && it.task.scheduleId == selection.errandScheduleId }
            ?.minimumDistanceMeters
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(controller, list, state.items.size, state.loading, state.end, state.moreError, state.error) {
        if (!state.loading && !state.end && state.error == null && state.moreError == null && state.items.isNotEmpty()) snapshotFlow {
            val layout = list.layoutInfo
            layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { if (it && fence.isCurrent()) controller.more() }
    }
    val display = recordListDisplay(state, render, minimum, settings.metricUnits, planQuery.error)
    RunRecordsContent(display, RunListControls(canGoBack = true, canRefresh = !state.loading, canMore = !state.loading && !state.end), list,
        onEvent = { event -> if (bindings.isCurrent(render)) when (event) {
            RunListUiEvent.BACK -> back()
            RunListUiEvent.REFRESH -> if (fence.isCurrent()) { detailRefresh++; scope.launch { controller.refresh() } }
            RunListUiEvent.MORE -> if (fence.isCurrent()) scope.launch { controller.more() }
            RunListUiEvent.SELECT_TASKS -> open(Route.Page("runs", title = AppText.Run.SELECT_RUN_TASK))
            RunListUiEvent.LOCAL_TRACKER -> Unit
        } },
        onOpenRow = { key -> bindings.lookup(render, key)?.let { record -> recordListDestination(record)?.let(open) } })
}

private fun recordPace(row: RunRecordItem, metric: Boolean): String {
    val distance = row.distanceMeters
    val elapsed = row.elapsedMillis
    return if (distance != null && elapsed != null) RunDisplay.pace(distance, elapsed, metric) else "—"
}

@Composable
private fun RunRecordPage(graph: AppGraph, page: Route.Page, back: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val settings by graph.settings.settings.collectAsState()
    val recordQuery = rememberRunQuery(graph, "record:${page.id}", refresh) { if (page.id.isBlank()) missingRunId() else it.record(page.id) }
    val trackQuery = rememberRunQuery(graph, "track:${page.id}", refresh) { if (page.id.isBlank()) missingRunId() else it.track(page.id) }
    val record = remember(recordQuery.data) { recordQuery.data?.value }
    val points = remember(trackQuery.data, record) { trackQuery.data?.items.orEmpty().ifEmpty { record?.points.orEmpty() } }
    Screen(AppText.Run.RUN_SCORES_TITLE, back, actions = { TextButton(AppText.Common.QUERY_AGAIN, enabled = !recordQuery.loading && !trackQuery.loading, onClick = { refresh++ }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(AppUiTokens.PagePadding), verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
            runFeedback(recordQuery, { refresh++ }, "record")
            runFeedback(trackQuery, { refresh++ }, "track")
            item("map") { RunMapPreview(graph, listOf(AppText.Run.SERVER_TRACK_TITLE to points), role = RunMapRole.ACTUAL, height = 260.dp) }
            if (record != null) {
                item("metrics") { RunMetricGrid(listOf(
                    UiMetric(RunDisplay.distance(record.distanceMeters, settings.metricUnits), AppText.Run.DISTANCE_LABEL),
                    UiMetric(RunDisplay.duration(record.elapsedMillis), AppText.Run.SAMPLING_DURATION_LABEL),
                    UiMetric(recordPace(record, settings.metricUnits), AppText.Run.AVERAGE_PACE_LABEL),
                )) }
                record.status?.let { status -> item("status") { RunStatus(status) } }
                runWarnings(record.warnings)
            } else if (!recordQuery.loading && recordQuery.error == null) item("no-record") { InfoBanner(AppText.Run.UNRECOGNIZED_RECORD_RESULT_UNKNOWN_HINT, AppText.Run.NO_DECODABLE_SCORE, UiTone.Neutral) }
            item("checkpoint-result") { InfoBanner(AppText.Run.CHECKPOINT_RESULT_PENDING_HINT, tone = UiTone.Neutral) }
            if (points.isEmpty() && !trackQuery.loading) item("no-track") { InfoBanner(AppText.Run.TRACK_PENDING_OR_COORDINATES_UNVERIFIED_HINT, tone = UiTone.Neutral) }
            item("truth") { RunDisclosure(AppText.Run.SERVER_SCORE_QUERY_TITLE) {
                Text(AppText.Run.BATCH_RECEIPT_NOT_SCORE_APPROVAL_HINT, fontSize = 13.sp)
                points.take(100).forEachIndexed { index, point -> Text("${AppText.Run.serverCoordinateOrdinal(index + 1)} · ${coordinate(point)}", fontSize = 12.sp) }
                if (points.size > 100) Text(AppText.Run.trackPreviewBound(points.size), fontSize = 12.sp)
            } }
            record?.let { runDisplayFields(it.safeDisplayFields) }
        }
    }
}
