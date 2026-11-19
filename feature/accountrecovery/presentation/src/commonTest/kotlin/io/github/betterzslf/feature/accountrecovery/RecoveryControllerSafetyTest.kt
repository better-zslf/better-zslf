package io.github.betterzslf.feature.accountrecovery

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class RecoveryControllerSafetyTest {
    private val phone = "13800000000"
    private val code = "000000"
    private val password = "Fixture#1"

    private class Source : AccountRecoverySource {
        var current = true
        var loggedOut = false
        var smsCalls = 0
        var verifyCalls = 0
        var resetCalls = 0
        var sms: RecoverySmsOutcome = RecoverySmsOutcome.Accepted
        var verified: RecoveryVerifyOutcome = RecoveryVerifyOutcome.Verified(requireNotNull(RecoveryTarget.fromVerifiedId("verified-secret-target")))
        var reset: RecoveryResetOutcome = RecoveryResetOutcome.Accepted(RecoveryLogout.CLEARED)
        var smsGate: CompletableDeferred<Unit>? = null
        var verifyGate: CompletableDeferred<Unit>? = null
        var resetGate: CompletableDeferred<Unit>? = null
        var ignoreResetCancellation = false
        override fun isCurrentSession() = current
        override fun isLoggedOut() = loggedOut
        override suspend fun sendCode(phone: String): RecoverySmsOutcome { smsCalls++; smsGate?.await(); return sms }
        override suspend fun verify(phone: String, code: String): RecoveryVerifyOutcome { verifyCalls++; verifyGate?.await(); return verified }
        override suspend fun reset(target: RecoveryTarget, password: String): RecoveryResetOutcome {
            resetCalls++
            if (ignoreResetCancellation) withContext(NonCancellable) { resetGate?.await() } else resetGate?.await()
            if (reset is RecoveryResetOutcome.Accepted) {
                current = false
                loggedOut = (reset as RecoveryResetOutcome.Accepted).logout != RecoveryLogout.NEW_SESSION_PRESERVED
            }
            return reset
        }
    }

    private suspend fun prepared(source: Source, onAccepted: (RecoveryLogout) -> Unit = {}): Pair<AccountRecoveryController, RecoveryResetTicket> {
        val controller = AccountRecoveryController(source, onAccepted = onAccepted)
        assertTrue(controller.sendCode(phone, true))
        assertTrue(controller.verify(phone, code))
        assertTrue(controller.prepare(phone, code, password, password))
        return controller to assertNotNull(controller.captureResetTicket())
    }

    @Test fun fixedTicketsRejectOtherControllersAndRetiredPreviewsWithoutAnyWrite() = runTest {
        val first = Source()
        val second = Source()
        val (a, ticketA) = prepared(first)
        val (b, ticketB) = prepared(second)
        try {
            assertEquals("RecoveryResetTicket(redacted)", ticketA.toString())
            assertTrue(a.isResetTicketCurrent(ticketA))
            assertFalse(b.isResetTicketCurrent(ticketA))
            assertFalse(b.confirm(ticketA, true))
            assertFalse(a.confirm(ticketB, true))
            assertEquals(0, first.resetCalls)
            assertEquals(0, second.resetCalls)
            a.editPreview()
            assertFalse(a.isResetTicketCurrent(ticketA))
            assertTrue(a.prepare(phone, code, password, password))
            val current = assertNotNull(a.captureResetTicket())
            assertFalse(a.isResetTicketCurrent(ticketA))
            assertFalse(a.confirm(ticketA, true))
            assertTrue(a.isResetTicketCurrent(current))
            assertFalse(a.confirm(current, false))
            assertTrue(a.isResetTicketCurrent(current))
            assertTrue(a.confirm(current, true))
            assertFalse(a.isResetTicketCurrent(current))
            assertNull(a.captureResetTicket())
            assertFalse(a.confirm(current, true))
            assertEquals(1, first.resetCalls)
        } finally { a.close(); b.close() }
    }

    @Test fun safeStateContainsNeitherTargetNorTicketNorInputCredentials() = runTest {
        val source = Source()
        val (controller, ticket) = prepared(source)
        try {
            assertEquals(AccountRecoveryPreview("138••••00"), controller.state.value.preview)
            val diagnostics = controller.state.value.toString() + controller.toString() + ticket.toString()
            for (secret in listOf(phone, code, password, "verified-secret-target", "RecoveryTarget", "nonce", "previewId"))
                assertFalse(diagnostics.contains(secret))
        } finally { controller.close() }
    }

    @Test fun eachTypedUnknownSmsIssueStopsTheAttemptWithoutCountdownOrResend() = runTest {
        for (issue in RecoveryIssue.entries.filter { it !in setOf(RecoveryIssue.BUSINESS, RecoveryIssue.VALIDATION) }) {
            val source = Source().apply { sms = RecoverySmsOutcome.Unknown(issue) }
            val controller = AccountRecoveryController(source)
            try {
                assertFalse(controller.sendCode(phone, true))
                assertEquals(AccountRecoveryPhase.UNCERTAIN, controller.state.value.phase)
                assertEquals(0, controller.state.value.cooldownSeconds)
                assertFalse(controller.sendCode(phone, true))
                assertFalse(controller.verify(phone, code))
                assertNull(controller.captureResetTicket())
                assertEquals(1, source.smsCalls)
                assertEquals(0, source.verifyCalls)
                assertEquals(0, source.resetCalls)
            } finally { controller.close() }
        }
    }

    @Test fun lateSmsAckAfterCodeEditKeepsCooldownWithoutAuthorizingAnyInput() = runTest {
        var now = 1000L
        val gate = CompletableDeferred<Unit>()
        val source = Source().apply { smsGate = gate }
        val controller = AccountRecoveryController(source, nowMillis = { now })
        try {
            val job = launch { assertFalse(controller.sendCode(phone, true)) }
            runCurrent()
            assertEquals(RecoveryBackDecision.WAIT_FOR_RECEIPT, recoveryBackDecision(controller.state.value))
            controller.codeChanged()
            gate.complete(Unit); job.join()
            assertEquals(60, controller.state.value.cooldownSeconds)
            assertFalse(controller.state.value.codeRequested)
            assertFalse(controller.verify(phone, code))
            now += 60_000L; controller.tickCooldown()
            assertEquals(0, controller.state.value.cooldownSeconds)
            assertEquals(1, source.smsCalls)
            assertEquals(0, source.verifyCalls)
            assertEquals(RecoveryBackDecision.ALLOW, recoveryBackDecision(controller.state.value))
        } finally { gate.complete(Unit); controller.close() }
    }

    @Test fun cancellingSmsIsUnknownNotRevokedAndCannotAutomaticallyResend() = runTest {
        val source = Source().apply { smsGate = CompletableDeferred() }
        val controller = AccountRecoveryController(source)
        try {
            val job = launch { controller.sendCode(phone, true) }
            runCurrent(); job.cancelAndJoin()
            assertEquals(AccountRecoveryPhase.UNCERTAIN, controller.state.value.phase)
            assertFalse(controller.state.value.sending)
            assertEquals(0, controller.state.value.cooldownSeconds)
            assertFalse(controller.sendCode(phone, true))
            assertEquals(1, source.smsCalls)
            assertEquals(RecoveryBackDecision.ALLOW, recoveryBackDecision(controller.state.value))
        } finally { controller.close() }
    }

    @Test fun missingTargetAndVerificationFailuresNeverCreateAConfirmationTicket() = runTest {
        for (outcome in listOf(RecoveryVerifyOutcome.MissingTarget, RecoveryVerifyOutcome.Failed(RecoveryIssue.DECODE))) {
            val source = Source().apply { verified = outcome }
            val controller = AccountRecoveryController(source)
            try {
                assertTrue(controller.sendCode(phone, true))
                assertFalse(controller.verify(phone, code))
                assertFalse(controller.state.value.verified)
                assertFalse(controller.prepare(phone, code, password, password))
                assertNull(controller.captureResetTicket())
                assertEquals(0, source.resetCalls)
            } finally { controller.close() }
        }
    }

    @Test fun closeDuringLateAcceptedResetNeverPublishesReceiptOrReopensState() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = Source().apply { resetGate = gate; ignoreResetCancellation = true }
        val receipts = mutableListOf<RecoveryLogout>()
        val (controller, ticket) = prepared(source) { receipts += it }
        val job = launch { assertFalse(controller.confirm(ticket, true)) }
        runCurrent(); controller.close(); gate.complete(Unit); job.join()
        assertEquals(AccountRecoveryPhase.CLOSED, controller.state.value.phase)
        assertNull(controller.state.value.preview)
        assertNull(controller.captureResetTicket())
        assertTrue(receipts.isEmpty())
        assertEquals(1, source.resetCalls)
        assertFalse(controller.confirm(ticket, true))
    }

    @Test fun typedResetRejectionAndEveryUnknownIssueEndTheAttemptWithoutAnotherWrite() = runTest {
        for (issue in RecoveryIssue.entries) {
            val explicit = issue == RecoveryIssue.BUSINESS || issue == RecoveryIssue.VALIDATION
            val source = Source().apply { reset = if (explicit) RecoveryResetOutcome.Rejected(issue) else RecoveryResetOutcome.Unknown(issue) }
            val receipts = mutableListOf<RecoveryLogout>()
            val (controller, ticket) = prepared(source) { receipts += it }
            try {
                assertFalse(controller.confirm(ticket, true))
                assertEquals(if (explicit) AccountRecoveryPhase.FAILED else AccountRecoveryPhase.UNCERTAIN, controller.state.value.phase)
                assertFalse(controller.confirm(ticket, true))
                assertFalse(controller.prepare(phone, code, password, password))
                assertFalse(controller.sendCode(phone, true))
                assertNull(controller.captureResetTicket())
                assertTrue(receipts.isEmpty())
                assertEquals(1, source.resetCalls)
                assertEquals(1, source.smsCalls)
            } finally { controller.close() }
        }
    }

    @Test fun retiredBindingInvalidatesCapturedTicketBeforeAnyConfirmationWrite() = runTest {
        val source = Source()
        val (controller, ticket) = prepared(source)
        try {
            source.current = false
            assertFalse(controller.isResetTicketCurrent(ticket))
            assertEquals(AccountRecoveryPhase.SESSION_CHANGED, controller.state.value.phase)
            assertNull(controller.captureResetTicket())
            assertFalse(controller.confirm(ticket, true))
            assertNull(controller.state.value.preview)
            assertFalse(controller.state.value.verified)
            assertEquals(0, source.resetCalls)
        } finally { controller.close() }
    }

    @Test fun acceptedCleanupFailurePublishesOneReceiptWithoutGenericCurrentCheckRejectingOwnLogout() = runTest {
        val source = Source().apply { reset = RecoveryResetOutcome.Accepted(RecoveryLogout.CLEAR_FAILED) }
        val receipts = mutableListOf<RecoveryLogout>()
        val (controller, ticket) = prepared(source) { receipts += it }
        try {
            assertTrue(controller.confirm(ticket, true))
            assertFalse(source.isCurrentSession())
            assertTrue(source.isLoggedOut())
            assertEquals(AccountRecoveryPhase.CLEANUP_WARNING, controller.state.value.phase)
            assertEquals(listOf(RecoveryLogout.CLEAR_FAILED), receipts)
            assertFalse(controller.confirm(ticket, true))
            assertFalse(controller.prepare(phone, code, password, password))
            assertEquals(1, source.resetCalls)
        } finally { controller.close() }
    }
}
