package io.github.betterzslf.ui.schedule

import androidx.compose.ui.graphics.Color
import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import io.github.betterzslf.feature.schedule.ScheduleCourse
import io.github.betterzslf.feature.schedule.SchedulePeriod
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Exact Ardot P39 course-block palette. Every block pairs one fill with a title tint and a
 * lighter meta tint. The dark screen (Ardot 14:119) reuses the same five fills unchanged --
 * they read as raised pastel cards on #0D0F12 -- so a single set of constants covers both
 * themes; only the surrounding surface comes from [MiuixTheme].
 */
internal data class ScheduleCoursePalette(
    val background: Color,
    val title: Color,
    val meta: Color,
)

internal object SchedulePalettes {
    /** Ardot 14:191 高等数学 -- #E3EDFF fill, #2C5FE8 title, #5B85F0 meta. */
    internal val Blue = ScheduleCoursePalette(Color(0xFFE3EDFF), Color(0xFF2C5FE8), Color(0xFF5B85F0))

    /** Ardot 14:199 数据结构 -- #E7F6EC fill, #1F8A4C title, #4CAF7D meta. */
    internal val Green = ScheduleCoursePalette(Color(0xFFE7F6EC), Color(0xFF1F8A4C), Color(0xFF4CAF7D))

    /** Ardot 14:207 大学英语 -- #FFF2E3 fill, #B25E00 title, #D98A2B meta. */
    internal val Orange = ScheduleCoursePalette(Color(0xFFFFF2E3), Color(0xFFB25E00), Color(0xFFD98A2B))

    /** Ardot 14:195 线性代数 -- #EEEAFF fill, #5B3FD4 title, #8A6FF0 meta. */
    internal val Purple = ScheduleCoursePalette(Color(0xFFEEEAFF), Color(0xFF5B3FD4), Color(0xFF8A6FF0))

    /** Ardot 14:203 体育 -- #FDE9EC fill, #C03540 title, #E5484D meta, drawn with a red outline. */
    internal val Running = ScheduleCoursePalette(Color(0xFFFDE9EC), Color(0xFFC03540), Color(0xFFE5484D))

    /** Outline colour for the in-progress block, Ardot 14:144 stroke. */
    internal val RunningOutline = Color(0xFFE5484D)

    /** Rotation used by every non-running block, in design reading order. */
    internal val rotation = listOf(Blue, Green, Orange, Purple)
}

/**
 * Stable block colour for the same course across weeks and refreshes: derived from the
 * course id, never from list position, so a refresh cannot recolour a course.
 * An actively running course takes the red treatment to match the design's 进行中 block.
 */
internal fun scheduleCoursePalette(course: ScheduleCourse, running: Boolean = false): ScheduleCoursePalette =
    if (running) SchedulePalettes.Running
    else SchedulePalettes.rotation[course.id.hashCode().mod(SchedulePalettes.rotation.size)]

/**
 * The design only marks the block of a course that is happening right now, on the school day
 * it is actually taught. Periods are discrete numbers, so the comparison is inclusive on both
 * ends of the current period's wall-clock window rather than a filled 1..N span.
 */
internal fun scheduleCourseRunning(
    course: ScheduleCourse,
    day: Int,
    periods: List<SchedulePeriod>,
    nowMinutes: Int?,
): Boolean {
    if (nowMinutes == null || course.dayOfWeek != day) return false
    val windows = course.periods.distinct().mapNotNull { number ->
        periods.firstOrNull { it.number == number }
    }
    return windows.any { window ->
        val start = scheduleClockMinutes(window.start) ?: return@any false
        val end = scheduleClockMinutes(window.end) ?: return@any false
        nowMinutes in start until end
    }
}

/** "08:00" -> 480. Anything the school service returns outside HH:mm is not a guess. */
internal fun scheduleClockMinutes(value: String): Int? {
    val match = Regex("^([01][0-9]|2[0-3]):([0-5][0-9])$").find(value.trim()) ?: return null
    return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
}

/** Wall-clock minutes of the school day for [nowMillis], in the school time zone. */
internal fun scheduleSchoolMinutes(nowMillis: Long): Int {
    val local = Instant.fromEpochMilliseconds(nowMillis)
        .toLocalDateTime(TimeZone.of(SCHEDULE_TIME_ZONE_ID))
    return local.hour * 60 + local.minute
}

/** A course placed on the 7x12 board, ready for absolute positioning. */
internal data class ScheduleBoardBlock(
    val course: ScheduleCourse,
    val day: Int,
    val startRow: Int,
    val spanRows: Int,
    val running: Boolean,
) {
    /** Stable identity for lazy/measurement keys; a course id can repeat across a term. */
    val key: String get() = "${course.id}@$day:$startRow:$spanRows"
}

/**
 * Consecutive period numbers become one block, so a 3-period lecture draws as one card rather
 * than three stacked ones. Non-consecutive periods (1,3) stay two blocks: the school's discrete
 * numbers never imply the gap between them is occupied.
 */
internal fun scheduleBoardBlocks(
    snapshot: ScheduleSnapshot,
    week: Int,
    periods: List<SchedulePeriod>,
    today: Int?,
    nowMinutes: Int?,
): List<ScheduleBoardBlock> = snapshot.courses
    .filter { week in it.weeks && it.dayOfWeek in 1..7 }
    .flatMap { course ->
        runOfPeriods(course.periods).map { (start, span) ->
            ScheduleBoardBlock(
                course = course,
                day = course.dayOfWeek,
                startRow = start,
                spanRows = span,
                running = scheduleCourseRunning(course, today ?: -1, periods, nowMinutes),
            )
        }
    }

/** Split sorted distinct period numbers into maximal consecutive runs: [1,2,3,5] -> (1,3), (5,1). */
internal fun runOfPeriods(numbers: List<Int>): List<Pair<Int, Int>> {
    val valid = numbers.filter { it in 1..12 }.distinct().sorted()
    if (valid.isEmpty()) return emptyList()
    val runs = mutableListOf<Pair<Int, Int>>()
    var start = valid.first()
    var previous = start
    for (number in valid.drop(1)) {
        if (number == previous + 1) previous = number
        else {
            runs += start to (previous - start + 1)
            start = number
            previous = number
        }
    }
    runs += start to (previous - start + 1)
    return runs
}

/**
 * Compact period label in the design's style: a consecutive run collapses to "1-2",
 * separate runs are comma-joined ("1-2,5"). Ardot P41 shows "第1-2节", P39b shows "1-2节".
 */
internal fun compactPeriods(numbers: List<Int>): String = runOfPeriods(numbers)
    .joinToString(",") { (start, span) -> if (span > 1) "$start-${start + span - 1}" else "$start" }
