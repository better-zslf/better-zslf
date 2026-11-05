package io.github.betterzslf.feature.campusnet

/**
 * The `local-time` field the dialer's envelopes carry.
 *
 * The reference formats the device clock as `%Y-%m-%d %H:%M:%S` in local time. It is not used for
 * authentication, but the field must be present and well-formed: the gateway parses these
 * documents strictly, and an empty or malformed timestamp is the kind of difference that fails in
 * a way indistinguishable from a wrong password.
 *
 * `commonMain` has no locale-aware formatter, so this converts epoch milliseconds itself. It works
 * on the local offset the caller supplies rather than assuming UTC, because the reference uses
 * local time.
 */
internal fun formatDormLocalTime(epochMillis: Long, offsetMinutes: Int): String {
    // Shift into local wall-clock, then work entirely in "seconds since local epoch".
    val shifted = epochMillis + offsetMinutes * 60_000L
    var days = floorDiv(shifted, 86_400_000L)
    val msInDay = shifted - days * 86_400_000L
    val hour = (msInDay / 3_600_000L).toInt()
    val minute = ((msInDay / 60_000L) % 60).toInt()
    val second = ((msInDay / 1000L) % 60).toInt()

    // Civil-from-days (Howard Hinnant's algorithm), valid for the whole range we can encounter.
    days += 719_468
    val era = floorDiv(days, 146_097L)
    val dayOfEra = days - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
    val year = yearOfEra + era * 400
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val mp = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val calendarYear = if (month <= 2) year + 1 else year

    fun pad(v: Long, width: Int) = v.toString().padStart(width, '0')
    return "${pad(calendarYear, 4)}-${pad(month, 2)}-${pad(day, 2)} " +
        "${pad(hour.toLong(), 2)}:${pad(minute.toLong(), 2)}:${pad(second.toLong(), 2)}"
}

/** Floor division: `days` must round down for negative epochs, unlike Kotlin's `/`. */
private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if (a % b != 0L && (a xor b) < 0) q - 1 else q
}
