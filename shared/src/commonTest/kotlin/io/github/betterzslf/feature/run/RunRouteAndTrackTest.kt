package io.github.betterzslf.feature.run

import kotlin.math.abs
import kotlin.test.*

class RunRouteAndTrackTest {
    private val origin = GeoPoint(113.4, 22.5)
    private fun east(meters: Double) = RunGeo.offset(origin, meters, 0.0)
    private fun road(length: Int = 400): List<GeoPoint> = (0..length step 20).map { east(it.toDouble()) }
    private fun requirements(minimum: Double = 0.0, duration: Long = 200_000) = RouteRequirements(minimum, duration)

    @Test fun validatesOrderedCheckpointCoverageOnSegmentsRatherThanOnlyVertices() {
        val points = RunRouteNormalizer.normalize(listOf(origin, east(400.0)))
        val checkpoints = listOf(RunCheckpoint("a", east(120.0)), RunCheckpoint("b", east(320.0)))
        val result = RunRouteValidator.validate(points, checkpoints, requirements(399.0))
        assertTrue(result.valid, result.issues.toString())
        assertEquals(2, result.checkpointHits.size)
        assertTrue(result.checkpointHits[0].routeDistanceMeters < result.checkpointHits[1].routeDistanceMeters)
        assertEquals(70.0, result.checkpointHits[0].routeDistanceMeters, 0.001)
    }

    @Test fun detectsMissingAndOutOfOrderCheckpointsSeparately() {
        val result = RunRouteValidator.validate(road(), listOf(
            RunCheckpoint("later", east(300.0)), RunCheckpoint("earlier", east(50.0)),
            RunCheckpoint("off-road", RunGeo.offset(origin, 0.0, 200.0)),
        ), requirements())
        assertTrue(result.issues.any { it.code == RouteIssueCode.CHECKPOINT_ORDER && it.index == 1 })
        assertTrue(result.issues.any { it.code == RouteIssueCode.MISSING_CHECKPOINT && it.index == 2 })
    }

    @Test fun allFourImportValidationClassesHaveFailingExamples() {
        val sparse = listOf(origin, east(400.0))
        val result = RunRouteValidator.validate(sparse, listOf(RunCheckpoint("missing", RunGeo.offset(origin, 0.0, 200.0))), requirements(500.0, 10_000))
        assertTrue(result.issues.any { it.code == RouteIssueCode.MISSING_CHECKPOINT })
        assertTrue(result.issues.any { it.code == RouteIssueCode.DISTANCE_TOO_SHORT })
        assertTrue(result.issues.any { it.code == RouteIssueCode.SPEED })
        assertTrue(result.issues.any { it.code == RouteIssueCode.SPARSE })
        val dense = RunRouteValidator.validate(listOf(origin, origin, east(10.0)), emptyList(), requirements())
        assertTrue(dense.issues.any { it.code == RouteIssueCode.DENSE })
    }

    @Test fun normalizesDensityAndPreservesEndpoints() {
        val raw = listOf(origin, east(0.1), east(0.2), east(400.0), east(400.1))
        val dense = RunRouteNormalizer.normalize(raw)
        assertEquals(origin, dense.first())
        assertEquals(raw.last(), dense.last())
        assertTrue(dense.zipWithNext().all { (a, b) -> RunGeo.distanceMeters(a, b) <= 40.000001 })
        assertTrue(RunRouteValidator.validate(dense, emptyList(), requirements()).valid)
        assertTrue(RunRouteValidator.validate(emptyList(), emptyList(), requirements()).issues.any { it.code == RouteIssueCode.TOO_FEW_POINTS })
    }

    @Test fun rejectsImpossibleRequirementsAndTooLongRoutes() {
        assertFailsWith<IllegalArgumentException> { RouteRequirements(durationMillis = 0) }
        assertFailsWith<IllegalArgumentException> { RouteRequirements(Double.NaN, 1000) }
        val report = RunRouteValidator.validate(road(), emptyList(), RouteRequirements(durationMillis = 200_000, maximumDistanceMeters = 300.0))
        assertTrue(report.issues.any { it.code == RouteIssueCode.DISTANCE_TOO_LONG })
    }

    @Test fun detoursUseRoadsReserveLast50MetersAndStayWithinBudget() {
        val base = road()
        val originalLength = RunGeo.pathLengthMeters(base)
        val result = assertIs<DetourResult.Success>(RunDetourPlanner.extend(base, listOf(base), listOf(
            RunCheckpoint("a", east(50.0)), RunCheckpoint("b", east(150.0)), RunCheckpoint("end", east(400.0)),
        ), originalLength + 1500.0))
        assertEquals(base.first(), result.points.first())
        assertEquals(base.last(), result.points.last())
        assertEquals(50.0, RunGeo.distanceMeters(result.turnaroundPoint!!, base.last()), 0.001)
        assertTrue(result.distanceMeters <= originalLength + 1500.0 + 1e-5)
        assertTrue(result.remainingMeters < 0.01)
        assertTrue(result.points.zipWithNext().all { (a, b) -> RunGeo.distanceMeters(a, b) <= 45.000001 })
        assertTrue(result.visitedCheckpointIds.isNotEmpty())
        assertTrue(result.visitedCheckpointIds.zipWithNext().all { (a, b) -> a != b })
    }

    @Test fun detoursRejectDisconnectedPathsJumpingBasesAndTooSmallBudgets() {
        val base = road()
        assertIs<DetourResult.Failure>(RunDetourPlanner.extend(listOf(origin, east(400.0)), listOf(base), emptyList(), 1000.0))
        assertIs<DetourResult.Failure>(RunDetourPlanner.extend(base, listOf(road().map { RunGeo.offset(it, 0.0, 100.0) }), emptyList(), 1000.0))
        assertIs<DetourResult.Failure>(RunDetourPlanner.extend(base, emptyList(), emptyList(), 1000.0))
        assertIs<DetourResult.Failure>(RunDetourPlanner.extend(base, listOf(base), emptyList(), 300.0))
        assertIs<DetourResult.Failure>(RunDetourPlanner.extend(base, listOf(base), emptyList(), RunGeo.pathLengthMeters(base) + 20.0))
    }

    @Test fun reroutingVisitsCheckpointsInOrderAndRejectsDisconnectedRoads() {
        val base = road()
        val checkpoints = listOf(RunCheckpoint("far", east(300.0)), RunCheckpoint("back", east(100.0)))
        val result = assertIs<DetourResult.Success>(RunDetourPlanner.throughCheckpoints(origin, east(400.0), listOf(base), checkpoints))
        assertTrue(RunRouteValidator.validate(result.points, checkpoints, requirements(duration = 300_000)).valid)
        val detached = road().map { RunGeo.offset(it, 0.0, 200.0) }
        assertIs<DetourResult.Failure>(RunDetourPlanner.throughCheckpoints(origin, detached.last(), listOf(base, detached), emptyList()))
    }

    @Test fun generationIsSeededHasExactDurationAndTruthfulTotals() {
        val options = TrackGenerationOptions(1_000_000, 240_000, seed = 123)
        val first = RunTrackGenerator.generate(road(), options)
        assertEquals(first, RunTrackGenerator.generate(road(), options))
        assertNotEquals(first.points, RunTrackGenerator.generate(road(), options.copy(seed = 124)).points)
        assertEquals(1_240_000L, first.endTimeMillis)
        assertEquals(240.0, first.runTimeSeconds)
        assertEquals(first.distanceMeters / 240.0, first.averageSpeedMetersPerSecond, 1e-8)
        assertEquals(first.distanceMeters, first.points.sumOf { it.dis }, 1e-6)
        assertTrue(RunTrackValidator.validate(first).valid)
        assertEquals(road().first(), first.points.first().point)
        assertEquals(road().last(), first.points.last().point)
        assertTrue(first.points.zipWithNext().dropLast(1).all { (a, b) -> b.locatetime - a.locatetime in 9000L..11000L })
    }

    @Test fun randomWalkNoiseIsCorrelatedNotIndependentWhiteNoise() {
        val options = TrackGenerationOptions(0, 400_000, seed = 44, speedVariation = 0.0)
        val track = RunTrackGenerator.generate(road(), options)
        val offsets = track.points.drop(1).dropLast(1).map { sample ->
            val along = RunGeo.offset(origin, 400.0 * sample.locatetime / 400_000.0, 0.0)
            RunGeo.distanceMeters(along, sample.point)
        }
        assertTrue(offsets.all { it in 4.99..15.01 }, offsets.toString())
        assertTrue(offsets.zipWithNext().all { (a, b) -> abs(a - b) <= 3.0 })
    }

    @Test fun generationRejectsImpossibleSpeedAndValidatorDetectsTampering() {
        assertFailsWith<IllegalArgumentException> { RunTrackGenerator.generate(road(), TrackGenerationOptions(0, 1000)) }
        val track = RunTrackGenerator.generate(road(), TrackGenerationOptions(0, 240_000, maximumNoiseMeters = 0.0, minimumNoiseMeters = 0.0))
        assertFalse(RunTrackValidator.validate(track.copy(runTimeSeconds = 10.0)).valid)
        assertFalse(RunTrackValidator.validate(track.copy(points = track.points.mapIndexed { i, p -> if (i == 1) p.copy(dis = p.dis + 100) else p })).valid)
        assertFalse(RunTrackValidator.validate(track.copy(points = track.points.mapIndexed { i, p -> if (i == 1) p.copy(locatetime = 0) else p })).valid)
        assertFalse(RunTrackValidator.validate(track.copy(distanceMeters = Double.NaN)).valid)
    }

    @Test fun realSamplesUseTheSameCanonicalRepresentationWithoutGeneration() {
        val samples = listOf(0L, 10_000L, 20_000L).mapIndexed { i, t -> RunLocationSample(east(i * 20.0), t, 10.0) }
        val track = RunTrackGenerator.fromSamples(samples)
        assertEquals(samples.map { it.point }, track.points.map { it.point })
        assertEquals(40.0, track.distanceMeters, 1e-6)
        assertTrue(RunTrackValidator.validate(track).valid)
        assertFailsWith<IllegalArgumentException> { RunTrackGenerator.fromSamples(samples.mapIndexed { i, p -> if (i == 1) p.copy(accuracyMeters = 51.0) else p }) }
    }
}
