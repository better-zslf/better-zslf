package io.github.betterzslf.feature.run

import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.storage.SessionStore
import io.github.betterzslf.platform.RunMapCoordinates
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** MockEngine only: no production write or real network connection is created. */
class RunTransportContractTest {
    private fun batch(): RunUploadBatch {
        val points = (0..5).map { RunLocationSample(RunGeo.offset(GeoPoint(10.0, 20.0), it * 20.0, 0.0), it * 10_000L, 10.0) }
        return RunBatcher.create(RunMetadata("fixture-run", "fixture-schedule", "fixture-user-id"), RunTrackGenerator.fromSamples(points), "fixture-batch").first()
    }

    @Test fun productionAdapterUsesAllThreeRepositoryPathsAndPreservesWireJson() = runTest {
        val batch = batch()
        val paths = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("run.fixture.invalid", request.url.host)
            val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            assertEquals(batch.payload.coordinate, body["coordinate"]!!.jsonPrimitive.content)
            assertEquals(batch.batchId, body["batchId"]!!.jsonPrimitive.content)
            assertEquals(batch.payload.runTime.toString(), body["runTime"]!!.jsonPrimitive.content)
            respond("""{"code":200,"data":null,"success":true}""")
        })
        try {
            val transport = RunRepositoryTransport(fixture.repositories.run)
            for (endpoint in RunUploadEndpoint.entries) assertEquals(RunSendResult.Accepted(batch.batchId), transport.send(endpoint, batch))
            assertEquals(listOf("/run/errand/addErrandTrackByData", "/run/errand/syncSportData", "/run/errand/reportLocation"), paths)
        } finally { fixture.close() }
    }

    @Test fun onlyDefinitivelyMissingEndpointsEnableAutomaticFailover() = runTest {
        for (status in listOf(404, 405, 500, 502, 503)) {
            val fixture = ApiTestFixture(MockEngine { respond("fixture response", HttpStatusCode.fromValue(status)) })
            try {
                val result = RunRepositoryTransport(fixture.repositories.run).send(RunUploadEndpoint.PRIMARY, batch())
                if (status == 404 || status == 405) assertTrue(assertIs<RunSendResult.NotAccepted>(result).mayFailover)
                else assertIs<RunSendResult.Unknown>(result)
            } finally { fixture.close() }
        }
    }

    @Test fun businessRejectionAndAuthenticationFailureHaltInsteadOfFanningOut() = runTest {
        for ((status, body) in listOf(
            HttpStatusCode.OK to """{"code":400,"message":"fixture rejected","success":false}""",
            HttpStatusCode.Unauthorized to "fixture expired",
        )) {
            var calls = 0
            val fixture = ApiTestFixture(MockEngine { calls++; respond(body, status) })
            try {
                val result = assertIs<RunSendResult.NotAccepted>(RunRepositoryTransport(fixture.repositories.run).send(RunUploadEndpoint.PRIMARY, batch()))
                assertFalse(result.mayFailover)
                assertEquals(1, calls)
                if (status == HttpStatusCode.Unauthorized) assertNull(fixture.sessions.session.value)
            } finally { fixture.close() }
        }
    }

    @Test fun malformedResponseOrNetworkExceptionHasUncertainWriteOutcome() = runTest {
        val malformed = ApiTestFixture(MockEngine { respond("not-json") })
        try {
            assertIs<RunSendResult.Unknown>(RunRepositoryTransport(malformed.repositories.run).send(RunUploadEndpoint.PRIMARY, batch()))
        } finally { malformed.close() }
        val network = ApiTestFixture(MockEngine { throw IllegalStateException("fixture socket lost") })
        try {
            assertIs<RunSendResult.Unknown>(RunRepositoryTransport(network.repositories.run).send(RunUploadEndpoint.PRIMARY, batch()))
        } finally { network.close() }
    }

    @Test fun emptyTridIsRejectedLocallyWithoutStartingARequest() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; respond("{}") })
        try {
            val batch = batch().let { it.copy(payload = it.payload.copy(trid = "")) }
            assertFalse(assertIs<RunSendResult.NotAccepted>(RunRepositoryTransport(fixture.repositories.run).send(RunUploadEndpoint.PRIMARY, batch)).mayFailover)
            assertEquals(0, calls)
        } finally { fixture.close() }
    }

    private fun mainlandBatches(count: Int = 11): List<RunUploadBatch> {
        val samples = (0 until count).map {
            RunLocationSample(RunGeo.offset(GeoPoint(116.397128, 39.916527), it * 20.0, 0.0), 1_000_000L + it * 10_000L, 7.0, 2.0)
        }
        return RunBatcher.create(RunMetadata("wire-run-$count", "wire-schedule", "fixture-user-id", "Fixture name", "fixture-avatar"),
            RunTrackGenerator.fromSamples(samples), "wire-batch-$count")
    }

    @Test fun allThreeBoundRepositoryPathsReceiveGcjCopiesAcrossFivePointBoundariesAndTails() = runTest {
        val batches = listOf(6, 10, 11).flatMap { mainlandBatches(it) }
        val beforeBytes = RunBatcher.json.encodeToString(batches).encodeToByteArray()
        val paths = mutableListOf<String>()
        val bodies = mutableListOf<JsonObject>()
        lateinit var expected: RunUploadBatch
        val fixture = ApiTestFixture(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("run.fixture.invalid", request.url.host)
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
            paths += request.url.encodedPath
            bodies += body
            assertEquals(expected.payload.toJsonObject().filterKeys { it != "coordinate" }, body.filterKeys { it != "coordinate" })
            assertTrue(body.getValue("coordinate").jsonPrimitive.isString)
            val wire = RunBatcher.json.decodeFromString<List<RunCoordinate>>(body.getValue("coordinate").jsonPrimitive.content)
            val canonical = RunBatcher.validateBatch(expected)
            assertEquals(canonical.size, wire.size)
            for ((old, converted) in canonical.zip(wire)) {
                val parts = old.location.split(',')
                val shifted = RunMapCoordinates.wgs84ToGcj02(GeoPoint(parts[0].toDouble(), parts[1].toDouble()))
                assertEquals("${shifted.longitude},${shifted.latitude}", converted.location)
                assertEquals(old, converted.copy(location = old.location))
            }
            if (expected.index == 0) {
                val parts = wire.first().location.split(',')
                assertEquals(116.40337249402477, parts[0].toDouble(), 1e-8)
                assertEquals(39.91793074924595, parts[1].toDouble(), 1e-8)
            }
            // No remote batchId/score/receipt is supplied: Accepted below remains a local code=200 mapping.
            respond("""{"code":200,"data":null}""")
        })
        try {
            val boundRepo = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            val transport = RunRepositoryTransport(boundRepo)
            val queueKey = "fixture.wire.transport.queue"
            val queue = RunUploadQueue(fixture.storage, queueKey)
            queue.enqueue(batches)
            val queueBytes = assertNotNull(fixture.storage.get(queueKey)).encodeToByteArray()
            val queueBefore = queue.snapshot()
            for (endpoint in RunUploadEndpoint.entries) for (batch in batches) {
                expected = batch
                assertEquals(RunSendResult.Accepted(batch.batchId), transport.send(endpoint, batch))
            }
            assertEquals(listOf("/run/errand/addErrandTrackByData", "/run/errand/syncSportData", "/run/errand/reportLocation").flatMap { path -> List(batches.size) { path } }, paths)
            val firstBodies = bodies.take(batches.size)
            assertEquals(firstBodies, bodies.drop(batches.size).take(batches.size))
            assertEquals(firstBodies, bodies.takeLast(batches.size))
            assertContentEquals(beforeBytes, RunBatcher.json.encodeToString(batches).encodeToByteArray())
            assertContentEquals(queueBytes, assertNotNull(fixture.storage.get(queueKey)).encodeToByteArray())
            assertEquals(queueBefore, RunUploadQueue(fixture.storage, queueKey).snapshot())
        } finally { fixture.close() }
    }

    @Test fun invalidCanonicalCoordinatesOrMetricsStopLocallyForEveryEndpointWithZeroHttp() = runTest {
        val batch = mainlandBatches().first()
        val coordinates = RunBatcher.validateBatch(batch)
        fun withCoordinates(points: List<RunCoordinate>) = batch.copy(payload = batch.payload.copy(coordinate = RunBatcher.json.encodeToString(points)))
        val invalid = listOf(
            batch.copy(index = -1),
            batch.copy(payload = batch.payload.copy(coordinate = "not-json")),
            batch.copy(payload = batch.payload.copy(coordinate = "[]")),
            batch.copy(payload = batch.payload.copy(coordinate = batch.payload.coordinate.replaceFirst("\"dis\":0.0", "\"dis\":NaN"))),
            withCoordinates(coordinates.dropLast(1)), // A non-finish block must still contain five points.
            withCoordinates(coordinates + coordinates.last()),
            batch.copy(payload = batch.payload.copy(runTime = batch.payload.runTime + 1)),
            batch.copy(payload = batch.payload.copy(runLength = "NaN")),
            batch.copy(payload = batch.payload.copy(speed = "99")),
            batch.copy(payload = batch.payload.copy(finishType = "2")),
        ) + listOf("181.0,39.0", "116.0,91.0", "NaN,39.0", "116.0,Infinity", "116.0", "116.0,39.0,0", "bad,39.0").map { location ->
            withCoordinates(coordinates.mapIndexed { index, point -> if (index == 0) point.copy(location = location) else point })
        } + listOf(
            withCoordinates(coordinates.mapIndexed { index, point -> if (index == 0) point.copy(count = 1.0) else point }),
            withCoordinates(coordinates.mapIndexed { index, point -> if (index == 0) point.copy(dis = -1.0) else point }),
            withCoordinates(coordinates.mapIndexed { index, point -> if (index == 1) point.copy(locatetime = coordinates[0].locatetime, count = 0.0) else point }),
        )
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; error("Invalid canonical payload must stay local") })
        try {
            val transport = RunRepositoryTransport(assertNotNull(fixture.repositories.run.boundToCurrentSession()))
            for (endpoint in RunUploadEndpoint.entries) for (bad in invalid) {
                val beforeBytes = RunBatcher.json.encodeToString(bad).encodeToByteArray()
                val result = assertIs<RunSendResult.NotAccepted>(transport.send(endpoint, bad))
                assertFalse(result.mayFailover)
                assertContentEquals(beforeBytes, RunBatcher.json.encodeToString(bad).encodeToByteArray())
            }
            assertEquals(0, calls)
            assertNotNull(fixture.sessions.session.value)
        } finally { fixture.close() }
    }

    @Test fun suppliedBoundRepositoryKeepsOwnerAndTokenGuardsWithZeroHttpAfterSessionChanges() = runTest {
        for (change in 0..2) {
            var calls = 0
            val fixture = ApiTestFixture(MockEngine { calls++; error("Stale or wrong-owner operation must stay local") })
            try {
                val boundRepo = assertNotNull(fixture.repositories.run.boundToCurrentSession())
                val transport = RunRepositoryTransport(boundRepo)
                val batch = mainlandBatches().first()
                val wrongOwner = batch.copy(payload = batch.payload.copy(userId = "other-fixture-owner"))
                for (endpoint in RunUploadEndpoint.entries) assertFalse(assertIs<RunSendResult.NotAccepted>(transport.send(endpoint, wrongOwner)).mayFailover)
                when (change) {
                    0 -> fixture.sessions.save(fixtureSession("replacement-fixture-token"))
                    1 -> fixture.sessions.save(fixtureSession().copy(userInfo = buildJsonObject { put("id", "other-fixture-owner") }))
                    else -> fixture.sessions.clear()
                }
                val replacement = fixture.sessions.session.value
                val storedReplacement = fixture.storage.get(SessionStore.STORAGE_KEY)
                for (endpoint in RunUploadEndpoint.entries) assertFalse(assertIs<RunSendResult.NotAccepted>(transport.send(endpoint, batch)).mayFailover)
                assertEquals(0, calls)
                assertEquals("fixture-user-id", boundRepo.boundOwnerId)
                assertSame(replacement, fixture.sessions.session.value)
                assertEquals(storedReplacement, fixture.storage.get(SessionStore.STORAGE_KEY))
            } finally { fixture.close() }
        }
    }

    @Test fun delayedOldBoundHttpOrEnvelope401NeverClearsANewSessionOrRetargetsTheNextBatch() = runTest {
        for (endpoint in RunUploadEndpoint.entries) for (envelope in listOf(false, true)) for (changeOwner in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val batch = mainlandBatches().first()
            val fixture = ApiTestFixture(MockEngine { request ->
                calls++
                assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
                val body = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                assertEquals("fixture-user-id", body.getValue("userId").jsonPrimitive.content)
                assertNotEquals(batch.payload.coordinate, body.getValue("coordinate").jsonPrimitive.content)
                entered.complete(Unit)
                release.await()
                if (envelope) respond("""{"code":401,"message":"fixture expired"}""") else respond("", HttpStatusCode.Unauthorized)
            })
            try {
                val boundRepo = assertNotNull(fixture.repositories.run.boundToCurrentSession())
                val transport = RunRepositoryTransport(boundRepo)
                val pending = async { transport.send(endpoint, batch) }
                entered.await()
                val replacement = fixtureSession("replacement-fixture-token").let { session ->
                    if (changeOwner) session.copy(userInfo = buildJsonObject { put("id", "other-fixture-owner") }) else session
                }
                fixture.sessions.save(replacement)
                val storedReplacement = fixture.storage.get(SessionStore.STORAGE_KEY)
                release.complete(Unit)
                assertFalse(assertIs<RunSendResult.NotAccepted>(pending.await()).mayFailover)
                assertSame(replacement, fixture.sessions.session.value)
                assertEquals(storedReplacement, fixture.storage.get(SessionStore.STORAGE_KEY))
                assertEquals("fixture-user-id", boundRepo.boundOwnerId)
                // The adapter must retain the supplied old binding rather than find a fresh global repo.
                assertFalse(assertIs<RunSendResult.NotAccepted>(transport.send(endpoint, batch)).mayFailover)
                assertEquals(1, calls)
                assertEquals("replacement-fixture-token", fixture.sessions.session.value?.token)
            } finally { release.complete(Unit); fixture.close() }
        }
    }
}
