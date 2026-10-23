package io.github.betterzslf.ui.run

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.edition.EditionAccess
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.feature.runui.*
import io.github.betterzslf.platform.RunMapCoordinateSystem
import io.github.betterzslf.platform.rememberLocationPermissionRequester
import io.github.betterzslf.ui.*
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Navigation changes presentation only; the app-level native runtime keeps its existing ownership. */
@Composable
internal fun RunTrackerPage(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit, runtime: RunRuntime) {
    val currentSession by graph.sessions.session.collectAsState()
    val runtimeState by runtime.state.collectAsState()
    val settings by graph.settings.settings.collectAsState()
    val model = if (runtime.isCurrentSession() && runtimeState.ownerId == currentSession?.userId) runtimeState else RunRuntimeState(ownerId = currentSession?.userId, requiresRestore = true)
    val local = model.session
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    // A newly navigated task must not relabel an already active local journal.
    val activeTask = model.taskSelection.takeIf { local.metadata != null && local.phase !in listOf(RunSessionPhase.FINISHED, RunSessionPhase.CANCELLED) }
    val selection = activeTask ?: RunTaskSelection.decode(page.id) ?: model.taskSelection
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
    var showDetails by remember { mutableStateOf(false) }
    val busy = model.busy || model.closed
    val isCapturing = local.phase == RunSessionPhase.RECORDING
    val canResume = local.phase in listOf(RunSessionPhase.PAUSED, RunSessionPhase.RECOVERED) && !local.finishRequested
    val canEnd = local.metadata != null && local.phase in listOf(RunSessionPhase.RECORDING, RunSessionPhase.PAUSED, RunSessionPhase.RECOVERED, RunSessionPhase.FINISHING)
    val mayStart = local.metadata == null || local.phase in listOf(RunSessionPhase.FINISHED, RunSessionPhase.CANCELLED)
    val pending = model.queue.filter { it.status != RunBatchStatus.ACKNOWLEDGED }

    Screen(AppText.Run.REAL_RUN_RECORD_TITLE, back, actions = {
        TextButton(AppText.Run.LOCAL_RECORD_TITLE, enabled = !busy, onClick = { showDetails = true; scope.launch { runtime.loadLocalSessions() } })
    }) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            // Native WGS path and server GCJ checkpoints keep their independently declared frames.
            RunMapPreview(graph, listOf(AppText.Run.NATIVE_MEASURED_TRACK_TITLE to local.acceptedSamples.map { it.point }),
                checkpoints = detail?.takeIf {
                    it.task.errandId == selection?.errandId && it.task.scheduleId == selection?.errandScheduleId &&
                        (local.metadata == null || model.taskSelection == selection)
                }?.checkpoints.orEmpty(), coordinateSystem = RunMapCoordinateSystem.WGS84,
                role = RunMapRole.ACTUAL, height = maxHeight, modifier = Modifier.fillMaxSize())
            val compact = maxHeight < 560.dp || LocalDensity.current.fontScale > 1.25f
            val controlsScroll = rememberScrollState()
            Column(Modifier.fillMaxSize().then(if (compact) Modifier.verticalScroll(controlsScroll) else Modifier).padding(AppUiTokens.PagePadding),
                verticalArrangement = if (compact) Arrangement.spacedBy(AppUiTokens.SectionGap) else Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                    RunMetrics(local, settings.metricUnits)
                    model.message?.let { InfoBanner(it, tone = UiTone.Neutral) }
                    if (model.requiresRestore) InfoBanner(AppText.Run.SESSION_CHANGED_NO_AUTOMATIC_RECORD_OR_SEND_HINT, tone = UiTone.Warning)
                }
                RunCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        RunStatus(runPhase(local.phase), if (isCapturing) UiTone.Info else UiTone.Neutral)
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(RunDisplay.duration(local.elapsedMillis), fontSize = 42.sp, fontWeight = FontWeight.SemiBold)
                    Text(AppText.Run.RECORD_SPAN_INCLUDES_PAUSES_LABEL, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    if (!model.transportAvailable) {
                        Spacer(Modifier.height(8.dp))
                        Text(AppText.Run.TRACK_SAVED_LOCALLY_UPLOAD_DISABLED_HINT, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                    permissionMessage?.let { Text(it, fontSize = 12.sp) }
                    if (!permissionGranted && (mayStart || canResume)) TextButton(AppText.Run.CHECK_OR_REQUEST_PRECISE_LOCATION, enabled = !busy,
                        onClick = permission::launch, modifier = Modifier.fillMaxWidth())
                    if (mayStart) {
                        Spacer(Modifier.height(10.dp))
                        if (detail != null) RunAction(AppText.Run.START_REAL_RECORDING,
                            enabled = !busy && permissionGranted && detail.canStart && !currentSession?.userId.isNullOrBlank(), primary = true,
                            onClick = { action = "start" }, modifier = Modifier.fillMaxWidth())
                        else RunAction(AppText.Run.SELECT_FROM_MY_TASKS, primary = true, enabled = !busy,
                            onClick = { open(Route.Page("runs", title = AppText.Navigation.CAMPUS_RUN)) }, modifier = Modifier.fillMaxWidth())
                        if (query.loading) Text(AppText.Run.TASK_DETAIL_TITLE, fontSize = 12.sp)
                        if (detail != null && !detail.canStart) InfoBanner(AppText.Run.realTaskMissingFields(detail.missingFields.joinToString()), tone = UiTone.Warning)
                    }
                    if (isCapturing || canResume || canEnd) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                            if (isCapturing) RunAction(AppText.Run.PAUSE_SAMPLING, enabled = !busy, modifier = Modifier.weight(1f), onClick = { action = "pause" })
                            if (canResume) RunAction(AppText.Run.RESUME_SAMPLING_EXPLICITLY, enabled = !busy && permissionGranted, primary = true,
                                modifier = Modifier.weight(1f), onClick = { action = "resume" })
                            if (canEnd) RunAction(if (local.phase == RunSessionPhase.FINISHING) AppText.Run.COMPLETE_LOCAL_END_SAVE else AppText.Run.END_AND_KEEP_TRACK,
                                enabled = !busy, primary = isCapturing, modifier = Modifier.weight(1f), onClick = { action = "finish" })
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(AppText.Run.LOCAL_RECORD_TITLE, onClick = { showDetails = true })
                        if (canEnd && local.phase != RunSessionPhase.FINISHING) TextButton(AppText.Run.CANCEL_RECORDING, enabled = !busy, onClick = { action = "cancel" })
                    }
                }
            }
        }
    }

    // Recovery, point journals and queues remain reachable without obscuring the recording map.
    OverlayBottomSheet(show = showDetails, title = AppText.Run.LOCAL_RECORD_TITLE, onDismissRequest = { showDetails = false }) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (currentSession?.userId.isNullOrBlank()) item("login") { InfoBanner(AppText.Run.CURRENT_ACCOUNT_RECORD_ISOLATION_HINT, AppText.Auth.LOGIN_REQUIRED, UiTone.Warning) }
            runFeedback(query, { refresh++ })
            item("source") { InfoBanner(AppText.Run.NATIVE_SAMPLES_ONLY_APP_LEVEL_RECORDING_HINT, tone = UiTone.Neutral) }
            item("native-state") { Text(AppText.Run.nativeLocationSummary(local.nativeLocation.phase.toString(), local.nativeLocation.message.orEmpty(), settings.samplingSeconds), fontSize = 13.sp) }
            item("permission") { TextButton(AppText.Run.CHECK_OR_REQUEST_PRECISE_LOCATION, enabled = !busy, onClick = permission::launch) }
            detail?.let { task ->
                item("task") { Text(task.task.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                runWarnings(task.warnings, "task-warning")
            }
            local.metadata?.let { metadata ->
                item("journal") { RunDisclosure(AppText.Run.CURRENT_LOCAL_RECORD_TITLE) {
                    Text(metadata.trid, fontSize = 12.sp)
                    Text(runUploadPhase(local.uploadPhase), fontSize = 13.sp)
                    Text(AppText.Run.capturedPointCounts(local.acceptedSampleCount, local.rejectedSampleCount), fontSize = 13.sp)
                    Text("${AppText.Run.DISTANCE_TO_NEXT_POINT_LABEL} · ${RunDisplay.distance(local.distanceToNextCheckpointMeters, settings.metricUnits)}", fontSize = 13.sp)
                } }
                if (local.hasUnpersistedSample) item("retry-disk") { SectionCard(AppText.Run.REAL_POINT_SAVE_PENDING, AppText.Run.RETRY_PERSISTENCE_WITHOUT_FABRICATING_POINTS_HINT, { scope.launch { runtime.retryPersistence() } }) }
                if (local.acceptedSampleCount <= 1 && local.phase == RunSessionPhase.FINISHED) item("short") { InfoBanner(AppText.Run.LOCAL_ONLY_WITHOUT_FABRICATED_FINISH_POINT_HINT, AppText.Run.ZERO_OR_SINGLE_POINT_RECORD_RETAINED, UiTone.Neutral) }
                item("queue-refresh") { TextButton(AppText.Run.REFRESH_LOCAL_UPLOAD_QUEUE, enabled = !busy, onClick = { scope.launch { runtime.refreshQueue() } }) }
                itemsIndexed(model.queue, key = { _, row -> "batch:${row.batch.batchId}" }) { _, row ->
                    RunDisclosure(AppText.Run.queueBatchTitle(row.batch.index + 1, row.status.toString())) {
                        Text(AppText.Run.queueBatchDetails(row.batch.batchId, row.batch.payload.finishType, row.attempts, row.failures.lastOrNull()?.message.orEmpty()), fontSize = 12.sp)
                    }
                }
                if (model.queue.any { it.status == RunBatchStatus.UNCERTAIN || it.status == RunBatchStatus.IN_FLIGHT }) item("uncertain") { InfoBanner(AppText.Run.UNCERTAIN_REQUEST_NO_AUTOMATIC_RESEND_HINT, AppText.Common.RESULT_NEEDS_REVIEW, UiTone.Warning) }
                if (local.phase == RunSessionPhase.FINISHED && pending.isNotEmpty()) item("upload") {
                    RunAction(AppText.Run.PREVIEW_AND_CONFIRM_UPLOAD, enabled = !busy && model.upload != RunUploadUiPhase.SENDING && model.transportAvailable,
                        onClick = { showDetails = false; uploadConfirmed = false; uploadPreview = pending.toList() }, modifier = Modifier.fillMaxWidth())
                }
                if (model.upload == RunUploadUiPhase.SENDING) item("stop-upload") { TextButton(AppText.Run.STOP_FUTURE_SENDS, onClick = { scope.launch { runtime.stopUpload() } }) }
                if (local.finishRequested) item("server-record") { TextButton(AppText.Run.READ_ONLY_SERVER_SCORE_AND_TRACK_QUERY, onClick = {
                    showDetails = false; open(Route.Page("runrecord", metadata.trid, AppText.Run.SERVER_SCORE_TITLE))
                }) }
                item("samples-title") { SectionTitle(AppText.Run.NATIVE_MEASURED_TRACK_TITLE) }
                itemsIndexed(local.acceptedSamples.takeLast(sampleWindow).reversed(), key = { index, row -> "native:${row.timestampMillis}:$index" }) { index, sample ->
                    RunDisclosure(AppText.Run.realPointOrdinal(local.acceptedSampleCount - index)) {
                        Text(AppText.Run.realPointDetails(coordinate(sample.point), sample.timestampMillis, RunDisplay.decimal(sample.accuracyMeters),
                            sample.speedMetersPerSecond?.let { AppText.Run.speedMetresPerSecond(RunDisplay.decimal(it)) } ?: AppText.Common.NOT_PROVIDED), fontSize = 12.sp)
                    }
                }
                if (sampleWindow < local.acceptedSampleCount) item("more-samples") { TextButton(AppText.Run.EXPAND_MORE_REAL_POINTS, onClick = { sampleWindow += 20 }) }
            }
            item("history-read") { RunAction(AppText.Run.LOAD_CURRENT_ACCOUNT_LOCAL_RECORDS, enabled = !busy, onClick = { scope.launch { runtime.loadLocalSessions() } }, modifier = Modifier.fillMaxWidth()) }
            itemsIndexed(model.localSessions, key = { index, row -> "local:${row.metadata.trid}:$index" }) { _, row ->
                SectionCard(AppText.Run.localSessionTitle(row.metadata.trid), AppText.Run.localSessionSummary(runPhase(row.phase), row.acceptedSampleCount), { scope.launch { runtime.restore(row.metadata.trid) } })
            }
            if (selection != null && EditionAccess.routeEditorsEnabled) item("import-mode") { TextButton(AppText.Run.SWITCH_TO_ROUTE_IMPORT_PREVIEW_THIS_TIME, onClick = {
                if (EditionAccess.routeEditorsEnabled) { showDetails = false; open(Route.Page("runimport", selection.encode(), AppText.Run.ROUTE_IMPORT_PREVIEW_TITLE)) }
            }) }
        }
    }

    OverlayDialog(show = action != null, title = when (action) { "start" -> AppText.Run.START_REAL_RECORDING_CONFIRM_TITLE; "pause" -> AppText.Run.PAUSE_SAMPLING_CONFIRM_TITLE; "resume" -> AppText.Run.RESUME_REAL_RECORDING_CONFIRM_TITLE; "finish" -> AppText.Run.END_AND_SAVE_LOCAL_RECORD_CONFIRM_TITLE; else -> AppText.Run.CANCEL_RECORDING_CONFIRM_TITLE },
        summary = when (action) {
            "start" -> AppText.Run.beginCaptureNotice(settings.samplingSeconds)
            "pause" -> AppText.Run.PAUSE_PRESERVES_SAVED_POINTS_HINT
            "resume" -> AppText.Run.RESUME_WITHOUT_ALTERING_OLD_POINTS_HINT
            "finish" -> AppText.Run.END_RECORD_AND_KEEP_TRACK_HINT
            else -> AppText.Run.CANCEL_RECORD_KEEP_POINTS_AND_UNCERTAIN_RESULTS_HINT
        }, onDismissRequest = { action = null }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (action == "start" || action == "resume") RunCheck(captureConfirmed, AppText.Run.REAL_TRACK_UPLOAD_PURPOSE_ACKNOWLEDGEMENT) { captureConfirmed = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RunAction(AppText.Common.BACK, onClick = { action = null }, modifier = Modifier.weight(1f))
                RunAction(AppText.Common.CONFIRM, enabled = !busy && (!(action == "start" || action == "resume") || captureConfirmed && permissionGranted), primary = true, onClick = {
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
                itemsIndexed(preview.orEmpty(), key = { _, row -> row.batch.batchId }) { _, row -> Text(AppText.Run.uploadBatchPreview(row.batch.batchId, row.batch.payload.userId, row.batch.payload.finishType, row.status.toString())) }
            }
            RunCheck(uploadConfirmed, AppText.Run.SEND_REAL_TRACK_BATCHES_ACKNOWLEDGEMENT) { uploadConfirmed = it }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RunAction(AppText.Run.KEEP_LOCALLY, onClick = { uploadPreview = null }, modifier = Modifier.weight(1f))
                RunAction(AppText.Run.SEND_NOW, enabled = uploadConfirmed && model.transportAvailable && model.upload != RunUploadUiPhase.SENDING, primary = true, onClick = {
                    val expected = preview.orEmpty().map { it.batch.batchId }
                    uploadPreview = null
                    scope.launch { runtime.upload(expected, true) }
                }, modifier = Modifier.weight(1f))
            }
        }
    }
}
