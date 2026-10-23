package io.github.betterzslf.core.net

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MultipartTest {
    @Test
    fun sendsAllMultipartFieldsAndUsesMsgResponseAlias() = runTest {
        val fixture = ApiTestFixture(MockEngine { request ->
            assertEquals("/app/appCommomUpload/uploadIdCardImg", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            assertEquals(0, request.url.parameters.names().size)
            assertEquals("multipart", request.body.contentType?.contentType)
            assertEquals("form-data", request.body.contentType?.contentSubtype)
            val channel = ByteChannel(autoFlush = true)
            val writer = launch {
                (request.body as OutgoingContent.WriteChannelContent).writeTo(channel)
                channel.close()
            }
            val multipart = channel.readRemaining().readByteArray().decodeToString()
            writer.join()
            listOf("name=childType", "name=tablename", "name=tableId", "name=file").forEach { field ->
                // Ktor permits either a quoted or unquoted disposition parameter.
                assertTrue(multipart.contains(field) || multipart.contains(field.replace("=", "=\"") + "\""))
            }
            assertTrue(multipart.contains("feedbackImage"))
            assertTrue(multipart.contains("sch_feedback"))
            assertTrue(multipart.contains("fixture-user-id"))
            assertTrue(multipart.contains("fixture-file-data"))
            assertTrue(multipart.contains("filename=\"fixture.png\""))
            respond("{\"code\":200,\"data\":\"image/fixture.png\",\"msg\":\"uploaded\"}")
        })
        try {
            val result = assertIs<ApiResult.Success<*>>(fixture.api.upload(
                UploadFile("fixture.png", "fixture-file-data".encodeToByteArray(), "image/png"),
                tableId = "fixture-user-id",
            ))
            assertEquals(JsonPrimitive("image/fixture.png"), result.data)
            assertEquals("uploaded", result.message)
        } finally { fixture.close() }
    }

    @Test
    fun validatesEmptyFilesAndHeaderInjectionAndRequiresSession() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; error("Invalid uploads should stay local") })
        try {
            val badFiles = listOf(
                UploadFile("fixture.png", byteArrayOf()),
                UploadFile("../fixture.png", byteArrayOf(1)),
                UploadFile("bad\r\nX-Header.png", byteArrayOf(1)),
                UploadFile("bad\"name.png", byteArrayOf(1)),
                UploadFile("fixture.png", byteArrayOf(1), "image/png\r\nX-Bad: yes"),
            )
            for (file in badFiles) {
                assertIs<ApiError.Validation>(assertIs<ApiResult.Failure>(fixture.api.upload(file, "fixture-user-id")).error)
            }
            fixture.sessions.clear()
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.api.upload(
                UploadFile("fixture.png", byteArrayOf(1)), "fixture-user-id",
            )).error)
            assertEquals(0, calls)
        } finally { fixture.close() }
    }
}
