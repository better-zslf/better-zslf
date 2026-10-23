package io.github.betterzslf.feature.run

import io.github.betterzslf.core.text.AppText
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Shared real-sample budget. Import parsing is not a dependency of real GPS recording. */
object RunTrackLimits { const val MAX_POINTS: Int = 100_000 }

@Serializable
data class RunTrackPoint(val point: GeoPoint, val locatetime: Long, val dis: Double, val count: Double) {
    val location: String get() = "${point.longitude},${point.latitude}"
}

/** Canonical track representation; the historical name also covers accepted real samples. */
@Serializable
data class GeneratedRunTrack(
    val points: List<RunTrackPoint>,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val distanceMeters: Double,
    val runTimeSeconds: Double,
    val averageSpeedMetersPerSecond: Double,
)

data class TrackConsistencyReport(val issues: List<String>) { val valid: Boolean get() = issues.isEmpty() }

object RunTrackValidator {
    fun validate(track: GeneratedRunTrack, maximumSpeedMetersPerSecond: Double = 5.0): TrackConsistencyReport {
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0)
        val issues = mutableListOf<String>()
        val points = track.points
        if (points.size < 2) issues += AppText.Run.TRACK_REQUIRES_TWO_POINTS
        if (track.startTimeMillis < 0 || track.endTimeMillis <= track.startTimeMillis) issues += AppText.Run.INVALID_TRACK_START_OR_END_TIME
        val elapsed = (track.endTimeMillis - track.startTimeMillis) / 1000.0
        if (!track.runTimeSeconds.isFinite() || track.runTimeSeconds <= 0 || abs(elapsed - track.runTimeSeconds) > 1e-6) issues += AppText.Run.DURATION_START_END_TIME_MISMATCH
        if (points.firstOrNull()?.locatetime != track.startTimeMillis || points.lastOrNull()?.locatetime != track.endTimeMillis) issues += AppText.Run.TRACK_TIME_START_END_MISMATCH
        if (points.firstOrNull()?.let { abs(it.dis) > 1e-8 || abs(it.count) > 1e-8 } == true) issues += AppText.Run.FIRST_POINT_CUMULATIVE_VALUES_MUST_BE_ZERO
        var sum = 0.0
        for ((i, point) in points.withIndex()) {
            if (!point.dis.isFinite() || point.dis < 0 || !point.count.isFinite() || point.count < 0) issues += AppText.Run.trackPointDistanceOrCountInvalid((i))
            if (point.locatetime < track.startTimeMillis || point.locatetime > track.endTimeMillis) issues += AppText.Run.trackPointTimeOutOfRange((i))
            val count = (point.locatetime - track.startTimeMillis) / 1000.0
            if (abs(point.count - count) > 1e-6) issues += AppText.Run.trackPointElapsedMismatch((i))
            if (i > 0) {
                val previous = points[i - 1]
                val distance = RunGeo.distanceMeters(previous.point, point.point)
                sum += distance
                if (abs(distance - point.dis) > 0.01) issues += AppText.Run.trackPointDistanceMismatch((i))
                val delta = point.locatetime - previous.locatetime
                if (delta <= 0) issues += AppText.Run.TRACK_TIMESTAMPS_MUST_STRICTLY_INCREASE
                else if (distance / (delta / 1000.0) > maximumSpeedMetersPerSecond + 1e-6) issues += AppText.Run.trackPointSpeedExceeded((i))
            }
        }
        if (!track.distanceMeters.isFinite() || track.distanceMeters < 0 || abs(track.distanceMeters - sum) > 0.01) issues += AppText.Run.TOTAL_DISTANCE_POINT_DISTANCE_MISMATCH
        if (!track.averageSpeedMetersPerSecond.isFinite() || elapsed <= 0 || abs(track.averageSpeedMetersPerSecond - sum / elapsed) > 1e-6) issues += AppText.Run.AVERAGE_SPEED_DISTANCE_DURATION_MISMATCH
        if (track.averageSpeedMetersPerSecond > maximumSpeedMetersPerSecond + 1e-6) issues += AppText.Run.TRACK_AVERAGE_SPEED_LIMIT_EXCEEDED
        return TrackConsistencyReport(issues)
    }
}

/** Shared canonical builder. The synthetic-preview extension exists only in Pro source inputs. */
object RunTrackGenerator {
    /** Builds the identical canonical track representation from already accepted real GPS samples. */
    fun fromSamples(samples: List<RunLocationSample>, filter: RunLocationFilter = RunLocationFilter()): GeneratedRunTrack {
        require(samples.size in 2..RunTrackLimits.MAX_POINTS) { AppText.Run.LOCATION_TRACK_REQUIRES_TWO_SAMPLES }
        var previous: RunLocationSample? = null
        for (sample in samples) {
            require(filter.evaluate(previous, sample) is LocationFilterResult.Accepted) { AppText.Run.REAL_TRACK_CONTAINS_REJECTED_SAMPLES }
            previous = sample
        }
        val intervals = samples.zipWithNext().map { (a, b) -> b.timestampMillis - a.timestampMillis }
        return assemble(samples.map { it.point }, intervals, samples.first().timestampMillis)
    }

    internal fun assemble(points: List<GeoPoint>, intervals: List<Long>, start: Long): GeneratedRunTrack {
        require(points.size == intervals.size + 1)
        var timestamp = start
        var total = 0.0
        val records = points.mapIndexed { index, point ->
            val distance = if (index == 0) 0.0 else RunGeo.distanceMeters(points[index - 1], point)
            if (index > 0) timestamp += intervals[index - 1]
            total += distance
            RunTrackPoint(point, timestamp, distance, (timestamp - start) / 1000.0)
        }
        val seconds = (timestamp - start) / 1000.0
        return GeneratedRunTrack(records, start, timestamp, total, seconds, total / seconds)
    }
}
