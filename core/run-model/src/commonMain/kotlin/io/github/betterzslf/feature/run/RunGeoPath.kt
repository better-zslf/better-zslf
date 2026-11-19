package io.github.betterzslf.feature.run

/**
 * Immutable numeric path index shared by read-only geometry consumers, including optional previews.
 * It grants no authority to record native samples, acknowledge checkpoints, persist or upload tracks.
 * Empty paths are allowed for validation; [pointAt] requires at least one point and a finite distance.
 */
class RunGeoPath(points: List<GeoPoint>) {
    private val snapshot = points.toList()
    private val cumulative = RunGeo.cumulative(snapshot)

    val size: Int get() = snapshot.size
    val lengthMeters: Double get() = cumulative.lastOrNull() ?: 0.0

    /** Defensive copy: callers cannot change this index by mutating its cumulative measurements. */
    fun cumulativeDistances(): DoubleArray = cumulative.copyOf()

    /** Finite distances outside the path clamp to its original first/last point, without extrapolation. */
    fun pointAt(distanceMeters: Double): GeoPoint {
        require(distanceMeters.isFinite())
        require(snapshot.isNotEmpty())
        return RunGeo.pointAt(snapshot, cumulative, distanceMeters)
    }
}
