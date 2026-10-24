package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class CampusNetworkRepositoryTest {
    @Test fun boundQueriesUseOnlyConfirmedFieldsAndOneCapturedAuthorization() = runTest {
        val calls = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request ->
            calls += request.url.encodedPath
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            when (request.url.encodedPath.substringAfterLast('/')) {
                "getOperatorAccountByIDCard" -> assertTrue(request.url.parameters.isEmpty())
                "checkPhone" -> {
                    assertEquals(setOf("phone"), request.url.parameters.names())
                    assertEquals("13800000000", request.url.parameters["phone"])
                }
                "updateOperatorPasswordById" -> {
                    assertEquals(setOf("operatorAccountId", "newOperatorPassword"), request.url.parameters.names())
                    assertEquals("fixture-operator-id", request.url.parameters["operatorAccountId"])
                    assertEquals("fixture-operator-password", request.url.parameters["newOperatorPassword"])
                }
                else -> fail("Unexpected fixture route")
            }
            respond("""{"code":200,"data":{}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val bound = assertNotNull(fixture.repositories.campusNetwork.boundToCurrentSession())
            assertTrue(bound.isBoundToCurrentSession)
            assertIs<ApiResult.Success<*>>(bound.accounts())
            assertIs<ApiResult.Success<*>>(bound.checkPhone("13800000000"))
            assertIs<ApiResult.Success<*>>(bound.updatePassword("fixture-operator-id", "fixture-operator-password"))
            assertEquals(3, calls.size)
            assertFalse(bound.toString().contains("fixture-token"))
        } finally { fixture.close() }
    }

    @Test fun tokenReplacementMakesEveryStaleBoundCallLocalAndDoesNotRebind() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("A stale binding must not reach HTTP") })
        try {
            val bound = assertNotNull(fixture.repositories.campusNetwork.boundToCurrentSession())
            fixture.sessions.save(fixtureSession("replacement-token"))
            assertFalse(bound.isBoundToCurrentSession)
            assertNull(bound.boundToCurrentSession())
            for (result in listOf(bound.accounts(), bound.checkPhone("13800000000"), bound.updatePassword("fixture-id", "new"))) {
                assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(result).error)
            }
            assertEquals(0, requests)
            assertEquals("replacement-token", fixture.sessions.session.value?.token)
        } finally { fixture.close() }
    }

    @Test fun ownerReplacementEvenWithTheSameTokenInvalidatesTheBinding() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("Old owner must not reach HTTP") })
        try {
            val bound = assertNotNull(fixture.repositories.campusNetwork.boundToCurrentSession())
            fixture.sessions.save(fixtureSession().copy(userInfo = buildJsonObject { put("id", "different-fixture-owner") }))
            assertFalse(bound.isBoundToCurrentSession)
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(bound.updatePassword("old-target", "new")).error)
            assertEquals(0, requests)
            assertEquals("different-fixture-owner", fixture.sessions.session.value?.userId)
        } finally { fixture.close() }
    }

    @Test fun anonymousRepositoryDoesNotOfferABoundOperation() {
        val fixture = ApiTestFixture(MockEngine { error("No fixture requests expected") }, authenticated = false)
        try { assertNull(fixture.repositories.campusNetwork.boundToCurrentSession()) }
        finally { fixture.close() }
    }
}
