package io.github.betterzslf.runmodel

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunGeo
import io.github.betterzslf.feature.run.RunGeoPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** A different-package consumer uses only the finite public geometry surface, not internal arrays. */
class RunGeoPathConsumerTest {
    private val origin = GeoPoint(113.4, 22.5)

    @Test fun pathSnapshotsItsInputAndNeverExportsItsCumulativeStorage() {
        val middle = RunGeo.offset(origin, 20.0, 0.0)
        val end = RunGeo.offset(origin, 40.0, 0.0)
        val source = mutableListOf(origin, middle, end)
        val path = RunGeoPath(source)
        source.clear()
        val distances = path.cumulativeDistances()
        assertEquals(3, path.size)
        assertEquals(40.0, path.lengthMeters, 1e-6)
        assertEquals(20.0, distances[1], 1e-6)
        distances.fill(-1.0)
        assertEquals(0.0, path.cumulativeDistances().first())
        assertEquals(40.0, path.cumulativeDistances().last(), 1e-6)
        assertSame(origin, path.pointAt(-1.0))
        assertSame(end, path.pointAt(100.0))
        assertEquals(10.0, RunGeo.distanceMeters(origin, path.pointAt(10.0)), 1e-6)
    }

    @Test fun emptyPathIsValidForInspectionButCannotProduceAPoint() {
        val empty = RunGeoPath(emptyList())
        assertEquals(0, empty.size)
        assertEquals(0.0, empty.lengthMeters)
        assertTrue(empty.cumulativeDistances().isEmpty())
        assertFailsWith<IllegalArgumentException> { empty.pointAt(0.0) }
        val single = RunGeoPath(listOf(origin))
        assertSame(origin, single.pointAt(0.0))
        assertSame(origin, single.pointAt(-100.0))
        assertSame(origin, single.pointAt(100.0))
    }

    @Test fun pointAtRejectsNonfiniteDistancesRatherThanGuessingAnEndpoint() {
        val path = RunGeoPath(listOf(origin, RunGeo.offset(origin, 20.0, 0.0)))
        for (distance in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { path.pointAt(distance) }
        }
    }

    @Test fun interpolationFollowsTheShortArcAcrossTheDateLine() {
        val start = GeoPoint(179.9, 10.0)
        val end = GeoPoint(-179.9, 10.0)
        val path = RunGeoPath(listOf(start, end))
        val middle = path.pointAt(path.lengthMeters / 2)
        assertTrue(kotlin.math.abs(middle.longitude) > 179.9)
        assertEquals(path.lengthMeters / 2, RunGeo.distanceMeters(start, middle), 1e-6)
        assertEquals(path.lengthMeters / 2, RunGeo.distanceMeters(middle, end), 1e-6)
    }

    @Test fun projectionIsBoundedAndAntipodalArcsRemainExplicitlyInvalid() {
        val end = RunGeo.offset(origin, 200.0, 0.0)
        val midpoint = RunGeo.interpolate(origin, end, 0.5)
        val target = RunGeo.offset(midpoint, 0.0, 50.0)
        assertEquals(0.5, RunGeo.projectionFraction(target, origin, end), 1e-4)
        assertEquals(0.0, RunGeo.projectionFraction(target, origin, origin))
        assertTrue(RunGeo.projectionFraction(origin, origin, end) in 0.0..1.0)
        assertFailsWith<IllegalArgumentException> {
            RunGeo.projectionFraction(GeoPoint(90.0, 1.0), GeoPoint(0.0, 0.0), GeoPoint(180.0, 0.0))
        }
    }

    @Test fun coordinateValidationRetainsFiniteWorldBounds() {
        for (point in listOf(GeoPoint(-180.0, -90.0), GeoPoint(180.0, 90.0))) {
            assertTrue(point.longitude.isFinite() && point.latitude.isFinite())
        }
        for ((longitude, latitude) in listOf(Double.NaN to 0.0, 0.0 to Double.POSITIVE_INFINITY,
            180.001 to 0.0, 0.0 to -90.001)) {
            assertFailsWith<IllegalArgumentException> { GeoPoint(longitude, latitude) }
        }
    }
}
