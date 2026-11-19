package io.github.betterzslf.feature.run

import kotlin.test.*

class RunRealTrackTest {
    @Test fun realSamplesUseTheSameCanonicalRepresentationWithoutGeneration() {
        val origin = GeoPoint(113.4, 22.5)
        val samples = listOf(0L, 10_000L, 20_000L).mapIndexed { i, t -> RunLocationSample(RunGeo.offset(origin, i * 20.0, 0.0), t, 10.0) }
        val track = RunTrackGenerator.fromSamples(samples)
        assertEquals(samples.map { it.point }, track.points.map { it.point })
        assertEquals(40.0, track.distanceMeters, 1e-6)
        assertTrue(RunTrackValidator.validate(track).valid)
        assertFailsWith<IllegalArgumentException> { RunTrackGenerator.fromSamples(samples.mapIndexed { i, p -> if (i == 1) p.copy(accuracyMeters = 51.0) else p }) }
    }

    /** Exercise only public core APIs from a real downstream module, without Pro preview inputs. */
    @Test fun downstreamConsumerKeepsWireConversionSeparateFromCanonicalRealSamples() {
        val origin = GeoPoint(116.397128, 39.916527)
        val samples = listOf(0L, 10_000L).mapIndexed { index, time ->
            RunLocationSample(RunGeo.offset(origin, index * 20.0, 0.0), time, 10.0)
        }
        val track = RunTrackGenerator.fromSamples(samples)
        val path = RunGeoPath(track.points.map { it.point })
        val distances = path.cumulativeDistances()
        distances.fill(-1.0)
        assertEquals(track.distanceMeters, path.lengthMeters, 1e-6)
        assertEquals(origin, path.pointAt(-1.0))
        assertEquals(samples.last().point, path.pointAt(1_000.0))
        val wire = wgs84ToGcj02WireLocation(track.points.first().location)
        assertNotEquals(track.points.first().location, wire)
        assertEquals(origin, track.points.first().point)
        assertEquals(samples.map { it.timestampMillis }, track.points.map { it.locatetime })
        assertTrue(RunTrackValidator.validate(track).valid)
    }
}
