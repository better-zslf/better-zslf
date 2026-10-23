package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class RunRepositoryTest {
    private val owner = "fixture-user-id"
    private fun body(userId: JsonElement = JsonPrimitive(owner)) = buildJsonObject {
        put("trid", "fixture-task")
        put("userId", userId)
        put("coordinate", "[{\"latitude\":20.0,\"longitude\":110.0}]")
    }
    private fun operations(): List<suspend RunRepository.() -> ApiResult<JsonElement>> {
        val data = body()
        return listOf(
            { tasks(owner) },
            { detail("fixture-errand", owner, "fixture-schedule") },
            { records(owner) },
            { track("fixture-task") },
            { record("fixture-task") },
            { punch(queryParameters("longitude1" to "110", "latitude1" to "20", "longitude2" to "110", "latitude2" to "20")) },
            { addTrack(data) },
            { syncSportData(data) },
            { reportLocation(data) },
            { quickSave(data) },
        )
    }

    @Test
    fun capturedOwnerIsStableAndABoundRepositoryNeverSilentlyRebinds() {
        val fixture = ApiTestFixture(MockEngine { error("No request expected") })
        try {
            assertNull(fixture.repositories.run.boundOwnerId)
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            assertEquals(owner, bound.boundOwnerId)
            assertSame(bound, bound.boundToCurrentSession())
            fixture.sessions.save(fixtureSession("replacement-fixture-token").copy(userInfo = buildJsonObject { put("id", "new-fixture-owner") }))
            assertEquals(owner, bound.boundOwnerId)
            assertNull(bound.boundToCurrentSession())
            val fresh = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            assertEquals("new-fixture-owner", fresh.boundOwnerId)
        } finally { fixture.close() }
    }

    @Test
    fun allTenBoundRepositoryMethodsPreventHttpAfterTheSessionChanges() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("Stale repository must stay local") })
        try {
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            fixture.sessions.save(fixtureSession("replacement-fixture-token").copy(userInfo = buildJsonObject { put("id", "new-fixture-owner") }))
            for (operation in operations()) {
                assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(operation.invoke(bound)).error)
            }
            assertEquals(10, operations().size)
            assertEquals(0, requests)
            assertEquals("new-fixture-owner", fixture.sessions.session.value?.userId)
            assertEquals("replacement-fixture-token", fixture.sessions.session.value?.token)
        } finally { fixture.close() }
    }

    @Test
    fun allBoundJsonWritesRejectMissingBlankDifferentAndNonStringOwnersLocally() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("Wrong owner must stay local") })
        try {
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            val writes = listOf(bound::addTrack, bound::syncSportData, bound::reportLocation, bound::quickSave)
            val invalidBodies = listOf(
                JsonObject(body().filterKeys { it != "userId" }),
                body(JsonPrimitive("")), body(JsonPrimitive("different-fixture-owner")),
                body(JsonNull), body(JsonPrimitive(123)), body(buildJsonObject { put("id", owner) }),
            )
            for (invalid in invalidBodies) for (write in writes) {
                val error = assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(write(invalid)).error)
                assertEquals(listOf("userId"), error.fields)
                assertFalse(error.message.contains(owner))
                assertFalse(error.message.contains("fixture-token"))
            }
            assertEquals(0, requests)
            assertEquals(owner, fixture.sessions.session.value?.userId)
        } finally { fixture.close() }
    }

    @Test
    fun explicitQueryOwnersAreAlsoCheckedWithoutChangingTheCoordinateOnlyPunchContract() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; respond("{\"code\":200,\"data\":{}}") })
        try {
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(bound.tasks("different-fixture-owner")).error)
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(bound.records("different-fixture-owner")).error)
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(bound.punch(queryParameters("userId" to "different-fixture-owner"))).error)
            assertEquals(0, requests)
            assertIs<ApiResult.Success<*>>(bound.punch(queryParameters("longitude1" to "110", "latitude1" to "20", "longitude2" to "110", "latitude2" to "20")))
            assertEquals(1, requests)
        } finally { fixture.close() }
    }

    @Test
    fun unchangedSessionAllowsEveryBoundMethodAndPreservesNestedCoordinateVerbatim() = runTest {
        val paths = mutableSetOf<String>()
        val expectedBody = body()
        val fixture = ApiTestFixture(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            val requestBody = request.body
            if (requestBody is TextContent) assertEquals(expectedBody.toString(), requestBody.text)
            respond("{\"code\":200,\"data\":{}}")
        })
        try {
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            for (operation in operations()) assertIs<ApiResult.Success<*>>(operation.invoke(bound))
            assertEquals(10, paths.size)
        } finally { fixture.close() }
    }

    @Test
    fun legacyUnboundJsonCallsDoNotGainAUserIdRequirement() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            requests++
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            assertFalse((request.body as TextContent).text.contains("userId"))
            respond("{\"code\":200,\"data\":{}}")
        })
        try {
            assertIs<ApiResult.Success<*>>(fixture.repositories.run.quickSave(buildJsonObject { put("trid", "fixture-task") }))
            assertEquals(1, requests)
        } finally { fixture.close() }
    }

    @Test
    fun taskDetailSendsOnlyTheThreeObservedIdentityFields() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            requests++
            assertEquals("/run/errand/getErrandDetail", request.url.encodedPath)
            assertEquals(setOf("errandId", "userId", "errandScheduleId"), request.url.parameters.names())
            assertEquals("fixture-errand", request.url.parameters["errandId"])
            assertEquals(owner, request.url.parameters["userId"])
            assertEquals("fixture-schedule", request.url.parameters["errandScheduleId"])
            assertNull(request.url.parameters["trid"])
            respond("{\"code\":200,\"data\":{\"errandDetail\":{\"name\":\"Fixture task\"}}}")
        })
        try {
            val result = fixture.repositories.run.detail("fixture-errand", owner, "fixture-schedule")
            val data = assertIs<ApiResult.Success<JsonElement>>(result).data.jsonObject
            assertEquals("Fixture task", data.getValue("errandDetail").jsonObject.getValue("name").jsonPrimitive.content)
            assertEquals(1, requests)
        } finally { fixture.close() }
    }

    @Test
    fun taskDetailRequiresEachObservedParameterBeforeAnyHttp() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("Missing task identity must stay local") })
        try {
            val invalid = listOf(
                Triple("", owner, "fixture-schedule") to "errandId",
                Triple("fixture-errand", "", "fixture-schedule") to "userId",
                Triple("fixture-errand", owner, "") to "errandScheduleId",
            )
            for ((args, field) in invalid) {
                val result = fixture.repositories.run.detail(args.first, args.second, args.third)
                assertEquals(listOf(field), assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(result).error).fields)
            }
            assertEquals(0, requests)
        } finally { fixture.close() }
    }

    @Test
    @Suppress("DEPRECATION")
    fun deprecatedTridOnlyDetailIsAnExplicitLocalFailureNotAWrongQuery() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("Legacy detail must stay local") })
        try {
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            for (repository in listOf(fixture.repositories.run, bound)) {
                val result = repository.detail("fixture-attempt-trid")
                assertEquals(listOf("errandId", "userId", "errandScheduleId"), assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(result).error).fields)
            }
            assertEquals(0, requests)
            assertNotNull(fixture.sessions.session.value)
        } finally { fixture.close() }
    }

    @Test
    fun scheduleRecordsUseExactlyTheObservedFilterAndServerPaging() = runTest {
        val offsets = mutableListOf<String?>()
        val fixture = ApiTestFixture(MockEngine { request ->
            assertEquals("/run/errand/getUserErrandTrackRecord", request.url.encodedPath)
            assertEquals(setOf("errandScheduleId", "userId", "offset", "limit"), request.url.parameters.names())
            assertEquals("fixture-schedule", request.url.parameters["errandScheduleId"])
            assertEquals(owner, request.url.parameters["userId"])
            assertEquals("10", request.url.parameters["limit"])
            offsets += request.url.parameters["offset"]
            respond("{\"code\":200,\"data\":{\"userErrandTrackRecord\":[]}}")
        })
        try {
            val repository = fixture.repositories.run
            assertIs<ApiResult.Success<*>>(repository.records(owner, "fixture-schedule"))
            assertIs<ApiResult.Success<*>>(repository.records(owner, "fixture-schedule", offset = 10))
            assertEquals(listOf<String?>("0", "10"), offsets)
        } finally { fixture.close() }
    }

    @Test
    fun scheduleRecordsValidateTheFilterAndPaginationWithoutHttp() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("Invalid paging must stay local") })
        try {
            val repository = fixture.repositories.run
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(repository.records(owner, "")).error)
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(repository.records(owner, "fixture-schedule", offset = -1)).error)
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(repository.records(owner, "fixture-schedule", limit = 0)).error)
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(repository.records("", "fixture-schedule")).error)
            assertEquals(0, requests)
        } finally { fixture.close() }
    }

    @Test
    fun newDetailAndFilteredRecordsKeepTheSameAtomicBindingAndOwnerChecks() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("Wrong owner or stale binding must stay local") })
        try {
            val bound = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(bound.detail("fixture-errand", "other-owner", "fixture-schedule")).error)
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(bound.records("other-owner", "fixture-schedule")).error)
            fixture.sessions.save(fixtureSession("replacement-fixture-token"))
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(bound.detail("fixture-errand", owner, "fixture-schedule")).error)
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(bound.records(owner, "fixture-schedule")).error)
            assertEquals(0, requests)
            assertEquals("replacement-fixture-token", fixture.sessions.session.value?.token)
        } finally { fixture.close() }
    }

    @Test
    fun userWideRecordQueryRemainsOnlyUserIdForCompatibility() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            requests++
            assertEquals(setOf("userId"), request.url.parameters.names())
            assertEquals(owner, request.url.parameters["userId"])
            respond("{\"code\":200,\"data\":{\"userErrandTrackRecord\":[]}}")
        })
        try {
            assertIs<ApiResult.Success<*>>(fixture.repositories.run.records(owner))
            assertEquals(1, requests)
        } finally { fixture.close() }
    }

    @Test
    fun attemptTrackAndRecordEndpointsContinueToUseTridNotTaskOrScheduleIds() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            requests++
            assertEquals(setOf("trid"), request.url.parameters.names())
            assertEquals("fixture-attempt-trid", request.url.parameters["trid"])
            respond("{\"code\":200,\"data\":{}}")
        })
        try {
            assertIs<ApiResult.Success<*>>(fixture.repositories.run.track("fixture-attempt-trid"))
            assertIs<ApiResult.Success<*>>(fixture.repositories.run.record("fixture-attempt-trid"))
            assertEquals(2, requests)
        } finally { fixture.close() }
    }
}
