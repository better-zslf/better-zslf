package io.github.betterzslf.feature.schedule

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import kotlin.io.encoding.Base64
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

private const val LOGIN_PATH = "/jsxsd/xk/LoginToXk"
private const val MAIN_PATH = "/jsxsd/framework/xsMain.jsp"
private const val SCHEDULE_PATH = "/jsxsd/xskb/xskb_list.do"
private const val CALENDAR_PATH = "/jsxsd/jxzl/jxzl_query"
private const val MAX_ACADEMIC_BYTES = 2_097_152

/** Fresh engine-only client: no campus auth, owner default headers, cookie storage or logging plugins are inherited. */
internal class KtorAcademicSource(private val ownerClient: HttpClient) : AcademicSource {
    private val lock = Mutex()
    private val closed = MutableStateFlow(false)
    private val identity = MutableStateFlow<AcademicIdentity?>(null)
    private val cookies = AcademicCookies()
    private var catalog: List<ScheduleTerm>? = null
    private val client = HttpClient(ownerClient.engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 20_000
        }
        install(HttpCookies) { storage = cookies }
        // TLS remains entirely the platform engine's system trust; no certificate or hostname override.
    }

    override suspend fun authenticate(account: String, password: String): AcademicIdentity = lock.withLock {
        ensureOpen()
        if (!Regex("^[A-Za-z0-9_.-]{1,64}$").matches(account) || password.isEmpty() || password.length > 512 || password.any { it == '\u0000' })
            throw AcademicAuthenticationException()
        identity.value = null
        catalog = null
        cookies.reset()
        try {
            exchange(HttpMethod.Get, ACADEMIC_BASE_PATH, authenticating = true)
            val encoded = Base64.Default.encode(account.encodeToByteArray()) + "%%%" + Base64.Default.encode(password.encodeToByteArray())
            exchange(HttpMethod.Post, LOGIN_PATH, parametersOf("encoded", encoded), authenticating = true)
            // Login responses may retain their template after establishing a session; verify a fresh profile instead.
            val page = exchange(HttpMethod.Get, MAIN_PATH, authenticating = true)
            val verified = parseAcademicIdentity(page, account) ?: throw AcademicAuthenticationException()
            ensureOpen()
            identity.value = verified
            if (closed.value) { identity.value = null; ensureOpen() }
            verified
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: AcademicAuthenticationException) { cookies.reset(); throw failure }
    }

    override suspend fun terms(): List<ScheduleTerm> = lock.withLock {
        requireIdentity()
        readTerms()
    }

    override suspend fun fetch(term: ScheduleTerm): ScheduleSnapshot = lock.withLock {
        requireIdentity()
        val terms = catalog ?: readTerms()
        val canonical = terms.singleOrNull { it.id == term.id } ?: throw AcademicProtocolException()
        val html = readPage(HttpMethod.Post, SCHEDULE_PATH, parameters {
            append("xnxq01id", canonical.id); append("zc", "0"); append("demo", ""); append("sfFD", "1"); append("cj0701id", "")
        })
        val calendar = try {
            val calendarHtml = readPage(HttpMethod.Post, CALENDAR_PATH, parametersOf("xnxq01id", canonical.id))
            // Never attach a current-semester calendar to a historical schedule without term proof.
            val calendarTerms = runCatching { parseAcademicTerms(calendarHtml) }.getOrNull()
            val selectedCalendarTerm = calendarTerms?.singleOrNull { it.isDefault }?.id
            if (selectedCalendarTerm == canonical.id) parseAcademicCalendar(calendarHtml)
            else AcademicCalendar(emptyMap(), listOf("CALENDAR_TERM_UNVERIFIED"))
        } catch (_: AcademicNetworkException) {
            AcademicCalendar(emptyMap(), listOf("CALENDAR_UNAVAILABLE"))
        } catch (_: AcademicProtocolException) {
            AcademicCalendar(emptyMap(), listOf("CALENDAR_UNAVAILABLE"))
        }
        // Session expiry and cancellation deliberately escape the calendar-only downgrade.
        ensureOpen()
        parseAcademicSchedule(html, canonical, calendar, Clock.System.now().toEpochMilliseconds())
    }

    private suspend fun readTerms(): List<ScheduleTerm> {
        val result = parseAcademicTerms(readPage(HttpMethod.Get, SCHEDULE_PATH))
        ensureOpen()
        catalog = result
        return result
    }

    private suspend fun readPage(method: HttpMethod, path: String, form: Parameters? = null): String {
        val html = exchange(method, path, form)
        if (academicLoginShell(html)) {
            identity.value = null
            catalog = null
            throw AcademicSessionExpiredException()
        }
        return html
    }

    private fun requireIdentity() {
        ensureOpen()
        if (identity.value == null) throw AcademicSessionExpiredException()
    }

    private fun ensureOpen() { if (closed.value) throw CancellationException("Academic source closed") }

    private suspend fun exchange(initialMethod: HttpMethod, path: String, form: Parameters? = null, authenticating: Boolean = false): String {
        var url = Url(ACADEMIC_ORIGIN + path)
        var method = initialMethod
        val visited = mutableSetOf<String>()
        try {
            for (hop in 0..3) {
                ensureOpen()
                if (!academicUrlAllowed(url) || !visited.add(url.toString())) throw AcademicProtocolException()
                val currentMethod = method
                val response = client.prepareRequest(url) {
                    this.method = currentMethod
                    header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
                    header(HttpHeaders.AcceptEncoding, "identity")
                    header(HttpHeaders.UserAgent, "better-zslf-academic")
                    header("Referer", ACADEMIC_ORIGIN + if (authenticating || initialMethod != HttpMethod.Post) ACADEMIC_BASE_PATH else path)
                    if (currentMethod == HttpMethod.Post && form != null) setBody(FormDataContent(form))
                }.execute { received ->
                    val status = received.status.value
                    val channel = received.bodyAsChannel()
                    if (status in setOf(301, 302, 303, 307, 308)) {
                        channel.cancel(null)
                        return@execute AcademicResponse(status, redirect = received.headers[HttpHeaders.Location])
                    }
                    if (status != 200) { channel.cancel(null); return@execute AcademicResponse(status) }
                    val length = received.headers[HttpHeaders.ContentLength]
                    if (length != null && (length.toLongOrNull() == null || length.toLong() !in 0..MAX_ACADEMIC_BYTES.toLong())) {
                        channel.cancel(null); throw AcademicProtocolException()
                    }
                    val bytes = ByteArray(MAX_ACADEMIC_BYTES + 1)
                    var used = 0
                    while (used <= MAX_ACADEMIC_BYTES) {
                        val count = channel.readAvailable(bytes, used, bytes.size - used)
                        if (count < 0) break
                        used += count
                        if (used > MAX_ACADEMIC_BYTES) { channel.cancel(null); throw AcademicProtocolException() }
                    }
                    AcademicResponse(status, bytes.copyOf(used))
                }
                ensureOpen()
                if (response.status == 401 || (authenticating && response.status == 403)) {
                    identity.value = null
                    catalog = null
                    if (authenticating) throw AcademicAuthenticationException() else throw AcademicSessionExpiredException()
                }
                if (response.status in setOf(301, 302, 303, 307, 308)) {
                    if (hop == 3 || (method == HttpMethod.Post && response.status in setOf(307, 308))) throw AcademicProtocolException()
                    url = academicRedirect(url, response.redirect ?: throw AcademicProtocolException())
                    if (method == HttpMethod.Post) method = HttpMethod.Get // Never forward the credential form to another path.
                    continue
                }
                if (response.status != 200) throw AcademicNetworkException()
                return decodeAcademicHtml(response.bytes ?: throw AcademicProtocolException())
            }
            throw AcademicProtocolException()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (known: AcademicAuthenticationException) { throw known }
        catch (known: AcademicSessionExpiredException) { throw known }
        catch (known: AcademicProtocolException) { throw known }
        catch (known: AcademicNetworkException) { throw known }
        catch (_: Exception) { throw AcademicNetworkException() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        identity.value = null
        catalog = null
        cookies.close()
        client.close()
        ownerClient.close()
    }
}

private class AcademicResponse(val status: Int, val bytes: ByteArray? = null, val redirect: String? = null) {
    override fun toString(): String = "AcademicResponse(redacted)"
}

/** Fresh authentication generation; a closed source never accepts late response cookies. */
internal class AcademicCookies : CookiesStorage {
    private val current = MutableStateFlow<AcademicCookieScopes?>(AcademicCookieScopes())
    override suspend fun get(requestUrl: Url): List<Cookie> {
        val generation = current.value ?: return emptyList()
        val result = generation.get(requestUrl)
        return if (current.value === generation) result else emptyList()
    }
    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) { current.value?.addCookie(requestUrl, cookie) }
    fun reset() { current.getAndUpdate { if (it == null) null else AcademicCookieScopes() }?.close() }
    override fun close() { current.getAndUpdate { null }?.close() }
}

private data class AcademicCookieScope(val name: String, val domain: String, val path: String) {
    override fun toString(): String = "AcademicCookieScope(redacted)"
}

/**
 * Ktor's single AcceptAll store replaces equal name/domain cookies even when Path differs.
 * Isolate each RFC cookie identity, retaining Ktor's secure/domain/expiry filtering within it.
 * In particular, a pathless LoginToXk cookie must not erase a pre-existing broad session cookie.
 */
private class AcademicCookieEntry(val store: AcceptAllCookiesStorage, var hostOnly: Boolean)

private class AcademicCookieScopes : CookiesStorage {
    private val mutex = Mutex()
    private val scopes = MutableStateFlow<Map<AcademicCookieScope, AcademicCookieEntry>?>(emptyMap())

    override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
        val captured = scopes.value ?: return@withLock emptyList()
        val result = captured.filter { (key, entry) ->
            val host = requestUrl.host.lowercase()
            (host == key.domain || (!entry.hostOnly && academicCookieDomainMatches(host, key.domain))) &&
                academicCookiePathMatches(requestUrl.encodedPath, key.path)
        }.values.flatMap { it.store.get(requestUrl) }.sortedByDescending { it.path?.length ?: 0 }
        if (scopes.value == null) emptyList() else result
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = mutex.withLock {
        val captured = scopes.value ?: return@withLock
        if (cookie.name.length !in 1..128 || cookie.value.length > 8192) return@withLock
        val domain = (cookie.domain ?: requestUrl.host).lowercase().removePrefix(".")
        if (domain.isBlank() || domain.endsWith('.') || !academicCookieDomainMatches(requestUrl.host.lowercase(), domain) ||
            (cookie.secure && requestUrl.protocol != URLProtocol.HTTPS)) return@withLock
        val path = cookie.path?.takeIf { it.startsWith('/') } ?: run {
            val requestPath = requestUrl.encodedPath
            val lastSlash = requestPath.lastIndexOf('/')
            if (!requestPath.startsWith('/') || lastSlash <= 0) "/" else requestPath.substring(0, lastSlash)
        }
        if (domain.length > 253 || path.length > 1024) return@withLock
        val key = AcademicCookieScope(cookie.name, domain, path)
        if (cookie.maxAge?.let { it <= 0 } == true || cookie.expires?.let { it.timestamp <= Clock.System.now().toEpochMilliseconds() } == true) {
            val expired = captured[key] ?: return@withLock
            if (scopes.compareAndSet(captured, captured - key)) expired.store.close()
            return@withLock
        }
        val entry = captured[key] ?: run {
            // Bound untrusted cookie metadata without evicting the established broad session.
            if (captured.size >= 64) return@withLock
            val created = AcademicCookieEntry(AcceptAllCookiesStorage(), cookie.domain == null)
            if (!scopes.compareAndSet(captured, captured + (key to created))) {
                created.store.close()
                return@withLock // close() detached this generation while the store was created.
            }
            created
        }
        entry.hostOnly = cookie.domain == null
        entry.store.addCookie(requestUrl, cookie.copy(domain = domain, path = path))
        // Never republish a map after this suspension: close/reset can detach it at any time.
    }

    override fun close() { scopes.getAndUpdate { null }?.values?.forEach { it.store.close() } }
}

private fun academicCookieDomainMatches(host: String, domain: String): Boolean = host == domain ||
    (!domain.all { it.isDigit() || it == '.' } && ':' !in domain && host.endsWith(".$domain"))

private fun academicCookiePathMatches(request: String, cookie: String): Boolean = request == cookie ||
    (request.startsWith(cookie) && (cookie.endsWith('/') || request.drop(cookie.length).startsWith('/')))

internal fun academicUrlAllowed(url: Url): Boolean {
    if (url.protocol != URLProtocol.HTTPS || url.host != "jwgln.zsc.edu.cn" || url.port != 443 ||
        url.user != null || url.password != null || url.fragment.isNotEmpty() || url.toString().length > 2048) return false
    val path = url.encodedPath
    if (!path.startsWith(ACADEMIC_BASE_PATH) || path.split('/').any { it == "." || it == ".." } ||
        Regex("%(?:2e|2f|5c|00|0a|0d)", RegexOption.IGNORE_CASE).containsMatchIn(url.toString()) ||
        url.toString().any { it <= ' ' || it == '\\' }) return false
    return true
}

internal fun academicRedirect(base: Url, location: String): Url {
    if (location.isEmpty() || location.length > 2048 || location.startsWith("//") || location.any { it <= ' ' || it == '\\' }) throw AcademicProtocolException()
    val resolved = when {
        location.startsWith("https://") || location.startsWith("http://") -> Url(location)
        location.startsWith('/') -> Url(ACADEMIC_ORIGIN + location)
        location.startsWith('?') -> Url(ACADEMIC_ORIGIN + base.encodedPath + location)
        else -> Url(ACADEMIC_ORIGIN + base.encodedPath.substringBeforeLast('/') + "/" + location)
    }
    if (!academicUrlAllowed(resolved)) throw AcademicProtocolException()
    return resolved
}
