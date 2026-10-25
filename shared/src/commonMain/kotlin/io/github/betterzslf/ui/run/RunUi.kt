package io.github.betterzslf.ui.run

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.RunRepository
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.feature.runui.RunDisplay
import io.github.betterzslf.platform.*
import io.github.betterzslf.ui.ConfirmationCheckRow
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
        if (bound == null) value = RunPageQuery(data = previous, error = AppText.Run.LOGIN_TO_VIEW_CURRENT_ACCOUNT_RUN_DATA_HINT)
        else try {
            value = when (val response = loader(bound)) {
                is ApiResult.Success -> RunPageQuery(data = response.data, message = response.message.takeIf { it.isNotBlank() })
                is ApiResult.Failure -> RunPageQuery(data = previous, error = response.error.message)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { value = RunPageQuery(data = previous, error = AppText.Run.READ_FAILURE_MANUAL_REFRESH_NO_WRITE_RETRY_HINT) }
    }
    return result
}

internal fun missingRunId() = ApiResult.Failure(ApiError.Validation(listOf("trid"), AppText.Run.RECORD_ID_MISSING_RESELECT_HINT))
internal fun missingRunSelection() = ApiResult.Failure(ApiError.Validation(listOf("errandId", "errandScheduleId"), AppText.Run.INCOMPLETE_TASK_RESELECT_HINT))

internal fun LazyListScope.runFeedback(query: RunPageQuery, retry: () -> Unit, keyPrefix: String = "read") {
    if (query.loading) item("$keyPrefix:loading") { Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
    query.error?.let { error -> item("$keyPrefix:error") { SectionCard(if (query.data == null) AppText.Common.READ_FAILED else AppText.Common.REFRESH_FAILED_PREVIOUS_RESULTS_RETAINED, error + AppText.Common.TAP_TO_QUERY_AGAIN_NEWLINE_SUFFIX, retry) } }
    query.message?.let { text -> item("$keyPrefix:message") { SectionCard(AppText.Common.SERVER_NOTICE_TITLE, text) } }
}

internal fun LazyListScope.runWarnings(warnings: List<String>, keyPrefix: String = "warning") {
    itemsIndexed(warnings.distinct(), key = { index, _ -> "$keyPrefix:$index" }) { _, warning -> SectionCard(AppText.Common.INFORMATION_AND_LIMITS_TITLE, warning) }
}

@Composable
internal fun RunCheck(checked: Boolean, text: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ConfirmationCheckRow(checked = checked, label = text, enabled = enabled, onCheckedChange = onChange)
}

@Composable
internal fun RunMetrics(session: RunSessionState, metric: Boolean) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
        Text(AppText.Run.realRecordTitle((runPhase(session.phase))), style = MiuixTheme.textStyles.title4)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { Text(AppText.Run.DISTANCE_LABEL); Text(RunDisplay.distance(session.distanceMeters, metric), style = MiuixTheme.textStyles.title4) }
            Column(Modifier.weight(1f)) { Text(AppText.Run.SAMPLING_DURATION_LABEL); Text(RunDisplay.duration(session.elapsedMillis), style = MiuixTheme.textStyles.title4) }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) { Text(AppText.Run.AVERAGE_PACE_LABEL); Text(RunDisplay.pace(session.distanceMeters, session.elapsedMillis, metric)) }
            Column(Modifier.weight(1f)) { Text(AppText.Run.DISTANCE_TO_NEXT_POINT_LABEL); Text(RunDisplay.distance(session.distanceToNextCheckpointMeters, metric)) }
        }
        Spacer(Modifier.height(12.dp))
        Text(AppText.Run.capturedPointCounts((session.acceptedSampleCount), (session.rejectedSampleCount)), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

internal fun runPhase(phase: RunSessionPhase): String = when (phase) {
    RunSessionPhase.IDLE -> AppText.Run.RECORD_NOT_STARTED
    RunSessionPhase.RECORDING -> AppText.Run.LOCATION_RECORDING_ACTIVE
    RunSessionPhase.PAUSED -> AppText.Run.SAMPLING_PAUSED
    RunSessionPhase.RECOVERED -> AppText.Run.LOCAL_RECOVERY_PREVIEW_LOCATION_OFF
    RunSessionPhase.FINISHING -> AppText.Run.END_STATE_SAVE_PENDING
    RunSessionPhase.FINISHED -> AppText.Run.LOCAL_RECORD_ENDED
    RunSessionPhase.CANCELLED -> AppText.Run.RECORD_CANCELLED_ORIGINAL_POINTS_RETAINED
    RunSessionPhase.OWNER_MISMATCH -> AppText.Run.ACCOUNT_OWNERSHIP_MISMATCH
    RunSessionPhase.CLOSED -> AppText.Common.CLOSED
}

internal fun runUploadPhase(phase: RunSessionUploadPhase): String = when (phase) {
    RunSessionUploadPhase.NONE -> AppText.Run.NO_UPLOAD_BATCHES
    RunSessionUploadPhase.INSUFFICIENT_SAMPLES -> AppText.Run.ZERO_OR_SINGLE_POINT_LOCAL_ONLY
    RunSessionUploadPhase.STAGED -> AppText.Run.UPLOAD_AWAITING_CONFIRMATION_NOT_SENT
    RunSessionUploadPhase.CONFIRMED -> AppText.Run.UPLOAD_PREPARED_FINAL_CONFIRMATION_REQUIRED
    RunSessionUploadPhase.IN_FLIGHT -> AppText.Run.UPLOAD_AWAITING_RECEIPT
    RunSessionUploadPhase.ACKNOWLEDGED -> AppText.Run.BATCHES_ACCEPTED_SCORE_QUERY_REQUIRED
    RunSessionUploadPhase.FAILED -> AppText.Run.UPLOAD_FAILED_NO_AUTO_RETRY
    RunSessionUploadPhase.UNCERTAIN -> AppText.Run.UPLOAD_UNCERTAIN_NO_AUTO_RESEND
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
    // Same proven owner scope as RunFeaturePage; credentials are only in-memory remember keys.
    val owner = remember(graph, session?.userId, session?.token) { Any() }
    // Verified against the original client by Lead; unknown values have no guessed/key fallback.
    val provider = runMapProviderFor(session?.mapWebType)
    val apiKey = session?.mapToken
    val identity = remember(owner, provider, apiKey) { RunMapFeedbackIdentity(owner, provider, apiKey) }
    if (useful.isEmpty() && checkpoints.isEmpty()) {
        SectionCard(AppText.Run.NO_DISPLAYABLE_COORDINATES, AppText.Run.NO_REAL_POSITION_OR_ROUTE_NO_SIMULATION_HINT)
    } else key(identity) {
        // Feedback and the native panel share this configuration/mount lifetime, not the payload key.
        val document = remember { Any() }
        val data = RunMapFeedbackData(paths, checkpoints, coordinateSystem, role)
        val latestData by rememberUpdatedState(data)
        var feedback by remember { mutableStateOf(RunMapFeedbackState.initial(identity, document, data)) }
        val visibleFeedback = feedback.forDocument(identity, document, data)
        // Read the latest state here so an event delivered during apply is never overwritten.
        SideEffect { feedback = feedback.forDocument(identity, document, latestData) }
        var selected by remember(paths) { mutableIntStateOf(0) }
        val path = useful.getOrNull(selected.coerceIn(0, (useful.size - 1).coerceAtLeast(0)))
        val line = RunMapPath(coordinateSystem = coordinateSystem, segments = useful.map { it.second })
        val dark = when (settings.theme) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
        val model = RunMapModel(provider = provider, apiKey = apiKey,
            actualPath = if (role == RunMapRole.ACTUAL) line else RunMapPath(),
            recommendedPath = if (role == RunMapRole.RECOMMENDED) line else RunMapPath(),
            importedPath = if (role == RunMapRole.IMPORTED) line else RunMapPath(),
            checkpoints = checkpoints.mapIndexed { index, point -> RunMapCheckpoint(point.id, point.point, AppText.Run.checkpointOrdinal((index + 1)), RunMapCoordinateSystem.GCJ02) },
            currentLocation = if (role == RunMapRole.ACTUAL) path?.second?.lastOrNull() else null,
            currentCoordinateSystem = coordinateSystem, dark = dark, editMode = false)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            RunMapPanel(model, { event ->
                feedback = feedback.forDocument(identity, document, latestData).onEvent(identity, document, event)
            }, Modifier.fillMaxWidth().height(280.dp))
            if (useful.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(AppText.Run.PREVIOUS_ROUTE_SEGMENT, enabled = selected > 0, onClick = { selected-- })
                Text(AppText.Run.segmentSelection((selected + 1), (useful.size)))
                TextButton(AppText.Run.NEXT_ROUTE_SEGMENT, enabled = selected + 1 < useful.size, onClick = { selected++ })
            }
            visibleFeedback.text()?.let { Text(it) }
            SectionCard(AppText.Run.coordinatePreviewFrame((coordinateSystem).toString()), (path?.let { (name, points) ->
                AppText.Run.pathCoordinateSummary((name), (points.size), (coordinate(points.first())), (coordinate(points.last())))
            } ?: AppText.Run.NO_AVAILABLE_PATH) + AppText.Run.checkpointPreviewFooter((checkpoints.size)))
        }
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
