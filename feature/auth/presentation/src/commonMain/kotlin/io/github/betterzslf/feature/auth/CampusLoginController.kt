package io.github.betterzslf.feature.auth

import kotlin.coroutines.coroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * The campus-login presentation controller: a finite phase machine plus a
 * single-use ticket gate, nothing else. It starts no coroutines, performs no
 * network or storage access, and never navigates — the root owns the job, the
 * mount, the render and the edit callbacks.
 *
 * Construction takes exactly two typed inputs:
 *  - [sourceFactory], which the controller calls exactly once inside
 *    [beginSubmission] (never auto-recaptured), to capture the fixed source
 *    snapshot that enters the ticket;
 *  - [isCurrent], the root's fixed-page passive-lease gate.
 *
 * Credential rule: the username and password exist only as parameters of
 * [submit] for the instant of the source call. They are never stored in the
 * state, in controller fields, in any DTO, or in the public ticket.
 *
 * Threading rule: like the session store, the caller keeps the state-dispatcher
 * obligation — every method of this class must be invoked from one thread (or
 * one serialized dispatcher); the internal bookkeeping is not synchronized.
 */
class CampusLoginController(
    private val sourceFactory: () -> CampusLoginSource?,
    private val isCurrent: () -> Boolean,
) {
    private var phase: CampusLoginPhase = CampusLoginPhase.EDITING
    private var issue: CampusLoginIssue? = null
    private var inputRevision: Long = 0L
    private var busy: Boolean = false
    private var active: CampusLoginSubmission? = null
    private var closed: Boolean = false

    /** The finite public snapshot; read it after every controller call you make. */
    val state: CampusLoginState
        get() = CampusLoginState(
            phase = phase,
            issue = issue,
            inputRevision = inputRevision,
            busy = busy,
            submitAllowed = phase == CampusLoginPhase.EDITING || phase == CampusLoginPhase.REJECTED,
        )

    /**
     * The synchronous submit gate, run at the moment of the explicit SUBMIT tap.
     * Checks alive (not closed), current (the root's fixed-page gate still holds),
     * no active attempt, and an allowed phase (EDITING, or REJECTED for a manual
     * retry — never UNKNOWN without a new edit). On success it captures the source
     * via the factory exactly once, claims it into the ticket, and takes the busy
     * lock before returning, so a second synchronous begin is refused.
     * Returns null — changing nothing — whenever any clause fails.
     */
    fun beginSubmission(): CampusLoginSubmission? {
        if (closed) return null
        if (busy) return null
        if (phase != CampusLoginPhase.EDITING && phase != CampusLoginPhase.REJECTED) return null
        if (!isCurrent()) return null
        val source = sourceFactory() ?: return null
        if (!source.claim()) return null
        val ticket = CampusLoginSubmission(this, source)
        active = ticket
        busy = true
        phase = CampusLoginPhase.SUBMITTING
        issue = null
        return ticket
    }

    /**
     * Runs the one login attempt bound to [ticket]. The credentials are used only
     * as locals of this call frame and handed straight to the source; nothing is
     * retained afterwards.
     *
     * Returns true when this attempt terminated and its final phase was written
     * into the state (ACCEPTED / REJECTED / UNKNOWN / SESSION_CHANGED); false when
     * the receipt was dropped: a foreign ticket, a replayed ticket, or a ticket the
     * controller already retired (via [inputsChanged] or [close]).
     *
     * An accepted outcome is never re-checked against the gate — the login's own
     * session save advances the epoch, and the controller must not mistake its
     * own advance for a foreign change (own-accepted is not killed). Rejected and
     * unknown outcomes are re-checked: a gate that moved on converts the receipt
     * to SESSION_CHANGED.
     */
    suspend fun submit(ticket: CampusLoginSubmission, username: String, password: String): Boolean {
        if (ticket.owner !== this) return false
        if (ticket.started) return false
        if (ticket.retired) return false
        ticket.started = true
        try {
            coroutineContext.ensureActive()
            val outcome = ticket.source.submit(username, password)
            coroutineContext.ensureActive()
            return finishSubmission(ticket, outcome)
        } catch (cancelled: CancellationException) {
            // Retire our own attempt only; an old finally never touches a newer ticket.
            retireActiveAttempt()
            throw cancelled
        }
    }

    private fun finishSubmission(ticket: CampusLoginSubmission, outcome: CampusLoginOutcome): Boolean {
        if (closed) return false
        if (ticket.retired) return false
        when (outcome) {
            is CampusLoginOutcome.Accepted -> {
                // Own-accepted: no gate re-check; keep the private source for the
                // root's single consumeAcceptedContinuation call.
                busy = false
                phase = CampusLoginPhase.ACCEPTED
                issue = null
                return true
            }
            is CampusLoginOutcome.Rejected -> {
                busy = false
                active = null
                if (isCurrent()) {
                    phase = CampusLoginPhase.REJECTED
                    issue = outcome.issue
                } else {
                    phase = CampusLoginPhase.SESSION_CHANGED
                    issue = CampusLoginIssue.SESSION_CHANGED
                }
                return true
            }
            is CampusLoginOutcome.Unknown -> {
                busy = false
                active = null
                if (isCurrent()) {
                    phase = CampusLoginPhase.UNKNOWN
                    issue = outcome.issue
                } else {
                    phase = CampusLoginPhase.SESSION_CHANGED
                    issue = CampusLoginIssue.SESSION_CHANGED
                }
                return true
            }
        }
    }

    /**
     * The root consumes the accepted continuation exactly once, after the fixed
     * page decided the sign-in may continue into the app. Uses the source snapshot
     * bound to the ticket — never a re-capture — and is refused for a foreign,
     * replayed, retired, or already-consumed ticket.
     */
    fun consumeAcceptedContinuation(ticket: CampusLoginSubmission): Boolean {
        if (ticket.owner !== this) return false
        if (ticket.retired || ticket.consumed) return false
        if (phase != CampusLoginPhase.ACCEPTED || active !== ticket) return false
        ticket.consumed = true
        return ticket.source.consumeAcceptedContinuation()
    }

    /**
     * The edit generation advanced. Retires an in-flight attempt (its source is
     * cancelled; a late receipt is converted to a drop) and retires a pending
     * accepted or unknown receipt, reopening the submit gate. The session is never
     * touched here; REJECTED keeps its issue so the banner stays informative, and
     * SESSION_CHANGED stays for the root to resolve.
     */
    fun inputsChanged() {
        if (closed) return
        inputRevision += 1
        retireActiveAttempt()
        if (phase == CampusLoginPhase.ACCEPTED || phase == CampusLoginPhase.UNKNOWN) {
            phase = CampusLoginPhase.EDITING
            issue = null
        }
    }

    /**
     * Releases the controller: cancels only its own in-flight source, retires its
     * private state, and moves to the terminal CLOSED phase. A late finally of an
     * in-flight submit cannot write anything afterwards, and can never disturb a
     * newer ticket (none can exist after close, and none is created by the retire
     * path itself).
     */
    fun close() {
        if (closed) return
        closed = true
        retireActiveAttempt()
        phase = CampusLoginPhase.CLOSED
        issue = null
        busy = false
    }

    /**
     * Retires the active attempt, if any: cancels its own source, marks the ticket
     * retired (dropping any late receipt), releases the busy lock, and returns the
     * phase to EDITING. Never clears any session state.
     */
    private fun retireActiveAttempt() {
        val current = active ?: return
        current.source.cancel()
        current.retired = true
        active = null
        busy = false
        if (phase == CampusLoginPhase.SUBMITTING || phase == CampusLoginPhase.ACCEPTED) {
            phase = CampusLoginPhase.EDITING
            issue = null
        }
    }
}
