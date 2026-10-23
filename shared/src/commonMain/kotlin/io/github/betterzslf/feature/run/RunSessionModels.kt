package io.github.betterzslf.feature.run

import io.github.betterzslf.platform.LocationTrackingState
import kotlinx.serialization.Serializable

@Serializable
data class RunSessionStart(
    val metadata: RunMetadata,
    val checkpoints: List<RunCheckpoint> = emptyList(),
    val samplingSeconds: Int = 10,
    val batchIdPrefix: String = metadata.trid,
) {
    init {
        require(samplingSeconds in 10..60) { "采样间隔须为 10 至 60 秒" }
        require(batchIdPrefix.isNotBlank() && batchIdPrefix.length <= 128) { "批次前缀须为稳定的非空标识" }
        require(checkpoints.size <= 256 && checkpoints.all { it.id.isNotBlank() } && checkpoints.map { it.id }.distinct().size == checkpoints.size) { "打卡点标识须唯一且数量不超过 256" }
    }
}

enum class RunSessionPhase { IDLE, RECORDING, PAUSED, RECOVERED, FINISHING, FINISHED, CANCELLED, OWNER_MISMATCH, CLOSED }
enum class RunSessionUploadPhase { NONE, INSUFFICIENT_SAMPLES, STAGED, CONFIRMED, IN_FLIGHT, ACKNOWLEDGED, FAILED, UNCERTAIN }
enum class RunSessionProblemCode { CONFIRMATION_REQUIRED, OWNER_MISMATCH, ACTIVE_SESSION_EXISTS, NO_SESSION, INVALID_STATE, INVALID_INPUT, STORAGE, QUEUE, LOCATION, SAMPLE_LIMIT, CLOCK, CLOSED }
data class RunSessionProblem(val code: RunSessionProblemCode, val message: String)
enum class RunSessionSampleRejection { FILTER, BEFORE_RECORDING_WINDOW, FUTURE_TIME, TOO_FREQUENT }

/**
 * elapsedMillis is the first-to-last ACCEPTED native sample span, including pauses between them.
 * It advances only with real GPS samples; finish/pause never invent a position or retime a sample.
 * Distance across a sampling pause is the geodesic chord between its two real bounding samples.
 * startedAtMillis/finishedAtMillis describe button actions separately from the measured track span.
 */
data class RunSessionState(
    val phase: RunSessionPhase = RunSessionPhase.IDLE,
    val metadata: RunMetadata? = null,
    val acceptedSampleCount: Int = 0,
    val acceptedSamples: List<RunLocationSample> = emptyList(),
    val rejectedSampleCount: Int = 0,
    val distanceMeters: Double = 0.0,
    val elapsedMillis: Long = 0,
    val averageSpeedMetersPerSecond: Double = 0.0,
    val nextCheckpointIndex: Int = 0,
    val distanceToNextCheckpointMeters: Double? = null,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    val lastAcceptedSample: RunLocationSample? = null,
    val lastSampleRejection: RunSessionSampleRejection? = null,
    val lastFilterRejection: LocationRejection? = null,
    val uploadPhase: RunSessionUploadPhase = RunSessionUploadPhase.NONE,
    val pendingBatchIds: List<String> = emptyList(),
    val queuedSampleCount: Int = 0,
    val uploadConfirmationRequired: Boolean = false,
    val finishRequested: Boolean = false,
    val hasUnpersistedSample: Boolean = false,
    val nativeLocation: LocationTrackingState = LocationTrackingState(),
    val problem: RunSessionProblem? = null,
) {
    val hasFinishableTrack: Boolean get() = acceptedSampleCount >= 2
}

sealed interface RunSessionAction {
    data class Applied(val state: RunSessionState) : RunSessionAction
    data class Rejected(val problem: RunSessionProblem) : RunSessionAction
}

data class RunSessionSummary(
    val metadata: RunMetadata,
    val phase: RunSessionPhase,
    val acceptedSampleCount: Int,
    val startedAtMillis: Long,
    val finishRequested: Boolean,
)

@Serializable
internal enum class StoredRunSessionPhase { RECORDING, PAUSED, FINISHING, FINISHED, CANCELLED }

@Serializable
internal data class StoredRunSession(
    val request: RunSessionStart,
    val phase: StoredRunSessionPhase,
    val startedAtMillis: Long,
    val samples: List<RunLocationSample> = emptyList(),
    val finishedAtMillis: Long? = null,
    val lastConsentAtMillis: Long? = null,
) {
    val trid: String get() = request.metadata.trid
    val ownerId: String get() = request.metadata.userId
    val finishRequested: Boolean get() = finishedAtMillis != null
}
