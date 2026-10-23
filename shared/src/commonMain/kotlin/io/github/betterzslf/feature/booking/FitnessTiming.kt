package io.github.betterzslf.feature.booking

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
    if (test.bookingText("userStatus") != "1") return "当前不在已报名状态，请先刷新确认"
    val date = test.bookingText("userPhysicalTime").take(10)
    val midnight = bookingSchoolMidnight(date) ?: return "报名日期缺失，请先刷新确认"
    val hour = when (test.bookingText("userPeriod")) { "0" -> 8; "1" -> 12; else -> return "报名时段缺失，请先刷新确认" }
    val hours = (midnight + hour * 3_600_000L - nowMillis) / 3_600_000L
    return if (hours > 5) null else "距本次体测已不足允许取消的提前时间，请先核对场次规则"
}

fun fitnessEnrolmentTiming(date: String, test: JsonElement, nowMillis: Long): List<String> = buildList {
    val day = bookingSchoolMidnight(date)
    // The shipped picker exposes future calendar days only.
    if (day != null && day <= nowMillis) add("请选择尚未开始的预约日期（学校时间）")
    val end = bookingSchoolMidnight(test.bookingText("testEnd").take(10))
    if (end != null && nowMillis >= end + 86_400_000L) add("本场体测已结束")
}
