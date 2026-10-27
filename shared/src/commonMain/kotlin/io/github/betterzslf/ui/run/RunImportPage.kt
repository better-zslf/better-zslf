package io.github.betterzslf.ui.run

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.edition.EditionAccess
import io.github.betterzslf.edition.EditionUnavailablePage
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.feature.runui.*
import io.github.betterzslf.platform.FilePickResult
import io.github.betterzslf.platform.rememberFilePicker
import io.github.betterzslf.platform.runMapProviderFor
import io.github.betterzslf.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Every source/edit/parameter/selection lives only in this route's memory, never the real-run runtime. */
@Composable
internal fun RunImportPage(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    if (!EditionAccess.routeEditorsEnabled) { EditionUnavailablePage(back); return }
    val settings by graph.settings.settings.collectAsState()
    val account by graph.sessions.session.collectAsState()
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
    var generatedSeed by remember(controller) { mutableStateOf<Int?>(null) }
    var fileMessage by remember { mutableStateOf<String?>(null) }
    var visible by remember { mutableIntStateOf(40) }
    var showParameters by remember(controller) { mutableStateOf(false) }
    var mapStage by remember(controller) { mutableIntStateOf(0) }
    var chosenPreview by remember(controller) { mutableStateOf<RunImportState?>(null) }
    val sourceBusy = model.busy || query.loading
    val owner = remember(graph, account?.userId, account?.token) { Any() }
    val provider = runMapProviderFor(account?.mapWebType)
    val apiKey = account?.mapToken
    val identity = remember(owner, controller, provider, apiKey) { RunManualDraftIdentity(owner, controller, provider, apiKey) }
    val latestController by rememberUpdatedState(controller)
    var manualEditor by remember(controller) { mutableStateOf<RunManualImportEditor?>(null) }
    var manualNotice by remember(controller) { mutableStateOf<String?>(null) }
    fun captureManualEditor(): RunManualImportEditor? {
        if (sourceBusy || !identity.configured) return null
        val admission = controller.beginManualDraft() ?: return null
        val capturedController = controller
        val capturedAccount = account
        return RunManualImportEditor(RunManualDraftSession(identity,
            isIdentityCurrent = { latestController === capturedController && graph.sessions.session.value == capturedAccount },
            isAdmissionCurrent = { capturedController.isManualDraftCurrent(admission) }), admission)
    }
    fun cancelManualEditor(editor: RunManualImportEditor) {
        editor.close()
        if (manualEditor === editor) manualEditor = null
    }
    val mountedEditor = manualEditor
    DisposableEffect(mountedEditor) { onDispose { mountedEditor?.close() } }
    SideEffect {
        val editor = manualEditor
        if (editor != null) {
            if (editor.session.identity != identity) {
                val canReset = editor.session.identity.sharesOwner(identity) && controller.isManualDraftCurrent(editor.admission)
                editor.close()
                manualEditor = if (canReset) captureManualEditor() else null
                manualNotice = AppText.ManualRoute.SOURCE_CHANGED
            } else if (!editor.session.refresh() && editor.session.state.value.phase == RunManualDraftPhase.STALE) {
                cancelManualEditor(editor)
                manualNotice = AppText.ManualRoute.SOURCE_CHANGED
            }
        }
    }
    val picker = rememberFilePicker { result ->
        when (result) {
            FilePickResult.Cancelled -> fileMessage = AppText.Run.IMPORT_CANCELLED_PREVIEW_RETAINED
            is FilePickResult.Failed -> fileMessage = result.message
            is FilePickResult.Selected -> {
                manualEditor?.let(::cancelManualEditor)
                fileMessage = null
                scope.launch { if (controller.read(result.file)) { mapStage = 0; visible = 40 } }
            }
        }
    }
    val distance = target.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
    val duration = minutes.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 && it <= 1440 }?.let { (it * 60_000).toLong() }?.takeIf { it > 0 }
    val seedNumber = seed.trim().toIntOrNull()
    val parameterized = distance != null && duration != null
    val changed = model.validation != null && (model.targetDistanceMeters != distance || model.durationMillis != duration)
    val generatedChanged = model.generated != null && (changed || generatedSeed != seedNumber || model.generationSamplingSeconds != settings.samplingSeconds)
    val busy = sourceBusy || manualEditor != null
    val frame = model.workingCoordinateSystem
    val dark = when (settings.theme) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    val guardedBack: () -> Unit = { manualEditor?.let(::cancelManualEditor) ?: back() }

    Screen(AppText.Run.ROUTE_IMPORT_PREVIEW_TITLE, guardedBack, actions = {
        TextButton(AppText.Run.CLEAR_DRAFT, enabled = !busy, onClick = { scope.launch { controller.clear(); chosenPreview = null; mapStage = 0 } })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(AppUiTokens.PagePadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item("local-only") { InfoBanner(AppText.Run.IMPORT_PREVIEW_ISOLATION_HINT, AppText.Run.LOCAL_ROUTE_PREVIEW_ONLY_TITLE, UiTone.Neutral) }
                runFeedback(query, { refresh++ })
                item("source-picker") { RunCard {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        RunFileGlyph()
                        RunAction(AppText.Run.SELECT_ROUTE_FILE, enabled = !busy, primary = true, onClick = picker::launch, modifier = Modifier.fillMaxWidth())
                        TextButton(AppText.ManualRoute.OPEN_EDITOR, enabled = !busy && identity.configured, onClick = {
                            if (manualEditor == null) { manualNotice = null; manualEditor = captureManualEditor() }
                        }, modifier = Modifier.fillMaxWidth())
                        TextButton(AppText.Run.USE_RECOMMENDED_PATH, enabled = !busy && detail.walkPaths.isNotEmpty(), onClick = {
                            scope.launch { if (controller.useRecommended()) mapStage = 0 }
                        }, modifier = Modifier.fillMaxWidth())
                    }
                    if (!identity.configured) InfoBanner(AppText.Run.MAP_PROVIDER_AND_PUBLIC_KEY_CONFIGURATION_REQUIRED, tone = UiTone.Neutral)
                    if (detail.walkPaths.isEmpty()) Text(AppText.Run.SERVER_USABLE_RECOMMENDED_PATH_MISSING, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                } }
                manualNotice?.let { notice -> item("manual-notice") { InfoBanner(notice, tone = UiTone.Neutral) } }
                fileMessage?.let { message -> item("file-message") { InfoBanner(message, tone = UiTone.Neutral) } }
                model.error?.let { message -> item("error") { InfoBanner(message, AppText.Run.LOCAL_PREVIEW_INCOMPLETE, UiTone.Warning) } }
                if (model.busy) item("busy") { CircularProgressIndicator() }
                if (model.source != null) {
                    item("source-summary") { SectionTitle(model.fileName ?: AppText.Run.ROUTE_DRAFT_TITLE, AppText.Run.VALIDATION_PARAMETERS_TITLE, { showParameters = true }) }
                    item("map-tabs") { TabRow(
                        tabs = listOf(AppText.Run.ROUTE_DRAFT_TITLE, AppText.Run.PROCESSED_WORKING_COPY_TITLE, AppText.Run.GENERATED_NON_GPS_PREVIEW_TITLE),
                        selectedTabIndex = mapStage, onTabSelected = { index -> mapStage = index }, height = 44.dp, cornerRadius = AppUiTokens.ButtonRadius,
                    ) }
                    item("preview-map") {
                        val paths = when (mapStage) {
                            1 -> listOf(AppText.Run.PROCESSED_WORKING_COPY_TITLE to model.workingPoints)
                            2 -> listOf(AppText.Run.GENERATED_NOT_MEASURED_LABEL to model.generated?.points.orEmpty().map { it.point })
                            else -> model.originalSegments.mapIndexed { index, points -> AppText.Run.originalSegment(index + 1) to points }
                        }
                        RunMapPreview(graph, paths, coordinateSystem = if (mapStage == 0) model.originalCoordinateSystem else frame, height = 220.dp, showDetails = true)
                    }
                    item("validation-summary") { RunCard {
                        SectionTitle(AppText.Run.VALIDATE_ROUTE_GEOMETRY, AppText.Run.VALIDATION_PARAMETERS_TITLE, { showParameters = true })
                        val report = if (mapStage == 2) model.generatedValidation else model.validation
                        if (report != null) {
                            Text(RunDisplay.distance(report.distanceMeters, settings.metricUnits), fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (report.valid) AppText.Run.LOCAL_GEOMETRY_VALID_NOT_SERVER_ACCEPTANCE else AppText.Run.LOCAL_PREVIEW_INCOMPLETE,
                                fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            if (report.issues.isNotEmpty()) InfoBanner(report.issues.joinToString("\n") { it.message }, tone = UiTone.Warning)
                        } else Text(AppText.Run.VALIDATION_PARAMETERS_TITLE, fontSize = 14.sp)
                        if (detail.minimumDistanceMeters == null) InfoBanner(AppText.Run.LOCAL_TARGET_VALID_SCHOOL_MINIMUM_UNKNOWN, tone = UiTone.Neutral)
                        if (detail.checkpoints.isEmpty() || detail.checkpoints.size != detail.checkpointSourceCount) Text(AppText.Run.CHECKPOINT_DATA_INCOMPLETE_HINT, fontSize = 12.sp)
                        if (changed || generatedChanged) InfoBanner(AppText.Run.REVALIDATE_REPORT_AFTER_PARAMETER_CHANGE_HINT, AppText.Run.TARGET_PARAMETERS_CHANGED, UiTone.Warning)
                    } }
                    item("use-preview") { RunAction(AppText.Run.USE_CURRENT_PREVIEW_ACTION, primary = true,
                        enabled = !busy && !changed && !generatedChanged && model.validation?.valid == true && (model.generated == null || model.generatedValidation?.valid == true),
                        modifier = Modifier.fillMaxWidth(), onClick = {
                            // Selection only: no repository, real-run runtime or persistent settings mutation.
                            chosenPreview = model
                            mapStage = if (model.generated != null) 2 else 1
                        }) }
                    if (chosenPreview === model && !changed && !generatedChanged) item("chosen") { InfoBanner(AppText.Run.IMPORT_PREVIEW_ISOLATION_HINT, AppText.Run.LOCAL_ROUTE_PREVIEW_ONLY_TITLE, UiTone.Neutral) }
                    item("more-tools") { RunAction(AppText.Run.VALIDATION_PARAMETERS_TITLE, enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = { showParameters = true }) }
                }
                runWarnings(model.warnings)
                if (page.id.isNotBlank()) item("real-mode") { TextButton(AppText.Run.SWITCH_TO_REAL_RECORDING_THIS_TIME, enabled = !busy, onClick = {
                    open(Route.Page("runtracker", page.id, AppText.Run.REAL_RUN_RECORD_TITLE))
                }) }
            }
            manualEditor?.let { editor ->
                key(editor.session.mount) {
                    RunManualDraftSheet(editor.session,
                        availableHeight = (maxHeight - padding.calculateTopPadding() - padding.calculateBottomPadding()).coerceAtLeast(0.dp),
                        dark = dark, onCancel = { cancelManualEditor(editor) }, onCommit = { commit ->
                            val route = commit.route
                            if (route == null || manualEditor !== editor || !editor.session.refresh()) cancelManualEditor(editor)
                            else {
                                editor.saveJob = scope.launch {
                                    try {
                                        val accepted = manualEditor === editor && editor.session.refresh() && controller.useManual(route, editor.admission) {
                                            manualEditor === editor && editor.session.canPublishCommit(commit)
                                        }
                                        if (manualEditor === editor && editor.session.finishCommit(commit, accepted) && accepted) {
                                            editor.session.close(); manualEditor = null; fileMessage = null; mapStage = 0; visible = 40
                                        }
                                    } catch (cancelled: CancellationException) { editor.session.close(); throw cancelled }
                                    catch (_: Exception) { editor.session.finishCommit(commit, accepted = false) }
                                    finally { editor.saveJob = null }
                                }
                            }
                        })
                }
            }
        }
    }

    OverlayBottomSheet(show = showParameters && manualEditor == null, title = AppText.Run.VALIDATION_PARAMETERS_TITLE, onDismissRequest = { showParameters = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 580.dp), verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("target") { TextField(target, { target = it }, enabled = !busy, label = AppText.Run.LOCAL_TARGET_DISTANCE_METERS_FIELD, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("duration") { TextField(minutes, { minutes = it }, enabled = !busy, label = AppText.Run.TARGET_DURATION_MINUTES_FIELD, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("seed") { TextField(seed, { seed = it }, enabled = !busy, label = AppText.Run.REPRODUCIBLE_GENERATION_SEED_FIELD, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("requirements") { InfoBanner(AppText.Run.importDistanceRequirements(RunDisplay.distance(detail.minimumDistanceMeters, settings.metricUnits), settings.samplingSeconds) +
                if (detail.minimumDistanceMeters == null) AppText.Run.SERVER_MINIMUM_DISTANCE_UNIT_UNKNOWN_NEWLINE_HINT else "", tone = UiTone.Neutral) }
            if (!parameterized) item("bad-input") { InfoBanner(AppText.Run.TARGET_DISTANCE_AND_DURATION_VALIDATION_HINT, tone = UiTone.Warning) }
            if (changed || generatedChanged) item("stale-report") { InfoBanner(AppText.Run.REVALIDATE_REPORT_AFTER_PARAMETER_CHANGE_HINT, tone = UiTone.Warning) }
            item("geometry-actions") { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RunAction(AppText.Run.ADJUST_WORKING_ROUTE_DENSITY, enabled = !busy && model.originalSegments.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch { if (controller.normalize()) mapStage = 1 }
                })
                RunAction(AppText.Run.VALIDATE_ROUTE_GEOMETRY, enabled = !busy && parameterized && model.workingPoints.isNotEmpty(), primary = true, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch { controller.validate(distance!!, duration!!) }
                })
                RunAction(AppText.Run.EXTEND_DISTANCE_ALONG_RECOMMENDED_PATH, enabled = !busy && parameterized && detail.walkPaths.isNotEmpty() && model.workingPoints.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch { if (controller.supplement(distance!!, duration!!)) mapStage = 1 }
                })
            } }
            model.validation?.let { report -> routeReportItems(report, detail, "working") }
            item("generate") { RunAction(AppText.Run.GENERATE_LOCAL_TIMED_PREVIEW,
                enabled = !busy && parameterized && seedNumber != null && model.workingPoints.size >= 2, primary = true, modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch { if (controller.generate(distance!!, duration!!, seedNumber!!, settings.samplingSeconds)) { generatedSeed = seedNumber; mapStage = 2; visible = 40 } }
                }) }
            val generated = model.generated
            if (generated != null) {
                item("generated") { InfoBanner(AppText.Run.generatedPreviewSummary(generated.points.size, RunDisplay.distance(generated.distanceMeters, settings.metricUnits),
                    RunDisplay.duration(generated.endTimeMillis - generated.startTimeMillis), model.generationSamplingSeconds), AppText.Run.GENERATED_NON_GPS_PREVIEW_TITLE, UiTone.Neutral) }
                model.generatedValidation?.let { routeReportItems(it, detail, "generated") }
                item("generated-points") { RunDisclosure(AppText.Run.GENERATED_NOT_MEASURED_LABEL) {
                    generated.points.take(visible).forEachIndexed { index, point ->
                        Text("${AppText.Run.generatedPointOrdinal(index + 1)}\n${AppText.Run.generatedPointDetails(coordinate(point.point), point.locatetime, RunDisplay.distance(point.dis, settings.metricUnits), RunDisplay.decimal(point.count))}", fontSize = 12.sp)
                    }
                    if (visible < generated.points.size) TextButton(AppText.Run.EXPAND_MORE_GENERATED_POINTS, onClick = { visible += 40 })
                } }
            } else if (model.originalPoints.isNotEmpty()) item("original-points") { RunDisclosure(AppText.Run.ROUTE_DRAFT_TITLE) {
                model.originalPoints.take(visible).forEachIndexed { index, point -> Text("${AppText.Run.importedCoordinateOrdinal(index + 1)} · ${coordinate(point)}", fontSize = 12.sp) }
                if (visible < model.originalPoints.size) TextButton(AppText.Run.EXPAND_MORE_ORIGINAL_COORDINATES, onClick = { visible += 40 })
            } }
            item("limits") { InfoBanner(AppText.Run.ROUTE_FILE_LIMITS_AND_COORDINATES_HINT, tone = UiTone.Neutral) }
        }
    }
}

/** Page-scope job ownership is separate from the native document's event admission. */
private class RunManualImportEditor(val session: RunManualDraftSession, val admission: RunManualImportAdmission) {
    var saveJob: Job? = null
    fun close() { session.close(); saveJob?.cancel(); saveJob = null }
    override fun toString(): String = "RunManualImportEditor(redacted)"
}

private fun localPreviewDetail() = RunTaskDetail(
    RunTaskItem(null, null, AppText.Run.SERVER_TASK_NOT_SELECTED, null, null, null, "local-preview-only", JsonObject(emptyMap())),
    emptyList(), emptyList(), null, listOf(AppText.Run.UNSELECTED_TASK_LOCAL_GEOMETRY_ONLY_HINT),
)

private fun LazyListScope.routeReportItems(report: RouteValidationReport, detail: RunTaskDetail, prefix: String) {
    val groups = listOf(
        AppText.Run.ORDERED_CHECKPOINT_COVERAGE_TITLE to setOf(RouteIssueCode.MISSING_CHECKPOINT, RouteIssueCode.CHECKPOINT_ORDER),
        AppText.Run.DISTANCE_REQUIREMENT_TITLE to setOf(RouteIssueCode.DISTANCE_TOO_SHORT, RouteIssueCode.DISTANCE_TOO_LONG, RouteIssueCode.TOO_FEW_POINTS),
        AppText.Run.TARGET_SPEED_LIMIT_LABEL to setOf(RouteIssueCode.SPEED),
        AppText.Run.POINT_DENSITY_RANGE_LABEL to setOf(RouteIssueCode.SPARSE, RouteIssueCode.DENSE),
    )
    item("$prefix:report") { RunDisclosure(AppText.Run.GEOMETRY_REPORT_PARAMETERS_TITLE) {
        groups.forEachIndexed { index, (label, codes) ->
            val issues = report.issues.filter { it.code in codes }
            val unknown = index == 0 && (detail.checkpoints.isEmpty() || detail.checkpoints.size != detail.checkpointSourceCount)
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val text = when {
                unknown -> AppText.Run.CHECKPOINT_DATA_INCOMPLETE_HINT
                issues.isNotEmpty() -> issues.joinToString("\n") { it.message + (it.index?.let { position -> AppText.Run.issueIndex(position) } ?: "") }
                index == 1 && detail.minimumDistanceMeters == null -> AppText.Run.LOCAL_TARGET_VALID_SCHOOL_MINIMUM_UNKNOWN
                else -> AppText.Run.LOCAL_GEOMETRY_VALID_NOT_SERVER_ACCEPTANCE
            }
            InfoBanner(text, tone = if (issues.isNotEmpty()) UiTone.Warning else UiTone.Neutral)
        }
    } }
}
