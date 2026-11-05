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
 *
 * Two values must exist before a dial can work at all, and both come from the portal rather than
 * from the user: `wlanuserip` (this device's address on the campus network) and `wlanacip` (the
 * access controller). The reference implementation aborts outright when either is missing, so this
 * refuses locally with a stated reason instead of sending a request that cannot succeed.
 */
class DormSignInController(
    private val transport: DormTransport?,
    private val fields: () -> DormXmlFields,
    private val servers: List<AuthServer> = DORM_AUTH_SERVERS,
    /** Resolves the two portal addresses; `null` means the portal has not been reached yet. */
    private val portalAddress: () -> PortalAddress? = { null },
    /** Resolves the school identity captured from the portal's headers, if it was captured. */
    private val schoolIdentity: () -> Triple<String, String, String>? = {
        val id = DORM_SCHOOL_ID_HOLDER.value
        val domain = DORM_SCHOOL_DOMAIN_HOLDER.value
        val area = DORM_AREA_HOLDER.value
        if (id.isNullOrBlank() && domain.isNullOrBlank() && area.isNullOrBlank()) null
        else Triple(id.orEmpty(), domain.orEmpty(), area.orEmpty())
    },
    /** Remembers a live session so a process that dies without signing out can still be cleaned up. */
    private val logoutStore: DormLogoutStore? = null,
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
            // Without the portal's two addresses the ticket URL cannot even be built. Saying so is
            // far better than a request the gateway answers with a generic rejection.
            val address = portalAddress()
            if (address == null) {
                mutable.value = DormSignInState(DormSignInPhase.NotOnCampus,
                    AppText.CampusNetwork.DORM_NEEDS_PORTAL)
                return false
            }
            val identity = schoolIdentity()
            if (identity != null) recordDormSchoolIdentity(identity.first, identity.second, identity.third)
            // A fresh pseudo-identity per attempt: the reference regenerates it every session so a
            // re-dial after a dropped link is not refused as a duplicate client.
            val client = newDormClientIdentity()
            val base = fields().copy(
                ipv4 = address.clientIp,
                gwip = address.acIp,
                hostName = client.hostName,
                mac = client.mac,
                ostag = client.ostag,
            )
            val dialer = DormDialer(transport, server, base, server.baseUrl)
            return when (val result = dialer.dial(plainPhone, password)) {
                is DormAttemptResult.Connected -> {
                    session?.close()
                    // Use the interval the login reply advertised; the default only covers a
                    // gateway that said nothing.
                    session = DormSession(transport, server, base.clientId,
                        result.ticket, result.algoId, base, result.keepRetrySeconds)
                    // Record the live session BEFORE reporting success: if the process dies between
                    // here and the next line, the next start must still be able to sign it out.
                    logoutStore?.save(DormLogoutStore.Record(
                        serverHost = server.host, algoId = result.algoId, ticket = result.ticket,
                        clientId = base.clientId, userAgent = base.userAgent, hostName = base.hostName,
                        ipv4 = base.ipv4, mac = base.mac, ostag = base.ostag, gwip = base.gwip))
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
        // Clear the record first: a sign-out that fails must not be replayed forever, and the
        // session is almost certainly gone server-side by the next start anyway.
        logoutStore?.clear()
        val ok = current.logout()
        current.close()
        session = null
        mutable.value = DormSignInState(DormSignInPhase.Idle, AppText.CampusNetwork.DORM_SIGNED_OUT)
        return ok
    }

    /**
     * Signs out a session left behind by a previous run, if one was recorded.
     *
     * Best effort by design: the client's address may have changed while the app was closed, in
     * which case the gateway rejects the sign-out and the session simply times out. The record is
     * removed either way, because replaying a doomed request on every start is worse than letting
     * the server expire it.
     */
    suspend fun signOutPreviousSession(): Boolean {
        val store = logoutStore ?: return false
        val record = store.load() ?: return false
        store.clear()
        val transport = transport ?: return false
        val server = servers.firstOrNull { it.host == record.serverHost } ?: return false
        val fields = DormXmlFields(
            userAgent = record.userAgent.ifBlank { DORM_ANDROID_USER_AGENT },
            clientId = record.clientId, localTime = "",
            hostName = record.hostName, ipv4 = record.ipv4, mac = record.mac,
            ostag = record.ostag, gwip = record.gwip)
        val previous = DormSession(transport, server, record.clientId, record.ticket,
            record.algoId, fields, keepRetrySeconds = 300)
        return try {
            previous.logout()
        } finally {
            previous.close()
        }
    }

    val keepAliveMillis: Long get() = session?.keepAliveMillis ?: 0

    override fun toString(): String = "DormSignInController(phase=${mutable.value.phase})"
}
