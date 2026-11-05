package io.github.betterzslf.feature.campusnet

/**
 * Portal-side discovery for the ESurfing (天翼校园网) dorm dial-in protocol.
 *
 * Research note: `work/notes/esurfing-dorm-auth.md` §三 (认证流程) — specifically step 3
 * (提取 client_ip / ac_ip) and the `ticket.cgi` URL built in step 4.
 * Mirrors `work/notes/esurfing-ref/DialerAuth.c` steps 2-4.
 *
 * The `ticket.cgi` request needs two addresses that only the portal knows:
 *  - `wlanuserip` → [PortalAddress.clientIp] — the client's IP on the campus network
 *  - `wlanacip`   → [PortalAddress.acIp]     — the access controller's IP
 *
 * §三 step 3 defines where they come from, in priority order: first the query string of the 302
 * `Location` header (issued by the gateway as the client is bounced to the portal); if that does
 * not carry them, then the `ticket-url` advertised inside the portal page's
 * `<!--//config.campus.js.chinatelecom.com ... -->` configuration block. [parsePortalAddress] reads
 * one URL, and [resolvePortalAddress] applies the priority rule across both sources.
 *
 * Pure string work only: no `java.net`, no platform URL/URI type, nothing but Kotlin common APIs.
 */

/** The two addresses `ticket.cgi` requires, as sent verbatim in its query string. */
data class PortalAddress(val clientIp: String, val acIp: String)

/** Query-string delimiters that may legitimately precede a parameter name. */
private val PARAM_NAME_PREFIXES = charArrayOf('?', '&', ';', '#')

/** Characters that end a parameter value. */
private val PARAM_VALUE_TERMINATORS = charArrayOf('&', ';', '#')

/** Portal config block markers, from `DialerAuth.c` step 2. */
private const val PORTAL_CONFIG_START = "<!--//config.campus.js.chinatelecom.com"
private const val PORTAL_CONFIG_END = "//config.campus.js.chinatelecom.com-->"

/** The two parameter names, from `DialerAuth.c` step 3. */
private const val PARAM_CLIENT_IP = "wlanuserip"
private const val PARAM_AC_IP = "wlanacip"

/**
 * Extracts [PortalAddress] from a portal / redirect URL.
 *
 * Handles both plain and percent-encoded spellings, which is what "同时支持 URL 编码形式" in §三
 * asks for: a gateway may hand over `...?wlanuserip=10.1.2.3&wlanacip=10.1.2.1`, or an already
 * encoded copy of the same thing where `?`, `=` and `&` arrive as `%3F`, `%3D` and `%26`. The
 * search therefore runs over the raw text first and, for anything still missing, over a fully
 * percent-decoded copy. Parameter names are matched case-insensitively, and only at a parameter
 * boundary, so a longer name that merely ends with `wlanuserip` cannot be mistaken for it.
 *
 * Both addresses are mandatory — `DialerAuth.c` aborts authentication when either is missing — and
 * each must be a plausible IP literal, so a placeholder such as `0.0.0.0`-shaped garbage or a blank
 * value is reported as a parse failure rather than being sent upstream.
 *
 * @param url the 302 `Location` value, a portal URL, or a `ticket-url` value.
 * @return the two addresses, or `null` when either one is absent or not a valid IP literal.
 */
fun parsePortalAddress(url: String): PortalAddress? {
    val clientIp = findPortalParam(url, PARAM_CLIENT_IP) ?: return null
    val acIp = findPortalParam(url, PARAM_AC_IP) ?: return null
    if (!isIpLiteral(clientIp) || !isIpLiteral(acIp)) return null
    return PortalAddress(clientIp, acIp)
}

/**
 * Applies the §三 step 3 priority rule: read the `Location` query string first, and only fall back
 * to the portal page's advertised `ticket-url` when that yields nothing.
 *
 * @param locationUrl query string source of first resort (usually the 302 `Location`); `null` or
 *        blank when the redirect was not observed.
 * @param portalDocument body of the portal page, searched for a `ticket-url` element; `null` when
 *        the portal page could not be fetched. Not fetched here — no I/O happens in this file.
 * @return the resolved address, or `null` when neither source carries both parameters.
 */
fun resolvePortalAddress(locationUrl: String?, portalDocument: String?): PortalAddress? {
    if (!locationUrl.isNullOrBlank()) parsePortalAddress(locationUrl)?.let { return it }
    if (portalDocument.isNullOrBlank()) return null
    val ticketUrl = extractTicketUrl(portalDocument) ?: return null
    return parsePortalAddress(ticketUrl)
}

/**
 * Pulls the `ticket-url` value out of the portal page, which is the §三 step 3 fallback source for
 * `wlanuserip` / `wlanacip`.
 *
 * This mirrors `DialerAuth.c` step 2 exactly: the page is first narrowed to the
 * `<!--//config.campus.js.chinatelecom.com ... //config.campus.js.chinatelecom.com-->` block, and the
 * `<ticket-url>` element is read from inside that block. The block matters — it is what keeps an
 * unrelated URL elsewhere on the page from being picked up. When the markers are absent the whole
 * document is searched, so a portal that emits only the bare element still works.
 *
 * The value is returned with the CDATA wrapper removed (the C side's `clean_cdata`) and HTML
 * entities decoded, so `&amp;` becomes `&` and the result is directly usable as a query string. An
 * attribute spelling (`ticket-url="..."`, including `value="..."`) is accepted as well as the
 * element spelling.
 *
 * @param portalUrl body of the portal page (or any text containing the config block).
 * @return the cleaned `ticket-url`, or `null` when the page does not advertise one.
 */
fun extractTicketUrl(portalUrl: String): String? {
    if (portalUrl.isBlank()) return null
    val scope = readConfigBlock(portalUrl) ?: portalUrl
    val raw = readElementContent(scope, "ticket-url") ?: return null
    val cleaned = decodeHtmlEntities(stripCdata(raw)).trim()
    return cleaned.ifEmpty { null }
}

/** Returns the text between the portal config comment markers, or `null` when they are absent. */
private fun readConfigBlock(text: String): String? {
    val start = text.indexOf(PORTAL_CONFIG_START, ignoreCase = true)
    if (start < 0) return null
    val contentStart = start + PORTAL_CONFIG_START.length
    val end = text.indexOf(PORTAL_CONFIG_END, contentStart, ignoreCase = true)
    return if (end < 0) text.substring(contentStart) else text.substring(contentStart, end)
}

/**
 * Builds the `ticket.cgi` URL for a chosen [server], per `DialerAuth.c` step 4:
 * `<server>/ticket.cgi?wlanuserip=<clientIp>&wlanacip=<acIp>`.
 *
 * Deliberately carries no `portal_node` (the C comment calls that out): the two parameters are the
 * complete input the endpoint needs.
 */
fun buildDormTicketUrl(server: AuthServer, address: PortalAddress): String =
    "${server.baseUrl}/ticket.cgi?$PARAM_CLIENT_IP=${address.clientIp}&$PARAM_AC_IP=${address.acIp}"

/** Reads one query parameter, tolerating a percent-encoded spelling of the whole URL. */
private fun findPortalParam(url: String, name: String): String? {
    if (url.isBlank()) return null
    readPortalParam(url, name)?.let { return it }
    // Only pay for the decode when the plain spelling did not answer.
    val decoded = percentDecode(url)
    return if (decoded == url) null else readPortalParam(decoded, name)
}

/** Scans `text` for `name=value` at a parameter boundary and returns the first non-empty value. */
private fun readPortalParam(text: String, name: String): String? {
    var from = 0
    while (from <= text.length - name.length) {
        val hit = text.indexOf(name, from, ignoreCase = true)
        if (hit < 0) return null
        val boundaryOk = hit == 0 || text[hit - 1] in PARAM_NAME_PREFIXES
        val equalsAt = hit + name.length
        if (boundaryOk && equalsAt < text.length && text[equalsAt] == '=') {
            val start = equalsAt + 1
            var end = start
            while (end < text.length && text[end] !in PARAM_VALUE_TERMINATORS) end++
            val value = percentDecode(text.substring(start, end)).trim()
            if (value.isNotEmpty()) return value
            from = start
        } else {
            from = hit + name.length
        }
    }
    return null
}

/** Reads `<name>content</name>` (CDATA kept) or `<name attr="content">`, case-insensitively. */
private fun readElementContent(text: String, name: String): String? {
    val open = text.indexOf("<$name", ignoreCase = true)
    if (open >= 0) {
        val tagEnd = text.indexOf('>', open + name.length + 1)
        if (tagEnd >= 0) {
            val close = text.indexOf("</$name", tagEnd + 1, ignoreCase = true)
            if (close >= 0) return text.substring(tagEnd + 1, close)
            // Self-closing or attribute spelling: <ticket-url value="...">
            val attribute = text.substring(open + name.length + 1, tagEnd)
            readAttribute(attribute, "value")?.let { return it }
        }
    }
    return readAttribute(text, name)
}

/** Reads `name="value"` / `name='value'` anywhere in `text`. */
private fun readAttribute(text: String, name: String): String? {
    var from = 0
    while (from <= text.length - name.length) {
        val hit = text.indexOf(name, from, ignoreCase = true)
        if (hit < 0) return null
        var cursor = hit + name.length
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (cursor < text.length && text[cursor] == '=') {
            cursor++
            while (cursor < text.length && text[cursor].isWhitespace()) cursor++
            if (cursor < text.length && (text[cursor] == '"' || text[cursor] == '\'')) {
                val quote = text[cursor]
                val close = text.indexOf(quote, cursor + 1)
                if (close > cursor + 1) return text.substring(cursor + 1, close)
            }
        }
        from = hit + name.length
    }
    return null
}

/** Removes a `<![CDATA[ ... ]]>` wrapper, the C side's `clean_cdata`. */
private fun stripCdata(value: String): String {
    val start = value.indexOf("<![CDATA[")
    if (start < 0) return value
    val end = value.indexOf("]]>", start + 9)
    return if (end < 0) value.substring(start + 9) else value.substring(start + 9, end)
}

/** Decodes the HTML entities a portal may use inside an attribute or element value. */
private fun decodeHtmlEntities(value: String): String =
    value.replace("&amp;", "&", ignoreCase = false)
        .replace("&lt;", "<", ignoreCase = false)
        .replace("&gt;", ">", ignoreCase = false)
        .replace("&quot;", "\"", ignoreCase = false)
        .replace("&#39;", "'", ignoreCase = false)

/**
 * Percent-decodes `%XX` triplets into UTF-8.
 *
 * Only `%XX` is translated: a literal `+` is left alone, because the values here are IP literals and
 * treating `+` as a space would silently corrupt a malformed address instead of rejecting it.
 * Stray or truncated `%` sequences are kept verbatim rather than throwing.
 */
private fun percentDecode(value: String): String {
    if (value.indexOf('%') < 0) return value
    val bytes = ArrayList<Byte>(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] == '%' && index + 2 < value.length) {
            val high = hexDigit(value[index + 1])
            val low = hexDigit(value[index + 2])
            if (high >= 0 && low >= 0) {
                bytes.add(((high shl 4) or low).toByte())
                index += 3
                continue
            }
        }
        // Copy the literal character through its UTF-8 bytes so mixed encoded/plain text
        // round-trips through the same decoder at the end.
        for (byte in value[index].toString().encodeToByteArray()) bytes.add(byte)
        index++
    }
    return bytes.toByteArray().decodeToString()
}

/** `0-9 / a-f / A-F` → value, anything else → -1. */
private fun hexDigit(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
}

/**
 * Accepts an IPv4 dotted quad or a colon-bearing IPv6 literal.
 *
 * Deliberately strict on IPv4 (four labels, each `0..255`, no leading zeros beyond a bare `0`) so a
 * placeholder from a captive-portal template is rejected instead of being sent as a real address.
 */
private fun isIpLiteral(value: String): Boolean {
    if (value.isEmpty() || value.length > 45) return false
    if (':' in value) return value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    val labels = value.split('.')
    if (labels.size != 4) return false
    return labels.all { label ->
        label.isNotEmpty() && label.length <= 3 && label.all { it in '0'..'9' } &&
            (label.length == 1 || label[0] != '0') && label.toInt() in 0..255
    }
}
