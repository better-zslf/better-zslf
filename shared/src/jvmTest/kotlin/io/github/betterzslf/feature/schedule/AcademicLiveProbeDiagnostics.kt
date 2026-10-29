@file:OptIn(io.ktor.utils.io.InternalAPI::class)

package io.github.betterzslf.feature.schedule

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.encoding.Base64

/** Opt-in live-test helper only. No requests occur until the production source uses this fresh engine. */
fun createRedactedAcademicProbeClient(account: String, password: String): HttpClient {
    val account64 = Base64.Default.encode(account.encodeToByteArray())
    val password64 = Base64.Default.encode(password.encodeToByteArray())
    return HttpClient(RedactedAcademicProbeEngine(account, account64 + "%%%" + password64,
        listOf(account, password, account64, password64)))
}

private class RedactedAcademicProbeEngine(account: String, encoded: String, guards: List<String>) :
    HttpClientEngineBase("redacted-academic-probe") {
    private val delegate = CIO.create()
    override val config: HttpClientEngineConfig get() = delegate.config
    override val supportedCapabilities get() = delegate.supportedCapabilities
    private val sequence = AtomicInteger()
    private val expectedAccount = AtomicReference<String?>(account)
    private val expectedEncoded = AtomicReference<String?>(encoded)
    private val privateGuards = AtomicReference(guards)
    private val previousSession = AtomicReference<String?>(null)

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val index = sequence.incrementAndGet()
        try { observeRequest(index, data) } catch (_: Exception) { flag(index, "request_observer_failed", true) }
        val response = delegate.execute(data)
        val channel = response.body as? ByteReadChannel
        if (channel == null) { flag(index, "response_channel_missing", true); return response }
        val buffer = ByteArray(2_097_153)
        var used = 0
        while (used <= 2_097_152) {
            val count = channel.readAvailable(buffer, used, buffer.size - used)
            if (count < 0) break
            used += count
            if (used > 2_097_152) {
                channel.cancel(null)
                flag(index, "response_over_limit", true)
                throw IllegalStateException("Academic probe response limit exceeded")
            }
        }
        val bytes = buffer.copyOf(used)
        try { observeResponse(index, data, response, bytes) } catch (_: Exception) { flag(index, "response_observer_failed", true) }
        // Preserve status, timing, headers, protocol and call context. Replay the exact bytes, not a re-encoded DOM.
        return HttpResponseData(response.statusCode, response.requestTime, response.headers, response.version,
            ByteReadChannel(bytes), response.callContext)
    }

    private fun observeRequest(index: Int, data: HttpRequestData) {
        val method = data.method.value.takeIf { it == "GET" || it == "POST" } ?: "OTHER"
        val ua = when (data.headers[HttpHeaders.UserAgent]) {
            "better-zslf-academic" -> "production"
            "Mozilla/5.0" -> "browser_fixture"
            null -> "absent"
            else -> "other"
        }
        val referer = data.headers["Referer"]?.let { runCatching { publicPath(Url(it).encodedPath) }.getOrNull() } ?: "ABSENT_OR_OTHER"
        val form = (data.body as? OutgoingContent.ByteArrayContent)?.let { body ->
            runCatching { parseQueryString(body.bytes().decodeToString(throwOnInvalidSequence = true)) }.getOrNull()
        }
        val safeKeys = form?.names()?.map { if (it in setOf("encoded", "xnxq01id", "zc", "demo", "sfFD", "cj0701id")) it else "OTHER" }?.sorted().orEmpty()
        val requestCookies = data.headers.getAll(HttpHeaders.Cookie).orEmpty().flatMap { it.split(';') }.mapNotNull { part ->
            val equal = part.indexOf('=')
            if (equal <= 0) null else part.substring(0, equal).trim() to part.substring(equal + 1).trim().removeSurrounding("\"")
        }.toMap()
        val last = previousSession.get()
        val sentSession = requestCookies["JSESSIONID"]
        println("academic_probe_req seq=$index method=$method path=${publicPath(data.url.encodedPath)} ua=$ua referer_path=$referer " +
            "form_keys=${safeKeys.joinToString(",")} encoded_matches_expected=${expectedEncoded.get()?.let { form?.get("encoded") == it } == true} " +
            "cookie_names=${requestCookies.keys.map(::safeCookieName).sorted().joinToString(",")} " +
            "previous_jsession_present=${last != null} jsession_present=${sentSession != null} jsession_matches_previous=${last != null && sentSession == last}")
    }

    private fun observeResponse(index: Int, data: HttpRequestData, response: HttpResponseData, bytes: ByteArray) {
        val setCookies = response.headers.getAll(HttpHeaders.SetCookie).orEmpty()
        val parsed = setCookies.take(32).mapNotNull { raw -> runCatching { parseServerSetCookieHeader(raw) }.getOrNull()?.let { it to raw } }
        val last = parsed.lastOrNull { it.first.name == "JSESSIONID" }?.first
        if (last != null) previousSession.set(last.value)
        println("academic_probe_resp seq=$index status=${response.statusCode.value} " +
            "location_path=${response.headers[HttpHeaders.Location]?.let { publicLocation(data.url, it) } ?: "ABSENT"} " +
            "set_cookie_names=${parsed.map { safeCookieName(it.first.name) }.sorted().joinToString(",")} " +
            "set_cookie_parse_complete=${parsed.size == setCookies.size}")
        for ((cookie, raw) in parsed) {
            val path = cookie.path?.takeIf { it.startsWith('/') } ?: data.url.encodedPath.substringBeforeLast('/').ifEmpty { "/" }
            val maxAge = Regex("(?:^|;)\\s*Max-Age\\s*=\\s*(-?[0-9]+)", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.toLongOrNull()
            val expired = maxAge?.let { it <= 0 } == true || cookie.expires?.let { it.timestamp <= System.currentTimeMillis() } == true
            println("academic_probe_cookie seq=$index name=${safeCookieName(cookie.name)} secure=${cookie.secure} " +
                "path_present=${cookie.path != null} request_path_matches=${pathMatches(data.url.encodedPath, path)} " +
                "root_path_matches=${pathMatches("/jsxsd/", path)} login_path_matches=${pathMatches("/jsxsd/xk/LoginToXk", path)} " +
                "main_path_matches=${pathMatches("/jsxsd/framework/xsMain.jsp", path)} expired=$expired")
        }
        val html = runCatching { decodeAcademicHtml(bytes) }.getOrNull()
        val doc = html?.let { runCatching { academicDocument(it) }.getOrNull() }
        val expected = expectedAccount.get()
        val menu = doc?.select("div#Top1_divLoginName")?.singleOrNull()
        val menuAccount = menu?.ownText()?.trim()?.let { Regex("\\(([^()]*)\\)$").find(it)?.groupValues?.get(1) }
        flag(index, "html_decoded", html != null)
        flag(index, "has_login_shell", html?.let { runCatching { academicLoginShell(it) }.getOrDefault(false) } == true)
        flag(index, "has_password", doc?.select("input[type=password]")?.isNotEmpty() == true)
        flag(index, "fixed_menu_present", menu != null)
        flag(index, "fixed_menu_class_matches", doc?.select("div#Top1_divLoginName.Nsb_top_menu_nc")?.size == 1)
        flag(index, "menu_suffix_account_matches", expected != null && menuAccount == expected)
        flag(index, "exact_identity_match", html != null && expected != null && runCatching { parseAcademicIdentity(html, expected) != null }.getOrDefault(false))
        val text = doc?.text().orEmpty()
        flag(index, "error_password", text.contains("用户名或密码错误") || text.contains("密码错误"))
        flag(index, "error_unknown_account", text.contains("用户不存在") || text.contains("账号不存在"))
        flag(index, "captcha_present", text.contains("验证码"))
        flag(index, "password_change_present", text.contains("修改密码"))
        if (data.url.encodedPath == "/jsxsd/xskb/xskb_list.do") {
            // Only a tiny public period-label grammar is printable; never course/teacher/room text.
            val labelPattern = Regex("^第[一二三四五六七八九十0-9,，、~至 大小-]{1,20}节$")
            val labels = doc?.select("td,th")?.map { it.ownText().trim() }
                ?.filter { labelPattern.matches(it) }?.distinct()?.sorted()?.take(12).orEmpty()
            println("academic_probe_period_labels seq=$index labels=${labels.joinToString(",")}")
        }
    }

    private fun safeCookieName(name: String): String {
        val guards = privateGuards.get()
        if (guards.isEmpty()) return if (name in setOf("JSESSIONID", "Path")) name else "OTHER"
        return name.takeIf { it.matches(Regex("^[A-Za-z_][A-Za-z0-9_.-]{0,63}$")) &&
            guards.none { secret -> secret.isNotEmpty() && it.contains(secret, ignoreCase = true) } } ?: "OTHER"
    }

    override fun close() {
        expectedAccount.set(null)
        expectedEncoded.set(null)
        previousSession.set(null)
        privateGuards.set(emptyList())
        delegate.close()
        super.close()
    }
}

private fun flag(index: Int, key: String, value: Boolean) { println("academic_probe_flag seq=$index $key=$value") }

private fun publicPath(path: String): String = path.substringBefore(';').takeIf {
    it in setOf("/jsxsd/", "/jsxsd/xk/LoginToXk", "/jsxsd/framework/xsMain.jsp", "/jsxsd/xskb/xskb_list.do", "/jsxsd/jxzl/jxzl_query")
} ?: "OTHER"

private fun publicLocation(base: Url, location: String): String = runCatching {
    val resolved = when {
        location.startsWith("https://") || location.startsWith("http://") -> Url(location)
        location.startsWith('/') -> Url(ACADEMIC_ORIGIN + location)
        else -> Url(ACADEMIC_ORIGIN + base.encodedPath.substringBeforeLast('/') + "/" + location)
    }
    publicPath(resolved.encodedPath)
}.getOrDefault("OTHER")

private fun pathMatches(request: String, cookie: String): Boolean = request == cookie ||
    (request.startsWith(cookie) && (cookie.endsWith('/') || request.drop(cookie.length).startsWith('/')))
