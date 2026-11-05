package io.github.betterzslf.feature.campusnet

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException

/**
 * Performs the one handshake that reveals the cipher family, and reports what came back.
 *
 * On an authenticated campus network the gateway answers `ticket.cgi` in one of two ways:
 *  - `Error-Code: 200` with a body starting `001`: a cipher-negotiation round. The body is the
 *    `ZSM` blob and carries the `algo_id`; the client then retries with that id.
 *  - any other `Error-Code`: a real failure, whose code is reported verbatim.
 *
 * Verified against the live gateway from off campus: adding each required header walks the code
 * 1 → 13 → 12 → 15, i.e. the gateway validates them in order and only then issues a blob. That is
 * why the header set here matches the reference implementation exactly, including the ordering of
 * `wlanuserip` before `wlanacip`.
 *
 * This never sends credentials: the payload is the all-zero UUID the reference client also sends
 * on its very first round.
 */
class KtorDormHandshake(ownerClient: HttpClient) {

    private val client = HttpClient(ownerClient.engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = HANDSHAKE_TIMEOUT_MILLIS
            connectTimeoutMillis = HANDSHAKE_TIMEOUT_MILLIS
            socketTimeoutMillis = HANDSHAKE_TIMEOUT_MILLIS
        }
    }
    private var closed = false

    /**
     * @param server the auth server to ask (see `DORM_AUTH_SERVERS`)
     * @param address `wlanuserip` / `wlanacip`, from the portal redirect
     * @param schoolId / [domain] / [area] identity echoed back from the portal headers
     */
    suspend fun negotiate(
        server: AuthServer,
        address: PortalAddress,
        schoolId: String,
        domain: String,
        area: String,
        userAgent: String = DORM_ANDROID_USER_AGENT,
    ): DormHandshakeResult {
        if (closed) return DormHandshakeResult.Unreachable
        val url = buildDormTicketUrl(server, address)
        val clientId = newDormClientId()
        return try {
            val response = client.post(url) {
                timeout {
                    requestTimeoutMillis = HANDSHAKE_TIMEOUT_MILLIS
                    connectTimeoutMillis = HANDSHAKE_TIMEOUT_MILLIS
                    socketTimeoutMillis = HANDSHAKE_TIMEOUT_MILLIS
                }
                contentType(ContentType.Application.FormUrlEncoded)
                header(HttpHeaders.UserAgent, userAgent)
                header(HttpHeaders.Accept, DORM_ACCEPT)
                header("Client-ID", clientId)
                // First round always announces the all-zero id; the gateway answers with the real one.
                header("Algo-ID", DORM_NIL_ALGO_ID)
                header("CDC-SchoolId", schoolId)
                header("CDC-Domain", domain)
                header("CDC-Area", area)
                setBody(DORM_NIL_ALGO_ID)
            }
            val errorCode = response.headers["Error-Code"].orEmpty()
            val subError = response.headers["Sub-Error"].orEmpty()
            val body = response.bodyAsBytes()
            if (errorCode == "200" && body.isDormZsm()) {
                val algoId = parseDormAlgoId(body)
                if (algoId != null) DormHandshakeResult.Negotiated(algoId, body.size)
                else DormHandshakeResult.UnreadableBlob(errorCode, body.size)
            } else {
                DormHandshakeResult.Rejected(errorCode.ifBlank { "-" }, subError, body.size)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Exception text embeds the URL; nothing from it is surfaced.
            DormHandshakeResult.Unreachable
        }
    }

    fun close() {
        if (closed) return
        closed = true
        client.close()
    }

    override fun toString(): String = "KtorDormHandshake(closed=$closed)"
}

/** Every branch names what actually happened; none of them is a silent empty. */
sealed interface DormHandshakeResult {
    /** The gateway issued a cipher family. This is the value the dialer needs. */
    data class Negotiated(val algoId: String, val blobSize: Int) : DormHandshakeResult

    /** A blob arrived but carried no readable id — a layout we do not know yet. */
    data class UnreadableBlob(val errorCode: String, val blobSize: Int) : DormHandshakeResult

    /** The gateway refused. [errorCode] is its own header value, verbatim. */
    data class Rejected(val errorCode: String, val subError: String, val bodySize: Int) : DormHandshakeResult

    data object Unreachable : DormHandshakeResult
}

/**
 * The response is a `ZSM` only when the body starts with `001`.
 * Same test the reference client uses (`is_zsm_response`), and the reason it is needed: the gateway
 * reuses `Error-Code: 200` for ordinary successes with an empty body.
 */
internal fun ByteArray.isDormZsm(): Boolean =
    size >= 4 && this[0] == '0'.code.toByte() && this[1] == '0'.code.toByte() && this[2] == '1'.code.toByte()

/** 5 s: the gateway answers immediately, and a silent network should not stall the page. */
private const val HANDSHAKE_TIMEOUT_MILLIS: Long = 5_000

/** The all-zero UUID every client sends before it knows the cipher. */
const val DORM_NIL_ALGO_ID: String = "00000000-0000-0000-0000-000000000000"

/** `CCTP/android11_64/2104`: the channel tag that makes the gateway serve Android cipher ids. */
const val DORM_ANDROID_USER_AGENT: String = "CCTP/android11_64/2104"

private const val DORM_ACCEPT: String =
    "text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*"

/**
 * A `Client-ID` shaped like the reference client's: an uppercase UUID v4.
 * Generated locally per handshake; it identifies this attempt, not the user.
 */
internal fun newDormClientId(): String {
    val bytes = ByteArray(16)
    for (i in bytes.indices) bytes[i] = (kotlin.random.Random.nextInt(256) and 0xFF).toByte()
    // RFC 4122 version 4 and variant bits, so the value is a well-formed UUID.
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = bytes.joinToString("") { ((it.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    return (hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" +
        hex.substring(16, 20) + "-" + hex.substring(20, 32)).uppercase()
}
