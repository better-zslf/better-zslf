package io.github.betterzslf.ui.run

import io.github.betterzslf.core.text.AppText
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
            FilePickResult.Cancelled -> fileMessage = AppText.Run.IMPORT_CANCELLED_PREVIEW_RETAINED
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

    Screen(page.title.ifBlank { AppText.Run.ROUTE_IMPORT_LOCAL_PREVIEW_TITLE }, back, actions = { TextButton(AppText.Run.CLEAR_DRAFT, enabled = !busy, onClick = { scope.launch { controller.clear() } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("local-only") { SectionCard(AppText.Run.LOCAL_ROUTE_PREVIEW_ONLY_TITLE, AppText.Run.IMPORT_PREVIEW_ISOLATION_HINT) }
            runFeedback(query, { refresh++ })
            runWarnings(model.warnings)
            item("source-actions") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(AppText.Run.SELECT_ROUTE_FILE, enabled = !busy, onClick = picker::launch, modifier = Modifier.weight(1f))
                    TextButton(AppText.Run.USE_RECOMMENDED_PATH, enabled = !busy && detail.walkPaths.isNotEmpty(), onClick = { scope.launch { controller.useRecommended() } }, modifier = Modifier.weight(1f))
                }
            }
            item("size-limit") { Text(AppText.Run.ROUTE_FILE_LIMITS_AND_COORDINATES_HINT) }
            fileMessage?.let { message -> item("file-message") { SectionCard(AppText.Common.FILE_SELECTION_TITLE, message) } }
            model.error?.let { message -> item("error") { SectionCard(AppText.Run.LOCAL_PREVIEW_INCOMPLETE, message) } }
            if (model.busy) item("busy") { CircularProgressIndicator() }
            if (model.source != null) {
                item("source") { SectionCard(model.fileName ?: AppText.Run.ROUTE_DRAFT_TITLE, AppText.Run.importSourceSummary((model.source).toString(), (model.originalPoints.size), (model.originalSegments.size), (model.workingPoints.size), (if (model.normalized) AppText.Run.DENSITY_ADJUSTED_EXPLICITLY else AppText.Run.DENSITY_NOT_ADJUSTED), (if (model.detoured) AppText.Run.LOCAL_DISTANCE_EXTENSION_APPLIED else AppText.Run.NO_DISTANCE_EXTENSION))) }
                item("original-map") { RunMapPreview(graph, model.originalSegments.mapIndexed { index, points -> AppText.Run.originalSegment((index + 1)) to points }, coordinateSystem = model.originalCoordinateSystem) }
            }
            item("target") { TextField(target, { target = it }, enabled = !busy, label = AppText.Run.LOCAL_TARGET_DISTANCE_METERS_FIELD, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("duration") { TextField(minutes, { minutes = it }, enabled = !busy, label = AppText.Run.TARGET_DURATION_MINUTES_FIELD, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("seed") { TextField(seed, { seed = it }, enabled = !busy, label = AppText.Run.REPRODUCIBLE_GENERATION_SEED_FIELD, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("requirements") { SectionCard(AppText.Run.VALIDATION_PARAMETERS_TITLE, AppText.Run.importDistanceRequirements((RunDisplay.distance(detail.minimumDistanceMeters, settings.metricUnits)), (settings.samplingSeconds)) + if (detail.minimumDistanceMeters == null) AppText.Run.SERVER_MINIMUM_DISTANCE_UNIT_UNKNOWN_NEWLINE_HINT else "") }
            if (!parameterized) item("bad-input") { Text(AppText.Run.TARGET_DISTANCE_AND_DURATION_VALIDATION_HINT) }
            if (changed) item("stale-report") { SectionCard(AppText.Run.TARGET_PARAMETERS_CHANGED, AppText.Run.REVALIDATE_REPORT_AFTER_PARAMETER_CHANGE_HINT) }
            item("geometry-actions") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(AppText.Run.ADJUST_WORKING_ROUTE_DENSITY, enabled = !busy && model.originalSegments.isNotEmpty(), onClick = { scope.launch { controller.normalize() } }, modifier = Modifier.fillMaxWidth())
                    TextButton(AppText.Run.VALIDATE_ROUTE_GEOMETRY, enabled = !busy && parameterized && model.workingPoints.isNotEmpty(), onClick = { scope.launch { controller.validate(distance!!, duration!!) } }, modifier = Modifier.fillMaxWidth())
                    TextButton(AppText.Run.EXTEND_DISTANCE_ALONG_RECOMMENDED_PATH, enabled = !busy && parameterized && detail.walkPaths.isNotEmpty() && model.workingPoints.isNotEmpty(), onClick = { scope.launch { controller.supplement(distance!!, duration!!) } }, modifier = Modifier.fillMaxWidth())
                }
            }
            model.validation?.let { report ->
                item("report-parameters") { SectionCard(AppText.Run.GEOMETRY_REPORT_PARAMETERS_TITLE, AppText.Run.importValidationMetrics((RunDisplay.distance(model.targetDistanceMeters, settings.metricUnits)), (RunDisplay.duration(model.durationMillis)), (RunDisplay.distance(report.distanceMeters, settings.metricUnits)), (RunDisplay.decimal(report.averageSpeedMetersPerSecond, 2)))) }
                routeReportItems(report, detail, "working")
            }
            if (model.normalized || model.detoured) item("working-map") { RunMapPreview(graph, listOf(AppText.Run.PROCESSED_WORKING_COPY_TITLE to model.workingPoints), coordinateSystem = frame) }
            item("generate") { TextButton(AppText.Run.GENERATE_LOCAL_TIMED_PREVIEW, enabled = !busy && parameterized && seedNumber != null && model.workingPoints.size >= 2,
                onClick = { scope.launch { controller.generate(distance!!, duration!!, seedNumber!!, settings.samplingSeconds) } }, modifier = Modifier.fillMaxWidth()) }
            val generated = model.generated
            if (generated != null) {
                item("generated") { SectionCard(AppText.Run.GENERATED_NON_GPS_PREVIEW_TITLE, AppText.Run.generatedPreviewSummary((generated.points.size), (RunDisplay.distance(generated.distanceMeters, settings.metricUnits)), (RunDisplay.duration(generated.endTimeMillis - generated.startTimeMillis)), (model.generationSamplingSeconds))) }
                item("generated-map") { RunMapPreview(graph, listOf(AppText.Run.GENERATED_NOT_MEASURED_LABEL to generated.points.map { it.point }), coordinateSystem = frame) }
                model.generatedValidation?.let { routeReportItems(it, detail, "generated") }
                itemsIndexed(generated.points.take(visible), key = { index, _ -> "generated:$index" }) { index, point ->
                    SectionCard(AppText.Run.generatedPointOrdinal((index + 1)), AppText.Run.generatedPointDetails((coordinate(point.point)), (point.locatetime), (RunDisplay.distance(point.dis, settings.metricUnits)), (RunDisplay.decimal(point.count))))
                }
                if (visible < generated.points.size) item("more-generated") { TextButton(AppText.Run.EXPAND_MORE_GENERATED_POINTS, onClick = { visible += 40 }) }
            } else {
                itemsIndexed(model.originalPoints.take(visible), key = { index, _ -> "original:$index" }) { index, point -> SectionCard(AppText.Run.importedCoordinateOrdinal((index + 1)), coordinate(point)) }
                if (visible < model.originalPoints.size) item("more-original") { TextButton(AppText.Run.EXPAND_MORE_ORIGINAL_COORDINATES, onClick = { visible += 40 }) }
            }
            if (page.id.isNotBlank()) item("real-mode") { TextButton(AppText.Run.SWITCH_TO_REAL_RECORDING_THIS_TIME, onClick = { open(Route.Page("runtracker", page.id, AppText.Run.REAL_RUN_RECORD_TITLE)) }) }
        }
    }
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
    groups.forEachIndexed { index, (label, codes) ->
        val issues = report.issues.filter { it.code in codes }
        val unknown = index == 0 && (detail.checkpoints.isEmpty() || detail.checkpoints.size != detail.checkpointSourceCount)
        val text = when {
            unknown -> AppText.Run.CHECKPOINT_DATA_INCOMPLETE_HINT
            issues.isNotEmpty() -> issues.joinToString("\n") { it.message + (it.index?.let { position -> AppText.Run.issueIndex((position)) } ?: "") }
            index == 1 && detail.minimumDistanceMeters == null -> AppText.Run.LOCAL_TARGET_VALID_SCHOOL_MINIMUM_UNKNOWN
            else -> AppText.Run.LOCAL_GEOMETRY_VALID_NOT_SERVER_ACCEPTANCE
        }
        item("$prefix:report:$index") { SectionCard(label, text) }
    }
}
