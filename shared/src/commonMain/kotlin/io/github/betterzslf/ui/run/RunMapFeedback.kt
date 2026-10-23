package io.github.betterzslf.ui.run

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunCheckpoint
import io.github.betterzslf.platform.RunMapCoordinateSystem
import io.github.betterzslf.platform.RunMapEvent
import io.github.betterzslf.platform.RunMapFailure
import io.github.betterzslf.platform.RunMapProvider

/** owner is an opaque in-memory scope, not a Session or a persisted/loggable credential. */
internal class RunMapFeedbackIdentity(private val owner: Any, private val provider: RunMapProvider?, private val apiKey: String?) {
    override fun equals(other: Any?): Boolean = other is RunMapFeedbackIdentity &&
        owner === other.owner && provider == other.provider && apiKey == other.apiKey
    override fun hashCode(): Int = (owner.hashCode() * 31 + (provider?.hashCode() ?: 0)) * 31 + (apiKey?.hashCode() ?: 0)
    override fun toString(): String = "RunMapFeedbackIdentity(redacted)"
}

internal data class RunMapFeedbackData(
    val paths: List<Pair<String, List<GeoPoint>>>,
    val checkpoints: List<RunCheckpoint>,
    val coordinateSystem: RunMapCoordinateSystem,
    val role: RunMapRole,
) {
    val hasMapData: Boolean get() = paths.any { it.second.isNotEmpty() } || checkpoints.isNotEmpty()
    override fun toString(): String = "RunMapFeedbackData(redacted)"
}

internal sealed interface RunMapDocumentFeedback {
    data object Pending : RunMapDocumentFeedback
    data object Ready : RunMapDocumentFeedback
    data class Failed(val reason: RunMapFailure) : RunMapDocumentFeedback
}
internal sealed interface RunMapTransientFeedback {
    data class Checkpoint(val ordinal: Int?) : RunMapTransientFeedback
    data object PreviewEvent : RunMapTransientFeedback
}

/** Pure event consumer. Data changes never manufacture SDK success; a new mount starts Pending. */
@ConsistentCopyVisibility
internal data class RunMapFeedbackState private constructor(
    private val identity: RunMapFeedbackIdentity,
    private val document: Any,
    private val data: RunMapFeedbackData,
    private val active: Boolean,
    val status: RunMapDocumentFeedback = RunMapDocumentFeedback.Pending,
    val transient: RunMapTransientFeedback? = null,
) {
    fun forDocument(identity: RunMapFeedbackIdentity, document: Any, data: RunMapFeedbackData): RunMapFeedbackState {
        if (this.identity != identity || this.document !== document) return initial(identity, document, data)
        if (this.data == data) return this
        if (!data.hasMapData) return copy(data = data, active = false, status = RunMapDocumentFeedback.Pending, transient = null)
        // A retired mount is never reactivated by supplying data. The UI must mount a fresh document.
        return copy(data = data, transient = null)
    }

    fun onEvent(identity: RunMapFeedbackIdentity, document: Any, event: RunMapEvent): RunMapFeedbackState {
        if (!active || this.identity != identity || this.document !== document) return this
        return when (event) {
            RunMapEvent.Ready -> if (status == RunMapDocumentFeedback.Ready) this
                else copy(status = RunMapDocumentFeedback.Ready, transient = null)
            is RunMapEvent.Failed -> copy(status = RunMapDocumentFeedback.Failed(event.reason), transient = null)
            is RunMapEvent.CheckpointSelected -> copy(transient = RunMapTransientFeedback.Checkpoint(
                data.checkpoints.indexOfFirst { it.id == event.id }.takeIf { it >= 0 }?.plus(1)))
            is RunMapEvent.DrawPoint -> copy(transient = RunMapTransientFeedback.PreviewEvent)
        }
    }

    fun text(): String? {
        val failure = status as? RunMapDocumentFeedback.Failed
        if (failure != null) return AppText.Run.mapFallback(failure.reason.toString())
        return when (val notice = transient) {
            is RunMapTransientFeedback.Checkpoint -> AppText.Run.selectedCheckpointNotice(notice.ordinal?.toString() ?: AppText.Common.UNKNOWN)
            RunMapTransientFeedback.PreviewEvent -> AppText.Run.MAP_PREVIEW_EVENTS_NOT_REAL_LOCATION_HINT
            null -> if (status == RunMapDocumentFeedback.Ready) AppText.Run.MAP_READY_MARKER_VIEW_ONLY_HINT else null
        }
    }

    override fun toString(): String = "RunMapFeedbackState(redacted)"

    companion object {
        fun initial(identity: RunMapFeedbackIdentity, document: Any, data: RunMapFeedbackData): RunMapFeedbackState =
            RunMapFeedbackState(identity, document, data, active = data.hasMapData)
    }
}
