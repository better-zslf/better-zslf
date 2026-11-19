package io.github.betterzslf.feature.run.api

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunCheckpoint
import io.github.betterzslf.platform.RunMapCoordinateSystem

/** Typed read-only task projection. No raw response, campus identity, credentials or write capability. */
class RunPreviewContext(
    val title: String,
    checkpoints: List<RunCheckpoint>,
    walkPaths: List<List<GeoPoint>>,
    val minimumDistanceMeters: Double?,
    warnings: List<String>,
    val checkpointSourceCount: Int = checkpoints.size,
    val coordinateSystem: RunMapCoordinateSystem = RunMapCoordinateSystem.GCJ02,
) {
    val checkpoints: List<RunCheckpoint> = SnapshotList(checkpoints)
    val walkPaths: List<List<GeoPoint>> = SnapshotList(walkPaths.map { SnapshotList(it) })
    val warnings: List<String> = SnapshotList(warnings)

    init {
        require(title.isNotBlank())
        require(checkpointSourceCount >= checkpoints.size)
        require(minimumDistanceMeters == null || minimumDistanceMeters.isFinite() && minimumDistanceMeters > 0)
    }

    override fun toString(): String = "RunPreviewContext(checkpoints=${checkpoints.size}, paths=${walkPaths.size}, frame=$coordinateSystem)"
}

private class SnapshotList<T>(source: Collection<T>) : AbstractList<T>() {
    private val values = source.toList()
    override val size: Int get() = values.size
    override fun get(index: Int): T = values[index]
}
