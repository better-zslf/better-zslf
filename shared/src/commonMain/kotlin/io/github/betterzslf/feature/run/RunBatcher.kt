package io.github.betterzslf.feature.run

import io.github.betterzslf.core.text.AppText
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class RunMetadata(val trid: String, val scheduleId: String, val userId: String, val userName: String = "", val imgUrl: String = "") {
    init { require(trid.isNotBlank() && scheduleId.isNotBlank() && userId.isNotBlank()) { AppText.Run.TASK_SCHEDULE_AND_USER_IDS_REQUIRED } }
}

@Serializable
data class RunCoordinate(val location: String, val locatetime: Long, val dis: Double, val count: Double)

/** Wire units: runLength metres, runTime milliseconds, speed metres/second, count seconds. */
@Serializable
data class RunUploadPayload(
    val scheduleId: String,
    val userId: String,
    val userName: String,
    val runLength: String,
    val runTime: Long,
    val startPoint: String = "",
    val endPoint: String = "",
    val imgUrl: String = "",
    val startTime: Long,
    val endTime: Long,
    val trid: String,
    val sid: String = "",
    val tid: String = "",
    val speed: String,
    val finishType: String,
    val coordinate: String,
    val batchId: String,
) {
    fun toJsonObject(): JsonObject = buildJsonObject {
        put("scheduleId", scheduleId); put("userId", userId); put("userName", userName)
        put("runLength", runLength); put("runTime", runTime)
        put("startPoint", startPoint); put("endPoint", endPoint); put("imgUrl", imgUrl)
        put("startTime", startTime); put("endTime", endTime); put("trid", trid)
        put("sid", sid); put("tid", tid); put("speed", speed)
        put("finishType", finishType); put("coordinate", coordinate); put("batchId", batchId)
    }
}

@Serializable
data class RunUploadBatch(val index: Int, val payload: RunUploadPayload) {
    val batchId: String get() = payload.batchId
    val trid: String get() = payload.trid
}

object RunBatcher {
    const val BATCH_SIZE = 5
    internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; allowSpecialFloatingPointValues = false }

    /**
     * Stable IDs are derived from a caller-persisted prefix plus zero-based chunk index, not the retry clock.
     * For live recording, finished=false holds the last 1..5 points, so an already-sent finishType=0
     * batch never has to be overwritten with finishType=1. Calling again with the same samples is idempotent.
     */
    fun create(metadata: RunMetadata, track: GeneratedRunTrack, batchIdPrefix: String, finished: Boolean = true): List<RunUploadBatch> {
        require(batchIdPrefix.isNotBlank() && batchIdPrefix.length <= 128) { AppText.Run.BATCH_PREFIX_REQUIRES_STABLE_NONEMPTY_LOCAL_ID }
        require(RunTrackValidator.validate(track).valid) { AppText.Run.TRACK_CONSISTENCY_VALIDATION_FAILED }
        val count = if (finished) track.points.size else ((track.points.size - 1) / BATCH_SIZE) * BATCH_SIZE
        if (count == 0) return emptyList()
        var cumulativeDistance = 0.0
        return track.points.take(count).chunked(BATCH_SIZE).mapIndexed { index, chunk ->
            cumulativeDistance += chunk.sumOf { it.dis }
            val end = chunk.last().locatetime
            val elapsedMillis = end - track.startTimeMillis
            val speed = if (elapsedMillis == 0L) 0.0 else cumulativeDistance / (elapsedMillis / 1000.0)
            val batchId = "$batchIdPrefix-${index.toString().padStart(6, '0')}"
            RunUploadBatch(index, RunUploadPayload(
                scheduleId = metadata.scheduleId,
                userId = metadata.userId,
                userName = metadata.userName,
                runLength = cumulativeDistance.toString(),
                runTime = elapsedMillis,
                imgUrl = metadata.imgUrl,
                startTime = track.startTimeMillis,
                endTime = end,
                trid = metadata.trid,
                speed = speed.toString(),
                finishType = if (finished && index == (count - 1) / BATCH_SIZE) "1" else "0",
                coordinate = json.encodeToString(chunk.map { RunCoordinate(it.location, it.locatetime, it.dis, it.count) }),
                batchId = batchId,
            ))
        }
    }

    internal fun validateBatch(batch: RunUploadBatch): List<RunCoordinate> {
        val payload = batch.payload
        require(batch.index >= 0 && batch.batchId.isNotBlank() && batch.trid.isNotBlank()) { AppText.Run.INVALID_BATCH_IDENTIFIER }
        require(payload.userId.isNotBlank() && payload.scheduleId.isNotBlank()) { AppText.Run.BATCH_USER_OR_SCHEDULE_ID_EMPTY }
        require(payload.finishType in listOf("0", "1")) { AppText.Run.INVALID_BATCH_COMPLETION_MARKER }
        require(payload.startTime >= 0 && payload.endTime >= payload.startTime && payload.runTime == payload.endTime - payload.startTime) { AppText.Run.BATCH_TIME_CUMULATIVE_DURATION_MISMATCH }
        val distance = payload.runLength.toDoubleOrNull()
        val speed = payload.speed.toDoubleOrNull()
        require(distance != null && distance.isFinite() && distance >= 0 && speed != null && speed.isFinite() && speed in 0.0..5.000001) { AppText.Run.INVALID_BATCH_DISTANCE_OR_SPEED }
        require(kotlin.math.abs(speed - if (payload.runTime == 0L) 0.0 else distance / (payload.runTime / 1000.0)) < 1e-6) { AppText.Run.BATCH_AVERAGE_SPEED_DISTANCE_DURATION_MISMATCH }
        val coordinates = json.decodeFromString<List<RunCoordinate>>(payload.coordinate)
        require(coordinates.size in 1..BATCH_SIZE && (payload.finishType == "1" || coordinates.size == BATCH_SIZE)) { AppText.Run.BATCH_POINT_COUNT_CONSTRAINT }
        require(coordinates.last().locatetime == payload.endTime) { AppText.Run.LAST_POINT_BATCH_END_TIME_MISMATCH }
        for ((index, point) in coordinates.withIndex()) {
            val parts = point.location.split(',')
            require(parts.size == 2) { AppText.Run.INVALID_TRACK_COORDINATE_FORMAT }
            GeoPoint(parts[0].toDouble(), parts[1].toDouble())
            require(point.locatetime in payload.startTime..payload.endTime && point.count.isFinite() && kotlin.math.abs(point.count - (point.locatetime - payload.startTime) / 1000.0) < 1e-6) { AppText.Run.TRACK_CUMULATIVE_SECONDS_TIMESTAMP_MISMATCH }
            require(point.dis.isFinite() && point.dis >= 0) { AppText.Run.INVALID_TRACK_DISTANCE }
            if (index > 0) require(point.locatetime > coordinates[index - 1].locatetime) { AppText.Run.BATCH_TIMESTAMPS_MUST_STRICTLY_INCREASE }
        }
        return coordinates
    }
}
