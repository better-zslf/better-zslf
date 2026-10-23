package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.net.UploadFile
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiResult
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

    @Test
    fun all56RepositoryPathsMatchTheWireContract() = runTest {
        val trackBody = queryParameters("trid" to "fixture-task", "coordinate" to "[{\"location\":\"1,2\"}]", "batchId" to "fixture-batch")
        val form = queryParameters("userId" to "fixture-user-id", "uerName" to "fixture-display")
        val physicalForm = queryParameters("physicalId" to "fixture-physical", "userId" to "fixture-user-id")
        val punch = queryParameters("longitude1" to "1", "latitude1" to "2", "longitude2" to "3", "latitude2" to "4")
        val calls = listOf(
            Contract("/app/login", mapOf("username" to "fixture-user", "password" to "fixture-pass"), anonymous = true) { it.auth.login("fixture-user", "fixture-pass") },
            Contract("/app/appUser/checkPhone", mapOf("phone" to "fixture-phone", "smsCode" to "fixture-code")) { it.auth.checkPhone("fixture-phone", "fixture-code") },
            Contract("/app/appUser/resetNewPassWord", mapOf("userId" to "fixture-user-id", "newPassWord" to "Fixture#1")) { it.auth.resetPassword("fixture-user-id", "Fixture#1") },
            Contract("/app/appUser/modifyPassword", mapOf("oldPassword" to "fixture-old", "newPassWord" to "Fixture#1")) { it.auth.modifyPassword("fixture-old", "Fixture#1") },
            Contract("/app/appUser/getUserInfo", method = HttpMethod.Get) { it.profile.userInfo() },
            Contract("/app/appUser/getUserPhotoURL") { it.profile.photo() },
            Contract("/app/appUser/getUserArctileStatis") { it.profile.statistics() },
            Contract("/app/appCmsColumn/getCmsByModule", mapOf("name" to "学校动态")) { it.content.columns("学校动态") },
            Contract("/app/appCmsArticle/getCmsArticleByColumnId", mapOf("cmsCloumnId" to "fixture-column", "offset" to "0", "limit" to "20")) { it.content.articles("fixture-column") },
            Contract("/app/appCmsArticle/articleDetailsById", mapOf("articleId" to "fixture-article")) { it.content.article("fixture-article") },
            Contract("/app/appCmsArticle/increaseHits", mapOf("articleId" to "fixture-article")) { it.content.increaseHits("fixture-article") },
            Contract("/app/appCmsArticleLike/getCountLikeByAtricleId", mapOf("articleId" to "fixture-article")) { it.content.likeCount("fixture-article") },
            Contract("/app/appCmsArticleLike/getCountByUid", mapOf("articleId" to "fixture-article")) { it.content.myLike("fixture-article") },
            Contract("/app/appCmsArticleLike/setArticleLike", mapOf("articleId" to "fixture-article")) { it.content.toggleLike("fixture-article") },
            Contract("/app/appCmsArticle/articleHistory") { it.content.history() },
            Contract("/app/appCmsArticle/articleDetailsByName", mapOf("articleName" to "用户协议")) { it.content.document("用户协议") },
            Contract("/app/appNotice/unreadNumber") { it.notices.unread() },
            Contract("/app/announcement/announcementList") { it.notices.announcements() },
            Contract("/app/appNotice/noticeListByType", mapOf("typeId" to "0", "offset" to "0", "limit" to "20")) { it.notices.list("0") },
            Contract("/app/appNotice/updateNoticeIsRead", mapOf("noticeId" to "fixture-notice")) { it.notices.markRead("fixture-notice") },
            Contract("/app/appNotice/updateNoticeMutuaById", mapOf("noticeId" to "fixture-notice")) { it.notices.markMutualRead("fixture-notice") },
            Contract("/app/appFind/getAppFindById") { it.discover.menu() },
            Contract("/app/appFind/getDictTips", mapOf("code" to "attendanceRadius", "num" to "1")) { it.discover.dictionary("attendanceRadius") },
            Contract("/run/errand/getErrandList", mapOf("userId" to "fixture-user-id", "offset" to "0", "limit" to "20", "isFinish" to "0")) { it.run.tasks("fixture-user-id") },
            Contract("/run/errand/getErrandDetail", mapOf("errandId" to "fixture-errand", "userId" to "fixture-user-id", "errandScheduleId" to "fixture-schedule")) { it.run.detail("fixture-errand", "fixture-user-id", "fixture-schedule") },
            Contract("/run/errand/getUserErrandTrackRecord", mapOf("userId" to "fixture-user-id")) { it.run.records("fixture-user-id") },
            Contract("/run/errand/getTrackByTrid", mapOf("trid" to "fixture-task")) { it.run.track("fixture-task") },
            Contract("/run/errand/getTrackRecordByTrid", mapOf("trid" to "fixture-task")) { it.run.record("fixture-task") },
            Contract("/run/errand/punch", mapOf("longitude1" to "1", "latitude1" to "2", "longitude2" to "3", "latitude2" to "4")) { it.run.punch(punch) },
            Contract("/run/errand/addErrandTrackByData", body = trackBody, timeout = 30_000) { it.run.addTrack(trackBody) },
            Contract("/run/errand/syncSportData", body = trackBody, timeout = 30_000) { it.run.syncSportData(trackBody) },
            Contract("/run/errand/reportLocation", body = trackBody, timeout = 30_000) { it.run.reportLocation(trackBody) },
            Contract("/run/errand/quickSave", body = trackBody, timeout = 15_000) { it.run.quickSave(trackBody) },
            Contract("/run/site/getSiteList", mapOf("venueStatus" to "1")) { it.venues.list() },
            Contract("/run/site/getSiteInfo", mapOf("siteId" to "fixture-site", "sId" to "fixture-site")) { it.venues.info("fixture-site") },
            Contract("/run/site/getSiteTimeList", mapOf("siteId" to "fixture-site", "venueId" to "fixture-site", "dateTime" to "2026-01-01", "applyId" to "")) { it.venues.times("fixture-site", dateTime = "2026-01-01") },
            Contract("/run/site/addApplyFile", multipart = true) { it.venues.uploadAttachment(UploadFile("fixture.png", byteArrayOf(1)), "fixture-apply", "fixture-user-id") },
            Contract("/run/site/addApply", mapOf("userId" to "fixture-user-id", "uerName" to "fixture-display")) { it.venues.apply(form) },
            Contract("/run/site/addSiteApplyRelated", mapOf("venueId" to "fixture-site", "applyId" to "fixture-apply")) { it.venues.relate("fixture-site", "fixture-apply") },
            Contract("/run/site/addApplySelectTime", mapOf("venueTimeId" to "fixture-time", "applyId" to "fixture-apply")) { it.venues.selectTime("fixture-time", "fixture-apply") },
            Contract("/run/site/addApplyDenfition", mapOf("userId" to "fixture-user-id", "applyId" to "fixture-apply", "teacherId" to "fixture-teacher", "teacherName" to "fixture-name")) { it.venues.define("fixture-user-id", "fixture-apply", "fixture-teacher", "fixture-name") },
            Contract("/run/site/getSiteApplyList", mapOf("userId" to "fixture-user-id")) { it.venues.applications("fixture-user-id") },
            Contract("/run/site/getApplyDetail", mapOf("applyId" to "fixture-apply")) { it.venues.application("fixture-apply") },
            Contract("/run/site/intProcess", mapOf("id" to "fixture-apply")) { it.venues.process("fixture-apply") },
            Contract("/run/site/afterUploadFile", mapOf("applyId" to "fixture-apply")) { it.venues.afterUpload("fixture-apply") },
            Contract("/run/physical/getPhysicalTestList") { it.fitness.tests() },
            Contract("/run/physical/getPhysicalRelatedItemsList") { it.fitness.items() },
            Contract("/run/physical/getPhysicalUserCount") { it.fitness.counts() },
            Contract("/run/physical/getPhysicalMaInfo") { it.fitness.myInfo() },
            Contract("/run/physical/addPhysicalUser", mapOf("physicalId" to "fixture-physical", "userId" to "fixture-user-id")) { it.fitness.enrol(physicalForm) },
            Contract("/run/physical/cancelPhysical", mapOf("physicalId" to "fixture-physical", "userId" to "fixture-user-id")) { it.fitness.cancel(physicalForm) },
            Contract("/app/campusNetwork/checkPhone", mapOf("phone" to "fixture-phone")) { it.campusNetwork.checkPhone("fixture-phone") },
            Contract("/app/campusNetwork/getOperatorAccountByIDCard") { it.campusNetwork.accounts() },
            Contract("/app/campusNetwork/updateOperatorPasswordById", mapOf("operatorAccountId" to "fixture-operator", "newOperatorPassword" to "fixture-new")) { it.campusNetwork.updatePassword("fixture-operator", "fixture-new") },
            Contract("/app/checkVersion", method = HttpMethod.Get, anonymous = true) { it.updates.check() },
            Contract("/app/appCommomUpload/uploadIdCardImg", multipart = true) { it.upload(UploadFile("fixture.png", byteArrayOf(1)), "fixture-user-id") },
        )
        assertEquals(56, calls.size)
        assertEquals(56, calls.map { it.path }.toSet().size)
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
            } else respond("{\"code\":200,\"data\":{}}")
        })
        try {
            for (contract in calls) {
                current = contract
                fixture.sessions.save(fixtureSession())
                assertIs<ApiResult.Success<*>>(contract.call(fixture.repositories), contract.path)
            }
            assertEquals(56, actualCalls)
        } finally { fixture.close() }
    }
}
