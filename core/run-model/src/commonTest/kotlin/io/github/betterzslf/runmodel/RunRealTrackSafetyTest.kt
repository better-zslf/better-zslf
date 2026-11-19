package io.github.betterzslf.runmodel

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.LocationFilterResult
import io.github.betterzslf.feature.run.LocationRejection
import io.github.betterzslf.feature.run.RunCheckpoint
import io.github.betterzslf.feature.run.RunCheckpointProgress
import io.github.betterzslf.feature.run.RunGeo
import io.github.betterzslf.feature.run.RunLocationFilter
import io.github.betterzslf.feature.run.RunLocationSample
import io.github.betterzslf.feature.run.RunTrackGenerator
import io.github.betterzslf.feature.run.RunTrackValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Public model API only: fixtures are numeric samples, never a native source or an upload queue. */
class RunRealTrackSafetyTest {
    private val origin = GeoPoint(113.4, 22.5)
    private val samples = listOf(0L, 10_000L, 20_000L).mapIndexed { index, time ->
        RunLocationSample(RunGeo.offset(origin, index * 20.0, 0.0), time, 10.0, 2.0)
    }

    @Test fun fromSamplesPreservesEveryOriginalCoordinateAndTimestamp() {
        val track = RunTrackGenerator.fromSamples(samples)
        assertEquals(samples.map { it.point }, track.points.map { it.point })
        assertEquals(samples.map { it.timestampMillis }, track.points.map { it.locatetime })
        assertEquals(listOf(0.0, 10.0, 20.0), track.points.map { it.count })
        assertEquals(3, track.points.size)
        assertEquals(0.0, track.points.first().dis)
        assertEquals(20.0, track.points[1].dis, 1e-6)
        assertEquals(20.0, track.points[2].dis, 1e-6)
        assertEquals(40.0, track.distanceMeters, 1e-6)
        assertEquals(20.0, track.runTimeSeconds)
        assertEquals(2.0, track.averageSpeedMetersPerSecond, 1e-7)
        assertTrue(RunTrackValidator.validate(track).valid)
    }

    @Test fun fromSamplesRejectsAnEntireTrackWhenAnySampleFailsTheFilter() {
        val invalidMiddle = listOf(
            samples[1].copy(timestampMillis = -1),
            samples[1].copy(timestampMillis = samples[0].timestampMillis),
            samples[1].copy(accuracyMeters = -1.0),
            samples[1].copy(accuracyMeters = Double.NaN),
            samples[1].copy(accuracyMeters = 50.001),
            samples[1].copy(speedMetersPerSecond = Double.POSITIVE_INFINITY),
            samples[1].copy(speedMetersPerSecond = -1.0),
            samples[1].copy(speedMetersPerSecond = 5.001),
            samples[1].copy(point = RunGeo.offset(origin, 500.0, 0.0)),
        )
        invalidMiddle.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                RunTrackGenerator.fromSamples(listOf(samples.first(), invalid, samples.last()))
            }
        }
        assertFailsWith<IllegalArgumentException> { RunTrackGenerator.fromSamples(emptyList()) }
        assertFailsWith<IllegalArgumentException> { RunTrackGenerator.fromSamples(samples.take(1)) }
    }

    @Test fun filterRejectsReportedAndInferredSpeedWithoutChangingTheLastAcceptedSample() {
        val filter = RunLocationFilter()
        val previous = samples.first()
        val rejected = samples[1].copy(point = RunGeo.offset(origin, 500.0, 0.0))
        assertEquals(LocationRejection.SPEED,
            assertIs<LocationFilterResult.Rejected>(filter.evaluate(previous, rejected)).reason)
        assertEquals(LocationRejection.NON_INCREASING_TIME,
            assertIs<LocationFilterResult.Rejected>(filter.evaluate(previous, previous)).reason)
        val accepted = assertIs<LocationFilterResult.Accepted>(filter.evaluate(previous, samples[1]))
        assertEquals(samples[1], accepted.sample)
        assertEquals(20.0, accepted.distanceMeters, 1e-6)
        assertEquals(2.0, accepted.inferredSpeedMetersPerSecond, 1e-7)
        assertEquals(origin, previous.point)
        assertEquals(0L, previous.timestampMillis)
    }

    @Test fun consistencyValidatorDetectsChangedMeasurementsTimesAndNonfiniteTotals() {
        val track = RunTrackGenerator.fromSamples(samples)
        val tampered = listOf(
            track.copy(runTimeSeconds = 10.0),
            track.copy(distanceMeters = Double.NaN),
            track.copy(averageSpeedMetersPerSecond = Double.POSITIVE_INFINITY),
            track.copy(points = track.points.mapIndexed { index, point ->
                if (index == 1) point.copy(dis = point.dis + 100) else point
            }),
            track.copy(points = track.points.mapIndexed { index, point ->
                if (index == 1) point.copy(locatetime = 0) else point
            }),
        )
        tampered.forEach { assertFalse(RunTrackValidator.validate(it).valid) }
    }

    @Test fun checkpointProgressIsOrderedAndAcknowledgmentsRequireTheExpectedIndex() {
        val first = RunCheckpoint("first", origin)
        val second = RunCheckpoint("second", RunGeo.offset(origin, 200.0, 0.0))
        val progress = RunCheckpointProgress(listOf(first, second))
        assertFalse(progress.observe(second.point))
        assertEquals(0, progress.nextIndex)
        assertFalse(progress.acknowledgePunch(1))
        assertTrue(progress.observe(first.point))
        assertEquals(1, progress.nextIndex)
        assertFalse(progress.acknowledgePunch(0))
        assertTrue(progress.observe(second.point))
        assertTrue(progress.finished)
        assertFalse(progress.acknowledgePunch(2))
    }
}
