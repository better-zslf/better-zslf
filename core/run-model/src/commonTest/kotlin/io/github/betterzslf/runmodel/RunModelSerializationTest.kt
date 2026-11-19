package io.github.betterzslf.runmodel

import io.github.betterzslf.feature.run.GeneratedRunTrack
import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunCheckpoint
import io.github.betterzslf.feature.run.RunGeo
import io.github.betterzslf.feature.run.RunLocationSample
import io.github.betterzslf.feature.run.RunTrackGenerator
import io.github.betterzslf.feature.run.RunTrackPoint
import io.github.betterzslf.feature.run.RunTrackValidator
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Preserve existing serialized names so moving a module does not invalidate saved real-run data. */
@OptIn(ExperimentalSerializationApi::class)
class RunModelSerializationTest {
    private val json = Json { encodeDefaults = true }
    private val origin = GeoPoint(113.4, 22.5)

    @Test fun geometryAndSampleSerializerNamesAndFieldsRemainCompatible() {
        assertEquals("io.github.betterzslf.feature.run.GeoPoint", GeoPoint.serializer().descriptor.serialName)
        assertEquals("io.github.betterzslf.feature.run.RunCheckpoint", RunCheckpoint.serializer().descriptor.serialName)
        assertEquals("io.github.betterzslf.feature.run.RunLocationSample", RunLocationSample.serializer().descriptor.serialName)
        val sample = RunLocationSample(origin, 1234L, 6.5, 1.2)
        val encoded = json.encodeToString(sample)
        assertEquals(setOf("point", "timestampMillis", "accuracyMeters", "speedMetersPerSecond"),
            json.parseToJsonElement(encoded).jsonObject.keys)
        assertEquals(sample, json.decodeFromString<RunLocationSample>(encoded))
        val checkpoint = RunCheckpoint("fixture-checkpoint", origin)
        assertEquals(checkpoint, json.decodeFromString<RunCheckpoint>(json.encodeToString(checkpoint)))
    }

    @Test fun canonicalTrackRetainsHistoricalNameAndWireFieldSpelling() {
        val samples = listOf(0L, 10_000L, 20_000L).mapIndexed { index, time ->
            RunLocationSample(RunGeo.offset(origin, index * 20.0, 0.0), time, 10.0)
        }
        val track = RunTrackGenerator.fromSamples(samples)
        assertEquals("io.github.betterzslf.feature.run.GeneratedRunTrack", GeneratedRunTrack.serializer().descriptor.serialName)
        assertEquals("io.github.betterzslf.feature.run.RunTrackPoint", RunTrackPoint.serializer().descriptor.serialName)
        assertEquals(setOf("point", "locatetime", "dis", "count"),
            json.parseToJsonElement(json.encodeToString(track.points.first())).jsonObject.keys)
        val encoded = json.encodeToString(track)
        assertEquals(setOf("points", "startTimeMillis", "endTimeMillis", "distanceMeters", "runTimeSeconds", "averageSpeedMetersPerSecond"),
            json.parseToJsonElement(encoded).jsonObject.keys)
        val restored = json.decodeFromString<GeneratedRunTrack>(encoded)
        assertEquals(track, restored)
        assertTrue(RunTrackValidator.validate(restored).valid)
    }

    @Test fun decodingCannotBypassCoordinateRangeValidation() {
        assertFailsWith<IllegalArgumentException> { json.decodeFromString<GeoPoint>("{\"longitude\":181.0,\"latitude\":0.0}") }
        assertFailsWith<IllegalArgumentException> { json.decodeFromString<GeoPoint>("{\"longitude\":0.0,\"latitude\":-91.0}") }
    }
}
