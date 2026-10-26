package io.github.betterzslf.ui.run

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.edition.EditionAccess
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
        permissionMessage = if (granted) AppText.Run.PRECISE_LOCATION_ALLOWED_EXPLICIT_START_REQUIRED else AppText.Run.PRECISE_LOCATION_NOT_ALLOWED_HINT
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

    Screen(page.title.ifBlank { AppText.Run.REAL_RUN_RECORD_TITLE }, back, actions = { TextButton(AppText.Run.LOCAL_RECORD_TITLE, enabled = !busy, onClick = { scope.launch { runtime.loadLocalSessions() } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("native-only") { SectionCard(AppText.Run.REAL_LOCATION_ORIGINAL_COORDINATES_TITLE, AppText.Run.NATIVE_SAMPLES_ONLY_APP_LEVEL_RECORDING_HINT) }
            if (model.requiresRestore) item("restore-required") { SectionCard(AppText.Run.EXPLICIT_LOCAL_RECORD_SELECTION_REQUIRED, AppText.Run.SESSION_CHANGED_NO_AUTOMATIC_RECORD_OR_SEND_HINT) }
            model.message?.let { message -> item("runtime-message") { SectionCard(AppText.Run.LOCAL_OPERATION_AND_QUEUE_STATUS_TITLE, message) } }
            if (currentSession?.userId.isNullOrBlank()) item("login") { SectionCard(AppText.Auth.LOGIN_REQUIRED, AppText.Run.CURRENT_ACCOUNT_RECORD_ISOLATION_HINT) }
            item("metrics") { RunMetrics(local, settings.metricUnits) }
            item("native-state") { SectionCard(AppText.Run.LOCATION_SERVICE_TITLE, AppText.Run.nativeLocationSummary((local.nativeLocation.phase).toString(), (local.nativeLocation.message.orEmpty()), (settings.samplingSeconds))) }
            permissionMessage?.let { message -> item("permission-result") { SectionCard(AppText.Run.SYSTEM_LOCATION_PERMISSION_TITLE, message) } }
            item("permission") { TextButton(AppText.Run.CHECK_OR_REQUEST_PRECISE_LOCATION, enabled = !busy, onClick = permission::launch, modifier = Modifier.fillMaxWidth()) }
            if (local.metadata == null || local.phase in listOf(RunSessionPhase.FINISHED, RunSessionPhase.CANCELLED)) {
                runFeedback(query, { refresh++ })
                if (detail != null) {
                    item("task") { SectionCard(AppText.Run.pendingTask((detail.task.title)), AppText.Run.realTaskIdentity((detail.task.errandId ?: AppText.Common.NOT_RETURNED), (detail.task.scheduleId ?: AppText.Common.NOT_RETURNED))) }
                    runWarnings(detail.warnings)
                    item("start") { TextButton(AppText.Run.START_REAL_RECORDING, enabled = !busy && permissionGranted && detail.canStart && !currentSession?.userId.isNullOrBlank(), onClick = { action = "start" }, modifier = Modifier.fillMaxWidth()) }
                    if (!detail.canStart) item("start-blocked") { SectionCard(AppText.Run.TASK_MISSING_START_FIELDS, AppText.Run.realTaskMissingFields((detail.missingFields.joinToString()))) }
                } else item("choose-task") { SectionCard(AppText.Run.SELECT_TASK_FIRST, AppText.Run.NEW_RECORD_REQUIRES_TASK_DETAIL_HINT, { open(Route.Page("runs", title = AppText.Navigation.CAMPUS_RUN)) }) }
            }
            if (local.metadata != null) {
                item("selected") { SectionCard(AppText.Run.CURRENT_LOCAL_RECORD_TITLE, "trid：${local.metadata.trid}\n${runPhase(local.phase)}\n${runUploadPhase(local.uploadPhase)}") }
                item("native-map") { RunMapPreview(graph, listOf(AppText.Run.NATIVE_MEASURED_TRACK_TITLE to local.acceptedSamples.map { it.point }), coordinateSystem = io.github.betterzslf.platform.RunMapCoordinateSystem.WGS84, role = RunMapRole.ACTUAL) }
                if (isCapturing) item("pause") { TextButton(AppText.Run.PAUSE_SAMPLING, enabled = !busy, onClick = { action = "pause" }, modifier = Modifier.fillMaxWidth()) }
                if (canResume) item("resume") { TextButton(AppText.Run.RESUME_SAMPLING_EXPLICITLY, enabled = !busy && permissionGranted, onClick = { action = "resume" }, modifier = Modifier.fillMaxWidth()) }
                if (canEnd) item("end-actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TextButton(if (local.phase == RunSessionPhase.FINISHING) AppText.Run.COMPLETE_LOCAL_END_SAVE else AppText.Run.END_AND_KEEP_TRACK, enabled = !busy, onClick = { action = "finish" }, modifier = Modifier.weight(1f))
                        if (local.phase != RunSessionPhase.FINISHING) TextButton(AppText.Run.CANCEL_RECORDING, enabled = !busy, onClick = { action = "cancel" }, modifier = Modifier.weight(1f))
                    }
                }
                if (local.hasUnpersistedSample) item("retry-disk") { SectionCard(AppText.Run.REAL_POINT_SAVE_PENDING, AppText.Run.RETRY_PERSISTENCE_WITHOUT_FABRICATING_POINTS_HINT, { scope.launch { runtime.retryPersistence() } }) }
                if (local.acceptedSampleCount <= 1 && local.phase == RunSessionPhase.FINISHED) item("short") { SectionCard(AppText.Run.ZERO_OR_SINGLE_POINT_RECORD_RETAINED, AppText.Run.LOCAL_ONLY_WITHOUT_FABRICATED_FINISH_POINT_HINT) }
                item("queue-refresh") { TextButton(AppText.Run.REFRESH_LOCAL_UPLOAD_QUEUE, enabled = !busy, onClick = { scope.launch { runtime.refreshQueue() } }) }
                itemsIndexed(model.queue, key = { _, row -> "batch:${row.batch.batchId}" }) { _, row ->
                    SectionCard(AppText.Run.queueBatchTitle((row.batch.index + 1), (row.status).toString()), AppText.Run.queueBatchDetails((row.batch.batchId), (row.batch.payload.finishType), (row.attempts), (row.failures.lastOrNull()?.message.orEmpty())))
                }
                if (model.queue.any { it.status == RunBatchStatus.UNCERTAIN || it.status == RunBatchStatus.IN_FLIGHT }) item("uncertain") { SectionCard(AppText.Common.RESULT_NEEDS_REVIEW, AppText.Run.UNCERTAIN_REQUEST_NO_AUTOMATIC_RESEND_HINT) }
                if (local.phase == RunSessionPhase.FINISHED && pending.isNotEmpty()) item("upload") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(AppText.Run.PREVIEW_AND_CONFIRM_UPLOAD, enabled = !busy && model.upload != RunUploadUiPhase.SENDING && model.transportAvailable,
                            onClick = { uploadConfirmed = false; uploadPreview = pending.toList() }, modifier = Modifier.fillMaxWidth())
                        if (!model.transportAvailable) Text(AppText.Run.TRACK_SAVED_LOCALLY_UPLOAD_DISABLED_HINT)
                    }
                }
                if (model.upload == RunUploadUiPhase.SENDING) item("stop-upload") { TextButton(AppText.Run.STOP_FUTURE_SENDS, onClick = { scope.launch { runtime.stopUpload() } }) }
                if (local.finishRequested) item("server-record") { TextButton(AppText.Run.READ_ONLY_SERVER_SCORE_AND_TRACK_QUERY, onClick = { open(Route.Page("runrecord", local.metadata.trid, AppText.Run.SERVER_SCORE_TITLE)) }) }
                itemsIndexed(local.acceptedSamples.takeLast(sampleWindow).reversed(), key = { index, row -> "native:${row.timestampMillis}:$index" }) { index, sample ->
                    SectionCard(AppText.Run.realPointOrdinal((local.acceptedSampleCount - index)), AppText.Run.realPointDetails((coordinate(sample.point)), (sample.timestampMillis), (RunDisplay.decimal(sample.accuracyMeters)), (sample.speedMetersPerSecond?.let { AppText.Run.speedMetresPerSecond((RunDisplay.decimal(it))) } ?: AppText.Common.NOT_PROVIDED)))
                }
                if (sampleWindow < local.acceptedSampleCount) item("more-samples") { TextButton(AppText.Run.EXPAND_MORE_REAL_POINTS, onClick = { sampleWindow += 20 }) }
            }
            item("history-read") { TextButton(AppText.Run.LOAD_CURRENT_ACCOUNT_LOCAL_RECORDS, enabled = !busy, onClick = { scope.launch { runtime.loadLocalSessions() } }) }
            itemsIndexed(model.localSessions, key = { index, row -> "local:${row.metadata.trid}:$index" }) { _, row ->
                SectionCard(AppText.Run.localSessionTitle((row.metadata.trid)), AppText.Run.localSessionSummary((runPhase(row.phase)), (row.acceptedSampleCount)), { scope.launch { runtime.restore(row.metadata.trid) } })
            }
            if (selection != null && EditionAccess.routeEditorsEnabled) item("import-mode") { TextButton(AppText.Run.SWITCH_TO_ROUTE_IMPORT_PREVIEW_THIS_TIME, onClick = {
                if (EditionAccess.routeEditorsEnabled) open(Route.Page("runimport", selection.encode(), AppText.Run.ROUTE_IMPORT_PREVIEW_TITLE))
            }) }
        }
    }

    OverlayDialog(show = action != null, title = when (action) { "start" -> AppText.Run.START_REAL_RECORDING_CONFIRM_TITLE; "pause" -> AppText.Run.PAUSE_SAMPLING_CONFIRM_TITLE; "resume" -> AppText.Run.RESUME_REAL_RECORDING_CONFIRM_TITLE; "finish" -> AppText.Run.END_AND_SAVE_LOCAL_RECORD_CONFIRM_TITLE; else -> AppText.Run.CANCEL_RECORDING_CONFIRM_TITLE },
        summary = when (action) {
            "start" -> AppText.Run.beginCaptureNotice((settings.samplingSeconds))
            "pause" -> AppText.Run.PAUSE_PRESERVES_SAVED_POINTS_HINT
            "resume" -> AppText.Run.RESUME_WITHOUT_ALTERING_OLD_POINTS_HINT
            "finish" -> AppText.Run.END_RECORD_AND_KEEP_TRACK_HINT
            else -> AppText.Run.CANCEL_RECORD_KEEP_POINTS_AND_UNCERTAIN_RESULTS_HINT
        }, onDismissRequest = { action = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (action == "start" || action == "resume") RunCheck(captureConfirmed, AppText.Run.REAL_TRACK_UPLOAD_PURPOSE_ACKNOWLEDGEMENT) { captureConfirmed = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(AppText.Common.BACK, onClick = { action = null }, modifier = Modifier.weight(1f))
                TextButton(AppText.Common.CONFIRM, enabled = !busy && (!(action == "start" || action == "resume") || captureConfirmed && permissionGranted), onClick = {
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
    OverlayDialog(show = preview != null, title = AppText.Run.UPLOAD_REAL_TRACK_FINAL_CONFIRM_TITLE, summary = AppText.Run.EXACT_BATCH_REVIEW_AND_CONFIRMATION_INVALIDATION_HINT, onDismissRequest = { uploadPreview = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(preview.orEmpty(), key = { _, row -> row.batch.batchId }) { _, row ->
                    Text(AppText.Run.uploadBatchPreview((row.batch.batchId), (row.batch.payload.userId), (row.batch.payload.finishType), (row.status).toString()))
                }
            }
            RunCheck(uploadConfirmed, AppText.Run.SEND_REAL_TRACK_BATCHES_ACKNOWLEDGEMENT) { uploadConfirmed = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(AppText.Run.KEEP_LOCALLY, onClick = { uploadPreview = null }, modifier = Modifier.weight(1f))
                TextButton(AppText.Run.SEND_NOW, enabled = uploadConfirmed && model.transportAvailable && model.upload != RunUploadUiPhase.SENDING, onClick = {
                    val expected = preview.orEmpty().map { it.batch.batchId }
                    uploadPreview = null
                    scope.launch { runtime.upload(expected, true) }
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}
