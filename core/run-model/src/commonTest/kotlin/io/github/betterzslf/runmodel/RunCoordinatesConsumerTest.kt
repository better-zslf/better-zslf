package io.github.betterzslf.runmodel

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunLocationSample
import io.github.betterzslf.feature.run.wgs84ToGcj02WireLocation
import io.github.betterzslf.platform.RunMapCoordinateSystem
import io.github.betterzslf.platform.RunMapCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Pure public coordinate API: no map UI, provider SDK, HTTP body, batch or real request is involved. */
class RunCoordinatesConsumerTest {
    private val beijing = GeoPoint(116.397128, 39.916527)
    private val wgs = RunMapCoordinateSystem.WGS84
    private val gcj = RunMapCoordinateSystem.GCJ02

    @Test fun publishedForwardVectorAndInteriorInverseStayStable() {
        val shifted = RunMapCoordinates.wgs84ToGcj02(beijing)
        assertEquals(116.40337249402477, shifted.longitude, 1e-8)
        assertEquals(39.91793074924595, shifted.latitude, 1e-8)
        val restored = RunMapCoordinates.gcj02ToWgs84(shifted)
        assertEquals(beijing.longitude, restored.longitude, 1e-7)
        assertEquals(beijing.latitude, restored.latitude, 1e-7)
        assertEquals(shifted, RunMapCoordinates.convert(beijing, wgs, gcj))
        assertSame(beijing, RunMapCoordinates.convert(beijing, wgs, wgs))
    }

    @Test fun unknownFramesAlwaysRefuseConversionRatherThanGuessing() {
        for (frame in RunMapCoordinateSystem.entries) {
            assertNull(RunMapCoordinates.convert(beijing, frame, RunMapCoordinateSystem.UNKNOWN))
            assertNull(RunMapCoordinates.convert(beijing, RunMapCoordinateSystem.UNKNOWN, frame))
        }
    }

    @Test fun outsideChinaIsIdentityIncludingTheOriginalWireSpelling() {
        val original = GeoPoint(-74.006, 40.7128)
        assertSame(original, RunMapCoordinates.wgs84ToGcj02(original))
        assertSame(original, RunMapCoordinates.gcj02ToWgs84(original))
        for (location in listOf("-74.006000,40.712800", " -74.006,40.7128 ", "0.0,0.0")) {
            assertEquals(location, wgs84ToGcj02WireLocation(location))
        }
    }

    @Test fun wireCopyChangesOnlyLocationAndLeavesSampleMetadataUntouched() {
        val sample = RunLocationSample(beijing, 1_234_567L, 6.5, 1.2)
        val expected = RunMapCoordinates.wgs84ToGcj02(sample.point)
        val wire = wgs84ToGcj02WireLocation("${sample.point.longitude},${sample.point.latitude}")
        assertEquals("${expected.longitude},${expected.latitude}", wire)
        assertNotEquals("${beijing.longitude},${beijing.latitude}", wire)
        assertEquals(beijing, sample.point)
        assertEquals(1_234_567L, sample.timestampMillis)
        assertEquals(6.5, sample.accuracyMeters)
        assertEquals(1.2, sample.speedMetersPerSecond)
    }

    @Test fun wireLocationDoesNotAcceptMissingExtraNonfiniteOrOutOfRangeCoordinates() {
        for (location in listOf("", "116.4", "116.4,39.9,0", "NaN,0", "0,Infinity", "181,0", "0,-91")) {
            assertFailsWith<IllegalArgumentException> { wgs84ToGcj02WireLocation(location) }
        }
    }
}
