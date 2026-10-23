package io.github.betterzslf.feature.run

import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class GeoPoint(val longitude: Double, val latitude: Double) {
    init {
        require(longitude.isFinite() && longitude in -180.0..180.0) { "经度须在 -180 至 180 之间" }
        require(latitude.isFinite() && latitude in -90.0..90.0) { "纬度须在 -90 至 90 之间" }
    }
}

@Serializable
data class RunCheckpoint(val id: String, val point: GeoPoint)

@Serializable
data class RunLocationSample(
    val point: GeoPoint,
    val timestampMillis: Long,
    val accuracyMeters: Double,
    val speedMetersPerSecond: Double? = null,
)

/** All distances are metres on a sphere, including interpolation across the date line. */
object RunGeo {
    const val EARTH_RADIUS_METERS = 6_371_008.8
    private const val DEG = PI / 180.0

    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat = (b.latitude - a.latitude) * DEG
        val lon = (b.longitude - a.longitude) * DEG
        val h = sin(lat / 2).pow(2) + cos(a.latitude * DEG) * cos(b.latitude * DEG) * sin(lon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun pathLengthMeters(points: List<GeoPoint>): Double =
        points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }

    fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        require(fraction.isFinite() && fraction in 0.0..1.0)
        if (fraction == 0.0) return a
        if (fraction == 1.0) return b
        val angle = distanceMeters(a, b) / EARTH_RADIUS_METERS
        if (angle < 1e-12) return a
        require(PI - angle > 1e-8) { "对跖点之间需补充路线中间点" }
        val s = sin(angle)
        val left = sin((1 - fraction) * angle) / s
        val right = sin(fraction * angle) / s
        val x = left * cos(a.latitude * DEG) * cos(a.longitude * DEG) + right * cos(b.latitude * DEG) * cos(b.longitude * DEG)
        val y = left * cos(a.latitude * DEG) * sin(a.longitude * DEG) + right * cos(b.latitude * DEG) * sin(b.longitude * DEG)
        val z = left * sin(a.latitude * DEG) + right * sin(b.latitude * DEG)
        return GeoPoint(atan2(y, x) / DEG, atan2(z, sqrt(x * x + y * y)) / DEG)
    }

    /** Geodesic displacement; east/north are a local tangent-plane displacement in metres. */
    fun offset(point: GeoPoint, eastMeters: Double, northMeters: Double): GeoPoint {
        require(eastMeters.isFinite() && northMeters.isFinite())
        val distance = hypot(eastMeters, northMeters)
        if (distance == 0.0) return point
        val bearing = atan2(eastMeters, northMeters)
        val delta = distance / EARTH_RADIUS_METERS
        val lat = point.latitude * DEG
        val lon = point.longitude * DEG
        val nextLat = asin((sin(lat) * cos(delta) + cos(lat) * sin(delta) * cos(bearing)).coerceIn(-1.0, 1.0))
        val nextLon = lon + atan2(sin(bearing) * sin(delta) * cos(lat), cos(delta) - sin(lat) * sin(nextLat))
        val normalizedLon = ((nextLon / DEG + 540.0) % 360.0) - 180.0
        return GeoPoint(normalizedLon, nextLat / DEG)
    }

    internal fun cumulative(points: List<GeoPoint>): DoubleArray {
        val result = DoubleArray(points.size)
        for (i in 1 until points.size) result[i] = result[i - 1] + distanceMeters(points[i - 1], points[i])
        return result
    }

    internal fun pointAt(points: List<GeoPoint>, cumulative: DoubleArray, distance: Double): GeoPoint {
        require(points.isNotEmpty())
        if (distance <= 0) return points.first()
        if (distance >= cumulative.last()) return points.last()
        var lo = 1
        var hi = cumulative.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] < distance) lo = mid + 1 else hi = mid
        }
        val length = cumulative[lo] - cumulative[lo - 1]
        return if (length < 1e-9) points[lo] else interpolate(points[lo - 1], points[lo], (distance - cumulative[lo - 1]) / length)
    }

    /** Closest location on a short great-circle arc. */
    internal fun projectionFraction(point: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        if (distanceMeters(a, b) < 1e-8) return 0.0
        // A bounded search avoids planar longitude and polar singularities.
        var lo = 0.0
        var hi = 1.0
        repeat(44) {
            val l = lo + (hi - lo) / 3
            val r = hi - (hi - lo) / 3
            if (distanceMeters(point, interpolate(a, b, l)) <= distanceMeters(point, interpolate(a, b, r))) hi = r else lo = l
        }
        val mid = (lo + hi) / 2
        return listOf(0.0, mid, 1.0).minBy { distanceMeters(point, interpolate(a, b, it)) }
    }
}

enum class LocationRejection { INVALID_TIME, INVALID_ACCURACY, ACCURACY, INVALID_SPEED, SPEED, NON_INCREASING_TIME }

sealed interface LocationFilterResult {
    data class Accepted(val sample: RunLocationSample, val distanceMeters: Double, val inferredSpeedMetersPerSecond: Double) : LocationFilterResult
    data class Rejected(val reason: LocationRejection) : LocationFilterResult
}

class RunLocationFilter(
    private val maximumAccuracyMeters: Double = 50.0,
    private val maximumSpeedMetersPerSecond: Double = 5.0,
) {
    init {
        require(maximumAccuracyMeters.isFinite() && maximumAccuracyMeters > 0)
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0)
    }

    /** Rejected samples never replace the caller's last accepted sample. */
    fun evaluate(previous: RunLocationSample?, sample: RunLocationSample): LocationFilterResult {
        if (sample.timestampMillis < 0 || (previous != null && previous.timestampMillis < 0)) return LocationFilterResult.Rejected(LocationRejection.INVALID_TIME)
        if (!sample.accuracyMeters.isFinite() || sample.accuracyMeters < 0) return LocationFilterResult.Rejected(LocationRejection.INVALID_ACCURACY)
        if (sample.accuracyMeters > maximumAccuracyMeters) return LocationFilterResult.Rejected(LocationRejection.ACCURACY)
        val reported = sample.speedMetersPerSecond
        if (reported != null && (!reported.isFinite() || reported < 0)) return LocationFilterResult.Rejected(LocationRejection.INVALID_SPEED)
        if (reported != null && reported > maximumSpeedMetersPerSecond) return LocationFilterResult.Rejected(LocationRejection.SPEED)
        if (previous == null) return LocationFilterResult.Accepted(sample, 0.0, 0.0)
        if (sample.timestampMillis <= previous.timestampMillis) return LocationFilterResult.Rejected(LocationRejection.NON_INCREASING_TIME)
        val distance = RunGeo.distanceMeters(previous.point, sample.point)
        val inferred = distance / ((sample.timestampMillis - previous.timestampMillis) / 1000.0)
        if (inferred > maximumSpeedMetersPerSecond + 1e-8) return LocationFilterResult.Rejected(LocationRejection.SPEED)
        return LocationFilterResult.Accepted(sample, distance, inferred)
    }
}

/** Ordered local checkpoint progression; a server punch acknowledgment uses the same expected index. */
class RunCheckpointProgress(private val checkpoints: List<RunCheckpoint>, private val radiusMeters: Double = 50.0) {
    var nextIndex: Int = 0
        private set
    val finished: Boolean get() = checkpoints.isNotEmpty() && nextIndex == checkpoints.size
    init { require(radiusMeters.isFinite() && radiusMeters > 0) }
    fun observe(point: GeoPoint): Boolean {
        var advanced = false
        while (nextIndex < checkpoints.size && RunGeo.distanceMeters(point, checkpoints[nextIndex].point) <= radiusMeters) {
            nextIndex++
            advanced = true
        }
        return advanced
    }
    fun acknowledgePunch(expectedIndex: Int): Boolean {
        if (expectedIndex != nextIndex || nextIndex >= checkpoints.size) return false
        nextIndex++
        return true
    }
    fun distanceToNext(point: GeoPoint): Double? = checkpoints.getOrNull(nextIndex)?.let { RunGeo.distanceMeters(point, it.point) }
}
