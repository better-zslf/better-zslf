package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Eight original read definitions plus three read halves of mixed repository definitions. */
class RunReadTransportTest {
    private val owner = "fixture-user-id"
    private fun fixture(engine: MockEngine) = RunDataFixture(engine, owner = owner)
    private fun operations(): List<suspend RunReadTransport.() -> ApiResult<JsonElement>> = listOf(
        { tasks(owner) }, { detail("fixture-errand", owner, "fixture-schedule") }, { records(owner) },
        { track("fixture-task") }, { record("fixture-task") }, { records(owner, "fixture-schedule") },
    )

    @Test fun allSixBoundReadMethodsPreventHttpAfterTheSessionChanges() = runTest {
        var requests = 0
        val fixture = fixture(MockEngine { requests++; error("Stale read transport must stay local") })
        try {
            val bound = assertNotNull(fixture.readTransport.boundToCurrentSession())
            fixture.sessions.save(runFixtureSession("replacement-fixture-token", "new-fixture-owner"))
            for (operation in operations()) assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(operation.invoke(bound)).error)
            assertEquals(6, operations().size); assertEquals(0, requests)
            assertEquals("new-fixture-owner", fixture.sessions.session.value?.userId)
            assertEquals("replacement-fixture-token", fixture.sessions.session.value?.token)
        } finally { fixture.close() }
    }

    @Test fun explicitReadQueryOwnersAreCheckedLocally() = runTest {
        var requests = 0
        val fixture = fixture(MockEngine { requests++; error("Wrong owner must stay local") })
        try {
            val bound = assertNotNull(fixture.readTransport.boundToCurrentSession())
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(bound.tasks("different-fixture-owner")).error)
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(bound.records("different-fixture-owner")).error)
            assertEquals(0, requests)
        } finally { fixture.close() }
    }

    @Test fun unchangedSessionAllowsEveryBoundReadAndPreservesCapturedHeader() = runTest {
        val paths = mutableSetOf<String>(); var requests = 0
        val fixture = fixture(MockEngine { request ->
            paths += request.url.encodedPath; requests++
            assertEquals("fixture-token", request.headers["Authorization"])
            respond("""{"code":200,"data":{}}""")
        })
        try {
            val bound = assertNotNull(fixture.readTransport.boundToCurrentSession())
            for (operation in operations()) assertIs<ApiResult.Success<*>>(operation.invoke(bound))
            assertEquals(5, paths.size); assertEquals(6, requests)
        } finally { fixture.close() }
    }

    @Test fun taskDetailSendsOnlyTheThreeObservedIdentityFields() = runTest {
        var requests = 0
        val fixture = fixture(MockEngine { request ->
            requests++
            assertEquals("/run/errand/getErrandDetail", request.url.encodedPath)
            assertEquals(setOf("errandId", "userId", "errandScheduleId"), request.url.parameters.names())
            assertEquals("fixture-errand", request.url.parameters["errandId"])
            assertEquals(owner, request.url.parameters["userId"])
            assertEquals("fixture-schedule", request.url.parameters["errandScheduleId"])
            assertNull(request.url.parameters["trid"])
            respond("""{"code":200,"data":{"errandDetail":{"name":"Fixture task"}}}""")
        })
        try {
            val result = fixture.readTransport.detail("fixture-errand", owner, "fixture-schedule")
            val data = assertIs<ApiResult.Success<JsonElement>>(result).data.jsonObject
            assertEquals("Fixture task", data.getValue("errandDetail").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals(1, requests)
        } finally { fixture.close() }
    }

    @Test fun taskDetailRequiresEachObservedParameterBeforeAnyHttp() = runTest {
        var requests = 0
        val fixture = fixture(MockEngine { requests++; error("Missing task identity must stay local") })
        try {
            val invalid = listOf(Triple("", owner, "fixture-schedule") to "errandId",
                Triple("fixture-errand", "", "fixture-schedule") to "userId", Triple("fixture-errand", owner, "") to "errandScheduleId")
            for ((args, field) in invalid) {
                val result = fixture.readTransport.detail(args.first, args.second, args.third)
                assertEquals(listOf(field), assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(result).error).fields)
            }
            assertEquals(0, requests)
        } finally { fixture.close() }
    }

    @Test @Suppress("DEPRECATION") fun deprecatedTridOnlyDetailIsAnExplicitLocalFailureNotAWrongQuery() = runTest {
        var requests = 0
        val fixture = fixture(MockEngine { requests++; error("Legacy detail must stay local") })
        try {
            val bound = assertNotNull(fixture.readTransport.boundToCurrentSession())
            for (transport in listOf(fixture.readTransport, bound)) {
                val result = transport.detail("fixture-attempt-trid")
                assertEquals(listOf("errandId", "userId", "errandScheduleId"), assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(result).error).fields)
            }
            assertEquals(0, requests); assertNotNull(fixture.sessions.session.value)
        } finally { fixture.close() }
    }

    @Test fun scheduleRecordsUseExactlyTheObservedFilterAndServerPaging() = runTest {
        val offsets = mutableListOf<String?>()
        val fixture = fixture(MockEngine { request ->
            assertEquals("/run/errand/getUserErrandTrackRecord", request.url.encodedPath)
            assertEquals(setOf("errandScheduleId", "userId", "offset", "limit"), request.url.parameters.names())
            assertEquals("fixture-schedule", request.url.parameters["errandScheduleId"]); assertEquals(owner, request.url.parameters["userId"])
            assertEquals("10", request.url.parameters["limit"]); offsets += request.url.parameters["offset"]
            respond("""{"code":200,"data":{"userErrandTrackRecord":[]}}""")
        })
        try {
            assertIs<ApiResult.Success<*>>(fixture.readTransport.records(owner, "fixture-schedule"))
            assertIs<ApiResult.Success<*>>(fixture.readTransport.records(owner, "fixture-schedule", offset = 10))
            assertEquals(listOf<String?>("0", "10"), offsets)
        } finally { fixture.close() }
    }

    @Test fun scheduleRecordsValidateTheFilterAndPaginationWithoutHttp() = runTest {
        var requests = 0
        val fixture = fixture(MockEngine { requests++; error("Invalid paging must stay local") })
        try {
            val transport = fixture.readTransport
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(transport.records(owner, "")).error)
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(transport.records(owner, "fixture-schedule", offset = -1)).error)
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(transport.records(owner, "fixture-schedule", limit = 0)).error)
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(transport.records("", "fixture-schedule")).error)
            assertEquals(0, requests)
        } finally { fixture.close() }
    }

    @Test fun newDetailAndFilteredRecordsKeepTheSameAtomicBindingAndOwnerChecks() = runTest {
        var requests = 0
        val fixture = fixture(MockEngine { requests++; error("Wrong owner or stale binding must stay local") })
        try {
            val bound = assertNotNull(fixture.readTransport.boundToCurrentSession())
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(bound.detail("fixture-errand", "other-owner", "fixture-schedule")).error)
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(bound.records("other-owner", "fixture-schedule")).error)
            fixture.sessions.save(runFixtureSession("replacement-fixture-token", owner))
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(bound.detail("fixture-errand", owner, "fixture-schedule")).error)
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(bound.records(owner, "fixture-schedule")).error)
            assertEquals(0, requests); assertEquals("replacement-fixture-token", fixture.sessions.session.value?.token)
        } finally { fixture.close() }
    }

    @Test fun userWideRecordQueryRemainsOnlyUserIdForCompatibility() = runTest {
        var requests = 0
        val fixture = fixture(MockEngine { request ->
            requests++; assertEquals(setOf("userId"), request.url.parameters.names()); assertEquals(owner, request.url.parameters["userId"])
            respond("""{"code":200,"data":{"userErrandTrackRecord":[]}}""")
        })
        try { assertIs<ApiResult.Success<*>>(fixture.readTransport.records(owner)); assertEquals(1, requests) }
        finally { fixture.close() }
    }

    @Test fun attemptTrackAndRecordEndpointsContinueToUseTridNotTaskOrScheduleIds() = runTest {
        var requests = 0
        val fixture = fixture(MockEngine { request ->
            requests++; assertEquals(setOf("trid"), request.url.parameters.names()); assertEquals("fixture-attempt-trid", request.url.parameters["trid"])
            respond("""{"code":200,"data":{}}""")
        })
        try {
            assertIs<ApiResult.Success<*>>(fixture.readTransport.track("fixture-attempt-trid"))
            assertIs<ApiResult.Success<*>>(fixture.readTransport.record("fixture-attempt-trid")); assertEquals(2, requests)
        } finally { fixture.close() }
    }
}
