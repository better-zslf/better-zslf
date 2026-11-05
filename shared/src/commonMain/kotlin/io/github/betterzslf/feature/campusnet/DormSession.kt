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
            val result = send(DormXmlKind.Heartbeat)
            when (result) {
                is DormRoundResult.Reply -> {
                    val plain = decrypt(result) ?: return record(DormSessionPhase.Unknown, null)
                    // A heartbeat may carry a refreshed interval; honour it rather than keeping a
                    // stale value that would eventually arrive too late.
                    dormXmlValue(plain, "keep-retry")?.trim()?.toIntOrNull()?.let { advertised ->
                        if (advertised > 0) mutable.value = mutable.value.copy(keepRetrySeconds = advertised)
                    }
                    val healthy = result.errorCode.isBlank() || result.errorCode == "200"
                    return record(if (healthy) DormSessionPhase.Alive else DormSessionPhase.Lost,
                        dormXmlValue(plain, "code"))
                }
                DormRoundResult.Unreachable -> return record(DormSessionPhase.Lost, null)
                is DormRoundResult.CipherUpdate -> return record(DormSessionPhase.Unknown, null)
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
fun dormKeepRetryFrom(xml: String?): Int {
    val advertised = xml?.let { dormXmlValue(it, "keep-retry")?.trim()?.toIntOrNull() } ?: 0
    // 0 means "the gateway did not say". A five-minute floor keeps the session alive without
    // hammering it; the reference subtracts its operation timeout from whatever it was told.
    return if (advertised > 0) (advertised - 30).coerceAtLeast(60) else 300
}
