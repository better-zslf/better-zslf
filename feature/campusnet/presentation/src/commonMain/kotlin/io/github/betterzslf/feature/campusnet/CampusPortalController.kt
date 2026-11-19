package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** School-mediated portal path only. Construction performs no request, authentication or retry. */
class CampusPortalController(
    private val source: CampusPortalSource?,
    private val probe: CampusPortalProbeSource?,
    private val clockMillis: () -> Long,
) {
    private val mutable = MutableStateFlow(CampusPortalState())
    val state: StateFlow<CampusPortalState> = mutable.asStateFlow()
    private val flight = Mutex()
    private var alive = true

    fun close() { alive = false }

    private fun current(): Boolean = alive && source?.isCurrentSession() == true

    /** Read-only status check. Never authenticates, so it is safe to call on every page open. */
    suspend fun refresh(): Boolean {
        if (!flight.tryLock()) return false
        try {
            if (!current()) {
                mutable.value = mutable.value.copy(phase = CampusPortalPhase.NotSignedIn,
                    detail = AppText.CampusNetwork.PORTAL_SIGNED_OUT_DETAIL, connectedAtMillis = null)
                return false
            }
            val response = call { source!!.internetInfo() }
            currentCoroutineContext().ensureActive()
            if (!current()) return false
            return when (response) {
                is CampusOutcome.Failure -> {
                    mutable.value = mutable.value.copy(phase = CampusPortalPhase.Failed, detail = campusFailure(response.issue))
                    false
                }
                is CampusOutcome.Success -> applyStatus(response.data)
            }
        } finally { flight.unlock() }
    }

    private fun applyStatus(status: CampusOnlineStatus): Boolean = when (status) {
        CampusOnlineStatus.Online -> {
            val previous = mutable.value
            mutable.value = previous.copy(phase = CampusPortalPhase.Online, detail = "",
                connectedAtMillis = previous.connectedAtMillis ?: clockMillis())
            true
        }
        CampusOnlineStatus.Offline -> {
            mutable.value = mutable.value.copy(phase = CampusPortalPhase.Offline, detail = "", connectedAtMillis = null)
            true
        }
        is CampusOnlineStatus.Unreadable -> {
            mutable.value = mutable.value.copy(phase = CampusPortalPhase.Failed,
                detail = AppText.CampusNetwork.SERVICE_RESPONSE_CONTENT_UNCONFIRMED, connectedAtMillis = null)
            false
        }
    }

    /** One explicit authentication attempt; the caller owns all later retry decisions. */
    suspend fun connect(): Boolean {
        if (!flight.tryLock()) return false
        try {
            if (!current()) {
                mutable.value = mutable.value.copy(phase = CampusPortalPhase.NotSignedIn,
                    detail = AppText.CampusNetwork.PORTAL_SIGNED_OUT_DETAIL)
                return false
            }
            mutable.value = mutable.value.copy(phase = CampusPortalPhase.Connecting, detail = "",
                manualPortalOpened = false, autoAttempted = true)

            val response = call { source!!.redirectTarget() }
            currentCoroutineContext().ensureActive()
            if (!current()) return false
            val target = when (response) {
                is CampusOutcome.Failure -> { fail(campusFailure(response.issue)); return false }
                is CampusOutcome.Success -> response.data.address
            }
            if (target.isBlank()) {
                mutable.value = mutable.value.copy(portalAddress = null)
                fail(AppText.CampusNetwork.PORTAL_ADDRESS_MISSING_DETAIL); return false
            }
            val probeSource = probe
            if (probeSource == null) {
                fail(AppText.CampusNetwork.PORTAL_ADDRESS_BLOCKED_DETAIL); return false
            }
            currentCoroutineContext().ensureActive()
            if (!current()) return false
            val probed = probeSource.probe(target)
            currentCoroutineContext().ensureActive()
            if (!current()) return false
            val finalUrl = when (probed) {
                is PortalProbeResult.Answered -> probed.url
                PortalProbeResult.Blocked -> {
                    mutable.value = mutable.value.copy(portalAddress = target)
                    fail(AppText.CampusNetwork.PORTAL_ADDRESS_BLOCKED_DETAIL); return false
                }
                PortalProbeResult.Unreachable -> {
                    mutable.value = mutable.value.copy(portalAddress = target)
                    fail(AppText.CampusNetwork.PORTAL_NOT_ON_CAMPUS_DETAIL); return false
                }
                PortalProbeResult.MissingLocation -> {
                    mutable.value = mutable.value.copy(portalAddress = target)
                    fail(AppText.CampusNetwork.PORTAL_NO_REDIRECT_DETAIL, CampusPortalPhase.Failed); return false
                }
            }
            mutable.value = mutable.value.copy(portalAddress = target)

            currentCoroutineContext().ensureActive()
            if (!current()) return false
            val dialed = call { source!!.dialIn(finalUrl) }
            currentCoroutineContext().ensureActive()
            if (!current()) return false
            return when (dialed) {
                is CampusOutcome.Failure -> { fail(campusFailure(dialed.issue)); false }
                is CampusOutcome.Success -> when (val parsed = dialed.data) {
                    CampusDialResult.Accepted -> {
                        mutable.value = mutable.value.copy(phase = CampusPortalPhase.Online, detail = "",
                            connectedAtMillis = clockMillis())
                        true
                    }
                    is CampusDialResult.Rejected -> {
                        val phase = if (parsed.reason == CampusDialRejection.NoRedirect)
                            CampusPortalPhase.NotOnCampus else CampusPortalPhase.Failed
                        fail(campusDialRejectionDetail(parsed.reason, parsed.raw), phase)
                        false
                    }
                }
            }
        } finally { flight.unlock() }
    }

    /** Explicit user action only; a route rebuild never logs out automatically. */
    suspend fun disconnect(): Boolean {
        if (!flight.tryLock()) return false
        try {
            if (!current()) return false
            val response = call { source!!.offline() }
            currentCoroutineContext().ensureActive()
            if (!current()) return false
            return when (response) {
                is CampusOutcome.Failure -> { fail(campusFailure(response.issue)); false }
                is CampusOutcome.Success -> {
                    mutable.value = mutable.value.copy(phase = CampusPortalPhase.Offline, detail = "",
                        connectedAtMillis = null)
                    true
                }
            }
        } finally { flight.unlock() }
    }

    fun markManualPortalOpened() { mutable.value = mutable.value.copy(manualPortalOpened = true) }

    private fun fail(detail: String, phase: CampusPortalPhase = CampusPortalPhase.Failed) {
        mutable.value = mutable.value.copy(phase = phase, detail = detail)
    }

    private suspend fun <T> call(block: suspend () -> CampusOutcome<T>): CampusOutcome<T> = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { CampusOutcome.Failure(CampusIssue.NETWORK) }

    override fun toString() = "CampusPortalController(phase=${mutable.value.phase})"
}
