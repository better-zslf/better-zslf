package io.github.betterzslf.ui.run

import io.github.betterzslf.feature.runui.RunTaskFilter

/** Opaque keys are list-local identity only; no server identifiers or transport payloads are rendered. */
data class RunTaskRowDisplay(
    val localKey: String,
    val title: String,
    val minimumDistanceMeters: Double?,
    val endText: String?,
    val finish: String?,
    val canOpen: Boolean,
) {
    override fun toString(): String = "RunTaskRowDisplay(redacted)"
}

data class RunRecordRowDisplay(
    val localKey: String,
    val title: String,
    val distanceMeters: Double?,
    val elapsedMillis: Long?,
    val status: String?,
    val canOpen: Boolean,
) {
    override fun toString(): String = "RunRecordRowDisplay(redacted)"
}

data class RunListContributionDisplay(val localKey: String, val title: String) {
    override fun toString(): String = "RunListContributionDisplay(redacted)"
}

data class RunTaskListDisplay(
    val rows: List<RunTaskRowDisplay> = emptyList(),
    val filter: RunTaskFilter = RunTaskFilter.ALL,
    val signedIn: Boolean = false,
    val metricUnits: Boolean = true,
    val loading: Boolean = false,
    val end: Boolean = false,
    val error: String? = null,
    val moreError: String? = null,
    val warnings: List<String> = emptyList(),
    val contributions: List<RunListContributionDisplay> = emptyList(),
)

data class RunRecordListDisplay(
    val rows: List<RunRecordRowDisplay> = emptyList(),
    val hasSelection: Boolean = false,
    val minimumDistanceMeters: Double? = null,
    val metricUnits: Boolean = true,
    val loading: Boolean = false,
    val end: Boolean = false,
    val error: String? = null,
    val moreError: String? = null,
    val warnings: List<String> = emptyList(),
    val planError: String? = null,
)
