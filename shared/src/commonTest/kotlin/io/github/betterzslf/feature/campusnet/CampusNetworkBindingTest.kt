package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.net.fixtureSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private const val ACCOUNT_ENVELOPE = """{"code":200,"data":{"operatorAccount":[{"id":"fixture-target","operatorAccount":"13800000000","typeStatus":"1"}]}}"""
private const val ACK_ENVELOPE = """{"code":200,"data":{}}"""

class CampusNetworkBindingTest {
    private fun controller(fixture: ApiTestFixture) = CampusNetworkController(
        assertNotNull(RepositoryCampusNetworkSource.capture(fixture.repositories.campusNetwork)))

    private suspend fun prepare(controller: CampusNetworkController): Long {
        assertTrue(controller.refresh())
        controller.setExpanded(true)
        controller.selectAccount(controller.state.value.accounts.single().key)
        assertTrue(controller.checkPhone("13800000000"))
        assertTrue(controller.prepare("13800000000", "fixture-new-password", "fixture-new-password"))
        return assertNotNull(controller.state.value.preview).id
    }

    @Test fun changingTokenBetweenPreviewAndFinalConfirmationSendsZeroWriteRequests() = runTest {
        val calls = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request ->
            val route = request.url.encodedPath.substringAfterLast('/')
            calls += route
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            assertNotEquals("updateOperatorPasswordById", route)
            respond(if (route == "getOperatorAccountByIDCard") ACCOUNT_ENVELOPE else ACK_ENVELOPE,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val controller = controller(fixture)
            val id = prepare(controller)
            fixture.sessions.save(fixtureSession("replacement-token"))
            assertFalse(controller.confirm(id, true))
            assertEquals(listOf("getOperatorAccountByIDCard", "checkPhone"), calls)
            assertEquals(CampusPasswordPhase.SESSION_CHANGED, controller.state.value.phase)
            assertTrue(controller.state.value.accounts.isEmpty())
            assertNull(controller.state.value.preview)
            assertEquals("replacement-token", fixture.sessions.session.value?.token)
        } finally { fixture.close() }
    }

    @Test fun latePhoneAcknowledgementFromAnOldSessionIsDiscarded() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            val route = request.url.encodedPath.substringAfterLast('/')
            if (route == "checkPhone") { started.complete(Unit); release.await() }
            if (route == "updateOperatorPasswordById") writes++
            respond(if (route == "getOperatorAccountByIDCard") ACCOUNT_ENVELOPE else ACK_ENVELOPE,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val controller = controller(fixture)
            controller.refresh(); controller.setExpanded(true)
            controller.selectAccount(controller.state.value.accounts.single().key)
            val checking = launch { assertFalse(controller.checkPhone("13800000000")) }
            started.await()
            fixture.sessions.save(fixtureSession("replacement-token"))
            release.complete(Unit); checking.join()
            assertFalse(controller.state.value.phoneChecked)
            assertTrue(controller.state.value.accounts.isEmpty())
            assertEquals(CampusPasswordPhase.SESSION_CHANGED, controller.state.value.phase)
            assertFalse(controller.prepare("13800000000", "fixture-new", "fixture-new"))
            assertEquals(0, writes)
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun unknownActualTransportEnvelopeIsUncertainAndNeverRetransmitted() = runTest {
        val calls = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request ->
            val route = request.url.encodedPath.substringAfterLast('/')
            calls += route
            val body = when (route) {
                "getOperatorAccountByIDCard" -> ACCOUNT_ENVELOPE
                "checkPhone" -> ACK_ENVELOPE
                "updateOperatorPasswordById" -> "accepted-but-not-a-recognized-envelope"
                else -> error("Unexpected fixture route")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val controller = controller(fixture)
            val id = prepare(controller)
            assertFalse(controller.confirm(id, true))
            assertEquals(CampusPasswordPhase.UNCERTAIN, controller.state.value.phase)
            assertFalse(controller.confirm(id, true))
            assertFalse(controller.prepare("13800000000", "fixture-new", "fixture-new"))
            assertEquals(listOf("getOperatorAccountByIDCard", "checkPhone", "updateOperatorPasswordById"), calls)
        } finally { fixture.close() }
    }
}
