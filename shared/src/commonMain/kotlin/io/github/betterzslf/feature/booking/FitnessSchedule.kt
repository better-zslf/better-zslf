package io.github.betterzslf.feature.booking

import kotlinx.serialization.json.*

/** Sunday=0, matching the shipped showDay values; validated Gregorian calendar arithmetic. */
fun bookingWeekday(date: String): Int? {
    if (!validBookingDate(date)) return null
    var year = date.take(4).toInt()
    val month = date.substring(5, 7).toInt()
    val day = date.takeLast(2).toInt()
    val table = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
    if (month < 3) year--
    return (year + year / 4 - year / 100 + year / 400 + table[month - 1] + day) % 7
}

/** These exact capacity fields are observed in fitnessDetail, not inferred from abbreviations alone. */
fun fitnessScheduleValidation(date: String, sex: String, period: String, test: JsonElement, counts: JsonElement?): List<String> = buildList {
    val start = test.bookingText("testStart").take(10)
    val end = test.bookingText("testEnd").take(10)
    if (!validBookingDate(start) || !validBookingDate(end)) add("场次未返回有效开放日期范围，请重新查询")
    else if (validBookingDate(date) && (date < start || date > end)) add("预约日期不在该场次开放范围内")
    val weekday = bookingWeekday(date)
    val days = test.bookingText("showDay").split(',').map(String::trim).filter(String::isNotBlank)
    if (days.isEmpty()) add("场次未返回开放星期，请重新查询")
    else if (weekday != null && weekday.toString() !in days) add("所选日期不是该场次开放的星期")
    val status = test.bookingText("userStatus")
    if (status.isNotBlank() && status != "4") add("当前场次已有报名或其他处理状态，请先核对最新报名信息")
    val weekdayText = listOf("日", "一", "二", "三", "四", "五", "六").getOrNull(weekday ?: -1).orEmpty()
    val dayCounts = (counts as? JsonObject)?.let { it[date] ?: it["$date($weekdayText)"] }
    val fields = when {
        sex == "男" && period == "上午" -> "mlbCount" to "mLimitBoy"
        sex == "男" && period == "下午" -> "albCount" to "aLimitBoy"
        sex == "女" && period == "上午" -> "mlgCount" to "mLimitGirls"
        sex == "女" && period == "下午" -> "algCount" to "aLimitGirls"
        else -> null
    }
    if (fields != null) {
        val count = dayCounts?.bookingText(fields.first)?.toIntOrNull()
        val limit = test.bookingText(fields.second).toIntOrNull()
        if (count != null && limit != null && count >= limit) add("所选日期、性别与时段名额已满，请刷新后重新选择")
    }
}

fun fitnessCancellationLimit(info: JsonElement?): String? {
    val max = info?.bookingText("mcount")?.toIntOrNull() ?: return null
    val used = info.bookingText("pcount").toIntOrNull() ?: return null
    return if (used > max) "取消次数已超过该场次允许上限，请先核对报名信息" else null
}
