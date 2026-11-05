package io.github.betterzslf.feature.campusnet

/**
 * The protocol's XML envelopes, and the tiny parser needed to read its replies.
 *
 * Every request the dialer sends is an XML document encrypted with the session cipher, so the
 * envelope is not decorative: the gateway parses these tags by name. Field order and the exact
 * whitespace match the reference templates, because a stricter server could reject a reordered
 * document and the failure would look identical to a wrong password.
 *
 * Values are escaped: `client-id`, `mac` and the ticket are server-influenced strings, and an
 * unescaped `&` would produce malformed XML that the gateway answers with a generic error.
 */

/** Which envelope to build. */
enum class DormXmlKind { GetTicket, Login, Heartbeat, Term }

/** Escapes the five XML metacharacters. Only these can break out of element content. */
internal fun dormXmlEscape(value: String): String {
    if (value.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return value
    val out = StringBuilder(value.length + 16)
    for (c in value) when (c) {
        '&' -> out.append("&amp;")
        '<' -> out.append("&lt;")
        '>' -> out.append("&gt;")
        '"' -> out.append("&quot;")
        '\'' -> out.append("&apos;")
        else -> out.append(c)
    }
    return out.toString()
}

/** Everything a request needs. Grouped so the builder cannot accidentally read a stale field. */
data class DormXmlFields(
    val userAgent: String,
    val clientId: String,
    val localTime: String,
    val hostName: String = "",
    val ipv4: String = "",
    val mac: String = "",
    val ostag: String = "",
    val gwip: String = "",
    val ticket: String = "",
    val userId: String = "",
    val password: String = "",
)

/**
 * Builds one envelope.
 *
 * `localTime` is a parameter rather than a clock read so the output is reproducible in tests.
 */
fun buildDormXml(kind: DormXmlKind, fields: DormXmlFields): String {
    fun e(v: String) = dormXmlEscape(v)
    val body = when (kind) {
        DormXmlKind.GetTicket -> buildString {
            append("    <user-agent>").append(e(fields.userAgent)).append("</user-agent>\n")
            append("    <client-id>").append(e(fields.clientId)).append("</client-id>\n")
            append("    <local-time>").append(e(fields.localTime)).append("</local-time>\n")
            append("    <host-name>").append(e(fields.hostName)).append("</host-name>\n")
            append("    <ipv4>").append(e(fields.ipv4)).append("</ipv4>\n")
            append("    <ipv6></ipv6>\n")
            append("    <mac>").append(e(fields.mac)).append("</mac>\n")
            append("    <ostag>").append(e(fields.ostag)).append("</ostag>\n")
            append("    <gwip>").append(e(fields.gwip)).append("</gwip>\n")
        }
        DormXmlKind.Login -> buildString {
            append("    <user-agent>").append(e(fields.userAgent)).append("</user-agent>\n")
            append("    <client-id>").append(e(fields.clientId)).append("</client-id>\n")
            append("    <ticket>").append(e(fields.ticket)).append("</ticket>\n")
            append("    <local-time>").append(e(fields.localTime)).append("</local-time>\n")
            append("    <userid>").append(e(fields.userId)).append("</userid>\n")
            append("    <passwd>").append(e(fields.password)).append("</passwd>\n")
        }
        // The reference uses one template for both heartbeat and terminate.
        DormXmlKind.Heartbeat, DormXmlKind.Term -> buildString {
            append("    <user-agent>").append(e(fields.userAgent)).append("</user-agent>\n")
            append("    <client-id>").append(e(fields.clientId)).append("</client-id>\n")
            append("    <local-time>").append(e(fields.localTime)).append("</local-time>\n")
            append("    <host-name>").append(e(fields.hostName)).append("</host-name>\n")
            append("    <ipv4>").append(e(fields.ipv4)).append("</ipv4>\n")
            append("    <ticket>").append(e(fields.ticket)).append("</ticket>\n")
            append("    <ipv6></ipv6>\n")
            append("    <mac>").append(e(fields.mac)).append("</mac>\n")
            append("    <ostag>").append(e(fields.ostag)).append("</ostag>\n")
        }
    }
    return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<request>\n$body</request>\n"
}

/**
 * Reads the text of the first `<tag>` in an already-decrypted reply.
 *
 * Returns `null` when the tag is absent, which is a normal outcome: the gateway omits fields it has
 * nothing to say about, and callers distinguish "absent" from "empty".
 */
fun dormXmlValue(xml: String, tag: String): String? {
    val startTag = "<$tag>"
    val open = xml.indexOf(startTag)
    if (open < 0) return null
    val contentStart = open + startTag.length
    val close = xml.indexOf("</$tag>", contentStart)
    if (close < 0 || close <= contentStart) return null
    return xml.substring(contentStart, close)
}

/** The decrypted ticket, or `null` when the reply did not carry one. */
fun dormXmlTicket(xml: String): String? = dormXmlValue(xml, "ticket")?.takeIf { it.isNotBlank() }

/**
 * The `<response>` code, when present. The reference treats a missing code as "look at the ticket".
 * Kept separate from the ticket because a reply can legitimately carry neither.
 */
fun dormXmlCode(xml: String): String? = dormXmlValue(xml, "code")?.trim()
