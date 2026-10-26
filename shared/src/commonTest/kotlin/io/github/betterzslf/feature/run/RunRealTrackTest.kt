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
}
