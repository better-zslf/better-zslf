package io.github.betterzslf.ui.run

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.RunRepository
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.feature.runui.RunDisplay
import io.github.betterzslf.platform.*
import io.github.betterzslf.ui.*
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
    query.error?.let { error -> item("$keyPrefix:error") {
        RunCard {
            InfoBanner(error, title = if (query.data == null) AppText.Common.READ_FAILED else AppText.Common.REFRESH_FAILED_PREVIOUS_RESULTS_RETAINED, tone = UiTone.Warning)
            TextButton(AppText.Common.REFRESH, onClick = retry)
        }
    } }
    query.message?.let { text -> item("$keyPrefix:message") { InfoBanner(text, tone = UiTone.Neutral) } }
}

internal fun LazyListScope.runWarnings(warnings: List<String>, keyPrefix: String = "warning") {
    if (warnings.isNotEmpty()) item("$keyPrefix:details") {
        RunDisclosure(AppText.Common.INFORMATION_AND_LIMITS_TITLE) {
            warnings.distinct().forEach { warning -> InfoBanner(warning, tone = UiTone.Neutral) }
        }
    }
}

@Composable
internal fun RunCheck(checked: Boolean, text: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ConfirmationCheckRow(checked = checked, label = text, enabled = enabled, onCheckedChange = onChange)
}

@Composable
internal fun RunMetrics(session: RunSessionState, metric: Boolean) {
    MetricStrip(listOf(
        UiMetric(RunDisplay.distance(session.distanceMeters, metric), AppText.Run.DISTANCE_LABEL),
        UiMetric(RunDisplay.pace(session.distanceMeters, session.elapsedMillis, metric), AppText.Run.AVERAGE_PACE_LABEL),
        UiMetric(session.acceptedSampleCount.toString(), AppText.Run.SAVED_POINTS_LABEL),
    ))
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
    height: Dp = 260.dp,
    modifier: Modifier = Modifier,
    showDetails: Boolean = false,
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
        Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(AppUiTokens.CardRadius)), contentAlignment = Alignment.Center) {
            RunEmpty(AppText.Run.NO_DISPLAYABLE_COORDINATES, AppText.Run.NO_REAL_POSITION_OR_ROUTE_NO_SIMULATION_HINT)
        }
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
        var checkpointWindow by remember(checkpoints) { mutableIntStateOf(20) }
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
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(AppUiTokens.CardRadius))) {
                RunMapPanel(model, { event ->
                    feedback = feedback.forDocument(identity, document, latestData).onEvent(identity, document, event)
                }, Modifier.fillMaxSize())
                if (visibleFeedback.status is RunMapDocumentFeedback.Failed || visibleFeedback.transient != null) {
                    visibleFeedback.text()?.let { InfoBanner(it,
                        tone = if (visibleFeedback.status is RunMapDocumentFeedback.Failed) UiTone.Warning else UiTone.Neutral,
                        modifier = Modifier.align(Alignment.Center).padding(12.dp)) }
                }
            }
            if (showDetails || visibleFeedback.status is RunMapDocumentFeedback.Failed) RunDisclosure(AppText.Run.coordinatePreviewFrame(coordinateSystem.toString())) {
                if (useful.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(AppText.Run.PREVIOUS_ROUTE_SEGMENT, enabled = selected > 0, onClick = { selected-- })
                    Text(AppText.Run.segmentSelection(selected + 1, useful.size))
                    TextButton(AppText.Run.NEXT_ROUTE_SEGMENT, enabled = selected + 1 < useful.size, onClick = { selected++ })
                }
                visibleFeedback.text()?.let { Text(it, fontSize = 13.sp) }
                Text((path?.let { (name, points) ->
                    AppText.Run.pathCoordinateSummary(name, points.size, coordinate(points.first()), coordinate(points.last()))
                } ?: AppText.Run.NO_AVAILABLE_PATH) + AppText.Run.checkpointPreviewFooter(checkpoints.size), fontSize = 12.sp)
                checkpoints.take(checkpointWindow).forEachIndexed { index, point ->
                    Text("${AppText.Run.checkpointOrdinal(index + 1)} · ${coordinate(point.point)}", fontSize = 12.sp)
                }
                if (checkpointWindow < checkpoints.size) TextButton(AppText.Run.EXPAND_MORE_ORIGINAL_COORDINATES, onClick = { checkpointWindow += 20 })
            }
        }
    }
}

internal fun coordinate(point: GeoPoint): String = "${point.longitude},${point.latitude}"

internal fun runFields(raw: JsonObject): List<Pair<String, String>> = raw.entries.mapNotNull { (key, value) ->
    if (listOf("password", "token", "authorization", "cookie").any { key.contains(it, ignoreCase = true) }) null
    else (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { key to it.take(400) }
}

internal fun LazyListScope.runRawFields(raw: JsonObject, prefix: String = "raw") {
    val fields = runFields(raw)
    if (fields.isNotEmpty()) item("$prefix:fields") { RunDisclosure(AppText.Common.INFORMATION_AND_LIMITS_TITLE) {
        fields.forEach { (label, value) ->
            Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Text(value, fontSize = 14.sp)
        }
    } }
}
