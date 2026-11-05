package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.data.CampusPortalRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One opaque, owner+token-bound capability. A stale instance can never dial with a newer login. */
interface CampusPortalSource {
    fun isCurrentSession(): Boolean
    suspend fun internetInfo(): ApiResult<JsonElement>
    suspend fun redirectTarget(): ApiResult<JsonElement>
    suspend fun dialIn(portalUrl: String): ApiResult<JsonElement>
    suspend fun offline(): ApiResult<JsonElement>
}

class RepositoryCampusPortalSource private constructor(private val repository: CampusPortalRepository) : CampusPortalSource {
    override fun isCurrentSession() = repository.isBoundToCurrentSession
    override suspend fun internetInfo() = repository.internetInfo()
    override suspend fun redirectTarget() = repository.redirectTarget()
    override suspend fun dialIn(portalUrl: String) = repository.dialIn(portalUrl)
    override suspend fun offline() = repository.offline()
    override fun toString() = "RepositoryCampusPortalSource(redacted)"

    companion object {
        fun capture(repository: CampusPortalRepository): RepositoryCampusPortalSource? =
            repository.boundToCurrentSession()?.let(::RepositoryCampusPortalSource)
    }
}

/** The probe as a capability, so the controller is testable without any network. */
fun interface CampusPortalProbeSource {
    suspend fun probe(portalUrl: String): PortalProbeResult
}

data class CampusPortalState(
    val phase: CampusPortalPhase = CampusPortalPhase.NotSignedIn,
    /** Empty means "use the design's default sentence for this phase". */
    val detail: String = "",
    /** Wall-clock start of this session's connection, for the "connected for" line. Local only. */
    val connectedAtMillis: Long? = null,
    val maskedPhone: String = "",
    /** The address the school handed us, kept so the manual "open portal" action can use it. */
    val portalAddress: String? = null,
    val manualPortalOpened: Boolean = false,
    val autoAttempted: Boolean = false,
) {
    val busy: Boolean get() = phase == CampusPortalPhase.Connecting
    val canConnect: Boolean get() = phase == CampusPortalPhase.Offline
    val canDisconnect: Boolean get() = phase == CampusPortalPhase.Online
    val canRetry: Boolean get() = phase == CampusPortalPhase.Failed || phase == CampusPortalPhase.NotOnCampus
}

/**
 * Drives the campus-network status card.
 *
 * This path goes **through the school backend** (`/verification/…`), which is the only one that
 * works on an unauthenticated campus network: the token is already in place and the school does
 * the portal handshake server-side. The carrier's own `:7001` dialer is a different protocol and is
 * not attempted here.
 *
 * Nothing is sent on construction. `refresh()` only reads the online state; `connect()` is the only
 * method that authenticates, and it never retries on its own — the reference client's 30-minute
 * server-side login lockout makes a blind retry actively harmful.
 */
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
            return when (val response = call { source!!.internetInfo() }) {
                is ApiResult.Failure -> {
                    mutable.value = mutable.value.copy(phase = CampusPortalPhase.Failed, detail = campusFailure(response.error))
                    false
                }
                is ApiResult.Success -> applyStatus(parseCampusOnlineStatus(response.data))
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
            // A status we cannot read is not "offline": say so instead of inviting a needless login.
            mutable.value = mutable.value.copy(phase = CampusPortalPhase.Failed,
                detail = AppText.CampusNetwork.SERVICE_RESPONSE_CONTENT_UNCONFIRMED, connectedAtMillis = null)
            false
        }
    }

    /**
     * The one authenticating step: ask for the portal address, probe it, hand the final URL back.
     * Each failure states which stage failed, because "登录失败" alone is unactionable.
     */
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

            val target = when (val response = call { source!!.redirectTarget() }) {
                is ApiResult.Failure -> {
                    fail(campusFailure(response.error)); return false
                }
                // `getRedirectUrl` returns {addr, checkStr}. checkStr is assigned but never read in
                // the reference client either, so it is not carried any further.
                is ApiResult.Success -> (response.data as? JsonObject)
                    ?.get("addr")?.let { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
            }
            if (target.isBlank()) {
                mutable.value = mutable.value.copy(portalAddress = null)
                fail(AppText.CampusNetwork.PORTAL_ADDRESS_MISSING_DETAIL); return false
            }
            val probeSource = probe
            if (probeSource == null) {
                // No probe wired (no platform client): report it rather than pretending to try.
                fail(AppText.CampusNetwork.PORTAL_ADDRESS_BLOCKED_DETAIL); return false
            }
            val finalUrl = when (val probed = probeSource.probe(target)) {
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

            return when (val dialed = call { source!!.dialIn(finalUrl) }) {
                is ApiResult.Failure -> { fail(campusFailure(dialed.error)); false }
                is ApiResult.Success -> when (val parsed = parseCampusDialResult(dialed.data)) {
                    CampusDialResult.Accepted -> {
                        mutable.value = mutable.value.copy(phase = CampusPortalPhase.Online, detail = "",
                            connectedAtMillis = clockMillis())
                        true
                    }
                    is CampusDialResult.Rejected -> {
                        // no redirect means the portal answered but never produced a usable hop:
                        // surface it as retryable, everything else as a hard stop with its own reason.
                        val phase = if (parsed.reason == CampusDialRejection.NoRedirect)
                            CampusPortalPhase.NotOnCampus else CampusPortalPhase.Failed
                        fail(campusDialRejectionDetail(parsed.reason, parsed.raw), phase)
                        false
                    }
                }
            }
        } finally { flight.unlock() }
    }

    /** Explicit user action only; the reference client never logs out on its own. */
    suspend fun disconnect(): Boolean {
        if (!flight.tryLock()) return false
        try {
            if (!current()) return false
            return when (val response = call { source!!.offline() }) {
                is ApiResult.Failure -> { fail(campusFailure(response.error)); false }
                is ApiResult.Success -> {
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

    private suspend fun call(block: suspend () -> ApiResult<JsonElement>): ApiResult<JsonElement> = try {
        block()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { ApiResult.Failure(ApiError.Network()) }

    override fun toString() = "CampusPortalController(phase=${mutable.value.phase})"
}
