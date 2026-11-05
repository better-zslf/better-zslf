package io.github.betterzslf.feature.campusnet

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Fixed authentication servers for the ESurfing (天翼校园网) dorm dial-in protocol.
 *
 * Research note: `work/notes/esurfing-dorm-auth.md` §三 step 1 (选认证服务器) and §七 7.3.
 * Mirrors `work/notes/esurfing-ref/DialerServer.c` (`select_auth_server`).
 *
 * There are exactly two servers and they are fixed IPs on port 7001, so they are hard-coded
 * instead of being taken from the portal page. The portal's own `auth-url` is only a by-product
 * of the 302 redirect: it is neither guaranteed to be the fastest nor guaranteed to stay valid.
 *
 * Everything platform specific (opening a TCP connection, measuring elapsed time) is injected
 * by the caller through [rankAuthServers]'s `probe` lambda, so this file stays common-only and
 * never touches `java.net`, `NSURLSession`, `OkHttp`, or any other platform API.
 */

/** Transport port shared by both authentication servers. */
const val DORM_AUTH_SERVER_PORT: Int = 7001

/** Upper bound the reference implementation allows a single server's TCP probe to take. */
const val DORM_AUTH_PROBE_TIMEOUT_MS: Long = 2_000L

/** One dial-in authentication endpoint, identified by a plain IP and a port. */
data class AuthServer(val host: String, val port: Int = DORM_AUTH_SERVER_PORT) {
    /** `host:port`, the form used as the HTTP `Host` authority. */
    val authority: String get() = "$host:$port"

    /** `http://host:port`: the base of `/ticket.cgi`, `/auth.cgi`, `/keep.cgi` and `/term.cgi`. */
    val baseUrl: String get() = "http://$authority"

    override fun toString(): String = "AuthServer($authority)"
}

/** The two fixed servers, in the reference implementation's preference order. */
val DORM_AUTH_SERVERS: List<AuthServer> = listOf(
    AuthServer("14.146.227.141"),
    AuthServer("121.8.177.212"),
)

/**
 * Ranks [DORM_AUTH_SERVERS] fastest-first by probing every server concurrently.
 *
 * [probe] is supplied by the caller and must open a TCP connection to `host:port` and return the
 * elapsed time in milliseconds, or `null` when the connection could not be established. It should
 * not send or receive any application data: the reference implementation uses a connect-only
 * request, because the point is to measure reachability and latency, not to talk HTTP.
 *
 * All servers are probed at the same time (`async` + `awaitAll`), never one after another: two
 * sequential 2 s timeouts would otherwise add 4 s to every authentication attempt.
 *
 * A probe that throws is treated exactly like a probe that returns `null`, so one broken platform
 * implementation cannot take down the whole ranking. Cancellation is propagated, never swallowed.
 * Negative measurements are rejected as failures.
 *
 * @return every server, sortable order: ascending latency first, then the failures (in declaration
 *         order) at the end. The list is never empty and never loses an entry, so callers can always
 *         fall back to `first()` — that is exactly the reference behaviour when both probes fail
 *         ("先按第一台试, 让后续请求把真正的错误暴露出来"). Ties keep declaration order, because
 *         `sortedWith` is stable.
 */
suspend fun rankAuthServers(probe: suspend (host: String, port: Int) -> Long?): List<AuthServer> {
    val measured: List<MeasuredServer> = coroutineScope {
        DORM_AUTH_SERVERS.map { server ->
            async { MeasuredServer(server, measureServer(server, probe)) }
        }.awaitAll()
    }
    return measured.sortedWith(FastestFirst).map { it.server }
}

/**
 * Convenience wrapper around [rankAuthServers] for the common case: the reference implementation
 * only ever wants one server, and falls back to the first configured one when both probes fail.
 */
suspend fun selectAuthServer(probe: suspend (host: String, port: Int) -> Long?): AuthServer =
    rankAuthServers(probe).first()

/** A probe result paired with the server it belongs to. `latencyMs == null` means "unreachable". */
private data class MeasuredServer(val server: AuthServer, val latencyMs: Long?)

/** Ascending latency, with unreachable servers last; equal latencies keep declaration order. */
private val FastestFirst: Comparator<MeasuredServer> = Comparator { left, right ->
    val leftLatency = left.latencyMs
    val rightLatency = right.latencyMs
    when {
        leftLatency == null && rightLatency == null -> 0
        leftLatency == null -> 1
        rightLatency == null -> -1
        else -> leftLatency.compareTo(rightLatency)
    }
}

/** Runs one probe, normalising "blew up" and "negative" to the same `null` as "unreachable". */
private suspend fun measureServer(server: AuthServer, probe: suspend (host: String, port: Int) -> Long?): Long? {
    val latency = try {
        probe(server.host, server.port)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        null
    }
    return latency?.takeIf { it >= 0L }
}
