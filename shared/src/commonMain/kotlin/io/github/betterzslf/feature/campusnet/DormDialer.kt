package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.feature.campusnet.crypto.DormCipher
import io.github.betterzslf.feature.campusnet.crypto.DormCipherStatus

/**
 * One dorm dial-in attempt, start to finish.
 *
 * The carrier flow is four encrypted requests, and the first one may take two rounds because the
 * gateway issues the cipher family lazily:
 *
 *  1. `ticket.cgi` — first round with the all-zero `algo_id`. If the reply is `Error-Code: 200`
 *     with a `001`-prefixed body, that body carries the real `algo_id`, and the same request is
 *     retried with the session encrypted under it.
 *  2. `ticket.cgi` again — the reply decrypts to XML carrying `<ticket>`.
 *  3. `auth.cgi` — the login envelope (user id + password + ticket), decrypted reply says whether
 *     it worked.
 *  4. `keep.cgi` / `term.cgi` — heartbeat and sign-out, same envelope shape.
 *
 * Everything below is deliberately explicit about failure: the gateway reports errors in response
 * headers (`Error-Code`, `Sub-Error`), and a few of those codes mean "stop, retrying locks the
 * account for 30 minutes". [DormErrorFamily] drives that decision, not the message text.
 */

/** What one transport round produced. Kept structural so the orchestrator never parses strings. */
sealed interface DormRoundResult {
    /** A normal reply. [body] is still encrypted; [errorCode] / [subError] are the gateway's headers. */
    data class Reply(val errorCode: String, val subError: String, val body: ByteArray) : DormRoundResult

    /** `Error-Code: 200` with a `001`-prefixed body: a cipher negotiation, not a failure. */
    data class CipherUpdate(val algoId: String) : DormRoundResult

    /** Nothing answered. */
    data object Unreachable : DormRoundResult
}

/** One network round. Injectable so the whole flow is testable without a gateway. */
fun interface DormTransport {
    suspend fun post(url: String, encryptedPayload: String, algoId: String, clientId: String): DormRoundResult
}

/** Terminal states of an attempt. Each maps to a distinct thing the user is told. */
sealed interface DormAttemptResult {
    /** Signed in. [ticket] is needed for the heartbeat and the sign-out. */
    data class Connected(val ticket: String, val algoId: String) : DormAttemptResult

    /** The gateway refused. [error] carries its classification so the caller knows if retrying is safe. */
    data class Rejected(val error: DormError) : DormAttemptResult

    /** This build has no cipher for the id the gateway picked. */
    data class CipherUnsupported(val algoId: String, val family: String, val reason: String) : DormAttemptResult

    /** No answer at all — almost always "not on the campus network". */
    data object Unreachable : DormAttemptResult

    /** A reply arrived but could not be decrypted or parsed; the result is genuinely unknown. */
    data object Unreadable : DormAttemptResult
}

/**
 * Runs the flow.
 *
 * One instance per attempt: it holds the client id and the negotiated cipher, and reusing it across
 * attempts would carry a stale cipher into a fresh negotiation.
 */
class DormDialer(
    private val transport: DormTransport,
    private val server: AuthServer,
    private val fields: DormXmlFields,
    private val authServerBase: String = server.baseUrl,
) {

    /**
     * @param userId / [password] the broadband credentials, used only in the login envelope.
     * @return a terminal outcome; never throws for an ordinary network failure.
     */
    suspend fun dial(userId: String, password: String): DormAttemptResult {
        var algoId = DORM_NIL_ALGO_ID

        // Round 1: negotiate the cipher. The reference allows at most two updates before giving up,
        // because a gateway that keeps changing its mind would otherwise loop forever.
        var updates = 0
        var ticketXml: String? = null
        while (true) {
            when (val round = transport.post(
                url = "$authServerBase/ticket.cgi?wlanuserip=${encode(fields.ipv4)}&wlanacip=${encode(fields.gwip)}",
                encryptedPayload = initialPayload(),
                algoId = algoId,
                clientId = fields.clientId,
            )) {
                is DormRoundResult.CipherUpdate -> {
                    val status = DormCipher.statusOf(round.algoId)
                    if (status is DormCipherStatus.Unsupported) {
                        return DormAttemptResult.CipherUnsupported(round.algoId, status.family, status.reason)
                    }
                    if (status !is DormCipherStatus.Supported) {
                        // An id this build has never seen. Reporting it beats sending bytes that can
                        // only fail and may count against the account's login attempts.
                        return DormAttemptResult.CipherUnsupported(round.algoId, status.toString(), "unknown")
                    }
                    if (++updates > 2) return DormAttemptResult.Unreadable
                    algoId = round.algoId
                }
                is DormRoundResult.Reply -> {
                    // A non-negotiation reply here is the ticket itself, already encrypted under the
                    // current cipher.
                    val plain = decrypt(round, algoId) ?: return classify(round, algoId)
                    ticketXml = plain
                    break
                }
                DormRoundResult.Unreachable -> return DormAttemptResult.Unreachable
            }
        }

        val ticket = ticketXml?.let(::dormXmlTicket) ?: return DormAttemptResult.Unreadable

        // Round 3: log in with the ticket.
        val loginPayload = DormCipher.encryptHex(algoId,
            buildDormXml(DormXmlKind.Login, fields.copy(ticket = ticket, userId = userId, password = password))
                .encodeToByteArray()) ?: return unsupported(algoId)
        return when (val round = transport.post("$authServerBase/auth.cgi",
            loginPayload, algoId, fields.clientId)) {
            is DormRoundResult.Reply -> {
                val plain = decrypt(round, algoId) ?: return classify(round, algoId)
                // Success is the absence of an error code AND a decodable reply; the reference then
                // considers the session authenticated and starts heartbeats.
                if (isSuccess(plain, round)) DormAttemptResult.Connected(ticket, algoId)
                else DormAttemptResult.Rejected(gatewayError(round, plain))
            }
            is DormRoundResult.CipherUpdate -> DormAttemptResult.Unreadable
            DormRoundResult.Unreachable -> DormAttemptResult.Unreachable
        }
    }

    private fun initialPayload(): String =
        DormCipher.encryptHex(DORM_NIL_ALGO_ID,
            buildDormXml(DormXmlKind.GetTicket, fields).encodeToByteArray())
            ?: buildDormXml(DormXmlKind.GetTicket, fields)

    private fun decrypt(round: DormRoundResult.Reply, algoId: String): String? {
        val text = round.body.decodeToString()
        // Replies are hex ciphertext; anything else means the gateway answered with something we do
        // not understand, which must not be mistaken for a plain-text success.
        if (text.isBlank() || text.length % 2 != 0 || !text.all { it.isHexDigit() }) return null
        return DormCipher.decryptHex(algoId, text)
    }

    /** Turns a refusal into the classified error the UI acts on. */
    private fun classify(round: DormRoundResult.Reply, algoId: String): DormAttemptResult {
        val error = gatewayError(round, null)
        return if (error.family == DormErrorFamily.NONE) DormAttemptResult.Unreadable
        else DormAttemptResult.Rejected(error)
    }

    private fun gatewayError(round: DormRoundResult.Reply, plain: String?): DormError =
        dormErrorCode(round.errorCode.ifBlank { null }, round.subError.ifBlank { null })
            ?: DormError(
                code = round.errorCode.ifBlank { round.subError }.ifBlank { "-" },
                family = DormErrorFamily.NONE,
                message = plain.orEmpty().take(120),
            )

    private fun unsupported(algoId: String): DormAttemptResult {
        val status = DormCipher.statusOf(algoId)
        return DormAttemptResult.CipherUnsupported(algoId, (status as? DormCipherStatus.Unsupported)?.family.orEmpty(),
            (status as? DormCipherStatus.Unsupported)?.reason.orEmpty())
    }

    private fun isSuccess(plain: String, round: DormRoundResult.Reply): Boolean {
        // The reference treats Error-Code 200 (or empty) as success once the reply decrypts.
        if (round.errorCode.isNotBlank() && round.errorCode != "200") return false
        val code = dormXmlCode(plain)
        return code == null || code == "0" || code == "200"
    }

    private fun encode(v: String): String = v

    override fun toString(): String = "DormDialer(server=${server.host})"
}

private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
