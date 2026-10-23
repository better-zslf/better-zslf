package io.github.betterzslf.feature.booking

import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.platform.PickedFile
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** All requests are intercepted by MockEngine under *.fixture.invalid; no production request is made. */
class BookingWorkflowContractTest {
    private fun venueForm() = VenueApplicationForm("fixture-site", listOf("time-1", "time-2"), "fixture venue", JsonPrimitive(1),
        "fixture group", "fixture activity", "fixture contact", "0000000", "fixture-user-id", "fixture display")

    private suspend fun submit(flow: BookingConfirmation) {
        assertTrue(flow.preview(BookingPreview("fixture confirmation", listOf("fixture preview"))))
        flow.confirm(flow.state.value.previewId, true)
    }

    @Test fun venueFieldsAndSequentialDependencyChainMatchTheArtifact() = runTest {
        val paths = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("run.fixture.invalid", request.url.host)
            val q = request.url.parameters
            when (request.url.encodedPath) {
                "/run/site/addApply" -> {
                    assertEquals(setOf("id", "name", "userId", "uerName", "teacherId", "teacher", "applicants", "objectId", "place", "time", "deviceName", "remark", "venueStatus", "status", "content", "uName", "uPhone"), q.names())
                    assertEquals("fixture display", q["uerName"])
                    assertEquals("0", q["status"])
                    respond("""{"code":200,"data":{"sportsVenueApply":{"id":"fixture-apply"}}}""")
                }
                "/run/site/addSiteApplyRelated" -> { assertEquals("fixture-site", q["venueId"]); assertEquals("fixture-apply", q["applyId"]); respond("""{"code":200,"data":""}""") }
                "/run/site/addApplySelectTime" -> { assertEquals("time-1,time-2", q["venueTimeId"]); assertEquals("fixture-apply", q["applyId"]); respond("""{"code":200,"data":""}""") }
                "/run/site/addApplyDenfition" -> { assertEquals("fixture-user-id", q["userId"]); assertEquals("", q["teacherName"]); respond("""{"code":200,"data":""}""") }
                else -> error("Unexpected fixture path")
            }
        })
        try {
            val transport = RepositoryBookingTransport(fixture.repositories.venues, fixture.repositories.fitness)
            val flow = venueConfirmation(transport, venueForm())
            assertTrue(paths.isEmpty())
            submit(flow)
            assertEquals(BookingPhase.COMPLETED, flow.state.value.phase)
            assertEquals(listOf("/run/site/addApply", "/run/site/addSiteApplyRelated", "/run/site/addApplySelectTime", "/run/site/addApplyDenfition"), paths)
        } finally { fixture.close() }
    }

    @Test fun missingCreatedIdStopsAllAssociationsAndMarksUncertain() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; respond("""{"code":200,"data":{"sportsVenueApply":{}}}""") })
        try {
            val flow = venueConfirmation(RepositoryBookingTransport(fixture.repositories.venues, fixture.repositories.fitness), venueForm())
            submit(flow)
            assertEquals(1, calls)
            assertEquals(BookingPhase.UNCERTAIN, flow.state.value.phase)
            flow.confirm(flow.state.value.previewId, true)
            assertEquals(1, calls)
        } finally { fixture.close() }
    }

    @Test fun fitnessEnrolDoesNotInventProjectIdParameters() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            calls++
            assertEquals("/run/physical/addPhysicalUser", request.url.encodedPath)
            assertEquals("run.fixture.invalid", request.url.host)
            val q = request.url.parameters
            assertEquals(setOf("physicalId", "userId", "idcard", "name", "studentID", "status", "sex", "physicalTime", "period", "physicalUserId"), q.names())
            assertEquals("2030-01-10 00:00", q["physicalTime"])
            assertEquals("上午", q["period"])
            assertEquals("男", q["sex"])
            assertEquals("1", q["status"])
            assertNull(q["itemIds"])
            respond("""{"code":200,"data":""}""")
        })
        try {
            val form = FitnessEnrolmentForm("fixture-test", "fixture-enrol", "fixture-user-id", "fixture display", "fixture-student", "000000000000000000", "男", "2030-01-10", "上午")
            val flow = fitnessEnrolmentConfirmation(RepositoryBookingTransport(fixture.repositories.venues, fixture.repositories.fitness), form)
            submit(flow)
            assertEquals(BookingPhase.COMPLETED, flow.state.value.phase)
            assertEquals(1, calls)
        } finally { fixture.close() }
    }

    @Test fun fitnessCancellationUsesRidReasonAndStatusFour() = runTest {
        val fixture = ApiTestFixture(MockEngine { request ->
            assertEquals("/run/physical/cancelPhysical", request.url.encodedPath)
            assertEquals(mapOf("id" to listOf("fixture-rid"), "cancelText" to listOf("fixture reason"), "status" to listOf("4")), request.url.parameters.entries().associate { it.key to it.value })
            respond("""{"code":200,"data":""}""")
        })
        try {
            val flow = fitnessCancellationConfirmation(RepositoryBookingTransport(fixture.repositories.venues, fixture.repositories.fitness), FitnessCancellation("fixture-rid", "fixture reason"))
            submit(flow)
            assertEquals(BookingPhase.COMPLETED, flow.state.value.phase)
        } finally { fixture.close() }
    }

    @Test fun invalidFormsDoNotReachAnyTransport() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; error("Invalid form reached transport") })
        try {
            val transport = RepositoryBookingTransport(fixture.repositories.venues, fixture.repositories.fitness)
            val flow = fitnessCancellationConfirmation(transport, FitnessCancellation("", ""))
            submit(flow)
            assertEquals(0, calls)
            assertEquals(BookingPhase.FAILED, flow.state.value.phase)
        } finally { fixture.close() }
    }

    @Test fun attachmentTransferThenAfterUploadThenReadBackMustConfirmReceipts() = runTest {
        val paths = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/run/site/addApplyFile" -> { assertEquals("multipart", request.body.contentType?.contentType); respond("") }
                "/run/site/afterUploadFile" -> respond("""{"code":200,"data":""}""")
                "/run/site/getApplyDetail" -> respond("""{"code":200,"data":{"fileList":[{"id":"new-fixture-file","path":"fixture/image.jpg"}]}}""")
                else -> error("Unexpected fixture path")
            }
        })
        try {
            val flow = attachmentConfirmation(RepositoryAttachmentTransport(fixture.repositories.venues), "fixture-apply", "fixture-user-id",
                listOf(PickedFile("fixture.jpg", "image/jpeg", byteArrayOf(1, 2))), "1", emptySet())
            submit(flow)
            assertEquals(BookingPhase.COMPLETED, flow.state.value.phase)
            assertEquals(listOf("/run/site/addApplyFile", "/run/site/afterUploadFile", "/run/site/getApplyDetail"), paths)
        } finally { fixture.close() }
    }

    @Test fun unconfirmedAttachmentReadBackIsUncertainAndNeverRetransfers() = runTest {
        var uploads = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            when (request.url.encodedPath) {
                "/run/site/addApplyFile" -> { uploads++; respond("") }
                "/run/site/afterUploadFile" -> respond("""{"code":200,"data":""}""")
                else -> respond("""{"code":200,"data":{"fileList":[]}}""")
            }
        })
        try {
            val flow = attachmentConfirmation(RepositoryAttachmentTransport(fixture.repositories.venues), "fixture-apply", "fixture-user-id",
                listOf(PickedFile("fixture.jpg", "image/jpeg", byteArrayOf(1))), "1", emptySet())
            submit(flow); flow.confirm(flow.state.value.previewId, true)
            assertEquals(BookingPhase.UNCERTAIN, flow.state.value.phase)
            assertEquals(1, uploads)
            assertEquals(listOf(BookingStepPhase.COMPLETED, BookingStepPhase.COMPLETED, BookingStepPhase.UNCERTAIN), flow.state.value.steps.map { it.phase })
        } finally { fixture.close() }
    }

    @Test fun attachmentBusinessFailureDoesNotRunAfterUpload() = runTest {
        val paths = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request -> paths += request.url.encodedPath; respond("""{"code":400,"message":"fixture denied","data":""}""") })
        try {
            val flow = attachmentConfirmation(RepositoryAttachmentTransport(fixture.repositories.venues), "fixture-apply", "fixture-user-id",
                listOf(PickedFile("fixture.jpg", "image/jpeg", byteArrayOf(1))), "1", emptySet())
            submit(flow)
            assertEquals(BookingPhase.FAILED, flow.state.value.phase)
            assertEquals(listOf("/run/site/addApplyFile"), paths)
        } finally { fixture.close() }
    }

    @Test fun typedTimeQueryContainsDateVenueIdEmptyApplyIdAndCompatibilitySiteId() = runTest {
        val fixture = ApiTestFixture(MockEngine { request ->
            assertEquals("/run/site/getSiteTimeList", request.url.encodedPath)
            assertEquals(mapOf("venueId" to listOf("fixture-site"), "dateTime" to listOf("2030-01-10"), "applyId" to listOf(""), "siteId" to listOf("fixture-site")), request.url.parameters.entries().associate { it.key to it.value })
            respond("""{"code":200,"data":{"placeTimeList":[],"applyTimeList":[]}}""")
        })
        try { assertIs<ApiResult.Success<*>>(fixture.repositories.venues.times("fixture-site", "2030-01-10")) }
        finally { fixture.close() }
    }
}
