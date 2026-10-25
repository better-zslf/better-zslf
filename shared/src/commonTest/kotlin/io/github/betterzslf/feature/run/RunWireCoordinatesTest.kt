package io.github.betterzslf.feature.run

import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.platform.RunMapCoordinates
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.math.abs
import kotlin.test.*

/** Pure wire-copy/MemoryKeyValueStore fixtures; no runtime, provider or remote connection. */
class RunWireCoordinatesTest {
    private val beijing = GeoPoint(116.397128, 39.916527)
    private val base = 1_000_000L
    private val metadata = RunMetadata("wire-fixture-run", "wire-fixture-schedule", "fixture-user-id", "Fixture name", "fixture-avatar")

    private fun samples(count: Int, origin: GeoPoint = beijing) = (0 until count).map {
        RunLocationSample(RunGeo.offset(origin, it * 20.0, 0.0), base + it * 10_000L, 7.0, 2.0)
    }

    private fun batches(count: Int, origin: GeoPoint = beijing) = RunBatcher.create(
        metadata, RunTrackGenerator.fromSamples(samples(count, origin)), "wire-fixture-batch",
    )

    private fun coordinates(body: JsonObject) = RunBatcher.json.decodeFromString<List<RunCoordinate>>(body.getValue("coordinate").jsonPrimitive.content)
    private fun point(location: String): GeoPoint {
        val parts = location.split(',')
        return GeoPoint(parts[0].toDouble(), parts[1].toDouble())
    }

    private fun assertOnlyLocationsChange(batch: RunUploadBatch, body: JsonObject) {
        val original = batch.payload.toJsonObject()
        assertEquals(original.keys, body.keys)
        assertEquals(original.filterKeys { it != "coordinate" }, body.filterKeys { it != "coordinate" })
        assertTrue(body.getValue("coordinate").jsonPrimitive.isString)
        for (key in listOf("runTime", "startTime", "endTime")) assertFalse(body.getValue(key).jsonPrimitive.isString)
        for (key in original.keys - setOf("runTime", "startTime", "endTime")) assertTrue(body.getValue(key).jsonPrimitive.isString)
        val canonical = RunBatcher.validateBatch(batch)
        val shifted = coordinates(body)
        assertEquals(canonical.size, shifted.size)
        for ((old, wire) in canonical.zip(shifted)) {
            assertEquals(old, wire.copy(location = old.location))
            assertEquals(RunMapCoordinates.wgs84ToGcj02(point(old.location)), point(wire.location))
        }
        val elements = Json.parseToJsonElement(body.getValue("coordinate").jsonPrimitive.content).jsonArray
        for (element in elements) {
            val fields = element.jsonObject
            assertEquals(setOf("location", "locatetime", "dis", "count"), fields.keys)
            assertTrue(fields.getValue("location").jsonPrimitive.isString)
            for (key in listOf("locatetime", "dis", "count")) assertFalse(fields.getValue(key).jsonPrimitive.isString)
        }
    }

    @Test fun mainlandGoldenChangesOnlyLocationsAndRetainsAllMetadataAndWireTypes() {
        val original = batches(5).single()
        val batch = original.copy(payload = original.payload.copy(startPoint = "fixture-start", endPoint = "fixture-end", sid = "fixture-sid", tid = "fixture-tid"))
        val body = batch.toGcj02WireBody()
        val first = point(coordinates(body).first().location)
        assertEquals(116.40337249402477, first.longitude, 1e-8)
        assertEquals(39.91793074924595, first.latitude, 1e-8)
        assertNotEquals(beijing, first)
        assertOnlyLocationsChange(batch, body)
        assertEquals(batch.batchId, body.getValue("batchId").jsonPrimitive.content)
        assertEquals("1", body.getValue("finishType").jsonPrimitive.content)
        assertEquals(metadata.userName, body.getValue("userName").jsonPrimitive.content)
        assertEquals(metadata.imgUrl, body.getValue("imgUrl").jsonPrimitive.content)
    }

    @Test fun outsideChinaIsIdentityIncludingOriginalNestedStringBytes() {
        for (origin in listOf(GeoPoint(10.0, 20.0), GeoPoint(-74.006, 40.7128), GeoPoint(151.2093, -33.8688))) {
            val original = batches(2, origin).single()
            val batch = original.copy(payload = original.payload.copy(coordinate = " \n${original.payload.coordinate}\n "))
            val before = RunBatcher.json.encodeToString(batch).encodeToByteArray()
            val body = batch.toGcj02WireBody()
            assertEquals(batch.payload.toJsonObject(), body)
            assertEquals(batch.payload.coordinate, body.getValue("coordinate").jsonPrimitive.content)
            assertContentEquals(before, RunBatcher.json.encodeToString(batch).encodeToByteArray())
        }
    }

    @Test fun fivePointBoundariesAndSingleOrFullFinishTailsKeepWgsPhysicalMeasurements() {
        for (count in listOf(2, 5, 6, 10, 11, 16)) {
            val native = samples(count)
            val canonical = batches(count)
            val bodies = canonical.map { it.toGcj02WireBody() }
            val expectedSizes = native.chunked(RunBatcher.BATCH_SIZE).map { it.size }
            assertEquals(expectedSizes, bodies.map { coordinates(it).size })
            assertEquals(canonical.indices.map { if (it == canonical.lastIndex) "1" else "0" }, bodies.map { it.getValue("finishType").jsonPrimitive.content })
            canonical.zip(bodies).forEach { (batch, body) -> assertOnlyLocationsChange(batch, body) }

            val wire = bodies.flatMap { coordinates(it) }
            var totalWgsMeters = 0.0
            for (index in native.indices) {
                val expectedDistance = if (index == 0) 0.0 else RunGeo.distanceMeters(native[index - 1].point, native[index].point)
                assertEquals(expectedDistance, wire[index].dis, 1e-9)
                assertEquals(native[index].timestampMillis, wire[index].locatetime)
                assertEquals(index * 10.0, wire[index].count)
                totalWgsMeters += wire[index].dis
                if ((index + 1) % RunBatcher.BATCH_SIZE == 0 || index == native.lastIndex) {
                    val body = bodies[index / RunBatcher.BATCH_SIZE]
                    assertEquals(totalWgsMeters, body.getValue("runLength").jsonPrimitive.content.toDouble(), 1e-9)
                    assertEquals((index * 10_000L).toString(), body.getValue("runTime").jsonPrimitive.content)
                    assertEquals(canonical[index / RunBatcher.BATCH_SIZE].payload.speed, body.getValue("speed").jsonPrimitive.content)
                }
            }
            if (count > RunBatcher.BATCH_SIZE) {
                assertTrue(coordinates(bodies[1]).first().dis > 0.0)
                assertEquals(native[5].timestampMillis, coordinates(bodies[1]).first().locatetime)
            }
            // GCJ numeric segment lengths differ: these must not replace the WGS physical metrics.
            assertTrue(wire.zipWithNext().any { (a, b) -> abs(RunGeo.distanceMeters(point(a.location), point(b.location)) - b.dis) > 1e-7 })
        }
    }

    @Test fun repeatedConstructionAlwaysStartsFromCanonicalAndNeverAddsASecondOffset() {
        val batch = batches(6).first()
        val canonicalBytes = RunBatcher.json.encodeToString(batch).encodeToByteArray()
        val once = batch.toGcj02WireBody()
        repeat(5) { assertEquals(once, batch.toGcj02WireBody()) }
        val oncePoint = point(coordinates(once).first().location)
        val twicePoint = RunMapCoordinates.wgs84ToGcj02(oncePoint)
        assertNotEquals(oncePoint, twicePoint)
        assertEquals(RunMapCoordinates.wgs84ToGcj02(beijing), oncePoint)
        assertContentEquals(canonicalBytes, RunBatcher.json.encodeToString(batch).encodeToByteArray())
    }

    @Test fun mappingPreservesSamplesJournalQueueAndCanonicalBatchBytesIncludingAfterReopen() = runTest {
        val native = samples(11)
        val canonical = RunBatcher.create(metadata, RunTrackGenerator.fromSamples(native), "wire-fixture-batch")
        val store = MemoryKeyValueStore()
        val journal = RunSessionJournal(store)
        journal.save(StoredRunSession(
            request = RunSessionStart(metadata, batchIdPrefix = "wire-fixture-batch"),
            phase = StoredRunSessionPhase.FINISHED,
            startedAtMillis = base,
            samples = native,
            finishedAtMillis = native.last().timestampMillis + 123L,
            lastConsentAtMillis = base,
        ))
        val queueKey = "fixture.wire.queue"
        val queue = RunUploadQueue(store, queueKey)
        queue.enqueue(canonical)
        val sampleBytes = RunBatcher.json.encodeToString(native).encodeToByteArray()
        val batchBytes = RunBatcher.json.encodeToString(canonical).encodeToByteArray()
        val journalBytes = assertNotNull(store.get(RunSessionJournal.STORAGE_KEY)).encodeToByteArray()
        val queueBytes = assertNotNull(store.get(queueKey)).encodeToByteArray()
        val beforeEntries = queue.snapshot()

        repeat(2) { beforeEntries.forEach { assertOnlyLocationsChange(it.batch, it.batch.toGcj02WireBody()) } }

        assertContentEquals(sampleBytes, RunBatcher.json.encodeToString(native).encodeToByteArray())
        assertContentEquals(batchBytes, RunBatcher.json.encodeToString(canonical).encodeToByteArray())
        assertContentEquals(journalBytes, assertNotNull(store.get(RunSessionJournal.STORAGE_KEY)).encodeToByteArray())
        assertContentEquals(queueBytes, assertNotNull(store.get(queueKey)).encodeToByteArray())
        assertEquals(native, journal.load().sessions.single().samples)
        assertEquals(beforeEntries, queue.snapshot())
        assertEquals(beforeEntries, RunUploadQueue(store, queueKey).snapshot())
        assertContentEquals(queueBytes, assertNotNull(store.get(queueKey)).encodeToByteArray())
    }

    @Test fun canonicalValidatorRejectsBadSchemaAndMeasurementsBeforeMapping() {
        val batch = batches(6).first()
        for (invalid in listOf(
            batch.copy(index = -1),
            batch.copy(payload = batch.payload.copy(coordinate = "not-json")),
            batch.copy(payload = batch.payload.copy(coordinate = "[]")),
            batch.copy(payload = batch.payload.copy(runTime = batch.payload.runTime + 1)),
            batch.copy(payload = batch.payload.copy(runLength = "NaN")),
            batch.copy(payload = batch.payload.copy(speed = "99")),
            batch.copy(payload = batch.payload.copy(finishType = "2")),
        )) assertFailsWith<IllegalArgumentException> { invalid.toGcj02WireBody() }
    }
}
