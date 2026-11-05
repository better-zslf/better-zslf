package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.feature.campusnet.crypto.DormCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * One signed-in dorm session: keeps it alive and signs it out.
 *
 * The keep-alive interval is **not** a constant: the login reply tells us (`<keep-retry>`), and the
 * reference subtracts a safety margin so a heartbeat never arrives late. Hard-coding an interval
 * would either hammer the gateway or let the session lapse.
 *
 * Every method is user-initiated or timer-driven by the caller. Nothing here starts on its own, and
 * nothing retries blindly — the carrier locks an account for 30 minutes after repeated failures.
 */
class DormSession(
    private val transport: DormTransport,
    private val server: AuthServer,
    private val clientId: String,
    private val ticket: String,
    private val algoId: String,
    private val fields: DormXmlFields,
    /** Server-advertised keep-alive, already adjusted by the caller if it wants a safety margin. */
    private val keepRetrySeconds: Int,
) {
    private val mutable = MutableStateFlow(DormSessionState())
    val state: StateFlow<DormSessionState> = mutable.asStateFlow()
    private val flight = Mutex()
    private var closed = false

    val keepAliveMillis: Long get() = keepRetrySeconds.coerceAtLeast(1) * 1000L

    fun close() { closed = true }

    /** Sends one heartbeat. A reply that names a new interval updates [keepAliveMillis]. */
    suspend fun heartbeat(): Boolean {
        if (closed) return false
        if (!flight.tryLock()) return false
        try {
            return when (val result = send(DormXmlKind.Heartbeat)) {
                is DormRoundResult.Reply -> {
                    val plain = decrypt(result) ?: return record(DormSessionPhase.Unknown, null)
                    // The heartbeat reply carries the NEXT interval under `<interval>`. Note this is
                    // a different element from the login reply's `<keep-retry>`: reading the wrong
                    // one means the interval never updates and the keep-alive slowly drifts.
                    val interval = dormXmlValue(plain, "interval")?.trim()?.toIntOrNull()
                    if (interval == null) {
                        // The reference treats a reply without it as a protocol failure: the
                        // gateway answered but did not complete the exchange.
                        return record(DormSessionPhase.Lost, null)
                    }
                    if (interval > 0) {
                        mutable.value = mutable.value.copy(
                            keepRetrySeconds = (interval - 30).coerceAtLeast(60))
                    }
                    record(DormSessionPhase.Alive, null)
                }
                DormRoundResult.Unreachable -> record(DormSessionPhase.Lost, null)
                is DormRoundResult.CipherUpdate -> record(DormSessionPhase.Unknown, null)
            }
        } finally { flight.unlock() }
    }

    /** Signs out. Only ever called because the user asked; the reference never logs out by itself. */
    suspend fun logout(): Boolean {
        if (closed) return false
        if (!flight.tryLock()) return false
        try {
            return when (val result = send(DormXmlKind.Term)) {
                is DormRoundResult.Reply -> {
                    val ok = result.errorCode.isBlank() || result.errorCode == "200"
                    mutable.value = mutable.value.copy(phase = if (ok) DormSessionPhase.SignedOut
                    else DormSessionPhase.Unknown)
                    ok
                }
                DormRoundResult.Unreachable -> record(DormSessionPhase.Lost, null)
                is DormRoundResult.CipherUpdate -> record(DormSessionPhase.Unknown, null)
            }
        } finally { flight.unlock() }
    }

    private suspend fun send(kind: DormXmlKind): DormRoundResult {
        val xml = buildDormXml(kind, fields.copy(ticket = ticket))
        val payload = DormCipher.encryptHex(algoId, xml.encodeToByteArray()) ?: return DormRoundResult.Unreachable
        val path = if (kind == DormXmlKind.Heartbeat) "keep.cgi" else "term.cgi"
        return try {
            transport.post("${server.baseUrl}/$path", payload, algoId, clientId)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DormRoundResult.Unreachable }
    }

    private fun decrypt(reply: DormRoundResult.Reply): String? {
        val text = reply.body.decodeToString()
        if (text.isBlank() || text.length % 2 != 0) return null
        return DormCipher.decryptHex(algoId, text)
    }

    private fun record(phase: DormSessionPhase, code: String?): Boolean {
        mutable.value = mutable.value.copy(phase = phase, lastCode = code)
        return phase == DormSessionPhase.Alive
    }

    override fun toString(): String = "DormSession(phase=${mutable.value.phase})"
}

enum class DormSessionPhase { Alive, Lost, SignedOut, Unknown }

data class DormSessionState(
    val phase: DormSessionPhase = DormSessionPhase.Alive,
    val keepRetrySeconds: Int = 0,
    val lastCode: String? = null,
)

/** The keep-alive interval the gateway advertised, or a conservative default when it said nothing. */
/**
 * The keep-alive interval to use, given a decrypted login reply.
 *
 * `keep-retry` is mandatory in the reference: its absence means the login was not accepted, so by
 * the time this is called the element is known to be present. The arithmetic still clamps, because
 * a gateway could advertise a value small enough to turn the heartbeat into a tight loop against a
 * rate-limited server — the reference subtracts its operation timeout for exactly that reason.
 */
fun dormKeepRetryFrom(xml: String?): Int {
    val advertised = xml?.let { dormXmlValue(it, "keep-retry")?.trim()?.toIntOrNull() } ?: 0
    return if (advertised > 0) (advertised - 30).coerceAtLeast(60) else 300
}
