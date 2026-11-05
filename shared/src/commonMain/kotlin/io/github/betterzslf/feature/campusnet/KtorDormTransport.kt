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
 * The real transport for the carrier dialer: plain HTTP to one of the two fixed `:7001` servers.
 *
 * Every request carries the header set the gateway validates, in the order it validates them
 * (verified against the live gateway: omitting each in turn walks `Error-Code` 1 → 13 → 12 → 15).
 * The body is the ciphertext hex; the reply is ciphertext hex too, plus the two error headers.
 *
 * Three limits are deliberate:
 *  - it refuses to talk to a host outside the dialer allowlist, so a misconfigured server name
 *    cannot make the app emit cleartext somewhere unexpected;
 *  - it never retries: the caller owns that decision, because a few error codes mean "stop";
 *  - exception text is discarded, since Ktor embeds the request URL in it.
 */
class KtorDormTransport(ownerClient: HttpClient) : DormTransport {

    private val client = HttpClient(ownerClient.engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = DORM_ROUND_TIMEOUT_MILLIS
            connectTimeoutMillis = DORM_ROUND_TIMEOUT_MILLIS
            socketTimeoutMillis = DORM_ROUND_TIMEOUT_MILLIS
        }
    }
    private var closed = false

    override suspend fun post(
        url: String,
        encryptedPayload: String,
        algoId: String,
        clientId: String,
    ): DormRoundResult {
        if (closed) return DormRoundResult.Unreachable
        if (!dormAuthAddressAllowed(url)) return DormRoundResult.Unreachable
        return try {
            val response = client.post(url) {
                timeout {
                    requestTimeoutMillis = DORM_ROUND_TIMEOUT_MILLIS
                    connectTimeoutMillis = DORM_ROUND_TIMEOUT_MILLIS
                    socketTimeoutMillis = DORM_ROUND_TIMEOUT_MILLIS
                }
                contentType(ContentType.Application.FormUrlEncoded)
                header(HttpHeaders.UserAgent, DORM_ANDROID_USER_AGENT)
                header(HttpHeaders.Accept, DORM_ACCEPT)
                header("Client-ID", clientId)
                header("Algo-ID", algoId)
                // The school identity is required on every round; empty values are still sent, which
                // is what the reference does before the portal has supplied them.
                header("CDC-SchoolId", DORM_SCHOOL_ID_HOLDER.value.orEmpty())
                header("CDC-Domain", DORM_SCHOOL_DOMAIN_HOLDER.value.orEmpty())
                header("CDC-Area", DORM_AREA_HOLDER.value.orEmpty())
                setBody(encryptedPayload)
            }
            val errorCode = response.headers["Error-Code"].orEmpty()
            val subError = response.headers["Sub-Error"].orEmpty()
            val body = response.bodyAsBytes()
            // A cipher negotiation is signalled by Error-Code 200 plus a 001-prefixed blob; the same
            // code also appears on ordinary empty replies, so the body decides.
            if (errorCode == "200" && body.isDormZsm()) {
                val algoId = parseDormAlgoId(body)
                if (algoId != null) DormRoundResult.CipherUpdate(algoId)
                else DormRoundResult.Reply(errorCode, subError, body)
            } else {
                DormRoundResult.Reply(errorCode, subError, body)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DormRoundResult.Unreachable
        }
    }

    fun close() {
        if (closed) return
        closed = true
        client.close()
    }

    override fun toString(): String = "KtorDormTransport(closed=$closed)"
}

/** 8 s: the gateway answers promptly; a silent network must not hold the page. */
private const val DORM_ROUND_TIMEOUT_MILLIS: Long = 8_000

private const val DORM_ACCEPT: String =
    "text/html,text/xml,application/xhtml+xml,application/x-javascript,*/*"

/**
 * School identity, captured from the portal's response headers on campus.
 *
 * Held here because the dialer transport needs it on every round while the identity itself is only
 * obtainable from the portal probe. Process-local and never persisted: it is not a credential, but
 * it is only valid for the network the app is on.
 */
internal object DORM_SCHOOL_ID_HOLDER {
    @Volatile var value: String? = null
}

internal object DORM_SCHOOL_DOMAIN_HOLDER {
    @Volatile var value: String? = null
}

internal object DORM_AREA_HOLDER {
    @Volatile var value: String? = null
}

/** Records the identity a probe observed, so later dial rounds can echo it back. */
fun recordDormSchoolIdentity(schoolId: String, domain: String, area: String) {
    DORM_SCHOOL_ID_HOLDER.value = schoolId.takeIf { it.isNotBlank() }
    DORM_SCHOOL_DOMAIN_HOLDER.value = domain.takeIf { it.isNotBlank() }
    DORM_AREA_HOLDER.value = area.takeIf { it.isNotBlank() }
}

/** Test seam: clears the captured identity. */
internal fun clearDormSchoolIdentity() {
    DORM_SCHOOL_ID_HOLDER.value = null
    DORM_SCHOOL_DOMAIN_HOLDER.value = null
    DORM_AREA_HOLDER.value = null
}
