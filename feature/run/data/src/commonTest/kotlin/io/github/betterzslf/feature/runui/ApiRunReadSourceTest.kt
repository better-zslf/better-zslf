package io.github.betterzslf.feature.runui

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

private fun taskData(vararg rows: JsonElement) = buildJsonObject { put("errandList", JsonArray(rows.toList())) }
private fun envelope(data: JsonElement) = buildJsonObject { put("code", 200); put("data", data) }.toString()

class ApiRunReadSourceTest {
    @Test fun sixReadPathsUseExactObservedParametersAndCapturedAuthorizationWithoutImplicitRequests() = runTest {
        val queries = mutableListOf<Map<String, String>>()
        val paths = mutableListOf<String>()
        val fixture = RunDataFixture(MockEngine { request ->
            paths += request.url.encodedPath
            queries += request.url.parameters.entries().associate { it.key to it.value.single() }
            assertEquals(HttpMethod.Post, request.method); assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            assertEquals(8_000L, request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
            val data = when (request.url.encodedPath) {
                "/run/errand/getErrandList" -> Json.parseToJsonElement("""{"errandList":[{"errandId":"e","id":"s"},null,{"errandId":"e","id":"s"}]}""")
                "/run/errand/getErrandDetail" -> Json.parseToJsonElement("""{"errandDetail":{"errandId":"e","scheduleId":"s","geoCoorList":[[0,0]],"minimumDistanceMeters":0}}""")
                "/run/errand/getUserErrandTrackRecord" -> Json.parseToJsonElement("""{"userErrandTrackRecord":[]}""")
                "/run/errand/getTrackRecordByTrid" -> Json.parseToJsonElement("""{"recordMap":""}""")
                "/run/errand/getTrackByTrid" -> Json.parseToJsonElement("""{"trackPointList":[]}""")
                else -> error("Unexpected read-only fixture endpoint")
            }
            respond(envelope(data))
        })
        try {
            val source = assertNotNull(ApiRunReadSource.capture(fixture.api))
            assertEquals("owner", source.boundOwnerId); assertTrue(source.isCurrentSession()); assertTrue(paths.isEmpty())
            val tasks = assertIs<RunReadOutcome.Success<RunRows<RunTaskItem>>>(source.tasks("owner")).data
            assertTrue(tasks.recognized); assertEquals(3, tasks.rawSize); assertEquals(2, tasks.items.size)
            assertEquals(tasks.items[0].identityKey, tasks.items[1].identityKey)
            val detail = assertIs<RunReadOutcome.Success<RunReadValue<RunTaskDetail>>>(source.detail("e", "owner", "s")).data
            assertTrue(detail.recognized); assertEquals(0.0, assertNotNull(detail.value).minimumDistanceMeters)
            assertEquals(1, detail.value?.checkpointSourceCount); assertTrue(detail.value?.canStart == true)
            assertIs<RunReadOutcome.Success<RunRows<RunRecordItem>>>(source.records("owner"))
            assertIs<RunReadOutcome.Success<RunRows<RunRecordItem>>>(source.records("owner", "s"))
            val record = assertIs<RunReadOutcome.Success<RunReadValue<RunRecordItem>>>(source.record("t")).data
            assertTrue(record.recognized); assertNull(record.value); assertTrue(record.warnings.isEmpty())
            val track = assertIs<RunReadOutcome.Success<RunRows<io.github.betterzslf.feature.run.GeoPoint>>>(source.track("t")).data
            assertTrue(track.recognized); assertTrue(track.items.isEmpty())
            assertEquals(listOf(
                mapOf("userId" to "owner", "offset" to "0", "limit" to "20", "isFinish" to "0"),
                mapOf("errandId" to "e", "userId" to "owner", "errandScheduleId" to "s"),
                mapOf("userId" to "owner"), mapOf("errandScheduleId" to "s", "userId" to "owner", "offset" to "0", "limit" to "10"),
                mapOf("trid" to "t"), mapOf("trid" to "t"),
            ), queries)
            assertEquals(6, paths.size); assertEquals(5, paths.toSet().size)
            assertFalse(source.toString().contains("owner")); assertFalse(source.toString().contains("fixture-token"))
        } finally { fixture.close() }
    }

    @Test fun taskExactIdentityIsOpaqueAndStableAcrossBranchesRefreshesAndRawEqualityButNotDifferentSchedules() = runTest {
        val rows = listOf(
            """{"title":"Same","privateExtra":{"b":2,"a":1},"isFinish":0}""",
            """{"status":"changed","privateExtra":{"a":1,"b":2},"title":"Same","isFinish":1}""",
            """{"title":"Different","privateExtra":{"a":1,"b":2}}""",
            """{"trid":"server-trid","errandId":"e","scheduleId":"a","startTime":"9","endTime":"10"}""",
            """{"trid":"server-trid","errandId":"e","scheduleId":"b","startTime":"9","endTime":"10"}""",
        ).map(Json::parseToJsonElement)
        val fixture = RunDataFixture(MockEngine { respond(envelope(taskData(*rows.toTypedArray()))) })
        try {
            val source = assertNotNull(ApiRunReadSource.capture(fixture.api))
            val first = assertIs<RunReadOutcome.Success<RunRows<RunTaskItem>>>(source.tasks("owner", isFinish = "0")).data.items
            assertEquals(first[0].identityKey, first[1].identityKey)
            assertNotEquals(first[0].identityKey, first[2].identityKey); assertNotEquals(first[3].identityKey, first[4].identityKey)
            val second = assertIs<RunReadOutcome.Success<RunRows<RunTaskItem>>>(source.tasks("owner", isFinish = "1")).data.items
            val refreshed = assertIs<RunReadOutcome.Success<RunRows<RunTaskItem>>>(source.tasks("owner", offset = 0)).data.items
            assertEquals(first.map { it.identityKey }, second.map { it.identityKey }); assertEquals(first.map { it.identityKey }, refreshed.map { it.identityKey })
            first.forEach { assertFalse(it.identityKey.contains("privateExtra")); assertFalse(it.identityKey.contains("server-trid")); assertFalse(it.identityKey.contains("{")) }
        } finally { fixture.close() }
    }

    @Test fun presenceDisclosureAndListMinimumFollowTheOriginalDataPoliciesWithoutLeakingRawJson() = runTest {
        val data = Json.parseToJsonElement("""{"errandList":[
            {"errandId":null,"scheduleId":false,"eName":"Present invalid","minimumDistanceMeters":0,"safe":"visible","password":"never","TOKEN":"never","authorizationHint":"never","cookie":"never","nested":{"x":1}},
            {"title":"No task discriminator","minimumDistanceMeters":80},
            {"errandId":"e","minimumDistanceMeters":100,"minDistanceMeters":200}
        ]}""")
        val fixture = RunDataFixture(MockEngine { respond(envelope(data)) })
        try {
            val source = assertNotNull(ApiRunReadSource.capture(fixture.api))
            val rows = assertIs<RunReadOutcome.Success<RunRows<RunTaskItem>>>(source.tasks("owner")).data
            val invalid = rows.items[0]
            assertTrue(invalid.errandFieldPresent); assertTrue(invalid.scheduleFieldsPresent)
            assertNull(invalid.errandId); assertNull(invalid.scheduleId); assertEquals(0.0, invalid.minimumDistanceMeters)
            assertEquals(listOf("scheduleId", "eName", "minimumDistanceMeters", "safe"), invalid.safeDisplayFields.map { it.label })
            assertNull(rows.items[1].minimumDistanceMeters); assertFalse(rows.items[1].errandFieldPresent); assertFalse(rows.items[1].scheduleFieldsPresent)
            assertNull(rows.items[2].minimumDistanceMeters)
            assertTrue(rows.warnings.none { "同单位" in it }) // The old list did not publish detail/minimum warnings.
            val original = buildJsonObject { put("first", "x".repeat(401)); put("empty", " "); put("last", 3) }
            assertEquals(listOf(RunDisplayField("first", "x".repeat(400)), RunDisplayField("last", "3")), runDisplayFields(original))
            assertFalse(invalid.toString().contains("visible")); assertFalse(invalid.toString().contains("task-row"))
        } finally { fixture.close() }
    }

    @Test fun recognizedEmptyUnknownAndKnownAbsentRemainDifferentSuccessShapes() = runTest {
        for (data in listOf("{}", """{"errandList":null}""", """{"userErrandTrackRecord":null}""")) {
            val fixture = RunDataFixture(MockEngine { respond(envelope(Json.parseToJsonElement(data))) })
            try {
                val source = assertNotNull(ApiRunReadSource.capture(fixture.api))
                val tasks = assertIs<RunReadOutcome.Success<RunRows<RunTaskItem>>>(source.tasks("owner")).data
                assertFalse(tasks.recognized); assertEquals(0, tasks.rawSize)
                val records = assertIs<RunReadOutcome.Success<RunRows<RunRecordItem>>>(source.records("owner")).data
                assertFalse(records.recognized); assertTrue(records.items.isEmpty()) // Profile still sums/counts zero.
                val record = assertIs<RunReadOutcome.Success<RunReadValue<RunRecordItem>>>(source.record("t")).data
                assertFalse(record.recognized); assertNull(record.value)
                assertFalse(assertIs<RunReadOutcome.Success<RunReadValue<RunTaskDetail>>>(source.detail("e", "owner", "s")).data.recognized)
                assertFalse(assertIs<RunReadOutcome.Success<RunRows<io.github.betterzslf.feature.run.GeoPoint>>>(source.track("t")).data.recognized)
            } finally { fixture.close() }
        }
        for (empty in listOf(JsonNull, JsonPrimitive(""))) {
            val value = RunDataDecoder.recordValue(buildJsonObject { put("recordMap", empty) })
            assertTrue(value.recognized); assertNull(value.value); assertTrue(value.warnings.isEmpty())
        }
    }

    @Test fun sameSnapshotAndClearSaveAbaRetireAllSixPublicReadsWithoutHttpOrRebinding() = runTest {
        for (aba in listOf(false, true)) {
            var calls = 0
            val fixture = RunDataFixture(MockEngine { calls++; error("A retired capability must stay local") })
            try {
                val source = assertNotNull(ApiRunReadSource.capture(fixture.api)); val original = assertNotNull(fixture.sessions.session.value)
                if (aba) fixture.sessions.clear()
                fixture.sessions.save(original.copy())
                assertFalse(source.isCurrentSession()); assertEquals("owner", source.boundOwnerId)
                val results = listOf(source.tasks("owner"), source.detail("e", "owner", "s"), source.records("owner"), source.records("owner", "s"), source.record("t"), source.track("t"))
                results.forEach { assertEquals(RunReadIssue.AUTHENTICATION, assertIs<RunReadOutcome.Failure>(it).issue) }
                assertEquals(0, calls)
            } finally { fixture.close() }
        }
    }

    @Test fun lateSuccessFromAnOldEpochIsNotPublishedAndDoesNotReviveTheSource() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val fixture = RunDataFixture(MockEngine { entered.complete(Unit); release.await(); respond("""{"code":200,"data":{"errandList":[]}}""") })
        try {
            val source = assertNotNull(ApiRunReadSource.capture(fixture.api))
            val pending = async { source.tasks("owner") }
            entered.await(); fixture.sessions.save(assertNotNull(fixture.sessions.session.value).copy())
            release.complete(Unit)
            assertEquals(RunReadIssue.AUTHENTICATION, assertIs<RunReadOutcome.Failure>(pending.await()).issue)
            assertFalse(source.isCurrentSession())
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun finiteFailuresDiscardBackendMessagesAndNeverRetry() = runTest {
        for (expected in listOf(RunReadIssue.BUSINESS, RunReadIssue.HTTP, RunReadIssue.MALFORMED, RunReadIssue.TIMEOUT, RunReadIssue.NETWORK)) {
            var calls = 0
            val fixture = RunDataFixture(MockEngine {
                calls++
                when (expected) {
                    RunReadIssue.BUSINESS -> respond("""{"code":400,"message":"private-fixture-backend-message"}""")
                    RunReadIssue.HTTP -> respond("private-fixture-backend-message", HttpStatusCode.ServiceUnavailable)
                    RunReadIssue.MALFORMED -> respond("not-an-envelope")
                    RunReadIssue.TIMEOUT -> throw HttpRequestTimeoutException("https://fixture.invalid", 8_000)
                    else -> throw IllegalStateException("private-fixture-backend-message")
                }
            })
            try {
                val source = assertNotNull(ApiRunReadSource.capture(fixture.api))
                val failure = assertIs<RunReadOutcome.Failure>(source.tasks("owner"))
                assertEquals(expected, failure.issue); assertFalse(failure.toString().contains("private-fixture")); assertEquals(1, calls)
                assertEquals(RunReadIssue.VALIDATION, assertIs<RunReadOutcome.Failure>(source.tasks("different-owner")).issue)
                assertEquals(1, calls)
            } finally { fixture.close() }
        }
    }

    @Test fun cancellationSwallowedAndConvertedToAnotherExceptionStillPropagatesCancellation() = runTest {
        val entered = CompletableDeferred<Unit>(); val converted = CompletableDeferred<Unit>()
        val fixture = RunDataFixture(MockEngine {
            entered.complete(Unit)
            try { awaitCancellation() } catch (_: CancellationException) { converted.complete(Unit); throw IllegalStateException("fixture converted cancellation") }
        })
        try {
            val source = assertNotNull(ApiRunReadSource.capture(fixture.api))
            val pending = async { source.tasks("owner") }
            entered.await(); pending.cancel(); assertFailsWith<CancellationException> { pending.await() }
            converted.await()
        } finally { fixture.close() }
    }

    @Test fun lateExceptionAfterAnEpochMutationIsAuthenticationFailureNotNetwork() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val fixture = RunDataFixture(MockEngine { entered.complete(Unit); release.await(); throw IllegalStateException("fixture late network failure") })
        try {
            val source = assertNotNull(ApiRunReadSource.capture(fixture.api)); val pending = async { source.tasks("owner") }
            entered.await(); fixture.sessions.save(assertNotNull(fixture.sessions.session.value).copy())
            release.complete(Unit)
            assertEquals(RunReadIssue.AUTHENTICATION, assertIs<RunReadOutcome.Failure>(pending.await()).issue)
        } finally { release.complete(Unit); fixture.close() }
    }
}
