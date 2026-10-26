package io.github.betterzslf.ui.run

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.edition.EditionAccess
import io.github.betterzslf.edition.EditionUnavailablePage
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
    if (!EditionAccess.allowsPage(page.kind)) {
        EditionUnavailablePage(onBack)
        return
    }
    val session by graph.sessions.session.collectAsState()
    key(graph, session?.userId, session?.token, page) {
        when (page.kind) {
            "runs" -> RunTaskList(graph, onOpen, onBack)
            "rundetail" -> RunDetailPage(graph, page, onOpen, onBack)
            "runrecords" -> RunRecordsPage(graph, page, onOpen, onBack, runtime)
            "runrecord" -> RunRecordPage(graph, page, onBack)
            "runtracker" -> RunTrackerPage(graph, page, onOpen, onBack, runtime)
            "runimport" -> graph.editionFeatures.Page(graph, page, onOpen, onBack)
            else -> Screen(page.title.ifBlank { AppText.Navigation.CAMPUS_RUN }, onBack) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                    item { SectionCard(AppText.Common.UNKNOWN_PAGE_TYPE, AppText.Run.REOPEN_FROM_RUN_ENTRY_HINT) }
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
        Screen(AppText.Navigation.CAMPUS_RUN, onBack, actions = { TextButton(AppText.Run.SCORE_TAB_LABEL, onClick = { onOpen(Route.Page("runrecords", title = AppText.Run.MY_RUN_RECORDS_TITLE)) }) }) { padding ->
            PullToRefresh(state.loading, { scope.launch { controller.refresh() } }, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("actions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(AppText.Run.REAL_RECORDING_AND_RECOVERY, onClick = { onOpen(Route.Page("runtracker", title = AppText.Run.REAL_RUN_RECORD_TITLE)) })
                            if (EditionAccess.routeEditorsEnabled) TextButton(AppText.Run.LOCAL_ROUTE_PREVIEW_TITLE, onClick = {
                                if (EditionAccess.routeEditorsEnabled) onOpen(Route.Page("runimport", title = AppText.Run.ROUTE_IMPORT_PREVIEW_TITLE))
                            })
                        }
                    }
                    item("groups") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(RunTaskFilter.ALL to AppText.Common.ALL, RunTaskFilter.UNFINISHED to AppText.Common.INCOMPLETE, RunTaskFilter.FINISHED to AppText.Common.COMPLETED).forEach { (filter, label) ->
                                TextButton(if (filter == state.filter) "· $label ·" else label, enabled = !state.loading,
                                    onClick = { scope.launch { controller.select(filter); list.scrollToItem(0) } })
                            }
                        }
                    }
                    if (owner.isBlank()) item("login") { SectionCard(AppText.Auth.LOGIN_REQUIRED, AppText.Run.CURRENT_ACCOUNT_TASKS_ONLY_HINT) }
                    if (state.loading) item("loading") { CircularProgressIndicator() }
                    state.error?.let { error -> item("error") { SectionCard(AppText.Run.TASK_LOAD_FAILED, error + AppText.Common.TAP_TO_REFRESH_NEWLINE_SUFFIX, { scope.launch { controller.refresh() } }) } }
                    runWarnings(state.warnings)
                    if (!state.loading && state.error == null && state.items.isEmpty() && owner.isNotBlank()) item("empty") { SectionCard(AppText.Run.NO_RUN_TASKS, AppText.Run.NO_SERVER_TASKS_LOCAL_TOOLS_AVAILABLE_HINT) }
                    itemsIndexed(state.items, key = { index, row -> "task:${row.identityKey}:$index" }) { _, task ->
                        val selection = RunTaskSelection.from(task)
                        SectionCard(task.title, listOfNotNull(task.startText?.let { AppText.Run.startsAt((it)) }, task.endText?.let { AppText.Run.endsAt((it)) }, task.isFinish?.let { AppText.Run.serverCompletion((it)) },
                            if (selection == null) AppText.Run.MISSING_TASK_OR_SCHEDULE_READ_ONLY else AppText.Run.SESSION_ID_CREATED_ON_EXPLICIT_START_HINT).joinToString("\n"),
                            selection?.let { selected -> ({ onOpen(Route.Page("rundetail", selected.encode(), task.title)) }) })
                    }
                    state.moreError?.let { error -> item("more-error") { SectionCard(AppText.Run.NEXT_TASK_PAGE_FAILED_PREVIOUS_RETAINED, error, { scope.launch { controller.more() } }) } }
                    if (!state.end && state.items.isNotEmpty()) item("more") { TextButton(AppText.Common.LOAD_NEXT_PAGE, enabled = !state.loading, onClick = { scope.launch { controller.more() } }) }
                    if (state.end && state.items.isNotEmpty()) item("end") { Text(AppText.Run.tasksPaginationEnd((state.items.size))) }
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
    Screen(page.title.ifBlank { AppText.Run.TASK_DETAIL_TITLE }, back, actions = { TextButton(AppText.Common.REFRESH, enabled = !query.loading, onClick = { refresh++ }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            runFeedback(query, { refresh++ })
            if (detail != null) {
                item("task") { SectionCard(detail.task.title, AppText.Run.taskRequirements((RunDisplay.distance(detail.minimumDistanceMeters, settings.metricUnits)), (detail.task.errandId ?: AppText.Common.NOT_RETURNED), (detail.task.scheduleId ?: AppText.Common.NOT_RETURNED))) }
                runWarnings(detail.warnings)
                item("map") { RunMapPreview(graph, detail.walkPaths.mapIndexed { index, points -> AppText.Run.recommendedPath((index + 1)) to points }, detail.checkpoints, role = RunMapRole.RECOMMENDED) }
                itemsIndexed(detail.checkpoints, key = { index, _ -> "checkpoint:$index" }) { index, point -> SectionCard(AppText.Run.checkpointOrdinal((index + 1)), coordinate(point.point)) }
                item("record") { TextButton(AppText.Run.ENTER_REAL_LOCATION_RECORDING, enabled = detail.canStart && selection != null, onClick = { selection?.let { open(Route.Page("runtracker", it.encode(), detail.task.title)) } }, modifier = Modifier.fillMaxWidth()) }
                if (!detail.canStart) item("not-ready") { SectionCard(AppText.Run.DETAILS_ONLY_FOR_NOW, AppText.Run.missingTaskFields((detail.missingFields.joinToString()))) }
                item("records") { TextButton(AppText.Run.SCHEDULE_SCORES_AND_HISTORY, enabled = selection != null, onClick = { selection?.let { open(Route.Page("runrecords", it.encode(), AppText.Run.SCHEDULE_RUN_RECORDS_TITLE)) } }, modifier = Modifier.fillMaxWidth()) }
                if (EditionAccess.routeEditorsEnabled) item("import") { TextButton(AppText.Run.IMPORT_ROUTE_LOCAL_PREVIEW_ACTION, onClick = {
                    if (EditionAccess.routeEditorsEnabled) open(Route.Page("runimport", page.id, AppText.Run.ROUTE_IMPORT_PREVIEW_TITLE))
                }, modifier = Modifier.fillMaxWidth()) }
                runRawFields(detail.task.raw)
            } else if (!query.loading && query.error == null) item("shape") { SectionCard(AppText.Run.TASK_DETAIL_STRUCTURE_UNRECOGNIZED, AppText.Run.UNDECODABLE_TASK_DETAIL_NO_RECORDING_HINT) }
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
        Screen(page.title.ifBlank { AppText.Run.RUN_SCORES_TITLE }, back) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { SectionCard(AppText.Run.SELECT_TASK_SCHEDULE_FIRST, AppText.Run.SCHEDULE_PAGINATION_SCOPE_HINT) }
                item { TextButton(AppText.Run.SELECT_FROM_MY_TASKS, onClick = { open(Route.Page("runs", title = AppText.Run.SELECT_RUN_TASK)) }) }
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
    Screen(page.title.ifBlank { AppText.Run.SCHEDULE_RUN_RECORDS_TITLE }, back, actions = { TextButton(AppText.Common.REFRESH, enabled = !state.loading, onClick = { scope.launch { controller.refresh() } }) }) { padding ->
        PullToRefresh(state.loading, { scope.launch { controller.refresh() } }, modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("plan") { SectionCard(AppText.Run.CURRENT_SCHEDULE_TITLE, selection.errandScheduleId + AppText.Run.RECORD_QUERY_PARAMETERS_NEWLINE_HINT) }
                if (state.loading) item("loading") { CircularProgressIndicator() }
                state.error?.let { error -> item("error") { SectionCard(AppText.Run.RECORD_LOAD_FAILED_CONTENT_RETAINED, error, { scope.launch { controller.refresh() } }) } }
                runWarnings(state.warnings)
                if (!state.loading && state.error == null && state.items.isEmpty()) item("empty") { SectionCard(AppText.Run.NO_RECORDS_FOR_SCHEDULE, AppText.Run.SERVER_RECORD_LIST_EMPTY) }
                itemsIndexed(state.items, key = { index, row -> "record:${row.trid}:$index" }) { _, row ->
                    SectionCard(row.title, AppText.Run.recordMetrics((RunDisplay.distance(row.distanceMeters, settings.metricUnits)), (RunDisplay.duration(row.elapsedMillis)), (row.status ?: AppText.Common.NOT_RETURNED)) + if (row.trid.isNullOrBlank()) AppText.Run.MISSING_RECORD_ID_READ_ONLY_NEWLINE_SUFFIX else "",
                        if (row.trid.isNullOrBlank()) null else ({ open(Route.Page("runrecord", row.trid, row.title)) }))
                }
                state.moreError?.let { error -> item("more-error") { SectionCard(AppText.Run.NEXT_RECORD_PAGE_FAILED_PREVIOUS_RETAINED, error, { scope.launch { controller.more() } }) } }
                if (!state.end && state.items.isNotEmpty()) item("more") { TextButton(AppText.Common.LOAD_NEXT_PAGE, enabled = !state.loading, onClick = { scope.launch { controller.more() } }) }
                if (state.end && state.items.isNotEmpty()) item("end") { Text(AppText.Run.recordsPaginationEnd((state.items.size))) }
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
    Screen(page.title.ifBlank { AppText.Run.RUN_SCORE_AND_TRACK_TITLE }, back, actions = { TextButton(AppText.Common.QUERY_AGAIN, enabled = !recordQuery.loading && !trackQuery.loading, onClick = { refresh++ }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("truth") { SectionCard(AppText.Run.SERVER_SCORE_QUERY_TITLE, AppText.Run.BATCH_RECEIPT_NOT_SCORE_APPROVAL_HINT) }
            runFeedback(recordQuery, { refresh++ }, "record")
            runFeedback(trackQuery, { refresh++ }, "track")
            if (record != null) {
                item("summary") { SectionCard(record.title, AppText.Run.recordServerMetrics((record.status ?: AppText.Common.NOT_RETURNED), (RunDisplay.distance(record.distanceMeters, settings.metricUnits)), (RunDisplay.duration(record.elapsedMillis)))) }
                runWarnings(record.warnings)
                runRawFields(record.raw)
            } else if (!recordQuery.loading && recordQuery.error == null) item("no-record") { SectionCard(AppText.Run.NO_DECODABLE_SCORE, AppText.Run.UNRECOGNIZED_RECORD_RESULT_UNKNOWN_HINT) }
            item("map") { RunMapPreview(graph, listOf(AppText.Run.SERVER_TRACK_TITLE to points), role = RunMapRole.ACTUAL) }
            if (points.isEmpty() && !trackQuery.loading) item("no-track") { SectionCard(AppText.Run.NO_DECODABLE_TRACK, AppText.Run.TRACK_PENDING_OR_COORDINATES_UNVERIFIED_HINT) }
            itemsIndexed(points.take(100), key = { index, _ -> "point:$index" }) { index, point -> SectionCard(AppText.Run.serverCoordinateOrdinal((index + 1)), coordinate(point)) }
            if (points.size > 100) item("bounded") { Text(AppText.Run.trackPreviewBound((points.size))) }
        }
    }
}
