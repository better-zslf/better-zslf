package io.github.betterzslf.feature.runui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Single owner of the old primitive disclosure policy: entry order, four denies, nonblank, 400 chars. */
internal fun runDisplayFields(raw: JsonObject): List<RunDisplayField> = raw.entries.mapNotNull { (key, value) ->
    if (listOf("password", "token", "authorization", "cookie").any { key.contains(it, ignoreCase = true) }) null
    else (value as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { RunDisplayField(key, it.take(400)) }
}

internal fun RawRunTaskItem.toPublic(localKey: String, includeListMinimum: Boolean = true): RunTaskItem = RunTaskItem(
    trid, scheduleId, title, startText, endText, isFinish, localKey, runDisplayFields(raw), warnings, errandId,
    errandFieldPresent = "errandId" in raw,
    scheduleFieldsPresent = listOf("errandScheduleId", "errandSchedule", "scheduleId").any { it in raw },
    // Preserve the former task-list's detail-recognition path and its deliberately unpublished warnings.
    minimumDistanceMeters = if (includeListMinimum) RunDataDecoder.detail(raw)?.minimumDistanceMeters else null,
)

internal fun RawRunTaskDetail.toPublic(task: RunTaskItem): RunTaskDetail = RunTaskDetail(
    task, checkpoints, walkPaths, minimumDistanceMeters, warnings, checkpointNames, checkpointSourceCount,
)

internal fun RawRunRecordItem.toPublic(localKey: String): RunRecordItem = RunRecordItem(
    trid, title, distanceMeters, elapsedMillis, status, points, runDisplayFields(raw), warnings, localKey,
)
