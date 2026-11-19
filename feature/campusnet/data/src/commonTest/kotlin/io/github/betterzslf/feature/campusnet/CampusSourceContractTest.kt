package io.github.betterzslf.feature.campusnet

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CampusSourceContractTest {
    @Test fun sevenTypedEndpointsKeepExactPathsParametersAuthorizationAndEnvelopeOnlyUnitAcknowledgements() = runTest {
        val calls = mutableListOf<String>()
        val fixture = CampusDataFixture(MockEngine { request ->
            val path = request.url.encodedPath; calls += path
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            val expected = when (path) {
                "/app/campusNetwork/getOperatorAccountByIDCard", "/app/verification/getInternetInfo",
                "/app/verification/getRedirectUrl", "/app/verification/appOffline" -> emptyMap()
                "/app/campusNetwork/checkPhone" -> mapOf("phone" to "13800000000")
                "/app/campusNetwork/updateOperatorPasswordById" -> mapOf("operatorAccountId" to "fixture-id", "newOperatorPassword" to "fixture-new")
                "/app/verification/appDialInLogin" -> mapOf("urls" to "http://fixture.portal/a", "resData" to "http://fixture.portal/a")
                else -> error("Unexpected campus fixture route")
            }
            assertEquals(expected, request.url.parameters.entries().associate { it.key to it.value.single() })
            val data = when (path) {
                "/app/campusNetwork/getOperatorAccountByIDCard" -> """{"operatorAccount":[{"id":"fixture-id","operatorAccount":"13800000000","typeStatus":"future"}]}"""
                "/app/verification/getInternetInfo" -> """{"status":1}"""
                "/app/verification/getRedirectUrl" -> """{"addr":"http://9.9.9.9","checkStr":"ignored"}"""
                "/app/verification/appDialInLogin" -> """{"status":"1"}"""
                else -> """{"status":"0","msg":"ignored-unit-payload"}"""
            }
            respond("""{"code":200,"data":$data}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val accounts = assertNotNull(RepositoryCampusNetworkSource.capture(fixture.campusNetwork))
            val portal = assertNotNull(RepositoryCampusPortalSource.capture(fixture.campusPortal))
            assertTrue(calls.isEmpty())
            assertEquals("fixture-id", assertIs<CampusOutcome.Success<List<CampusAccountRecord>>>(accounts.accounts()).data.single().id)
            assertEquals(Unit, assertIs<CampusOutcome.Success<Unit>>(accounts.checkPhone("13800000000")).data)
            assertEquals(Unit, assertIs<CampusOutcome.Success<Unit>>(accounts.updatePassword("fixture-id", "fixture-new")).data)
            assertEquals(CampusOnlineStatus.Online, assertIs<CampusOutcome.Success<CampusOnlineStatus>>(portal.internetInfo()).data)
            assertEquals("http://9.9.9.9", assertIs<CampusOutcome.Success<CampusRedirectTarget>>(portal.redirectTarget()).data.address)
            assertEquals(CampusDialResult.Accepted, assertIs<CampusOutcome.Success<CampusDialResult>>(portal.dialIn("http://fixture.portal/a")).data)
            assertEquals(Unit, assertIs<CampusOutcome.Success<Unit>>(portal.offline()).data)
            assertEquals(7, calls.size); assertEquals(7, calls.toSet().size)
            assertFalse(accounts.toString().contains("fixture-token")); assertFalse(portal.toString().contains("fixture-token"))
        } finally { fixture.close() }

        // The same typed Unit method preserves finite failure classification without auto retry.
        val failureCases = listOf(CampusIssue.BUSINESS, CampusIssue.HTTP, CampusIssue.MALFORMED, CampusIssue.TIMEOUT, CampusIssue.NETWORK)
        for (issue in failureCases) {
            var requests = 0
            val failed = CampusDataFixture(MockEngine {
                requests++
                when (issue) {
                    CampusIssue.BUSINESS -> respond("""{"code":400,"message":"fixture rejection"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    CampusIssue.HTTP -> respond("unavailable", HttpStatusCode.ServiceUnavailable)
                    CampusIssue.MALFORMED -> respond("not-an-envelope")
                    CampusIssue.TIMEOUT -> throw io.ktor.client.plugins.HttpRequestTimeoutException("https://fixture.invalid", 8_000)
                    else -> throw IllegalStateException("fixture-only network failure")
                }
            })
            try {
                val source = assertNotNull(RepositoryCampusNetworkSource.capture(failed.campusNetwork))
                assertEquals(issue, assertIs<CampusOutcome.Failure>(source.checkPhone("13800000000")).issue)
                assertEquals(1, requests)
                assertEquals(CampusIssue.VALIDATION, assertIs<CampusOutcome.Failure>(source.checkPhone("")).issue)
                assertEquals(1, requests)
            } finally { failed.close() }
        }
        var cancelledCalls = 0
        val cancelled = CampusDataFixture(MockEngine { cancelledCalls++; throw kotlinx.coroutines.CancellationException("fixture cancellation") })
        try {
            val source = assertNotNull(RepositoryCampusNetworkSource.capture(cancelled.campusNetwork))
            assertFailsWith<kotlinx.coroutines.CancellationException> { source.checkPhone("13800000000") }
            assertEquals(1, cancelledCalls)
        } finally { cancelled.close() }
    }
}
