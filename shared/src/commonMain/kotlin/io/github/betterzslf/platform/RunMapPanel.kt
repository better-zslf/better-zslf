package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.betterzslf.feature.run.GeoPoint

/** Coordinate frames are explicit: a JavaScript map event is never a native GPS sample. */
enum class RunMapCoordinateSystem { WGS84, GCJ02, UNKNOWN }
enum class RunMapProvider { TIAN_DI_TU, AMAP }

data class RunMapPath(
    val points: List<GeoPoint> = emptyList(),
    val coordinateSystem: RunMapCoordinateSystem = RunMapCoordinateSystem.UNKNOWN,
    val segments: List<List<GeoPoint>> = emptyList(),
) {
    /** Disjoint segments are rendered independently; no synthetic bridging line is added. */
    fun allSegments(): List<List<GeoPoint>> = segments.ifEmpty { listOf(points) }.filter { it.isNotEmpty() }
    override fun toString(): String = "RunMapPath(points=${points.size}, segments=${segments.size}, frame=$coordinateSystem)"
}

data class RunMapCheckpoint(
    val id: String,
    val point: GeoPoint,
    val label: String = AppText.Run.CHECKPOINT_FALLBACK_LABEL,
    val coordinateSystem: RunMapCoordinateSystem = RunMapCoordinateSystem.UNKNOWN,
) {
    override fun toString(): String = "RunMapCheckpoint(frame=$coordinateSystem)"
}

/** Contains only the map provider's public API key, never the school's Authorization token. */
data class RunMapModel(
    val provider: RunMapProvider? = null,
    val apiKey: String? = null,
    val actualPath: RunMapPath = RunMapPath(),
    val recommendedPath: RunMapPath = RunMapPath(),
    val importedPath: RunMapPath = RunMapPath(),
    val checkpoints: List<RunMapCheckpoint> = emptyList(),
    val currentLocation: GeoPoint? = null,
    val currentCoordinateSystem: RunMapCoordinateSystem = RunMapCoordinateSystem.WGS84,
    val editMode: Boolean = false,
    val dark: Boolean = false,
) {
    override fun toString(): String = "RunMapModel(provider=$provider, actual=${actualPath.points.size}, recommended=${recommendedPath.points.size}, imported=${importedPath.points.size}, checkpoints=${checkpoints.size}, editing=$editMode)"
}

enum class RunMapFailure { CONFIGURATION, UNCONFIRMED_COORDINATES, TOO_MANY_POINTS, NETWORK, SDK, UNAVAILABLE }
sealed interface RunMapEvent {
    data object Ready : RunMapEvent
    data class Failed(val reason: RunMapFailure) : RunMapEvent
    data class DrawPoint(val point: GeoPoint, val coordinateSystem: RunMapCoordinateSystem) : RunMapEvent {
        override fun toString(): String = "RunMapEvent.DrawPoint(frame=$coordinateSystem)"
    }
    /** Selection only. The caller must independently validate native location and ask before punch. */
    data class CheckpointSelected(val id: String) : RunMapEvent {
        override fun toString(): String = "RunMapEvent.CheckpointSelected"
    }
}

/** Separate from HtmlPanel: this renderer permits only its bundled map and selected provider's CDN. */
@Composable
expect fun RunMapPanel(model: RunMapModel, onEvent: (RunMapEvent) -> Unit, modifier: Modifier = Modifier)
