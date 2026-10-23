package io.github.betterzslf.feature.booking

import io.github.betterzslf.core.text.AppText
import kotlinx.serialization.json.JsonElement

/** School calendar dates use UTC+08:00; no device timezone-dependent parsing. */
fun bookingSchoolMidnight(date: String): Long? {
    if (!validBookingDate(date)) return null
    var year = date.take(4).toInt()
    val month = date.substring(5, 7).toInt()
    val day = date.takeLast(2).toInt()
    if (month <= 2) year--
    val era = year / 400
    val yearOfEra = year - era * 400
    val dayOfYear = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
    val days = era * 146097L + yearOfEra * 365L + yearOfEra / 4 - yearOfEra / 100 + dayOfYear - 719468L
    return days * 86_400_000L - 8 * 3_600_000L
}

/** Original C() uses floor(hours)>5, with 08:00 morning / 12:00 afternoon. */
fun fitnessCancellationTiming(test: JsonElement, nowMillis: Long): String? {
    if (test.bookingText("userStatus") != "1") return AppText.Booking.NOT_CURRENTLY_REGISTERED_REFRESH_TO_CONFIRM
    val date = test.bookingText("userPhysicalTime").take(10)
    val midnight = bookingSchoolMidnight(date) ?: return AppText.Booking.REGISTRATION_DATE_MISSING_REFRESH_TO_CONFIRM
    val hour = when (test.bookingText("userPeriod")) { "0" -> 8; "1" -> 12; else -> return AppText.Booking.REGISTRATION_PERIOD_MISSING_REFRESH_TO_CONFIRM }
    val hours = (midnight + hour * 3_600_000L - nowMillis) / 3_600_000L
    return if (hours > 5) null else AppText.Booking.FITNESS_CANCELLATION_NOTICE_PERIOD_INSUFFICIENT_REVIEW_RULES
}

fun fitnessEnrolmentTiming(date: String, test: JsonElement, nowMillis: Long): List<String> = buildList {
    val day = bookingSchoolMidnight(date)
    // The shipped picker exposes future calendar days only.
    if (day != null && day <= nowMillis) add(AppText.Booking.SELECT_NOT_STARTED_BOOKING_DATE_IN_SCHOOL_TIME)
    val end = bookingSchoolMidnight(test.bookingText("testEnd").take(10))
    if (end != null && nowMillis >= end + 86_400_000L) add(AppText.Booking.FITNESS_SESSION_ENDED)
}
