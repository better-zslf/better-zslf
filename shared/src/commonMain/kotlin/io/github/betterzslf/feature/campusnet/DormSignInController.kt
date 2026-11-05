package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** What the dorm page shows. One primary state, not a bag of booleans. */
enum class DormSignInPhase { Idle, Running, Connected, Failed, Unsupported, NotOnCampus, Unreadable }

data class DormSignInState(
    val phase: DormSignInPhase = DormSignInPhase.Idle,
    val detail: String = "",
    val algoId: String = "",
    /** True once the user's credentials were accepted, so the caller can offer sign-out. */
    val connected: Boolean = false,
)

/**
 * Drives one dorm sign-in from the UI.
 *
 * Deliberately narrow: it owns the attempt, never retries, and reports the classified reason. The
 * gateway locks an account for 30 minutes after repeated failures, so an automatic retry would be
 * actively harmful — the user decides when to try again.
 */
class DormSignInController(
    private val transport: DormTransport?,
    private val fields: () -> DormXmlFields,
    private val servers: List<AuthServer> = DORM_AUTH_SERVERS,
) {
    private val mutable = MutableStateFlow(DormSignInState())
    val state: StateFlow<DormSignInState> = mutable.asStateFlow()
    private val flight = Mutex()
    private var session: DormSession? = null

    fun close() { session?.close(); session = null }

    /** One attempt. [plainPhone] and [password] are the broadband credentials. */
    suspend fun signIn(plainPhone: String, password: String): Boolean {
        if (!flight.tryLock()) return false
        try {
            val transport = transport
            if (transport == null) {
                mutable.value = DormSignInState(DormSignInPhase.NotOnCampus,
                    AppText.CampusNetwork.DORM_NEEDS_CAMPUS_NETWORK)
                return false
            }
            // An 11-digit mobile number is all the carrier accepts; anything else fails locally
            // rather than spending one of the account's limited login attempts.
            if (plainPhone.length != 11 || !plainPhone.all { it in '0'..'9' }) {
                mutable.value = DormSignInState(DormSignInPhase.Failed,
                    AppText.CampusNetwork.DORM_PHONE_INVALID)
                return false
            }
            mutable.value = DormSignInState(DormSignInPhase.Running, AppText.CampusNetwork.DORM_SIGNING_IN)
            val server = servers.firstOrNull() ?: run {
                mutable.value = DormSignInState(DormSignInPhase.NotOnCampus,
                    AppText.CampusNetwork.DORM_NEEDS_CAMPUS_NETWORK)
                return false
            }
            val base = fields()
            val dialer = DormDialer(transport, server, base, server.baseUrl)
            return when (val result = dialer.dial(plainPhone, password)) {
                is DormAttemptResult.Connected -> {
                    session?.close()
                    session = DormSession(transport, server, base.clientId,
                        result.ticket, result.algoId, base, dormKeepRetryFrom(null))
                    mutable.value = DormSignInState(DormSignInPhase.Connected,
                        AppText.CampusNetwork.DORM_SIGNED_IN, result.algoId, connected = true)
                    true
                }
                is DormAttemptResult.Rejected -> {
                    mutable.value = DormSignInState(DormSignInPhase.Failed,
                        dormErrorDisplayText(result.error), algoId = "")
                    false
                }
                is DormAttemptResult.CipherUnsupported -> {
                    mutable.value = DormSignInState(DormSignInPhase.Unsupported,
                        AppText.CampusNetwork.dormCipherUnsupported(result.reason), result.algoId)
                    false
                }
                DormAttemptResult.Unreachable -> {
                    mutable.value = DormSignInState(DormSignInPhase.NotOnCampus,
                        AppText.CampusNetwork.DORM_NEEDS_CAMPUS_NETWORK)
                    false
                }
                DormAttemptResult.Unreadable -> {
                    mutable.value = DormSignInState(DormSignInPhase.Unreadable,
                        AppText.CampusNetwork.DORM_RESULT_UNCONFIRMED)
                    false
                }
            }
        } finally { flight.unlock() }
    }

    /** Sends one heartbeat; the caller owns the timer. */
    suspend fun heartbeat(): Boolean = session?.heartbeat() ?: false

    /** Signs out, only because the user asked. */
    suspend fun signOut(): Boolean {
        val current = session ?: return false
        val ok = current.logout()
        current.close()
        session = null
        mutable.value = DormSignInState(DormSignInPhase.Idle, AppText.CampusNetwork.DORM_SIGNED_OUT)
        return ok
    }

    val keepAliveMillis: Long get() = session?.keepAliveMillis ?: 0

    override fun toString(): String = "DormSignInController(phase=${mutable.value.phase})"
}
