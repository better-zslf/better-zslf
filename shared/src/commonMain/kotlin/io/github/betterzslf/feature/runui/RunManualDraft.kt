package io.github.betterzslf.feature.runui

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.platform.RunMapCoordinateSystem
import io.github.betterzslf.platform.RunMapEvent
import io.github.betterzslf.platform.RunMapProvider

/** Pure local drawing outcomes; presentation and translations belong to the caller. */
enum class RunManualDraftError {
    UNKNOWN_FRAME,
    FRAME_MISMATCH,
    INVALID_COORDINATE,
    POINT_LIMIT_REACHED,
    ADJACENT_DUPLICATE,
    EMPTY_DRAFT,
    TOO_FEW_POINTS,
}

/** A rejected edit returns the exact previous immutable draft. */
class RunManualDraftEdit internal constructor(
    val draft: RunManualDraft,
    val error: RunManualDraftError? = null,
) {
    val accepted: Boolean get() = error == null
    override fun toString(): String =
        "RunManualDraftEdit(provider=${draft.provider}, frame=${draft.coordinateSystem}, count=${draft.pointCount})"
}

class RunManualDraftCommit internal constructor(
    val route: RunManualRoute?,
    val error: RunManualDraftError? = null,
) {
    val accepted: Boolean get() = route != null && error == null
    override fun toString(): String =
        "RunManualDraftCommit(provider=${route?.provider}, frame=${route?.coordinateSystem}, count=${route?.pointCount ?: 0})"
}

/** One detached segment in its original drawing frame, never GPS samples or a timed track. */
class RunManualRoute internal constructor(
    val provider: RunMapProvider,
    val coordinateSystem: RunMapCoordinateSystem,
    points: List<GeoPoint>,
) {
    private val retained = points.toList()
    val pointCount: Int get() = retained.size
    val points: List<GeoPoint> get() = retained.toList()
    val segments: List<List<GeoPoint>> get() = listOf(retained.toList())
    override fun toString(): String =
        "RunManualRoute(provider=$provider, frame=$coordinateSystem, count=$pointCount)"
}

/**
 * Immutable, bounded, in-memory map drawing only. No coordinate conversion, IO or clock.
 * A provider change requires a new empty draft; every draw retains its explicit frame.
 * Adjacent exact numeric duplicates are rejected; close points and nonadjacent loops remain.
 */
class RunManualDraft private constructor(
    val provider: RunMapProvider,
    points: List<GeoPoint>,
) {
    private val retained = points.toList()
    val coordinateSystem: RunMapCoordinateSystem = when (provider) {
        RunMapProvider.AMAP -> RunMapCoordinateSystem.GCJ02
        RunMapProvider.TIAN_DI_TU -> RunMapCoordinateSystem.WGS84
    }
    val pointCount: Int get() = retained.size
    val points: List<GeoPoint> get() = retained.toList()
    // All append paths reject adjacent duplicates, so the first two points are distinct.
    val canCommit: Boolean get() = pointCount >= 2

    fun append(event: RunMapEvent.DrawPoint): RunManualDraftEdit =
        append(event.point.longitude, event.point.latitude, event.coordinateSystem)

    /** Numeric boundary for callers validating values before constructing a GeoPoint. */
    fun append(longitude: Double, latitude: Double, frame: RunMapCoordinateSystem): RunManualDraftEdit {
        val error = validate(longitude, latitude, frame, retained.lastOrNull(), pointCount)
        if (error != null) return RunManualDraftEdit(this, error)
        return RunManualDraftEdit(RunManualDraft(provider, retained + GeoPoint(longitude, latitude)))
    }

    /** Atomic input snapshot: any rejected event leaves the entire previous draft unchanged. */
    fun appendAll(events: List<RunMapEvent.DrawPoint>): RunManualDraftEdit {
        // Bound work before copying a caller-owned collection. No prefix is silently accepted.
        if (events.size > MAX_POINTS - pointCount) return RunManualDraftEdit(this, RunManualDraftError.POINT_LIMIT_REACHED)
        if (events.isEmpty()) return RunManualDraftEdit(this)
        val incoming = events.toList()
        val next = retained.toMutableList()
        for (event in incoming) {
            val error = validate(event.point.longitude, event.point.latitude, event.coordinateSystem, next.lastOrNull(), next.size)
            if (error != null) return RunManualDraftEdit(this, error)
            next += event.point
        }
        return RunManualDraftEdit(RunManualDraft(provider, next))
    }

    fun undo(): RunManualDraftEdit =
        if (retained.isEmpty()) RunManualDraftEdit(this, RunManualDraftError.EMPTY_DRAFT)
        else RunManualDraftEdit(RunManualDraft(provider, retained.dropLast(1)))

    fun clear(): RunManualDraft = if (retained.isEmpty()) this else empty(provider)

    fun commit(): RunManualDraftCommit =
        if (!canCommit) RunManualDraftCommit(null, RunManualDraftError.TOO_FEW_POINTS)
        else RunManualDraftCommit(RunManualRoute(provider, coordinateSystem, retained))

    private fun validate(
        longitude: Double,
        latitude: Double,
        frame: RunMapCoordinateSystem,
        previous: GeoPoint?,
        size: Int,
    ): RunManualDraftError? = when {
        frame == RunMapCoordinateSystem.UNKNOWN -> RunManualDraftError.UNKNOWN_FRAME
        frame != coordinateSystem -> RunManualDraftError.FRAME_MISMATCH
        !longitude.isFinite() || !latitude.isFinite() || longitude !in -180.0..180.0 || latitude !in -90.0..90.0 ->
            RunManualDraftError.INVALID_COORDINATE
        previous != null && previous.longitude == longitude && previous.latitude == latitude ->
            RunManualDraftError.ADJACENT_DUPLICATE
        size >= MAX_POINTS -> RunManualDraftError.POINT_LIMIT_REACHED
        else -> null
    }

    override fun toString(): String =
        "RunManualDraft(provider=$provider, frame=$coordinateSystem, count=$pointCount)"

    companion object {
        // Manual-tap budget stays below RunMapWeb's current 20,000-point combined renderer cap.
        // The UI must still budget all paths/markers in its complete model.
        const val MAX_POINTS: Int = 2_000
        fun empty(provider: RunMapProvider): RunManualDraft = RunManualDraft(provider, emptyList())
    }
}
