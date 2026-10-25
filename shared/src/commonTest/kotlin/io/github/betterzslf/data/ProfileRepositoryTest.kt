package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.feature.profile.*
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ProfileRepositoryTest {
    @Test
    fun bindingAndTypedCallsRequireARealOwnerAndDoZeroHttpWhenUnbound() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("No HTTP expected") }, authenticated = false)
        try {
            assertNull(fixture.repositories.profile.boundToCurrentSession())
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(fixture.repositories.profile.readFields()).error)
            fixture.sessions.save(fixtureSession().copy(userInfo = JsonObject(emptyMap())))
            assertNull(fixture.repositories.profile.boundToCurrentSession())
            assertEquals(0, requests)
        } finally { fixture.close() }
    }

    @Test
    fun staleOwnerTokenAndLogoutPreventAllThreeRawAndTypedCallsWithoutClearingNewLogin() = runTest {
        for (change in 0..2) {
            var requests = 0
            val fixture = ApiTestFixture(MockEngine { requests++; error("Stale binding must stay local") })
            try {
                val bound = assertNotNull(fixture.repositories.profile.boundToCurrentSession())
                assertSame(bound, bound.boundToCurrentSession())
                when (change) {
                    0 -> fixture.sessions.save(fixtureSession("replacement-fixture-token"))
                    1 -> fixture.sessions.save(fixtureSession().copy(userInfo = buildJsonObject { put("id", "other-fixture-owner") }))
                    else -> fixture.sessions.clear()
                }
                val before = fixture.sessions.session.value
                assertFalse(bound.isBoundToCurrentSession); assertNull(bound.boundToCurrentSession())
                val results = listOf(bound.userInfo(), bound.photo(), bound.statistics(), bound.readFields(), bound.readStatistics(), bound.readPhotoMetadata())
                for (result in results) assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(result).error)
                assertEquals(0, requests); assertSame(before, fixture.sessions.session.value)
                assertFalse(bound.toString().contains("fixture-token")); assertFalse(bound.toString().contains("fixture-user-id"))
            } finally { fixture.close() }
        }
    }

    @Test
    fun typedReadsUseDocumentedFieldsAndDoNotInventDepartmentOrAvatarAliases() = runTest {
        val paths = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request ->
            paths += request.url.encodedPath
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            assertTrue(request.url.parameters.isEmpty())
            respond(when {
                request.url.encodedPath.endsWith("getUserInfo") -> """{"code":200,"data":{"userInfo":{"name":"Fixture name","schoolName":"Fixture school","studentNum":"Fixture student","account":"Fixture account","typeValue":"Fixture role","sexValue":"Fixture gender","department":"not evidenced"}}}"""
                request.url.encodedPath.endsWith("getUserPhotoURL") -> """{"code":200,"data":{"attachment":{"filepath":"image/fixture.png"},"url":"https://evil.invalid/not-used"}}"""
                else -> """{"code":200,"data":{"collect":1,"comment":"2","history":3}}"""
            })
        })
        try {
            val bound = assertNotNull(fixture.repositories.profile.boundToCurrentSession())
            val fields = assertIs<ApiResult.Success<ProfileFields>>(bound.readFields()).data
            assertEquals("Fixture name", fields.name); assertEquals("Fixture school", fields.schoolName)
            assertEquals("Fixture student", fields.studentNumber); assertEquals("Fixture account", fields.account)
            assertEquals("Fixture role", fields.identity); assertEquals("Fixture gender", fields.gender)
            assertEquals(ProfileStatistics(1, 2, 3), assertIs<ApiResult.Success<ProfileStatistics>>(bound.readStatistics()).data)
            val photo = assertIs<ProfilePhotoMetadata.Resource>(assertIs<ApiResult.Success<ProfilePhotoMetadata>>(bound.readPhotoMetadata()).data)
            assertEquals("https://files.fixture.invalid/userfiles/image/fixture.png", photo.url)
            assertFalse(fields.toString().contains("Fixture name")); assertFalse(photo.toString().contains(photo.url))
            assertEquals(3, paths.size)
        } finally { fixture.close() }
    }

    @Test
    fun lateTyped200Or401NeverReturnsTheOldProfileOrLogsOutTheNewOwner() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.Unauthorized)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val fixture = ApiTestFixture(MockEngine { request ->
                assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
                entered.complete(Unit); release.await()
                respond("""{"code":200,"data":{"userInfo":{"name":"old-owner-fixture"}}}""", status)
            })
            try {
                val bound = assertNotNull(fixture.repositories.profile.boundToCurrentSession())
                val pending = async { bound.readFields() }
                entered.await()
                fixture.sessions.save(fixtureSession("replacement-fixture-token").copy(userInfo = buildJsonObject { put("id", "other-fixture-owner") }))
                release.complete(Unit)
                assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(pending.await()).error)
                assertEquals("replacement-fixture-token", fixture.sessions.session.value?.token)
                assertEquals("other-fixture-owner", fixture.sessions.session.value?.userId)
            } finally { fixture.close() }
        }
    }

    @Test
    fun malformedPhotoResponseIsDecodeNotASyntheticEmptyAvatar() = runTest {
        for (data in listOf("[]", "{\"attachment\":\"wrong\"}", "{\"attachment\":{\"filepath\":123}}")) {
            val fixture = ApiTestFixture(MockEngine { respond("{\"code\":200,\"data\":$data}") })
            try {
                val bound = assertNotNull(fixture.repositories.profile.boundToCurrentSession())
                assertIs<ApiError.Decode>(assertIs<ApiResult.Failure>(bound.readPhotoMetadata()).error)
            } finally { fixture.close() }
        }
    }
}
