package io.github.betterzslf.core.net

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonNull
import kotlin.test.*

class VenueUploadTest {
    @Test
    fun multipartUsesTheVenueRouteAndOnlyConfirmedFields() = runTest {
        val fixture = ApiTestFixture(MockEngine { request ->
            assertEquals("run.fixture.invalid", request.url.host)
            assertEquals("/run/site/addApplyFile", request.url.encodedPath)
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            assertTrue(request.url.parameters.isEmpty())
            val channel = ByteChannel(autoFlush = true)
            val writer = launch { (request.body as OutgoingContent.WriteChannelContent).writeTo(channel); channel.close() }
            val body = channel.readRemaining().readByteArray().decodeToString(); writer.join()
            for (field in listOf("file", "applyId", "userId")) {
                assertTrue(body.contains("name=$field") || body.contains("name=\"$field\""))
            }
            assertTrue(body.contains("fixture-apply")); assertTrue(body.contains("fixture-user-id"))
            assertFalse(body.contains("childType")); assertFalse(body.contains("tablename")); assertFalse(body.contains("tableId"))
            respond("")
        })
        try {
            val result = fixture.repositories.venues.uploadAttachment(UploadFile("fixture.png", byteArrayOf(1)), "fixture-apply", "fixture-user-id")
            assertEquals(JsonNull, assertIs<ApiResult.Success<*>>(result).data)
        } finally { fixture.close() }
    }

    @Test
    fun explicitBusinessFailureAndUnknownBodyAreNotAssumedToBeLinkedAttachments() = runTest {
        for (body in listOf("{\"code\":400,\"message\":\"fixture failure\"}", "unexpected body")) {
            val fixture = ApiTestFixture(MockEngine { respond(body) })
            try {
                val error = assertIs<ApiResult.Failure>(fixture.repositories.venues.uploadAttachment(
                    UploadFile("fixture.png", byteArrayOf(1)), "fixture-apply", "fixture-user-id")).error
                if (body.startsWith("{")) assertIs<ApiError.Business>(error) else assertIs<ApiError.Decode>(error)
            } finally { fixture.close() }
        }
    }

    @Test
    fun attachment401ClearsSessionAndMissingFormIdsNeverReachEngine() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; respond("", HttpStatusCode.Unauthorized) })
        val file = UploadFile("fixture.png", byteArrayOf(1))
        try {
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(fixture.repositories.venues.uploadAttachment(file, "", "fixture-user-id")).error)
            assertEquals(0, calls)
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.repositories.venues.uploadAttachment(file, "fixture-apply", "fixture-user-id")).error)
            assertNull(fixture.sessions.session.value)
            assertEquals(1, calls)
        } finally { fixture.close() }
    }

    @Test
    fun timesRequireDateAndPreserveCompatibilityAliasAndEmptyApplyId() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            calls++
            assertEquals("fixture-site", request.url.parameters["siteId"])
            assertEquals("fixture-venue", request.url.parameters["venueId"])
            assertEquals("2026-01-01", request.url.parameters["dateTime"])
            assertEquals("", request.url.parameters["applyId"])
            respond("{\"code\":200,\"data\":{}}")
        })
        try {
            assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(fixture.repositories.venues.times("fixture-site")).error)
            assertEquals(0, calls)
            assertIs<ApiResult.Success<*>>(fixture.repositories.venues.times("fixture-venue", "2026-01-01", siteId = "fixture-site"))
            assertIs<ApiResult.Success<*>>(fixture.repositories.venues.times("fixture-site", queryParameters("venueId" to "fixture-venue", "dateTime" to "2026-01-01")))
            assertEquals(2, calls)
        } finally { fixture.close() }
    }
}
