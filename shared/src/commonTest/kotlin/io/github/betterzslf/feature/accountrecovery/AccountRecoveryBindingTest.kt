package io.github.betterzslf.feature.accountrecovery

import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.net.fixtureSession
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private const val SMS_ACK = """{"code":200,"data":{}}"""
private const val VERIFIED = """{"code":200,"data":{"user":{"id":901}}}"""

class AccountRecoveryBindingTest {
    private fun controller(fixture: ApiTestFixture, onAccepted: (RecoveryLogout) -> Unit = {}) =
        AccountRecoveryController(assertNotNull(ApiAccountRecoverySource.capture(fixture.api, fixture.sessions)), onAccepted = onAccepted)
    private suspend fun prepare(controller: AccountRecoveryController): RecoveryResetTicket {
        assertTrue(controller.sendCode("13800000000", true))
        assertTrue(controller.verify("13800000000", "000000"))
        assertTrue(controller.prepare("13800000000", "000000", "Fixture#1", "Fixture#1"))
        assertNotNull(controller.state.value.preview)
        return assertNotNull(controller.captureResetTicket())
    }

    @Test fun fullBoundFixtureUsesVerifiedTargetAndKeepsItsOwnLogoutAcknowledgement() = runTest {
        val calls = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request ->
            val path = request.url.encodedPath
            calls += path
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            if (path == "/app/appUser/resetNewPassWord") assertEquals("901", request.url.parameters["userId"])
            respond(if (path == "/app/appUser/checkPhone") VERIFIED else SMS_ACK)
        })
        try {
            val receipts = mutableListOf<RecoveryLogout>()
            val controller = controller(fixture) { receipts += it }
            val ticket = prepare(controller)
            assertTrue(controller.confirm(ticket, true))
            assertEquals(listOf("/app/campusNetwork/checkPhone", "/app/appUser/checkPhone", "/app/appUser/resetNewPassWord"), calls)
            assertEquals(AccountRecoveryPhase.COMPLETED, controller.state.value.phase)
            assertEquals(listOf(RecoveryLogout.CLEARED), receipts)
            assertNull(fixture.sessions.session.value)
            assertFalse(controller.confirm(ticket, true))
            assertEquals(AccountRecoveryPhase.COMPLETED, controller.state.value.phase)
        } finally { fixture.close() }
    }

    @Test fun newTokenBetweenVerificationAndConfirmationSendsZeroResetHttp() = runTest {
        val calls = mutableListOf<String>()
        val fixture = ApiTestFixture(MockEngine { request ->
            calls += request.url.encodedPath
            assertNotEquals("/app/appUser/resetNewPassWord", request.url.encodedPath)
            respond(if (request.url.encodedPath == "/app/appUser/checkPhone") VERIFIED else SMS_ACK)
        })
        try {
            val controller = controller(fixture)
            val id = prepare(controller)
            fixture.sessions.save(fixtureSession("replacement-token"))
            assertFalse(controller.confirm(id, true))
            assertEquals(AccountRecoveryPhase.SESSION_CHANGED, controller.state.value.phase)
            assertNull(controller.state.value.preview)
            assertEquals(2, calls.size)
            assertEquals("replacement-token", fixture.sessions.session.value?.token)
        } finally { fixture.close() }
    }

    @Test fun lateVerificationForAnOldTokenNeverBecomesIdentityForTheNewSession() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = ApiTestFixture(MockEngine { request ->
            val verifying = request.url.encodedPath == "/app/appUser/checkPhone"
            if (verifying) { started.complete(Unit); release.await() }
            respond(if (verifying) VERIFIED else SMS_ACK)
        })
        try {
            val controller = controller(fixture)
            controller.sendCode("13800000000", true)
            val job = launch { assertFalse(controller.verify("13800000000", "000000")) }
            started.await(); fixture.sessions.save(fixtureSession("replacement-token"))
            release.complete(Unit); job.join()
            assertEquals(AccountRecoveryPhase.SESSION_CHANGED, controller.state.value.phase)
            assertFalse(controller.state.value.verified)
            assertFalse(controller.prepare("13800000000", "000000", "Fixture#1", "Fixture#1"))
            assertEquals("replacement-token", fixture.sessions.session.value?.token)
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun sameValueAndClearSaveEpochsRetireTheCapturedTicketBeforeAnyWrite() = runTest {
        for (clearFirst in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val fixture = ApiTestFixture(MockEngine { request ->
                calls += request.url.encodedPath
                assertNotEquals("/app/appUser/resetNewPassWord", request.url.encodedPath)
                respond(if (request.url.encodedPath == "/app/appUser/checkPhone") VERIFIED else SMS_ACK)
            })
            try {
                val controller = controller(fixture)
                val ticket = prepare(controller)
                val original = assertNotNull(fixture.sessions.session.value)
                if (clearFirst) fixture.sessions.clear()
                fixture.sessions.save(original.copy())
                assertFalse(controller.isResetTicketCurrent(ticket))
                assertFalse(controller.confirm(ticket, true))
                assertEquals(AccountRecoveryPhase.SESSION_CHANGED, controller.state.value.phase)
                assertNull(controller.state.value.preview)
                assertEquals(2, calls.size)
                assertEquals(original, fixture.sessions.session.value)
            } finally { fixture.close() }
        }
    }

    @Test fun lateAcceptedResetCannotPublishOldReceiptAfterSameValueEpochReplacement() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            val path = request.url.encodedPath
            if (path == "/app/appUser/resetNewPassWord") { writes++; started.complete(Unit); release.await() }
            respond(if (path == "/app/appUser/checkPhone") VERIFIED else SMS_ACK)
        })
        try {
            val receipts = mutableListOf<RecoveryLogout>()
            val controller = controller(fixture) { receipts += it }
            val ticket = prepare(controller)
            val job = launch { assertFalse(controller.confirm(ticket, true)) }
            started.await()
            val original = assertNotNull(fixture.sessions.session.value)
            fixture.sessions.clear(); fixture.sessions.save(original.copy())
            val epoch = fixture.sessions.revision.value
            release.complete(Unit); job.join()
            assertEquals(AccountRecoveryPhase.SESSION_CHANGED, controller.state.value.phase)
            assertEquals(original, fixture.sessions.session.value)
            assertEquals(epoch, fixture.sessions.revision.value)
            assertTrue(receipts.isEmpty())
            assertEquals(1, writes)
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun lateResetAckPreservesTheNewLoginAndDoesNotDisplayAnOldReceipt() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            val path = request.url.encodedPath
            if (path == "/app/appUser/resetNewPassWord") { writes++; started.complete(Unit); release.await() }
            respond(if (path == "/app/appUser/checkPhone") VERIFIED else SMS_ACK)
        })
        try {
            val receipts = mutableListOf<RecoveryLogout>()
            val controller = controller(fixture) { receipts += it }
            val id = prepare(controller)
            val job = launch { assertFalse(controller.confirm(id, true)) }
            started.await(); fixture.sessions.save(fixtureSession("replacement-token"))
            release.complete(Unit); job.join()
            assertEquals(AccountRecoveryPhase.SESSION_CHANGED, controller.state.value.phase)
            assertEquals("replacement-token", fixture.sessions.session.value?.token)
            assertTrue(receipts.isEmpty())
            assertEquals(1, writes)
        } finally { release.complete(Unit); fixture.close() }
    }
}
