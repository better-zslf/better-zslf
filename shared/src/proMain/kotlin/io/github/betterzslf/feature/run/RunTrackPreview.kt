package io.github.betterzslf.feature.run

import io.github.betterzslf.core.text.AppText
import kotlin.math.*
import kotlin.random.Random

/** Pro-only local preview options; never accepted by the real-GPS runtime. */
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

/** Existing deterministic preview algorithm, source-selected into Pro only. No IO or upload. */
fun RunTrackGenerator.generate(points: List<GeoPoint>, options: TrackGenerationOptions): GeneratedRunTrack {
    require(points.size in 2..RunRouteParser.MAX_POINTS) { AppText.Run.INVALID_ROUTE_POINT_COUNT }
    val cumulative = RunGeo.cumulative(points)
    val pathLength = cumulative.last()
    require(pathLength > 0) { AppText.Run.ROUTE_LENGTH_MUST_BE_POSITIVE }
    val durationSeconds = options.durationMillis / 1000.0
    val average = pathLength / durationSeconds
    require(average <= options.maximumSpeedMetersPerSecond + 1e-9) { AppText.Run.TARGET_DURATION_AVERAGE_SPEED_LIMIT_EXCEEDED }
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
    throw IllegalArgumentException(AppText.Run.GPS_RANDOM_WALK_SPEED_MARGIN_INSUFFICIENT_HINT)
}

private fun intervals(options: TrackGenerationOptions, random: Random): List<Long> {
    val lower = (options.intervalMillis * (1 - options.intervalVariation)).roundToLong().coerceAtLeast(1)
    val upper = (options.intervalMillis * (1 + options.intervalVariation)).roundToLong().coerceAtLeast(lower)
    require(options.durationMillis / upper < RunRouteParser.MAX_POINTS - 1) { AppText.Run.TARGET_DURATION_GENERATED_POINT_LIMIT_EXCEEDED }
    val result = mutableListOf<Long>()
    var remaining = options.durationMillis
    while (remaining > upper) {
        // Preserve a full-size final sample whenever the remaining interval permits it.
        val maximum = minOf(upper, remaining - lower).coerceAtLeast(lower)
        val value = if (maximum == lower) lower else random.nextLong(lower, maximum + 1)
        result += value
        remaining -= value
        require(result.size < RunRouteParser.MAX_POINTS - 1) { AppText.Run.TARGET_DURATION_GENERATED_POINT_LIMIT_EXCEEDED }
    }
    if (remaining > 0) result += remaining
    return result
}

private fun randomWalk(ideal: List<GeoPoint>, options: TrackGenerationOptions, random: Random): List<GeoPoint> {
    if (options.maximumNoiseMeters == 0.0) return ideal
    val angle = random.nextDouble() * 2 * PI
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
