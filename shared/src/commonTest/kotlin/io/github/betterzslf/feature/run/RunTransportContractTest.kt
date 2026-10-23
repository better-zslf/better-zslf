package io.github.betterzslf.feature.run

import io.github.betterzslf.core.net.ApiTestFixture
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
}
