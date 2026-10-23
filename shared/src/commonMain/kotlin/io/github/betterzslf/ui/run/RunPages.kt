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
import io.github.betterzslf.edition.EditionAccess
import io.github.betterzslf.edition.EditionUnavailablePage
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.feature.runui.*
import io.github.betterzslf.ui.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The app owns runtime. Route composition never disposes a tracker, coordinator, or upload queue. */
@Composable
fun RunFeaturePage(graph: AppGraph, page: Route.Page, onOpen: (Route) -> Unit, onBack: () -> Unit, runtime: RunRuntime) {
    if (!EditionAccess.allowsPage(page.kind)) { EditionUnavailablePage(onBack); return }
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
                Box(Modifier.fillMaxSize().padding(padding).padding(AppUiTokens.PagePadding)) {
                    RunEmpty(AppText.Common.UNKNOWN_PAGE_TYPE, AppText.Run.REOPEN_FROM_RUN_ENTRY_HINT)
                }
            }
        }
    }
}

@Composable
fun RunTaskList(graph: AppGraph, onOpen: (Route) -> Unit, onBack: (() -> Unit)? = null) {
    val session by graph.sessions.session.collectAsState()
    val settings by graph.settings.settings.collectAsState()
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
        Screen(AppText.Navigation.CAMPUS_RUN, onBack, actions = {
            TextButton(AppText.Run.LOCAL_RECORD_TITLE, onClick = { onOpen(Route.Page("runtracker", title = AppText.Run.REAL_RUN_RECORD_TITLE)) })
        }) { padding ->
            val direction = LocalLayoutDirection.current
            // Reserve chrome in scroll content so cards can move under the blur and clear the floating tab bar.
            val contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction) + AppUiTokens.PagePadding,
                top = padding.calculateTopPadding() + AppUiTokens.PagePadding,
                end = padding.calculateEndPadding(direction) + AppUiTokens.PagePadding,
                bottom = padding.calculateBottomPadding() + LocalNavigationBottomInset.current + AppUiTokens.PagePadding,
            )
            PullToRefresh(state.loading, { scope.launch { controller.refresh() } },
                modifier = Modifier.fillMaxSize().consumeWindowInsets(padding), contentPadding = padding,
                refreshTexts = io.github.betterzslf.ui.appRefreshTexts()) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item("filters") {
                        val filters = listOf(RunTaskFilter.ALL, RunTaskFilter.UNFINISHED, RunTaskFilter.FINISHED)
                        TabRow(tabs = listOf(AppText.Common.ALL, AppText.Common.INCOMPLETE, AppText.Common.COMPLETED),
                            selectedTabIndex = filters.indexOf(state.filter).coerceAtLeast(0),
                            onTabSelected = { index -> if (!state.loading) scope.launch { controller.select(filters[index]); list.scrollToItem(0) } },
                            cornerRadius = AppUiTokens.ButtonRadius, height = 44.dp)
                    }
                    if (owner.isBlank()) item("login") { RunEmpty(AppText.Auth.LOGIN_REQUIRED, AppText.Run.CURRENT_ACCOUNT_TASKS_ONLY_HINT) }
                    if (state.loading) item("loading") { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
                    state.error?.let { error -> item("error") { RunCard {
                        InfoBanner(error, AppText.Run.TASK_LOAD_FAILED, UiTone.Warning)
                        TextButton(AppText.Common.REFRESH, onClick = { scope.launch { controller.refresh() } })
                    } } }
                    if (!state.loading && state.error == null && state.items.isEmpty() && owner.isNotBlank()) item("empty") {
                        RunEmpty(AppText.Run.NO_RUN_TASKS, AppText.Run.NO_SERVER_TASKS_LOCAL_TOOLS_AVAILABLE_HINT)
                    }
                    itemsIndexed(state.items, key = { index, row -> "task:${row.identityKey}:$index" }) { _, task ->
                        val selection = RunTaskSelection.from(task)
                        val minimum = remember(task.raw) { RunDataDecoder.detail(task.raw)?.minimumDistanceMeters }
                        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(AppUiTokens.CardPadding),
                            onClick = selection?.let { selected -> ({ onOpen(Route.Page("rundetail", selected.encode(), task.title)) }) }) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                Text(task.title, modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                when (task.isFinish) {
                                    "1" -> RunStatus(AppText.Common.COMPLETED, UiTone.Positive)
                                    "0" -> RunStatus(AppText.Common.INCOMPLETE, UiTone.Info)
                                    else -> RunStatus(task.isFinish ?: AppText.Common.UNKNOWN)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(listOfNotNull("${AppText.Run.DISTANCE_REQUIREMENT_TITLE} · ${RunDisplay.distance(minimum, settings.metricUnits)}", task.endText?.let(AppText.Run::endsAt)).joinToString(" · "),
                                fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (selection == null) InfoBanner(AppText.Run.MISSING_TASK_OR_SCHEDULE_READ_ONLY, tone = UiTone.Warning)
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                                RunAction(AppText.Run.SCHEDULE_SCORES_AND_HISTORY, enabled = selection != null, modifier = Modifier.weight(1f), onClick = {
                                    selection?.let { onOpen(Route.Page("runrecords", it.encode(), AppText.Run.SCHEDULE_RUN_RECORDS_TITLE)) }
                                })
                                RunAction(AppText.Run.START_REAL_RECORDING, enabled = selection != null, primary = true, modifier = Modifier.weight(1f), onClick = {
                                    selection?.let { onOpen(Route.Page("rundetail", it.encode(), task.title)) }
                                })
                            }
                        }
                    }
                    state.moreError?.let { error -> item("more-error") { SectionCard(AppText.Run.NEXT_TASK_PAGE_FAILED_PREVIOUS_RETAINED, error, { scope.launch { controller.more() } }) } }
                    if (!state.end && state.items.isNotEmpty()) item("more") { TextButton(AppText.Common.LOAD_NEXT_PAGE, enabled = !state.loading, onClick = { scope.launch { controller.more() } }) }
                    if (state.end && state.items.isNotEmpty()) item("end") { Text(AppText.Run.tasksPaginationEnd(state.items.size), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    if (EditionAccess.routeEditorsEnabled) item("pro") { RunCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            RunStatus("Pro", UiTone.Info)
                            TextButton(AppText.Run.LOCAL_ROUTE_PREVIEW_TITLE, onClick = {
                                if (EditionAccess.routeEditorsEnabled) onOpen(Route.Page("runimport", title = AppText.Run.ROUTE_IMPORT_PREVIEW_TITLE))
                            })
                        }
                    } }
                    runWarnings(state.warnings)
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
    Screen(AppText.Run.TASK_DETAIL_TITLE, back, actions = { TextButton(AppText.Common.REFRESH, enabled = !query.loading, onClick = { refresh++ }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(AppUiTokens.PagePadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                if (EditionAccess.routeEditorsEnabled) item("import") { TextButton(AppText.Run.IMPORT_ROUTE_LOCAL_PREVIEW_ACTION, onClick = {
                    if (EditionAccess.routeEditorsEnabled) open(Route.Page("runimport", page.id, AppText.Run.ROUTE_IMPORT_PREVIEW_TITLE))
                }, modifier = Modifier.fillMaxWidth()) }
                runWarnings(detail.warnings)
                runRawFields(detail.task.raw)
            } else if (!query.loading && query.error == null) item("shape") { RunEmpty(AppText.Run.TASK_DETAIL_STRUCTURE_UNRECOGNIZED, AppText.Run.UNDECODABLE_TASK_DETAIL_NO_RECORDING_HINT) }
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
        Screen(AppText.Run.SCHEDULE_RUN_RECORDS_TITLE, back) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(AppUiTokens.PagePadding)) {
                RunEmpty(AppText.Run.SELECT_TASK_SCHEDULE_FIRST, AppText.Run.SCHEDULE_PAGINATION_SCOPE_HINT, AppText.Run.SELECT_FROM_MY_TASKS,
                    { open(Route.Page("runs", title = AppText.Run.SELECT_RUN_TASK)) })
            }
        }
        return
    }
    val bound = remember(graph, session?.userId, session?.token) { graph.repositories.run.boundToCurrentSession() }
    val controller = remember(bound, owner, selection) { RunRecordController(RunRecordPageSource { requestedOwner, plan, offset, limit ->
        if (bound == null || bound.boundOwnerId != requestedOwner) ApiResult.Failure(ApiError.Authentication()) else bound.records(requestedOwner, plan, offset, limit)
    }, owner, selection.errandScheduleId) }
    val state by controller.state.collectAsState()
    var detailRefresh by remember { mutableIntStateOf(0) }
    val planQuery = rememberRunQuery(graph, selection, detailRefresh) { it.detail(selection.errandId, it.boundOwnerId.orEmpty(), selection.errandScheduleId) }
    val minimum = remember(planQuery.data, selection) {
        planQuery.data?.let(RunDataDecoder::detail)?.withQueryContext(selection)
            ?.takeIf { it.task.errandId == selection.errandId && it.task.scheduleId == selection.errandScheduleId }
            ?.minimumDistanceMeters
    }
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(controller, list, state.items.size, state.loading, state.end, state.moreError, state.error) {
        if (!state.loading && !state.end && state.error == null && state.moreError == null && state.items.isNotEmpty()) snapshotFlow {
            val layout = list.layoutInfo
            layout.totalItemsCount > 0 && (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3
        }.distinctUntilChanged().collect { if (it) controller.more() }
    }
    Screen(AppText.Run.SCHEDULE_RUN_RECORDS_TITLE, back) { padding ->
        val listPadding = pageContentPadding(padding, LocalNavigationBottomInset.current, LocalLayoutDirection.current)
        PullToRefresh(state.loading, { detailRefresh++; scope.launch { controller.refresh() } }, modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = padding, refreshTexts = appRefreshTexts()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("summary") { MetricStrip(listOf(
                    UiMetric(state.items.size.toString(), AppText.Run.LOADED_RECORDS_LABEL),
                    UiMetric(RunDisplay.distance(minimum, settings.metricUnits), AppText.Run.SCHEDULE_REQUIREMENT_LABEL),
                    UiMetric("—", AppText.Run.OTHER_SUMMARY_PENDING_LABEL),
                )) }
                if (state.loading) item("loading") { CircularProgressIndicator() }
                state.error?.let { error -> item("error") { SectionCard(AppText.Run.RECORD_LOAD_FAILED_CONTENT_RETAINED, error, { scope.launch { controller.refresh() } }) } }
                if (!state.loading && state.error == null && state.items.isEmpty()) item("empty") { RunEmpty(AppText.Run.NO_RECORDS_FOR_SCHEDULE, AppText.Run.SERVER_RECORD_LIST_EMPTY) }
                itemsIndexed(state.items, key = { index, row -> "record:${row.trid}:$index" }) { _, row ->
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(AppUiTokens.CardPadding),
                        onClick = if (row.trid.isNullOrBlank()) null else ({ open(Route.Page("runrecord", row.trid, row.title)) })) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(listOf(RunDisplay.distance(row.distanceMeters, settings.metricUnits), RunDisplay.duration(row.elapsedMillis), recordPace(row, settings.metricUnits)).joinToString(" · "),
                                    fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                            RunStatus(row.status ?: "—")
                        }
                        if (row.trid.isNullOrBlank()) Text(AppText.Run.MISSING_RECORD_ID_READ_ONLY_NEWLINE_SUFFIX, fontSize = 12.sp)
                    }
                }
                state.moreError?.let { error -> item("more-error") { SectionCard(AppText.Run.NEXT_RECORD_PAGE_FAILED_PREVIOUS_RETAINED, error, { scope.launch { controller.more() } }) } }
                if (!state.end && state.items.isNotEmpty()) item("more") { TextButton(AppText.Common.LOAD_NEXT_PAGE, enabled = !state.loading, onClick = { scope.launch { controller.more() } }) }
                if (state.end && state.items.isNotEmpty()) item("end") { Text(AppText.Run.recordsPaginationEnd(state.items.size), fontSize = 12.sp) }
                runWarnings(state.warnings)
                if (planQuery.error != null) item("plan-feedback") { InfoBanner(planQuery.error.orEmpty(), tone = UiTone.Warning) }
            }
        }
    }
}

private fun recordPace(row: RunRecordItem, metric: Boolean): String =
    if (row.distanceMeters != null && row.elapsedMillis != null) RunDisplay.pace(row.distanceMeters, row.elapsedMillis, metric) else "—"

@Composable
private fun RunRecordPage(graph: AppGraph, page: Route.Page, back: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val settings by graph.settings.settings.collectAsState()
    val recordQuery = rememberRunQuery(graph, "record:${page.id}", refresh) { if (page.id.isBlank()) missingRunId() else it.record(page.id) }
    val trackQuery = rememberRunQuery(graph, "track:${page.id}", refresh) { if (page.id.isBlank()) missingRunId() else it.track(page.id) }
    val record = remember(recordQuery.data) { recordQuery.data?.let(RunDataDecoder::record) }
    val points = remember(trackQuery.data, record) { trackQuery.data?.let(RunDataDecoder::track).orEmpty().ifEmpty { record?.points.orEmpty() } }
    Screen(AppText.Run.RUN_SCORES_TITLE, back, actions = { TextButton(AppText.Common.QUERY_AGAIN, enabled = !recordQuery.loading && !trackQuery.loading, onClick = { refresh++ }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(AppUiTokens.PagePadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
            record?.let { runRawFields(it.raw) }
        }
    }
}
