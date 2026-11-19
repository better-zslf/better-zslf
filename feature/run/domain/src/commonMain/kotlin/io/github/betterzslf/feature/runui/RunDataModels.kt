package io.github.betterzslf.feature.runui

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunCheckpoint

/** A primitive display field, already filtered and bounded at the data boundary. */
data class RunDisplayField(val label: String, val value: String) {
    override fun toString(): String = "RunDisplayField(redacted)"
}

/** Real server ids and an unrelated source-local list key. The key is never an action identifier. */
data class RunTaskItem(
    val trid: String?,
    val scheduleId: String?,
    val title: String,
    val startText: String?,
    val endText: String?,
    val isFinish: String?,
    val identityKey: String,
    val safeDisplayFields: List<RunDisplayField> = emptyList(),
    val warnings: List<String> = emptyList(),
    val errandId: String? = null,
    val errandFieldPresent: Boolean = false,
    val scheduleFieldsPresent: Boolean = false,
    val minimumDistanceMeters: Double? = null,
) {
    override fun toString(): String = "RunTaskItem(fields=${safeDisplayFields.size}, warnings=${warnings.size})"
}

data class RunTaskDetail(
    val task: RunTaskItem,
    val checkpoints: List<RunCheckpoint>,
    val walkPaths: List<List<GeoPoint>>,
    val minimumDistanceMeters: Double?,
    val warnings: List<String>,
    val checkpointNames: Map<String, String> = emptyMap(),
    val checkpointSourceCount: Int = checkpoints.size,
) {
    val missingFields: List<String> get() = buildList {
        if (task.errandId.isNullOrBlank()) add("errandId")
        if (task.scheduleId.isNullOrBlank()) add("errandScheduleId")
        if (checkpoints.isEmpty()) add("geoCoorList")
        else if (checkpoints.size != checkpointSourceCount) add("geoCoorList.coordinates")
    }
    val canStart: Boolean get() = missingFields.isEmpty()
}

data class RunRecordItem(
    val trid: String?,
    val title: String,
    val distanceMeters: Double?,
    val elapsedMillis: Long?,
    val status: String?,
    val points: List<GeoPoint>,
    val safeDisplayFields: List<RunDisplayField>,
    val warnings: List<String>,
    val identityKey: String,
) {
    override fun toString(): String = "RunRecordItem(points=${points.size}, fields=${safeDisplayFields.size}, warnings=${warnings.size})"
}

data class RunRows<T>(
    val items: List<T>,
    val recognized: Boolean,
    val warnings: List<String>,
    /** Counts every raw transport row, including malformed and duplicate rows. */
    val rawSize: Int = items.size,
)

/** Known absence is recognized=true/value=null; unknown structure is recognized=false/value=null. */
data class RunReadValue<T>(val value: T?, val recognized: Boolean, val warnings: List<String>)
