package io.github.betterzslf.ui.schedule

import io.github.betterzslf.feature.schedule.ScheduleCourse
import io.github.betterzslf.feature.schedule.SchedulePeriod
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.ScheduleTerm
import kotlin.test.*

/**
 * The board model is pure, so every design rule is pinned here rather than only on a device:
 * consecutive periods merge into one card, gaps stay separate, colours are stable per course,
 * and the 进行中 outline depends on the real school clock.
 */
class ScheduleBoardTest {
    private val term = ScheduleTerm("t1", "2025-2026 第1学期", isDefault = true)

    private val periods = listOf(
        SchedulePeriod(1, "08:00", "09:40"),
        SchedulePeriod(2, "08:55", "10:35"),
        SchedulePeriod(3, "10:45", "12:25"),
        SchedulePeriod(4, "14:00", "15:40"),
    )

    private fun course(
        id: String = "c1",
        title: String = "高等数学",
        day: Int = 1,
        periods: List<Int> = listOf(1, 2),
        weeks: List<Int> = listOf(9),
        location: String = "A101",
    ) = ScheduleCourse(id, title, "王老师", location, day, periods, weeks)

    private fun snapshot(vararg courses: ScheduleCourse) =
        ScheduleSnapshot(term, courses.toList(), mapOf(9 to "2026-10-26"))

    @Test fun consecutivePeriodsMergeIntoOneCard() {
        assertEquals(listOf(1 to 3), runOfPeriods(listOf(1, 2, 3)))
        assertEquals(listOf(1 to 2, 4 to 1), runOfPeriods(listOf(1, 2, 4)))
        assertEquals(listOf(5 to 1), runOfPeriods(listOf(5)))
        assertEquals(emptyList(), runOfPeriods(emptyList()))
    }

    @Test fun unsortedDuplicatesAndOutOfRangePeriodsNormalise() {
        assertEquals(listOf(1 to 2), runOfPeriods(listOf(2, 1, 2, 1)))
        // 0, 13 and 99 are not real periods; dropping them must not shrink a neighbouring run.
        assertEquals(listOf(1 to 2, 12 to 1), runOfPeriods(listOf(0, 1, 2, 12, 13, 99)))
        assertEquals(emptyList(), runOfPeriods(listOf(0, 13, 99)))
    }

    @Test fun boardPlacesCoursesByRowAndSpan() {
        val board = snapshot(course(periods = listOf(1, 2, 3)), course(id = "c2", day = 3, periods = listOf(3)))
        val blocks = scheduleBoardBlocks(board, 9, periods, today = null, nowMinutes = null)
        assertEquals(2, blocks.size)
        val first = blocks.first { it.course.id == "c1" }
        assertEquals(1, first.day)
        assertEquals(1, first.startRow)
        assertEquals(3, first.spanRows)
        val second = blocks.first { it.course.id == "c2" }
        assertEquals(3, second.day)
        assertEquals(3, second.startRow)
        assertEquals(1, second.spanRows)
    }

    @Test fun blocksOutsideTheSelectedWeekAreNotDrawn() {
        val board = snapshot(course(weeks = listOf(1)), course(id = "c2", weeks = listOf(9)))
        val blocks = scheduleBoardBlocks(board, 9, periods, today = null, nowMinutes = null)
        assertEquals(listOf("c2"), blocks.map { it.course.id })
    }

    @Test fun coursesWithoutARealWeekdayNeverLandOnTheGrid() {
        val board = snapshot(course(day = 0), course(id = "c2", day = 8))
        assertTrue(scheduleBoardBlocks(board, 9, periods, today = null, nowMinutes = null).isEmpty())
    }

    @Test fun sameCourseKeepsItsColourAcrossRebuildsAndReordering() {
        val a = course(id = "course-a")
        val b = course(id = "course-b", title = "数据结构")
        val forward = snapshot(a, b)
        val backward = snapshot(b, a)
        val first = scheduleBoardBlocks(forward, 9, periods, null, null).associate { it.course.id to scheduleCoursePalette(it.course) }
        val second = scheduleBoardBlocks(backward, 9, periods, null, null).associate { it.course.id to scheduleCoursePalette(it.course) }
        assertEquals(first, second)
    }

    @Test fun colourIsStableAcrossWeeks() {
        val one = course(id = "course-a")
        val palette = scheduleCoursePalette(one)
        repeat(50) { assertEquals(palette, scheduleCoursePalette(course(id = "course-a"))) }
    }

    @Test fun extremelyNegativeIdHashDoesNotCrash() {
        // Int.MIN_VALUE makes abs() overflow, which is exactly the case the modulo must survive.
        val ids = listOf("polygenelubricants", "", "a", "\u0000", "Aa", "BB")
        ids.forEach { id -> assertNotNull(scheduleCoursePalette(course(id = id))) }
    }

    @Test fun runningOnlyWhenTheSchoolClockIsInsideThePeriod() {
        val pe = course(id = "pe", title = "体育", day = 4, periods = listOf(4))
        assertEquals(14 * 60 + 30, scheduleClockMinutes("14:30"))
        assertFalse(scheduleCourseRunning(pe, 3, periods, 14 * 60 + 30)) // wrong weekday
        assertFalse(scheduleCourseRunning(pe, 4, periods, 13 * 60 + 59)) // before the window
        assertTrue(scheduleCourseRunning(pe, 4, periods, 14 * 60))       // inclusive start
        assertTrue(scheduleCourseRunning(pe, 4, periods, 15 * 60 + 39))  // last minute
        assertFalse(scheduleCourseRunning(pe, 4, periods, 15 * 60 + 40)) // end is exclusive
    }

    @Test fun runningIsNeverClaimedWithoutAKnownClockOrPeriodTable() {
        val pe = course(title = "体育", day = 4, periods = listOf(4))
        assertFalse(scheduleCourseRunning(pe, 4, periods, null))
        assertFalse(scheduleCourseRunning(pe, 4, emptyList(), 14 * 60 + 30))
        // Period 7 is absent from this table, so the window is unknown and must not be guessed.
        assertFalse(scheduleCourseRunning(course(title = "体育", day = 4, periods = listOf(7)), 4, periods, 14 * 60))
    }

    @Test fun malformedPeriodClocksAreRejectedRatherThanEstimated() {
        assertNull(scheduleClockMinutes("8:00"))
        assertNull(scheduleClockMinutes("24:00"))
        assertNull(scheduleClockMinutes("08:60"))
        assertNull(scheduleClockMinutes(""))
        assertNull(scheduleClockMinutes("08:00–09:40"))
        assertEquals(0, scheduleClockMinutes("00:00"))
        assertEquals(23 * 60 + 59, scheduleClockMinutes("23:59"))
    }

    @Test fun runningBlockUsesTheRedPalette() {
        val pe = course(id = "pe", title = "体育", day = 4, periods = listOf(4))
        val board = snapshot(pe)
        val running = scheduleBoardBlocks(board, 9, periods, today = 4, nowMinutes = 14 * 60 + 30).single()
        assertTrue(running.running)
        assertEquals(SchedulePalettes.Running, scheduleCoursePalette(running.course, running.running))
        val idle = scheduleBoardBlocks(board, 9, periods, today = 4, nowMinutes = 20 * 60).single()
        assertFalse(idle.running)
    }

    @Test fun blockKeyIsStablePerCoursePosition() {
        val block = scheduleBoardBlocks(snapshot(course(periods = listOf(1, 2, 3))), 9, periods, null, null).single()
        assertEquals("c1@1:1:3", block.key)
    }

    @Test fun compactPeriodsCollapsesRunsLikeTheDesign() {
        assertEquals("1-2", compactPeriods(listOf(1, 2)))
        assertEquals("1-3", compactPeriods(listOf(3, 1, 2)))
        assertEquals("1-2,5", compactPeriods(listOf(1, 2, 5)))
        assertEquals("4", compactPeriods(listOf(4)))
        assertEquals("9-10", compactPeriods(listOf(9, 10)))
        assertEquals("1-2,5-6", compactPeriods(listOf(1, 2, 5, 6)))
        // Out-of-range numbers are dropped, never rendered as a fake period.
        assertEquals("1-2", compactPeriods(listOf(0, 1, 2, 13)))
        assertEquals("", compactPeriods(emptyList()))
    }

    @Test fun coursePeriodTimesReportsOneRangePerConsecutiveRun() {
        // Adjacent periods overlap in real timetables; the design shows first start to last end.
        val math = course(periods = listOf(1, 2))
        assertEquals("08:00–10:35", coursePeriodTimes(math, periods))
        // A gap between runs stays two ranges rather than a filled 1..4 span.
        val split = course(periods = listOf(1, 4))
        assertEquals("08:00–09:40 · 14:00–15:40", coursePeriodTimes(split, periods))
    }

    @Test fun coursePeriodTimesNeverInventsMissingPeriodTimes() {
        // Period 7 has no entry in the table, so that run contributes nothing.
        assertEquals("", coursePeriodTimes(course(periods = listOf(7)), periods))
        assertEquals("", coursePeriodTimes(course(periods = emptyList()), periods))
        // One known and one unknown run: only the known range is printed.
        assertEquals("08:00–09:40", coursePeriodTimes(course(periods = listOf(1, 7)), periods))
    }
}
