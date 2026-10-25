package io.github.betterzslf.feature.run

import io.github.betterzslf.core.text.AppText
import kotlin.math.ceil
import kotlin.math.max

/** Duration is the requested elapsed time, in milliseconds; length is always metres. */
data class RouteRequirements(
    val minimumDistanceMeters: Double = 0.0,
    val durationMillis: Long,
    val maximumDistanceMeters: Double? = null,
    val checkpointRadiusMeters: Double = 50.0,
    val maximumSpeedMetersPerSecond: Double = 5.0,
    val maximumGapMeters: Double = 45.0,
    val minimumSpacingMeters: Double = 0.5,
) {
    init {
        require(minimumDistanceMeters.isFinite() && minimumDistanceMeters >= 0)
        require(durationMillis > 0)
        require(maximumDistanceMeters == null || (maximumDistanceMeters.isFinite() && maximumDistanceMeters >= minimumDistanceMeters))
        require(checkpointRadiusMeters.isFinite() && checkpointRadiusMeters > 0)
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0)
        require(maximumGapMeters.isFinite() && maximumGapMeters > 0)
        require(minimumSpacingMeters.isFinite() && minimumSpacingMeters >= 0 && minimumSpacingMeters < maximumGapMeters)
    }
}

enum class RouteIssueCode { TOO_FEW_POINTS, MISSING_CHECKPOINT, CHECKPOINT_ORDER, DISTANCE_TOO_SHORT, DISTANCE_TOO_LONG, SPEED, SPARSE, DENSE }
data class RouteIssue(val code: RouteIssueCode, val message: String, val index: Int? = null)
data class CheckpointHit(val checkpointId: String, val routeDistanceMeters: Double, val segmentIndex: Int)
data class RouteValidationReport(
    val distanceMeters: Double,
    val averageSpeedMetersPerSecond: Double,
    val checkpointHits: List<CheckpointHit>,
    val issues: List<RouteIssue>,
) { val valid: Boolean get() = issues.isEmpty() }

object RunRouteValidator {
    fun validate(points: List<GeoPoint>, checkpoints: List<RunCheckpoint>, requirements: RouteRequirements): RouteValidationReport {
        val issues = mutableListOf<RouteIssue>()
        val cumulative = RunGeo.cumulative(points)
        val length = cumulative.lastOrNull() ?: 0.0
        val average = length / (requirements.durationMillis / 1000.0)
        if (points.size < 2 || length < 1e-8) issues += RouteIssue(RouteIssueCode.TOO_FEW_POINTS, AppText.Run.ROUTE_REQUIRES_TWO_DISTINCT_COORDINATES)
        if (length + 1e-6 < requirements.minimumDistanceMeters) issues += RouteIssue(RouteIssueCode.DISTANCE_TOO_SHORT, AppText.Run.ROUTE_TOO_SHORT_RECOMMENDED_PATH_EXTENSION_HINT)
        if (requirements.maximumDistanceMeters != null && length - 1e-6 > requirements.maximumDistanceMeters) issues += RouteIssue(RouteIssueCode.DISTANCE_TOO_LONG, AppText.Run.ROUTE_EXCEEDS_TARGET_DISTANCE)
        if (average > requirements.maximumSpeedMetersPerSecond + 1e-8) issues += RouteIssue(RouteIssueCode.SPEED, AppText.Run.TARGET_DURATION_AVERAGE_SPEED_LIMIT_EXCEEDED)
        for (i in 1 until points.size) {
            val gap = cumulative[i] - cumulative[i - 1]
            if (gap > requirements.maximumGapMeters + 1e-6) issues += RouteIssue(RouteIssueCode.SPARSE, AppText.Run.ROUTE_COORDINATES_TOO_SPARSE_INTERPOLATE_FIRST, i)
            if (gap < requirements.minimumSpacingMeters) issues += RouteIssue(RouteIssueCode.DENSE, AppText.Run.ROUTE_COORDINATES_TOO_DENSE_SIMPLIFY_FIRST, i)
        }
        val hits = mutableListOf<CheckpointHit>()
        var cursor = 0.0
        for ((index, checkpoint) in checkpoints.withIndex()) {
            val hit = firstHit(points, cumulative, checkpoint.point, requirements.checkpointRadiusMeters, cursor)
            if (hit != null) {
                cursor = hit.first
                hits += CheckpointHit(checkpoint.id, hit.first, hit.second)
            } else {
                val unorderedHit = firstHit(points, cumulative, checkpoint.point, requirements.checkpointRadiusMeters, 0.0)
                issues += if (unorderedHit != null) RouteIssue(RouteIssueCode.CHECKPOINT_ORDER, AppText.Run.CHECKPOINT_ORDER_MISMATCH_REROUTING_AVAILABLE, index)
                    else RouteIssue(RouteIssueCode.MISSING_CHECKPOINT, AppText.Run.CHECKPOINT_NOT_COVERED_REROUTING_AVAILABLE, index)
            }
        }
        return RouteValidationReport(length, average, hits, issues)
    }

    /** Earliest point inside the checkpoint circle, not merely the closest stored vertex. */
    internal fun firstHit(points: List<GeoPoint>, cumulative: DoubleArray, target: GeoPoint, radius: Double, after: Double): Pair<Double, Int>? {
        if (points.isEmpty()) return null
        if (points.size == 1) return if (after <= 0 && RunGeo.distanceMeters(points[0], target) <= radius) 0.0 to 0 else null
        for (i in 1 until points.size) {
            if (cumulative[i] + 1e-7 < after) continue
            val length = cumulative[i] - cumulative[i - 1]
            if (length < 1e-9) {
                if (cumulative[i] >= after && RunGeo.distanceMeters(points[i], target) <= radius) return cumulative[i] to (i - 1)
                continue
            }
            val lower = max(0.0, (after - cumulative[i - 1]) / length).coerceAtMost(1.0)
            val closest = RunGeo.projectionFraction(target, points[i - 1], points[i]).coerceAtLeast(lower)
            if (RunGeo.distanceMeters(target, RunGeo.interpolate(points[i - 1], points[i], closest)) > radius + 1e-7) continue
            var lo = lower
            var hi = closest
            if (RunGeo.distanceMeters(target, RunGeo.interpolate(points[i - 1], points[i], lower)) <= radius + 1e-7) hi = lower
            else repeat(40) {
                val mid = (lo + hi) / 2
                if (RunGeo.distanceMeters(target, RunGeo.interpolate(points[i - 1], points[i], mid)) <= radius) hi = mid else lo = mid
            }
            return cumulative[i - 1] + hi * length to (i - 1)
        }
        return null
    }
}

object RunRouteNormalizer {
    /** Explicit geometric interpolation: this does not establish that a straight segment is a real road. */
    fun normalize(points: List<GeoPoint>, minimumSpacingMeters: Double = 1.0, maximumSpacingMeters: Double = 40.0): List<GeoPoint> {
        require(minimumSpacingMeters.isFinite() && minimumSpacingMeters >= 0)
        require(maximumSpacingMeters.isFinite() && maximumSpacingMeters > minimumSpacingMeters)
        require(points.size <= RunRouteParser.MAX_POINTS) { AppText.Run.ROUTE_POINT_PROCESSING_LIMIT_EXCEEDED }
        if (points.size < 2) return points.toList()
        val thinned = mutableListOf(points.first())
        for (i in 1 until points.lastIndex) {
            if (RunGeo.distanceMeters(thinned.last(), points[i]) >= minimumSpacingMeters) thinned += points[i]
        }
        while (thinned.size > 1 && RunGeo.distanceMeters(thinned.last(), points.last()) < minimumSpacingMeters) thinned.removeAt(thinned.lastIndex)
        if (RunGeo.distanceMeters(thinned.last(), points.last()) > 1e-8) thinned += points.last()
        val result = mutableListOf(thinned.first())
        for (i in 1 until thinned.size) {
            val length = RunGeo.distanceMeters(thinned[i - 1], thinned[i])
            val piecesDouble = ceil(length / maximumSpacingMeters).coerceAtLeast(1.0)
            require(piecesDouble <= RunRouteParser.MAX_POINTS - result.size) { AppText.Run.INTERPOLATED_ROUTE_POINT_LIMIT_EXCEEDED }
            val pieces = piecesDouble.toInt()
            for (step in 1..pieces) result += RunGeo.interpolate(thinned[i - 1], thinned[i], step.toDouble() / pieces)
        }
        return result
    }

    /** Call this for imported GPX/KML segments: distant joins are never silently joined. */
    fun normalizeSegments(segments: List<List<GeoPoint>>, maximumJoinMeters: Double = 45.0): List<GeoPoint> {
        require(maximumJoinMeters.isFinite() && maximumJoinMeters > 0)
        val result = mutableListOf<GeoPoint>()
        for (segment in segments.filter { it.isNotEmpty() }) {
            require(result.isEmpty() || RunGeo.distanceMeters(result.last(), segment.first()) <= maximumJoinMeters + 1e-6) { AppText.Run.ROUTE_SEGMENT_JOIN_GAP_LIMIT_EXCEEDED }
            val normalized = normalize(segment)
            val same = result.isNotEmpty() && RunGeo.distanceMeters(result.last(), normalized.first()) < 1e-8
            result += if (same) normalized.drop(1) else normalized
            require(result.size <= RunRouteParser.MAX_POINTS) { AppText.Run.ROUTE_POINT_PROCESSING_LIMIT_EXCEEDED }
        }
        return result
    }
}
