package io.github.betterzslf.feature.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * A pure-port fake of the domain source: counts every port call and can park the
 * in-flight submit on a gate so tests control exactly when a receipt arrives.
 */
private class FakeCampusLoginSource(
    private val claimResult: Boolean = true,
    private val outcome: () -> CampusLoginOutcome = {
        CampusLoginOutcome.Rejected(CampusLoginIssue.VALIDATION)
    },
) : CampusLoginSource {
    var currentResult: Boolean = true
    var submitted: Int = 0
    var cancelled: Int = 0
    var consumed: Int = 0
    var gate: CompletableDeferred<Unit>? = null

    override fun claim(): Boolean = claimResult
    override fun isCurrent(): Boolean = currentResult
    override suspend fun submit(username: String, password: String): CampusLoginOutcome {
        submitted += 1
        gate?.await()
        return outcome()
    }
    override fun cancel() { cancelled += 1 }
    override fun consumeAcceptedContinuation(): Boolean { consumed += 1; return true }
}

/**
 * Real controller against a fake source: the six legacy scenarios plus the gate,
 * resubmit, retry and retirement semantics.
 */
class CampusLoginControllerTest {

    private fun controller(
        source: CampusLoginSource?,
        current: () -> Boolean = { true },
        factoryCalls: (Int) -> Unit = {},
    ): CampusLoginController {
        var calls = 0
        return CampusLoginController(
            sourceFactory = { calls += 1; factoryCalls(calls); source },
            isCurrent = current,
        )
    }

    // --- beginSubmission gate, clause by clause ----------------------------

    @Test
    fun beginGateRefusesEachFailedClauseWithoutSideEffects() {
        // Closed.
        val closed = controller(FakeCampusLoginSource())
        closed.close()
        assertNull(closed.beginSubmission())
        assertEquals(CampusLoginPhase.CLOSED, closed.state.phase)

        // Fixed-page gate no longer current.
        var gate = false
        val foreign = controller(FakeCampusLoginSource(), current = { gate })
        assertNull(foreign.beginSubmission())
        assertEquals(CampusLoginState.initial(), foreign.state)

        // Factory yields nothing.
        assertNull(controller(null).beginSubmission())

        // Source refuses the claim.
        val refusing = FakeCampusLoginSource(claimResult = false)
        assertNull(controller(refusing).beginSubmission())
        assertEquals(0, refusing.submitted)
        assertEquals(0, refusing.cancelled)
        assertEquals(0, refusing.consumed)

        // Phase not allowed: UNKNOWN never begins without a new edit.
        val unknown = controller(FakeCampusLoginSource(outcome = {
            CampusLoginOutcome.Unknown(CampusLoginIssue.NETWORK)
        }))
        val unknownTicket = unknown.beginSubmission()
        assertNotNull(unknownTicket)
        runTest { unknown.submit(unknownTicket, "u", "p") }
        assertEquals(CampusLoginPhase.UNKNOWN, unknown.state.phase)
        assertNull(unknown.beginSubmission())

        // ACCEPTED never re-begins.
        val accepted = controller(FakeCampusLoginSource(outcome = { CampusLoginOutcome.Accepted }))
        val acceptedTicket = accepted.beginSubmission()!!
        runTest { accepted.submit(acceptedTicket, "u", "p") }
        assertEquals(CampusLoginPhase.ACCEPTED, accepted.state.phase)
        assertNull(accepted.beginSubmission())
    }

    @Test
    fun beginGateAcceptsFromEditingAndRejectedOnlyAndLocksBusyImmediately() {
        val fake = FakeCampusLoginSource()
        var factoryCalls = 0
        val login = CampusLoginController({ factoryCalls += 1; fake }, { true })

        val ticket = login.beginSubmission()
        assertNotNull(ticket)
        assertEquals(CampusLoginPhase.SUBMITTING, login.state.phase)
        assertTrue(login.state.busy)
        assertFalse(login.state.submitAllowed)
        assertEquals(1, factoryCalls)

        // A second synchronous begin is refused while the first holds the lock.
        assertNull(login.beginSubmission())
        assertEquals(1, factoryCalls)
    }

    @Test
    fun aRejectedPhaseAllowsOneManualResubmit() {
        val fake = FakeCampusLoginSource()
        val login = controller(fake)
        val first = login.beginSubmission()!!
        runTest { login.submit(first, "u", "p") }
        assertEquals(CampusLoginPhase.REJECTED, login.state.phase)
        assertEquals(CampusLoginIssue.VALIDATION, login.state.issue)
        assertTrue(login.state.submitAllowed)

        val second = login.beginSubmission()
        assertNotNull(second)
        assertTrue(second !== first)
        assertEquals(CampusLoginPhase.SUBMITTING, login.state.phase)
    }

    // --- ticket identity ----------------------------------------------------

    @Test
    fun aReplayedTicketIsRefusedAndNeverReachesTheSourceTwice() {
        val fake = FakeCampusLoginSource()
        val login = controller(fake)
        val ticket = login.beginSubmission()!!
        runTest {
            assertTrue(login.submit(ticket, "u", "p"))
            assertFalse(login.submit(ticket, "u", "p"))
        }
        assertEquals(1, fake.submitted)
        assertEquals(CampusLoginPhase.REJECTED, login.state.phase)
    }

    @Test
    fun aForeignTicketIsRefusedAndLeavesBothControllersUntouched() {
        val fakeA = FakeCampusLoginSource()
        val fakeB = FakeCampusLoginSource()
        val controllerA = controller(fakeA)
        val controllerB = controller(fakeB)
        val ticketA = controllerA.beginSubmission()!!
        val ticketB = controllerB.beginSubmission()!!

        runTest {
            assertFalse(controllerA.submit(ticketB, "u", "p"))
            assertFalse(controllerB.submit(ticketA, "u", "p"))
        }
        assertEquals(0, fakeA.submitted)
        assertEquals(0, fakeB.submitted)
        assertEquals(CampusLoginPhase.SUBMITTING, controllerA.state.phase)
        assertEquals(CampusLoginPhase.SUBMITTING, controllerB.state.phase)

        // Consuming across controllers is also refused.
        assertFalse(controllerA.consumeAcceptedContinuation(ticketB))
        assertEquals(0, fakeA.consumed)
        assertEquals(0, fakeB.consumed)
    }

    // --- receipt conversion --------------------------------------------------

    @Test
    fun aRetiredAttemptConvertsAnyLateReceiptIntoADrop() = runTest {
        val fake = FakeCampusLoginSource()
        fake.gate = CompletableDeferred()
        val login = controller(fake)
        val ticket = login.beginSubmission()!!
        var submitted: Boolean? = null
        val job = launch { submitted = login.submit(ticket, "u", "p") }
        advanceUntilIdle()

        // The edit generation retires the attempt: source cancelled, gate reopened.
        login.inputsChanged()
        assertEquals(1, fake.cancelled)
        assertEquals(CampusLoginPhase.EDITING, login.state.phase)
        assertFalse(login.state.busy)
        assertTrue(login.state.submitAllowed)
        assertEquals(1, login.state.inputRevision)

        fake.gate!!.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertEquals(false, submitted)
        // The late rejection never lands: the state stays retired-clean.
        assertEquals(CampusLoginPhase.EDITING, login.state.phase)
        assertNull(login.state.issue)
    }

    @Test
    fun aLateAcceptedReceiptAfterRetirementIsAlsoDropped() = runTest {
        val fake = FakeCampusLoginSource(outcome = { CampusLoginOutcome.Accepted })
        fake.gate = CompletableDeferred()
        val login = controller(fake)
        val ticket = login.beginSubmission()!!
        var submitted: Boolean? = null
        val job = launch { submitted = login.submit(ticket, "u", "p") }
        advanceUntilIdle()

        login.inputsChanged()
        fake.gate!!.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertEquals(false, submitted)
        assertEquals(CampusLoginPhase.EDITING, login.state.phase)
        // No continuation is left to consume after the receipt was retired.
        assertFalse(login.consumeAcceptedContinuation(ticket))
        assertEquals(0, fake.consumed)
    }

    @Test
    fun anOwnAcceptedReceiptIsNeverKilledByItsOwnSessionAdvance() = runTest {
        val fake = FakeCampusLoginSource(outcome = { CampusLoginOutcome.Accepted })
        // The root gate reads as not-current precisely because our own save advanced it.
        val login = controller(fake, current = { false })
        val ticket = login.beginSubmission()
        assertNull(ticket) // gate already closed — begin refused, nothing to kill.

        // A fresh controller whose gate closes only mid-flight:
        var current = true
        val live = CampusLoginController({ fake }, { current })
        val liveTicket = live.beginSubmission()!!
        current = false
        assertTrue(live.submit(liveTicket, "u", "p"))
        assertEquals(CampusLoginPhase.ACCEPTED, live.state.phase)
        assertNull(live.state.issue)
    }

    // --- failure receipts against the current gate ---------------------------

    @Test
    fun rejectedAndUnknownReceiptsLandWhenTheGateHolds() {
        val fake = FakeCampusLoginSource(outcome = {
            CampusLoginOutcome.Rejected(CampusLoginIssue.AUTHENTICATION)
        })
        val login = controller(fake)
        val ticket = login.beginSubmission()!!
        runTest { assertTrue(login.submit(ticket, "u", "p")) }
        assertEquals(CampusLoginPhase.REJECTED, login.state.phase)
        assertEquals(CampusLoginIssue.AUTHENTICATION, login.state.issue)
        assertTrue(login.state.submitAllowed)

        val uncertain = FakeCampusLoginSource(outcome = {
            CampusLoginOutcome.Unknown(CampusLoginIssue.TIMEOUT)
        })
        val unknownController = controller(uncertain)
        val unknownTicket = unknownController.beginSubmission()!!
        runTest { assertTrue(unknownController.submit(unknownTicket, "u", "p")) }
        assertEquals(CampusLoginPhase.UNKNOWN, unknownController.state.phase)
        assertEquals(CampusLoginIssue.TIMEOUT, unknownController.state.issue)
        assertFalse(unknownController.state.submitAllowed)
    }

    @Test
    fun rejectedAndUnknownReceiptsConvertToSessionChangedWhenTheGateMovedOn() {
        val fake = FakeCampusLoginSource(outcome = {
            CampusLoginOutcome.Rejected(CampusLoginIssue.AUTHENTICATION)
        })
        var current = true
        val login = CampusLoginController({ fake }, { current })
        val ticket = login.beginSubmission()!!
        current = false
        runTest { assertTrue(login.submit(ticket, "u", "p")) }
        assertEquals(CampusLoginPhase.SESSION_CHANGED, login.state.phase)
        assertEquals(CampusLoginIssue.SESSION_CHANGED, login.state.issue)
        assertFalse(login.state.submitAllowed)

        val uncertain = FakeCampusLoginSource(outcome = {
            CampusLoginOutcome.Unknown(CampusLoginIssue.NETWORK)
        })
        var uncertainCurrent = true
        val unknownController = CampusLoginController({ uncertain }, { uncertainCurrent })
        val unknownTicket = unknownController.beginSubmission()!!
        uncertainCurrent = false
        runTest { assertTrue(unknownController.submit(unknownTicket, "u", "p")) }
        assertEquals(CampusLoginPhase.SESSION_CHANGED, unknownController.state.phase)
        assertEquals(CampusLoginIssue.SESSION_CHANGED, unknownController.state.issue)
    }

    @Test
    fun unknownRefusesAnyResubmitUntilTheInputsChange() {
        val fake = FakeCampusLoginSource(outcome = {
            CampusLoginOutcome.Unknown(CampusLoginIssue.NETWORK)
        })
        val login = controller(fake)
        val ticket = login.beginSubmission()!!
        runTest { login.submit(ticket, "u", "p") }
        assertEquals(CampusLoginPhase.UNKNOWN, login.state.phase)
        assertNull(login.beginSubmission())

        login.inputsChanged()
        assertEquals(CampusLoginPhase.EDITING, login.state.phase)
        assertNotNull(login.beginSubmission())
    }

    // --- the single continuation ---------------------------------------------

    @Test
    fun theAcceptedContinuationIsConsumedExactlyOncePerTicket() = runTest {
        val fake = FakeCampusLoginSource(outcome = { CampusLoginOutcome.Accepted })
        val login = controller(fake)
        val ticket = login.beginSubmission()!!
        assertTrue(login.submit(ticket, "u", "p"))
        assertEquals(CampusLoginPhase.ACCEPTED, login.state.phase)

        assertTrue(login.consumeAcceptedContinuation(ticket))
        assertEquals(1, fake.consumed)
        assertFalse(login.consumeAcceptedContinuation(ticket))
        assertEquals(1, fake.consumed)

        // The phase is left for the root to act on; the ticket is spent.
        assertEquals(CampusLoginPhase.ACCEPTED, login.state.phase)
    }

    @Test
    fun anAcceptedReceiptIsRetiredByTheNextEditGeneration() = runTest {
        val fake = FakeCampusLoginSource(outcome = { CampusLoginOutcome.Accepted })
        val login = controller(fake)
        val ticket = login.beginSubmission()!!
        assertTrue(login.submit(ticket, "u", "p"))
        assertEquals(CampusLoginPhase.ACCEPTED, login.state.phase)

        login.inputsChanged()
        assertEquals(CampusLoginPhase.EDITING, login.state.phase)
        assertFalse(login.consumeAcceptedContinuation(ticket))
        assertEquals(0, fake.consumed)
    }

    // --- close ---------------------------------------------------------------

    @Test
    fun closeCancelsOnlyItsOwnSourceAndIsTerminal() = runTest {
        val fake = FakeCampusLoginSource()
        fake.gate = CompletableDeferred()
        val login = controller(fake)
        val ticket = login.beginSubmission()!!
        var submitted: Boolean? = null
        val job = launch { submitted = login.submit(ticket, "u", "p") }
        advanceUntilIdle()

        login.close()
        assertEquals(1, fake.cancelled)
        assertEquals(CampusLoginPhase.CLOSED, login.state.phase)
        assertNull(login.beginSubmission())

        // A late receipt after close never writes anything.
        fake.gate!!.complete(Unit)
        advanceUntilIdle()
        job.join()
        assertEquals(false, submitted)
        assertEquals(CampusLoginPhase.CLOSED, login.state.phase)

        // Idempotent: a second close is a no-op.
        login.close()
        assertEquals(1, fake.cancelled)
    }

    @Test
    fun closeIgnoresLaterInputsChangedAndNeverTouchesTheSession() {
        val fake = FakeCampusLoginSource()
        val login = controller(fake)
        login.close()
        login.inputsChanged()
        assertEquals(CampusLoginPhase.CLOSED, login.state.phase)
        assertEquals(0, fake.cancelled)
    }

    // --- cancellation and the old-finally guarantee --------------------------

    @Test
    fun aCancelledSubmitRetiresItsOwnAttemptOnlyAndNeverDisturbsANewTicket() = runTest {
        val fake = FakeCampusLoginSource()
        fake.gate = CompletableDeferred()
        val login = controller(fake)
        val first = login.beginSubmission()!!
        var cancelled = false
        val job = launch {
            try { login.submit(first, "u", "p") }
            catch (expected: CancellationException) { cancelled = true; throw expected }
        }
        advanceUntilIdle()

        job.cancel()
        job.join()
        assertTrue(cancelled)
        assertEquals(1, fake.cancelled)
        assertEquals(CampusLoginPhase.EDITING, login.state.phase)

        // The old finally must not block the next generation: a fresh begin works.
        val second = login.beginSubmission()
        assertNotNull(second)
        assertEquals(CampusLoginPhase.SUBMITTING, login.state.phase)
        assertTrue(login.state.busy)

        // Even if the old attempt's source answers now, the new ticket is untouched.
        fake.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(CampusLoginPhase.SUBMITTING, login.state.phase)
        assertTrue(login.state.busy)
    }

    // --- state invariants ------------------------------------------------------

    @Test
    fun everyPhaseKeepsTheFrozenInvariants() {
        val fake = FakeCampusLoginSource()
        val login = controller(fake)
        fun check() {
            val state = login.state
            assertEquals(state.submitAllowed,
                state.phase == CampusLoginPhase.EDITING || state.phase == CampusLoginPhase.REJECTED,
                "submitAllowed clause for ${state.phase}")
            assertEquals(state.busy, state.phase == CampusLoginPhase.SUBMITTING, "busy clause for ${state.phase}")
            assertEquals(
                state.issue != null,
                state.phase == CampusLoginPhase.REJECTED ||
                    state.phase == CampusLoginPhase.UNKNOWN ||
                    state.phase == CampusLoginPhase.SESSION_CHANGED,
                "issue clause for ${state.phase}",
            )
        }
        check()
        val ticket = login.beginSubmission()!!
        check()
        runTest { login.submit(ticket, "u", "p") }
        check()
        login.inputsChanged()
        check()
        login.close()
        check()
    }
}
