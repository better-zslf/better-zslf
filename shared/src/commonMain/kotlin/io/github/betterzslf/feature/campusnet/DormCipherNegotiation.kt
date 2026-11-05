package io.github.betterzslf.feature.campusnet

/**
 * Reads the cipher negotiation blob (`ZSM`) the carrier gateway returns.
 *
 * The gateway answers `ticket.cgi` with a binary blob whose layout is
 * `[3-byte header][u8 len][name][u8 len][algoId]...`. The `algoId` is a 36-character UUID that
 * selects which cipher family the rest of the session uses, and every branch carries its own
 * hard-coded key/iv — so nothing can be decrypted until this value is known.
 *
 * This parser exists so the app can *report* the value on a real campus network instead of
 * guessing. It deliberately does not attempt to decrypt anything.
 *
 * Mirrors `extract_algo_id_from_zsm` in the reference implementation
 * (`src/dialer/DialerTicket.c`), including its fallback.
 */

/** A 36-character UUID: 8-4-4-4-12 hex with hyphens at the standard positions. */
private val UUID_SHAPE = Regex("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")

internal fun isDormAlgoId(value: String): Boolean = UUID_SHAPE.matches(value)

private fun isUuidAt(bytes: ByteArray, offset: Int, length: Int): Boolean {
    if (length != 36 || offset < 0 || offset + 36 > bytes.size) return false
    val text = bytes.decodeToString(offset, offset + 36)
    return isDormAlgoId(text)
}

/** A length-prefixed string, as the ZSM header uses. Returns the next offset, or null if malformed. */
private class PascalCursor(val bytes: ByteArray, var offset: Int) {
    fun read(): String? {
        if (offset >= bytes.size) return null
        val length = bytes[offset].toInt() and 0xFF
        offset++
        if (length == 0 || offset + length > bytes.size) return null
        val value = bytes.decodeToString(offset, offset + length)
        offset += length
        return value
    }
}

/**
 * The cipher family id, uppercased, or `null` when the blob carries none.
 *
 * Order matters and follows the reference exactly:
 *  1. skip the 3-byte header, read two length-prefixed strings; the **second** is checked first,
 *     then the first — the newer gateway layout puts the id second;
 *  2. otherwise take the last 36 bytes, after trimming trailing whitespace/NULs.
 */
fun parseDormAlgoId(zsm: ByteArray): String? {
    if (zsm.size < 5) return null
    val cursor = PascalCursor(zsm, 3)
    val first = cursor.read()
    val second = cursor.read()
    if (first != null && second != null) {
        if (isDormAlgoId(second)) return second.uppercase()
        if (isDormAlgoId(first)) return first.uppercase()
    }
    var end = zsm.size
    while (end > 0 && zsm[end - 1].toInt().toChar() in charArrayOf('\n', '\r', '\u0000', ' ', '\t')) end--
    if (end >= 36 && isUuidAt(zsm, end - 36, 36)) return zsm.decodeToString(end - 36, end).uppercase()
    return null
}

/**
 * How many of the 27 Android-channel cipher families this build can actually apply.
 *
 * Zero today, and that is the honest number: the id is knowable but the key table is not portable
 * until the real value is captured. Reporting it keeps the UI from implying the dialer half-works.
 */
const val DORM_CIPHER_FAMILIES_IMPLEMENTED: Int = 0

/** Android is the channel this app reports, so the gateway serves that family of ids. */
const val DORM_CHANNEL_ANDROID: String = "android"
