package io.github.betterzslf.ui.schedule

import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import io.github.betterzslf.feature.schedule.ScheduleCourse
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.ScheduleTerm
import io.github.betterzslf.feature.schedule.defaultSchedulePeriods
import kotlin.test.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * The 下一节 focus card's pure resolver. It must reuse the real occurrence/date rules: no
 * invented class, a running class wins over the next, and an unmapped calendar yields nothing.
 */
class ScheduleNextCardTest {
    private val term = ScheduleTerm("t1", "2025-2026 第1学期", isDefault = true)
    // 12 ordered, non-overlapping periods: P1 08:00-08:45, P2 08:55-09:40, P3 10:00-10:45, ...
    private val periods = defaultSchedulePeriods()

    // Week 9 Monday = 2026-10-26. Times are Asia/Shanghai wall clock.
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime(2026, 10, 26 + day - 1, hour, minute)
            .toInstant(TimeZone.of(SCHEDULE_TIME_ZONE_ID))
            .toEpochMilliseconds()

    private fun course(id: String, title: String, day: Int, period: Int) =
        ScheduleCourse(id, title, "王老师", "A101", day, listOf(period), listOf(9))

    private fun snapshot(vararg courses: ScheduleCourse, complete: Boolean = true) = ScheduleSnapshot(
        term, courses.toList(), mapOf(9 to "2026-10-26"), complete = complete)

    @Test fun runningClassWinsAndCountsDownToItsEnd() {
        // Monday P1 08:00-08:45 is running at 08:20; P3 (10:00) is the next class.
        val focus = scheduleFocus(snapshot(course("c1", "高等数学", 1, 1), course("c2", "数据结构", 1, 3)),
            periods, at(1, 8, 20))
        assertEquals("c1", focus.running?.courseId)
        assertEquals("c2", focus.next?.courseId)
        assertEquals(25L, focus.remainingMinutes)  // 08:20 -> 08:45
        assertEquals(100L, focus.startsInMinutes) // 08:20 -> 10:00
        assertEquals(2, focus.todayCount)
    }

    @Test fun beforeFirstClassOnlyNextIsPresent() {
        val focus = scheduleFocus(snapshot(course("c1", "高等数学", 1, 1)), periods, at(1, 7, 30))
        assertNull(focus.running)
        assertEquals("c1", focus.next?.courseId)
        assertEquals(30L, focus.startsInMinutes)
        assertEquals(0L, focus.remainingMinutes)
        assertEquals(1, focus.todayCount)
    }

    @Test fun afterAllClassesTodayThereIsNoRunningOrNextToday() {
        // A later week/day class must not be mislabelled as "正在进行". The "today count" is the
        // number of distinct courses on today's calendar date, which remains 1 even after 20:00 —
        // it describes the day's timetable, not a claim that classes are still upcoming.
        val focus = scheduleFocus(snapshot(course("c1", "高等数学", 1, 1)), periods, at(1, 20, 0))
        assertNull(focus.running)
        assertNull(focus.next)
        assertEquals(1, focus.todayCount)
    }

    @Test fun incompleteSnapshotNeverInventsAClass() {
        val focus = scheduleFocus(snapshot(course("c1", "高等数学", 1, 1), complete = false), periods, at(1, 9, 0))
        assertNull(focus.running)
        assertNull(focus.next)
        assertEquals(0, focus.todayCount)
    }

    @Test fun missingCalendarMappingNeverInventsAClass() {
        val noDates = ScheduleSnapshot(term, listOf(course("c1", "高等数学", 1, 1)), emptyMap())
        val focus = scheduleFocus(noDates, periods, at(1, 9, 0))
        assertNull(focus.running)
        assertNull(focus.next)
    }
}
