package io.github.betterzslf.feature.run

import kotlinx.serialization.Serializable
import kotlin.math.*
import kotlin.random.Random

@Serializable
data class RunTrackPoint(val point: GeoPoint, val locatetime: Long, val dis: Double, val count: Double) {
    val location: String get() = "${point.longitude},${point.latitude}"
}

@Serializable
data class GeneratedRunTrack(
    val points: List<RunTrackPoint>,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val distanceMeters: Double,
    val runTimeSeconds: Double,
    val averageSpeedMetersPerSecond: Double,
)

data class TrackGenerationOptions(
    val startTimeMillis: Long,
    val durationMillis: Long,
    val seed: Int = 0,
    val intervalMillis: Long = 10_000,
    val intervalVariation: Double = 0.1,
    val speedVariation: Double = 0.1,
    val minimumNoiseMeters: Double = 5.0,
    val maximumNoiseMeters: Double = 15.0,
    val randomWalkStepMeters: Double = 2.0,
    val maximumSpeedMetersPerSecond: Double = 5.0,
) {
    init {
        require(startTimeMillis >= 0 && durationMillis > 0 && startTimeMillis <= Long.MAX_VALUE - durationMillis)
        require(intervalMillis in 1..3_600_000)
        require(intervalVariation.isFinite() && intervalVariation in 0.0..0.5)
        require(speedVariation.isFinite() && speedVariation in 0.0..0.5)
        require(minimumNoiseMeters.isFinite() && minimumNoiseMeters >= 0)
        require(maximumNoiseMeters.isFinite() && maximumNoiseMeters >= minimumNoiseMeters && maximumNoiseMeters <= 50)
        require(randomWalkStepMeters.isFinite() && randomWalkStepMeters > 0)
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0 && maximumSpeedMetersPerSecond <= 5)
    }
}

data class TrackConsistencyReport(val issues: List<String>) { val valid: Boolean get() = issues.isEmpty() }

object RunTrackValidator {
    fun validate(track: GeneratedRunTrack, maximumSpeedMetersPerSecond: Double = 5.0): TrackConsistencyReport {
        require(maximumSpeedMetersPerSecond.isFinite() && maximumSpeedMetersPerSecond > 0)
        val issues = mutableListOf<String>()
        val points = track.points
        if (points.size < 2) issues += "轨迹至少需要两个点"
        if (track.startTimeMillis < 0 || track.endTimeMillis <= track.startTimeMillis) issues += "起止时间异常"
        val elapsed = (track.endTimeMillis - track.startTimeMillis) / 1000.0
        if (!track.runTimeSeconds.isFinite() || track.runTimeSeconds <= 0 || abs(elapsed - track.runTimeSeconds) > 1e-6) issues += "用时与起止时间不一致"
        if (points.firstOrNull()?.locatetime != track.startTimeMillis || points.lastOrNull()?.locatetime != track.endTimeMillis) issues += "轨迹时间与起止时间不一致"
        if (points.firstOrNull()?.let { abs(it.dis) > 1e-8 || abs(it.count) > 1e-8 } == true) issues += "首点累计值应为零"
        var sum = 0.0
        for ((i, point) in points.withIndex()) {
            if (!point.dis.isFinite() || point.dis < 0 || !point.count.isFinite() || point.count < 0) issues += "第 $i 点距离或累计秒异常"
            if (point.locatetime < track.startTimeMillis || point.locatetime > track.endTimeMillis) issues += "第 $i 点时间超出范围"
            val count = (point.locatetime - track.startTimeMillis) / 1000.0
            if (abs(point.count - count) > 1e-6) issues += "第 $i 点累计秒与时间戳不一致"
            if (i > 0) {
                val previous = points[i - 1]
                val distance = RunGeo.distanceMeters(previous.point, point.point)
                sum += distance
                if (abs(distance - point.dis) > 0.01) issues += "第 $i 点距离与坐标不一致"
                val delta = point.locatetime - previous.locatetime
                if (delta <= 0) issues += "轨迹时间戳应严格递增"
                else if (distance / (delta / 1000.0) > maximumSpeedMetersPerSecond + 1e-6) issues += "第 $i 点速度超过上限"
            }
        }
        if (!track.distanceMeters.isFinite() || track.distanceMeters < 0 || abs(track.distanceMeters - sum) > 0.01) issues += "总距离与逐点距离不一致"
        if (!track.averageSpeedMetersPerSecond.isFinite() || elapsed <= 0 || abs(track.averageSpeedMetersPerSecond - sum / elapsed) > 1e-6) issues += "均速与距离用时不一致"
        if (track.averageSpeedMetersPerSecond > maximumSpeedMetersPerSecond + 1e-6) issues += "轨迹均速超过上限"
        return TrackConsistencyReport(issues)
    }
}

/** Deterministic local generation. This class neither sleeps nor sends or schedules any upload. */
object RunTrackGenerator {
    fun generate(points: List<GeoPoint>, options: TrackGenerationOptions): GeneratedRunTrack {
        require(points.size in 2..RunRouteParser.MAX_POINTS) { "路线点数异常" }
        val cumulative = RunGeo.cumulative(points)
        val pathLength = cumulative.last()
        require(pathLength > 0) { "路线长度应大于零" }
        val durationSeconds = options.durationMillis / 1000.0
        val average = pathLength / durationSeconds
        require(average <= options.maximumSpeedMetersPerSecond + 1e-9) { "目标用时对应的均速超过上限" }
        val random = Random(options.seed)
        val intervals = intervals(options, random)
        val weights = DoubleArray(intervals.size) { 1 + (random.nextDouble() * 2 - 1) * options.speedVariation }
        val minimumSpeed = average * (1 - options.speedVariation)
        val maximumSpeed = minOf(options.maximumSpeedMetersPerSecond, average * (1 + options.speedVariation))
        // Scale U(0.9,1.1) step velocities with a capped water-fill so the final distance is exact.
        var lo = 0.0
        var hi = max(1.0, average * 4)
        repeat(64) {
            val scale = (lo + hi) / 2
            val proposed = intervals.indices.sumOf { intervals[it] / 1000.0 * (weights[it] * scale).coerceIn(minimumSpeed, maximumSpeed) }
            if (proposed < pathLength) lo = scale else hi = scale
        }
        val progress = DoubleArray(intervals.size + 1)
        for (i in intervals.indices) progress[i + 1] = (progress[i] + intervals[i] / 1000.0 * (weights[i] * (lo + hi) / 2).coerceIn(minimumSpeed, maximumSpeed)).coerceAtMost(pathLength)
        progress[progress.lastIndex] = pathLength
        val ideal = progress.map { RunGeo.pointAt(points, cumulative, it) }
        // Retry bounded random walks, never silently attenuate requested noise or exceed the speed cap.
        repeat(32) {
            val noisy = randomWalk(ideal, options, random)
            val track = assemble(noisy, intervals, options.startTimeMillis)
            if (RunTrackValidator.validate(track, options.maximumSpeedMetersPerSecond).valid) return track
        }
        throw IllegalArgumentException("目标用时未给 GPS 随机游走留足速度余量，请增加用时或调整抖动参数")
    }

    /** Builds the identical canonical track representation from already accepted real GPS samples. */
    fun fromSamples(samples: List<RunLocationSample>, filter: RunLocationFilter = RunLocationFilter()): GeneratedRunTrack {
        require(samples.size in 2..RunRouteParser.MAX_POINTS) { "定位轨迹至少需要两个采样点" }
        var previous: RunLocationSample? = null
        for (sample in samples) {
            require(filter.evaluate(previous, sample) is LocationFilterResult.Accepted) { "真实轨迹中存在未通过阈值过滤的采样点" }
            previous = sample
        }
        val intervals = samples.zipWithNext().map { (a, b) -> b.timestampMillis - a.timestampMillis }
        return assemble(samples.map { it.point }, intervals, samples.first().timestampMillis)
    }

    private fun intervals(options: TrackGenerationOptions, random: Random): List<Long> {
        val lower = (options.intervalMillis * (1 - options.intervalVariation)).roundToLong().coerceAtLeast(1)
        val upper = (options.intervalMillis * (1 + options.intervalVariation)).roundToLong().coerceAtLeast(lower)
        require(options.durationMillis / upper < RunRouteParser.MAX_POINTS - 1) { "目标用时产生的点数超过上限" }
        val result = mutableListOf<Long>()
        var remaining = options.durationMillis
        while (remaining > upper) {
            // Preserve a full-size final sample whenever the remaining interval permits it.
            val maximum = minOf(upper, remaining - lower).coerceAtLeast(lower)
            val value = if (maximum == lower) lower else random.nextLong(lower, maximum + 1)
            result += value
            remaining -= value
            require(result.size < RunRouteParser.MAX_POINTS - 1) { "目标用时产生的点数超过上限" }
        }
        if (remaining > 0) result += remaining // A run may finish before the next nominal sample.
        return result
    }

    private fun randomWalk(ideal: List<GeoPoint>, options: TrackGenerationOptions, random: Random): List<GeoPoint> {
        if (options.maximumNoiseMeters == 0.0) return ideal
        var angle = random.nextDouble() * 2 * PI
        var radius = options.minimumNoiseMeters + random.nextDouble() * (options.maximumNoiseMeters - options.minimumNoiseMeters)
        var east = radius * cos(angle)
        var north = radius * sin(angle)
        return ideal.mapIndexed { index, point ->
            if (index == 0 || index == ideal.lastIndex) point else {
                east += (random.nextDouble() * 2 - 1) * options.randomWalkStepMeters
                north += (random.nextDouble() * 2 - 1) * options.randomWalkStepMeters
                radius = hypot(east, north)
                if (radius < 1e-9) { east = options.minimumNoiseMeters; north = 0.0; radius = hypot(east, north) }
                val bounded = radius.coerceIn(options.minimumNoiseMeters, options.maximumNoiseMeters)
                if (radius > 0) { east *= bounded / radius; north *= bounded / radius }
                RunGeo.offset(point, east, north)
            }
        }
    }

    private fun assemble(points: List<GeoPoint>, intervals: List<Long>, start: Long): GeneratedRunTrack {
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
