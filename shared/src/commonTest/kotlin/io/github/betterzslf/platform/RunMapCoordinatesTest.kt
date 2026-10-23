package io.github.betterzslf.platform

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunLocationSample
import kotlin.math.abs
import kotlin.test.*

/** Synthetic geographic vectors only; no native samples, provider calls or school requests. */
class RunMapCoordinatesTest {
    private val wgs = RunMapCoordinateSystem.WGS84
    private val gcj = RunMapCoordinateSystem.GCJ02
    private val beijing = GeoPoint(116.397128, 39.916527)
    private val interior = listOf(beijing, GeoPoint(113.264385, 23.129112),
        GeoPoint(121.473701, 31.230416), GeoPoint(104.066541, 30.572269), GeoPoint(87.6168, 43.8256))

    @Test fun beijingForwardMatchesThePublishedGcjTransformReference() {
        val shifted = RunMapCoordinates.wgs84ToGcj02(beijing)
        assertEquals(116.40337249402477, shifted.longitude, 1e-8)
        assertEquals(39.91793074924595, shifted.latitude, 1e-8)
        assertNotEquals(beijing, shifted)
    }

    @Test fun interiorForwardReverseRoundtripsWithinSmallAngularTolerance() {
        for (original in interior) {
            val shifted = RunMapCoordinates.wgs84ToGcj02(original)
            val restored = RunMapCoordinates.gcj02ToWgs84(shifted)
            assertEquals(original.longitude, restored.longitude, 1e-7)
            assertEquals(original.latitude, restored.latitude, 1e-7)
            assertTrue(abs(shifted.longitude - original.longitude) < 0.02)
            assertTrue(abs(shifted.latitude - original.latitude) < 0.02)
        }
    }

    @Test fun reverseForwardRoundtripAlsoPreservesInteriorGcjPoints() {
        for (original in interior) {
            val restored = RunMapCoordinates.gcj02ToWgs84(original)
            val shifted = RunMapCoordinates.wgs84ToGcj02(restored)
            assertEquals(original.longitude, shifted.longitude, 1e-7)
            assertEquals(original.latitude, shifted.latitude, 1e-7)
        }
    }

    @Test fun explicitFrameConversionSelectsTheCorrectDirection() {
        assertEquals(RunMapCoordinates.wgs84ToGcj02(beijing), RunMapCoordinates.convert(beijing, wgs, gcj))
        val shifted = RunMapCoordinates.wgs84ToGcj02(beijing)
        assertEquals(RunMapCoordinates.gcj02ToWgs84(shifted), RunMapCoordinates.convert(shifted, gcj, wgs))
    }

    @Test fun sameConfirmedFrameIsTheOriginalPointNotAnotherShift() {
        assertSame(beijing, RunMapCoordinates.convert(beijing, wgs, wgs))
        assertSame(beijing, RunMapCoordinates.convert(beijing, gcj, gcj))
    }

    @Test fun unknownFramesNeverSilentlyPassThroughEvenIfBothAreUnknown() {
        for (from in RunMapCoordinateSystem.entries) {
            assertNull(RunMapCoordinates.convert(beijing, from, RunMapCoordinateSystem.UNKNOWN))
            assertNull(RunMapCoordinates.convert(beijing, RunMapCoordinateSystem.UNKNOWN, from))
        }
    }

    @Test fun worldExtremesEquatorPolesAndDatelineAreIdentityOutsideChina() {
        val outside = listOf(GeoPoint(180.0, 90.0), GeoPoint(-180.0, -90.0), GeoPoint(180.0, -45.0),
            GeoPoint(-179.999999, 45.0), GeoPoint(0.0, 0.0), GeoPoint(-74.006, 40.7128), GeoPoint(151.2093, -33.8688))
        for (point in outside) {
            assertSame(point, RunMapCoordinates.wgs84ToGcj02(point))
            assertSame(point, RunMapCoordinates.gcj02ToWgs84(point))
            assertSame(point, RunMapCoordinates.convert(point, wgs, gcj))
            assertSame(point, RunMapCoordinates.convert(point, gcj, wgs))
        }
    }

    @Test fun pointsJustOutsideEveryBoundingEdgeRemainUnchanged() {
        val outside = listOf(GeoPoint(72.0039999999, 30.0), GeoPoint(137.8347000001, 30.0),
            GeoPoint(110.0, 0.8292999999), GeoPoint(110.0, 55.8271000001))
        for (point in outside) {
            assertEquals(point, RunMapCoordinates.wgs84ToGcj02(point))
            assertEquals(point, RunMapCoordinates.gcj02ToWgs84(point))
        }
    }

    @Test fun exactBoundingEdgesStayFiniteWithoutClaimingAGlobalInverseAcrossTheCutoff() {
        // The conventional rectangular outside-China cutoff is discontinuous. An inside
        // point may project just outside it; no universal inverse is claimed at that edge.
        val edges = listOf(GeoPoint(72.004, 30.0), GeoPoint(137.8347, 30.0),
            GeoPoint(110.0, 0.8293), GeoPoint(110.0, 55.8271))
        for (point in edges) {
            for (converted in listOf(RunMapCoordinates.wgs84ToGcj02(point), RunMapCoordinates.gcj02ToWgs84(point))) {
                assertTrue(converted.longitude.isFinite() && converted.longitude in -180.0..180.0)
                assertTrue(converted.latitude.isFinite() && converted.latitude in -90.0..90.0)
            }
        }
    }

    @Test fun conversionsDoNotChangeOriginalPointListsFramesOrNativeSampleMetadata() {
        val points = interior.toMutableList()
        val snapshot = points.toList()
        val sample = RunLocationSample(points.first(), 1_234_567L, 6.5, 1.2)
        val path = RunMapPath(points, wgs, listOf(points.take(2), points.drop(2)))
        val converted = points.map { assertNotNull(RunMapCoordinates.convert(it, wgs, gcj)) }
        assertEquals(snapshot, points)
        assertEquals(snapshot, path.points)
        assertEquals(wgs, path.coordinateSystem)
        assertEquals(listOf(snapshot.take(2), snapshot.drop(2)), path.segments)
        assertEquals(snapshot.first(), sample.point)
        assertEquals(1_234_567L, sample.timestampMillis)
        assertEquals(6.5, sample.accuracyMeters)
        assertEquals(1.2, sample.speedMetersPerSecond)
        assertNotEquals(snapshot.first(), converted.first())
    }

    @Test fun typedMapDiagnosticsDoNotIncludeKeysCoordinatesLabelsOrMarkerIds() {
        val key = "diagnostic_private_key_fixture"
        val id = "diagnostic_marker_identity"
        val label = "diagnostic_private_label"
        val path = RunMapPath(listOf(beijing), wgs)
        val checkpoint = RunMapCheckpoint(id, beijing, label, gcj)
        val model = RunMapModel(RunMapProvider.AMAP, key, actualPath = path, checkpoints = listOf(checkpoint), currentLocation = beijing)
        for (diagnostic in listOf(path.toString(), checkpoint.toString(), model.toString())) {
            for (privateValue in listOf(key, id, label, beijing.longitude.toString(), beijing.latitude.toString())) {
                assertFalse(diagnostic.contains(privateValue))
            }
        }
    }
}
