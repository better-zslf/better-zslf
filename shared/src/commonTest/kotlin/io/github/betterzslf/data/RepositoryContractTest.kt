package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.ApiLoginOutcome
import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.net.UploadFile
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.feature.campusnet.CampusOutcome
import io.github.betterzslf.feature.campusnet.RepositoryCampusNetworkSource
import io.github.betterzslf.feature.campusnet.RepositoryCampusPortalSource
import io.github.betterzslf.feature.runui.ApiRunReadSource
import io.github.betterzslf.feature.runui.RunReadOutcome
import io.github.betterzslf.feature.runui.RunReadSource
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Independent expected paths/parameters exercise actual repository methods, not just the enum. */
class RepositoryContractTest {
    private data class Contract(
        val path: String,
        val query: Map<String, String> = emptyMap(),
        val method: HttpMethod = HttpMethod.Post,
        val body: JsonObject? = null,
        val timeout: Long = 8_000,
        val anonymous: Boolean = false,
        val multipart: Boolean = false,
        val call: suspend (AppRepositories) -> ApiResult<*>,
    )

    /** Test-only assertion bridge; production never exposes raw transport results for campus calls. */
    private fun <T> CampusOutcome<T>.requireCampusSuccess(): ApiResult<T> =
        ApiResult.Success(assertIs<CampusOutcome.Success<T>>(this).data)

    /** Test-only bridge keeps the wire assertion table; no raw Run read escapes production. */
    private fun <T> RunReadOutcome<T>.requireRunSuccess(): ApiResult<T> =
        ApiResult.Success(assertIs<RunReadOutcome.Success<T>>(this).data)

    /** The shared fixture's ApiClient, assigned once the fixture exists; only the LOGIN row uses it. */
    private lateinit var loginApi: ApiClient

    /**
     * Test-only assertion bridge for the LOGIN row: production login is the typed campus login
     * flow, never a repository method, so the wire contract drives the real typed protocol here.
     */
    private suspend fun typedLoginBridge(username: String, password: String): ApiResult<*> {
        val api = loginApi
        val attempt = api.captureLoginAttempt()
        assertTrue(api.claimLoginAttempt(attempt))
        return ApiResult.Success(assertIs<ApiLoginOutcome.Accepted>(api.executeLogin(attempt, username, password)))
    }

    @Test
    fun everyRepositoryPathMatchesTheWireContract() = runTest {
        lateinit var runReads: RunReadSource
        val trackBody = queryParameters("trid" to "fixture-task", "coordinate" to "[{\"location\":\"1,2\"}]", "batchId" to "fixture-batch")
        val punch = queryParameters("longitude1" to "1", "latitude1" to "2", "longitude2" to "3", "latitude2" to "4")
        val calls = listOf(
            Contract("/app/login", mapOf("username" to "fixture-user", "password" to "fixture-pass"), anonymous = true) { typedLoginBridge("fixture-user", "fixture-pass") },
            Contract("/app/appUser/checkPhone", mapOf("phone" to "fixture-phone", "smsCode" to "fixture-code")) { it.auth.checkPhone("fixture-phone", "fixture-code") },
            Contract("/app/appUser/resetNewPassWord", mapOf("userId" to "fixture-user-id", "newPassWord" to "Fixture#1")) { it.auth.resetPassword("fixture-user-id", "Fixture#1") },
            Contract("/app/appUser/modifyPassword", mapOf("oldPassword" to "fixture-old", "newPassWord" to "Fixture#1")) { it.auth.modifyPassword("fixture-old", "Fixture#1") },
            Contract("/app/appUser/getUserInfo", method = HttpMethod.Get) { it.profile.userInfo() },
            Contract("/app/appUser/getUserPhotoURL") { it.profile.photo() },
            Contract("/app/appUser/getUserArctileStatis") { it.profile.statistics() },
            Contract("/app/appCmsArticle/articleDetailsByName", mapOf("articleName" to "用户协议")) { it.content.document("用户协议") },
            Contract("/run/errand/getErrandList", mapOf("userId" to "fixture-user-id", "offset" to "0", "limit" to "20", "isFinish" to "0")) { runReads.tasks("fixture-user-id").requireRunSuccess() },
            Contract("/run/errand/getErrandDetail", mapOf("errandId" to "fixture-errand", "userId" to "fixture-user-id", "errandScheduleId" to "fixture-schedule")) { runReads.detail("fixture-errand", "fixture-user-id", "fixture-schedule").requireRunSuccess() },
            Contract("/run/errand/getUserErrandTrackRecord", mapOf("userId" to "fixture-user-id")) { runReads.records("fixture-user-id").requireRunSuccess() },
            Contract("/run/errand/getTrackByTrid", mapOf("trid" to "fixture-task")) { runReads.track("fixture-task").requireRunSuccess() },
            Contract("/run/errand/getTrackRecordByTrid", mapOf("trid" to "fixture-task")) { runReads.record("fixture-task").requireRunSuccess() },
            Contract("/run/errand/punch", mapOf("longitude1" to "1", "latitude1" to "2", "longitude2" to "3", "latitude2" to "4")) { it.run.punch(punch) },
            Contract("/run/errand/addErrandTrackByData", body = trackBody, timeout = 30_000) { it.run.addTrack(trackBody) },
            Contract("/run/errand/syncSportData", body = trackBody, timeout = 30_000) { it.run.syncSportData(trackBody) },
            Contract("/run/errand/reportLocation", body = trackBody, timeout = 30_000) { it.run.reportLocation(trackBody) },
            Contract("/run/errand/quickSave", body = trackBody, timeout = 15_000) { it.run.quickSave(trackBody) },
            Contract("/app/verification/getInternetInfo") { assertNotNull(RepositoryCampusPortalSource.capture(it.campusPortal)).internetInfo().requireCampusSuccess() },
            Contract("/app/verification/getRedirectUrl") { assertNotNull(RepositoryCampusPortalSource.capture(it.campusPortal)).redirectTarget().requireCampusSuccess() },
            Contract("/app/verification/appDialInLogin", mapOf("urls" to "http://fixture.portal/a/wifi", "resData" to "http://fixture.portal/a/wifi")) { assertNotNull(RepositoryCampusPortalSource.capture(it.campusPortal)).dialIn("http://fixture.portal/a/wifi").requireCampusSuccess() },
            Contract("/app/verification/appOffline") { assertNotNull(RepositoryCampusPortalSource.capture(it.campusPortal)).offline().requireCampusSuccess() },
            Contract("/app/campusNetwork/checkPhone", mapOf("phone" to "fixture-phone")) { assertNotNull(RepositoryCampusNetworkSource.capture(it.campusNetwork)).checkPhone("fixture-phone").requireCampusSuccess() },
            Contract("/app/campusNetwork/getOperatorAccountByIDCard") { assertNotNull(RepositoryCampusNetworkSource.capture(it.campusNetwork)).accounts().requireCampusSuccess() },
            Contract("/app/campusNetwork/updateOperatorPasswordById", mapOf("operatorAccountId" to "fixture-operator", "newOperatorPassword" to "fixture-new")) { assertNotNull(RepositoryCampusNetworkSource.capture(it.campusNetwork)).updatePassword("fixture-operator", "fixture-new").requireCampusSuccess() },
            Contract("/app/checkVersion", method = HttpMethod.Get, anonymous = true) { it.updates.check() },
            Contract("/app/appCommomUpload/uploadIdCardImg", multipart = true) { it.upload(UploadFile("fixture.png", byteArrayOf(1)), "fixture-user-id") },
        )
        // Every ApiEndpoint must be exercised here; the set comparison below keeps this in sync.
        assertEquals(ApiEndpoint.entries.size, calls.size)
        assertEquals(calls.size, calls.map { it.path }.toSet().size)
        assertEquals(calls.map { it.path }.toSet(), ApiEndpoint.entries.map { it.path }.toSet())
        var current: Contract? = null
        var actualCalls = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            actualCalls++
            val expected = current ?: error("Missing test expectation")
            assertEquals(expected.path, request.url.encodedPath)
            assertEquals(if (expected.path.startsWith("/run/")) "run.fixture.invalid" else "app.fixture.invalid", request.url.host)
            assertEquals(expected.method, request.method)
            assertEquals(expected.query, request.url.parameters.entries().associate { it.key to it.value.single() })
            if (expected.anonymous) assertNull(request.headers[HttpHeaders.Authorization])
            else assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            val timeout = request.getCapabilityOrNull(HttpTimeoutCapability)
            assertEquals(expected.timeout, timeout?.requestTimeoutMillis)
            assertEquals(8_000L, timeout?.connectTimeoutMillis)
            if (expected.body != null) {
                val content = request.body as OutgoingContent.ByteArrayContent
                assertEquals(expected.body, Json.parseToJsonElement(content.bytes().decodeToString()))
                assertEquals("application", content.contentType?.contentType)
                assertEquals("json", content.contentType?.contentSubtype)
            } else if (expected.multipart) {
                assertEquals("multipart", request.body.contentType?.contentType)
            } else {
                assertTrue(request.body is OutgoingContent.NoContent)
            }
            if (expected.path == "/app/login") {
                respond("{\"code\":200,\"data\":{\"accessToken\":\"fixture-token\",\"userInfo\":{\"id\":\"fixture-user-id\"},\"deptInfo\":{}}}")
            } else if (expected.path == "/app/campusNetwork/getOperatorAccountByIDCard") {
                respond("{\"code\":200,\"data\":{\"operatorAccount\":[]}}")
            } else if (expected.path.startsWith("/run/")) {
                val data = when (expected.path) {
                    "/run/errand/getErrandList" -> """{"errandList":[]}"""
                    "/run/errand/getErrandDetail" -> """{"errandDetail":{"errandId":"fixture-errand","errandScheduleId":"fixture-schedule","eName":"Fixture task","geoCoorList":[[110.0,20.0]]}}"""
                    "/run/errand/getUserErrandTrackRecord" -> """{"userErrandTrackRecord":[]}"""
                    "/run/errand/getTrackByTrid" -> """{"trackPointList":[]}"""
                    "/run/errand/getTrackRecordByTrid" -> """{"recordMap":{"trid":"fixture-task","runLength":1.0,"runTime":1}}"""
                    else -> "{}"
                }
                respond("""{"code":200,"data":$data}""")
            } else respond("{\"code\":200,\"data\":{}}")
        })
        try {
            loginApi = fixture.api
            for (contract in calls) {
                current = contract
                fixture.sessions.save(fixtureSession())
                runReads = assertNotNull(ApiRunReadSource.capture(fixture.api))
                assertIs<ApiResult.Success<*>>(contract.call(fixture.repositories), contract.path)
            }
            assertEquals(calls.size, actualCalls)
        } finally { fixture.close() }
    }
}
