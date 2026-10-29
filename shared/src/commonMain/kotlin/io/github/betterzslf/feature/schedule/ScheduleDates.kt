package io.github.betterzslf.feature.schedule

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

const val SCHEDULE_TIME_ZONE_ID: String = "Asia/Shanghai"
private val schoolZone = TimeZone.of(SCHEDULE_TIME_ZONE_ID)
private val clockPattern = Regex("^(?:[01][0-9]|2[0-3]):[0-5][0-9]$")

/** User-confirmed timetable v1; each call returns fresh values rather than exposing global mutable state. */
fun defaultSchedulePeriods(): List<SchedulePeriod> = listOf(
    "08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40",
    "14:30" to "15:15", "15:25" to "16:10", "16:20" to "17:05", "17:15" to "18:00",
    "18:40" to "19:25", "19:35" to "20:20", "20:30" to "21:15", "21:25" to "22:10",
).mapIndexed { index, times -> SchedulePeriod(index + 1, times.first, times.second) }

/** Exactly 12 ordered, nonoverlapping, same-school-day periods; breaks remain real gaps. */
fun validateSchedulePeriods(periods: List<SchedulePeriod>): Boolean {
    if (periods.map { it.number } != (1..12).toList()) return false
    var previousEnd: LocalTime? = null
    for (period in periods) {
        if (!clockPattern.matches(period.start) || !clockPattern.matches(period.end)) return false
        val start = runCatching { LocalTime.parse(period.start) }.getOrNull() ?: return false
        val end = runCatching { LocalTime.parse(period.end) }.getOrNull() ?: return false
        if (start >= end || previousEnd?.let { start < it } == true) return false
        previousEnd = end
    }
    return true
}

fun scheduleSchoolDate(nowMillis: Long): LocalDate = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(schoolZone).date

/** No inference from semester names or a different week's Monday. Missing/ambiguous mappings stay unknown. */
fun scheduleDateForWeek(snapshot: ScheduleSnapshot, week: Int, dayOfWeek: Int): LocalDate? {
    if (week !in 0..99 || dayOfWeek !in 1..7) return null
    val raw = snapshot.weekMondays[week] ?: return null
    val monday = isoDate(raw) ?: return null
    if (monday.dayOfWeek != DayOfWeek.MONDAY || snapshot.weekMondays.values.count { it == raw } != 1) return null
    return runCatching { monday.plus(dayOfWeek - 1, DateTimeUnit.DAY) }.getOrNull()
}

fun scheduleWeekForDate(snapshot: ScheduleSnapshot, date: LocalDate): Int? = snapshot.weekMondays.keys.filter { week ->
    val monday = scheduleDateForWeek(snapshot, week, 1) ?: return@filter false
    date >= monday && date <= monday.plus(6, DateTimeUnit.DAY)
}.singleOrNull()

fun scheduleCoursesForWeek(snapshot: ScheduleSnapshot, week: Int): List<ScheduleCourse> =
    snapshot.courses.filter { week in it.weeks }.sortedWith(compareBy<ScheduleCourse> { it.dayOfWeek }
        .thenBy { it.periods.minOrNull() ?: Int.MAX_VALUE }.thenBy { it.id })

/**
 * Occurrences overlapping [fromMillis, untilMillis), using only explicit calendar mappings and complete snapshots.
 * One contiguous numbered-period run is one occurrence; a noncontiguous course produces separate occurrences.
 * Fixed school zone, never device zone. IDs do not change merely because period clock times are adjusted.
 */
fun scheduleOccurrences(
    snapshot: ScheduleSnapshot,
    periods: List<SchedulePeriod>,
    fromMillis: Long,
    untilMillis: Long,
): List<ScheduleOccurrence> {
    if (!snapshot.complete || fromMillis >= untilMillis || !validateSchedulePeriods(periods)) return emptyList()
    val byNumber = periods.associateBy { it.number }
    val output = mutableListOf<ScheduleOccurrence>()
    for (course in snapshot.courses) {
        if (course.id.isBlank() || course.title.isBlank() || course.dayOfWeek !in 1..7 || course.periods.isEmpty() ||
            course.periods.any { it !in 1..12 } || course.weeks.isEmpty() || course.weeks.any { it !in 0..99 }) continue
        val runs = contiguousPeriods(course.periods)
        for (week in course.weeks.distinct().sorted()) {
            val date = scheduleDateForWeek(snapshot, week, course.dayOfWeek) ?: continue
            for (run in runs) {
                val first = byNumber.getValue(run.first())
                val last = byNumber.getValue(run.last())
                val start = runCatching { date.atTime(LocalTime.parse(first.start)).toInstant(schoolZone).toEpochMilliseconds() }.getOrNull() ?: continue
                val end = runCatching { date.atTime(LocalTime.parse(last.end)).toInstant(schoolZone).toEpochMilliseconds() }.getOrNull() ?: continue
                if (start >= untilMillis || end <= fromMillis) continue
                val id = scheduleStableId("occ", course.id, week.toString(), date.toString(), run.joinToString(","))
                output += ScheduleOccurrence(id, course, week, date.toString(), run, start, end)
            }
        }
    }
    return output.distinctBy { it.id }.sortedWith(compareBy<ScheduleOccurrence> { it.startMillis }.thenBy { it.id })
}

/** Number of courses that have at least one unknown date, week, weekday or period; never estimates notifications. */
fun scheduleUndatedCourseCount(snapshot: ScheduleSnapshot): Int = snapshot.courses.count { course ->
    course.weeks.isEmpty() || course.dayOfWeek !in 1..7 || course.periods.isEmpty() || course.periods.any { it !in 1..12 } ||
        course.weeks.any { scheduleDateForWeek(snapshot, it, course.dayOfWeek) == null }
}

fun <T : ScheduleTimedOccurrence> scheduleTodayOccurrences(occurrences: List<T>, nowMillis: Long): List<T> {
    val today = scheduleSchoolDate(nowMillis).toString()
    return ordered(occurrences.filter { it.date == today && it.startsAtMillis < it.endsAtMillis })
}

fun <T : ScheduleTimedOccurrence> scheduleCurrentOccurrences(occurrences: List<T>, nowMillis: Long): List<T> =
    ordered(occurrences.filter { it.startsAtMillis <= nowMillis && it.endsAtMillis > nowMillis })

/** Strictly future start, not an already ongoing class. */
fun <T : ScheduleTimedOccurrence> scheduleNextOccurrence(occurrences: List<T>, nowMillis: Long): T? =
    ordered(occurrences.filter { it.startsAtMillis > nowMillis && it.startsAtMillis < it.endsAtMillis }).firstOrNull()

/** Includes the next school midnight even for an empty list, so a widget can leave yesterday without network login. */
fun <T : ScheduleTimedOccurrence> scheduleNextBoundaryMillis(occurrences: List<T>, nowMillis: Long): Long? {
    val midnight = runCatching {
        scheduleSchoolDate(nowMillis).plus(1, DateTimeUnit.DAY).atStartOfDayIn(schoolZone).toEpochMilliseconds()
    }.getOrNull()
    val times = occurrences.flatMap { listOf(it.startsAtMillis, it.endsAtMillis) }.filter { it > nowMillis }
    return (times + listOfNotNull(midnight)).minOrNull()
}

private fun <T : ScheduleTimedOccurrence> ordered(items: List<T>): List<T> =
    items.sortedWith(compareBy<T> { it.startsAtMillis }.thenBy { it.id })

internal fun isoDate(value: String): LocalDate? = runCatching { LocalDate.parse(value).takeIf { it.toString() == value } }.getOrNull()

private fun contiguousPeriods(periods: List<Int>): List<List<Int>> {
    val result = mutableListOf<MutableList<Int>>()
    for (number in periods.distinct().sorted()) {
        val last = result.lastOrNull()
        if (last == null || number != last.last() + 1) result.add(mutableListOf(number)) else last.add(number)
    }
    return result.map { it.toList() }
}

/** Deterministic local identifier, not a credential or signature. No clear-text course/account data in alarm IDs. */
internal fun scheduleStableId(kind: String, vararg fields: String): String {
    var hash = 14695981039346656037uL
    for (field in fields) {
        for (byte in field.encodeToByteArray()) hash = (hash xor (byte.toInt() and 255).toULong()) * 1099511628211uL
        hash = (hash xor 0uL) * 1099511628211uL
    }
    return "$kind-${hash.toString(16).padStart(16, '0')}"
}
