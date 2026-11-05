package io.github.betterzslf.feature.campusnet

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException

/** What the portal probe learned. Every branch is a real outcome, not a swallowed failure. */
sealed interface PortalProbeResult {
    /** The portal redirected; this is the URL to hand back to the school backend. */
    data class Redirected(val url: String) : PortalProbeResult

    /** No 3xx: the portal answered directly, so the original address is already the final one. */
    data class NotRedirected(val url: String) : PortalProbeResult

    /** The address is not plain HTTP to an allowlisted host; nothing was requested. */
    data object Blocked : PortalProbeResult

    /** Nothing answered at all — usually "not on the campus WiFi". */
    data object Unreachable : PortalProbeResult

    /** A 3xx without a usable `Location`; the reference client treats this as an error. */
    data object MissingLocation : PortalProbeResult
}

/** Exactly the reference client's UA. A real app UA changes portal behaviour. */
const val PORTAL_PROBE_USER_AGENT: String = "Mozilla/5.0 (Android)"

/** 5 s connect and 5 s read, matching the reference plugin: a dead portal fails fast. */
private const val PROBE_TIMEOUT_MILLIS: Long = 5_000

/**
 * Minimal single-hop redirect probe, equivalent to the reference app's `zjfRedirect` native plugin.
 *
 * Deliberate choices, all taken from the original implementation:
 * - redirects are **not** followed, so the first `Location` is readable;
 * - a browser-ish User-Agent is sent, because portals may not redirect otherwise;
 * - a non-3xx answer returns the input unchanged rather than an error.
 *
 * The client is derived from the caller's engine so no campus token, cookie jar or logging plugin
 * is inherited: this request carries no credentials, and it is the only request in the app that is
 * allowed to speak cleartext (see `network_security_config.xml`). Close it with the owner scope.
 */
class KtorPortalProbe(ownerClient: HttpClient) {
    private val client = HttpClient(ownerClient.engine) {
        expectSuccess = false
        // The single 302 is the answer; following it would discard the Location we need.
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = PROBE_TIMEOUT_MILLIS
            connectTimeoutMillis = PROBE_TIMEOUT_MILLIS
            socketTimeoutMillis = PROBE_TIMEOUT_MILLIS
        }
    }
    private var closed = false

    suspend fun probe(portalUrl: String): PortalProbeResult {
        if (closed) return PortalProbeResult.Unreachable
        if (!campusPortalAddressAllowed(portalUrl)) return PortalProbeResult.Blocked
        return try {
            val response = client.get(portalUrl) {
                timeout {
                    requestTimeoutMillis = PROBE_TIMEOUT_MILLIS
                    connectTimeoutMillis = PROBE_TIMEOUT_MILLIS
                    socketTimeoutMillis = PROBE_TIMEOUT_MILLIS
                }
                header(HttpHeaders.UserAgent, PORTAL_PROBE_USER_AGENT)
            }
            val status = response.status.value
            val location = response.headers[HttpHeaders.Location]
            // The body is never read: this is a redirect probe, not a page fetch.
            response.bodyAsChannel().cancel(null)
            when {
                status in 300..399 -> location?.takeIf { it.isNotBlank() }
                    ?.let { PortalProbeResult.Redirected(it) }
                    ?: PortalProbeResult.MissingLocation
                else -> PortalProbeResult.NotRedirected(portalUrl)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Ktor exception messages embed the URL; nothing from them is surfaced or logged.
            PortalProbeResult.Unreachable
        }
    }

    fun close() {
        if (closed) return
        closed = true
        client.close()
    }

    override fun toString(): String = "KtorPortalProbe(closed=$closed)"
}
