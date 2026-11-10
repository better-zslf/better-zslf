package io.github.betterzslf.ui

import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import io.github.betterzslf.feature.schedule.ScheduleCourse
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.ScheduleTerm
import io.github.betterzslf.feature.schedule.defaultSchedulePeriods
import kotlin.test.*
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** P00 首页's pure projection: only real occurrences are shown, never the design's sample data. */
class HomeViewTest {
    private val term = ScheduleTerm("t1", "2026-2027 第1学期", isDefault = true)
    private val periods = defaultSchedulePeriods()

    // Week 9 Monday = 2026-10-26; times are Asia/Shanghai wall clock.
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime(2026, 10, 26 + day - 1, hour, minute)
            .toInstant(TimeZone.of(SCHEDULE_TIME_ZONE_ID)).toEpochMilliseconds()

    private fun course(id: String, title: String, day: Int, period: Int) =
        ScheduleCourse(id, title, "王老师", "A101", day, listOf(period), listOf(9))

    private fun snapshot(vararg courses: ScheduleCourse, complete: Boolean = true) = ScheduleSnapshot(
        term, courses.toList(), mapOf(9 to "2026-10-26"), complete = complete)

    @Test fun beforeFirstClassHeroCountsDownToItAndTodayIsListed() {
        // P1 08:00-08:45 and P3 10:00-10:45 on Monday.
        val view = homeView(snapshot(course("c1", "高等数学", 1, 1), course("c2", "数据结构", 1, 3)),
            periods, 9, at(1, 7, 30))
        val hero = assertNotNull(view.hero)
        assertEquals("c1", hero.occurrence.courseId)
        assertFalse(hero.inClass)
        assertEquals(0L, hero.hours)
        assertEquals(30L, hero.minutes) // 07:30 -> 08:00
        assertEquals(2, view.today.size)
        assertTrue(view.today.first().isNext)
    }

    @Test fun insideClassHeroShowsInProgressAndCountsDownToEnd() {
        val view = homeView(snapshot(course("c1", "高等数学", 1, 1), course("c2", "数据结构", 1, 3)),
            periods, 9, at(1, 8, 20))
        val hero = assertNotNull(view.hero)
        assertEquals("c1", hero.occurrence.courseId)
        assertTrue(hero.inClass)
        assertEquals(0L, hero.hours)
        assertEquals(25L, hero.minutes) // 08:20 -> 08:45
        assertEquals("c2", view.today.last().occurrence.courseId)
    }

    @Test fun dateLineAndSubtitleUseRealTermAndWeek() {
        val view = homeView(snapshot(course("c1", "高等数学", 1, 1)), periods, 9, at(1, 7, 30))
        assertEquals("10月26日 周一", view.dateLine)
        assertTrue(view.subtitle.contains("2026-2027 第1学期"))
        assertTrue(view.subtitle.contains("第9周"))
    }

    @Test fun unboundSnapshotNeverInventsAClass() {
        val view = homeView(null, periods, 9, at(1, 9, 0))
        assertNull(view.hero)
        assertTrue(view.today.isEmpty())
        assertTrue(view.emptyMessage.isNotBlank())
    }

    @Test fun incompleteSnapshotNeverInventsAClass() {
        val view = homeView(snapshot(course("c1", "高等数学", 1, 1), complete = false), periods, 9, at(1, 9, 0))
        assertNull(view.hero)
        assertTrue(view.today.isEmpty())
    }

    @Test fun missingCalendarMappingNeverInventsAClass() {
        val noDates = ScheduleSnapshot(term, listOf(course("c1", "高等数学", 1, 1)), emptyMap())
        val view = homeView(noDates, periods, 9, at(1, 9, 0))
        assertNull(view.hero)
        assertTrue(view.today.isEmpty())
        assertTrue(view.emptyMessage.isNotBlank())
    }

    @Test fun aDayWithNoCoursesHasNoHeroButAReadableEmptyState() {
        // A complete, correctly mapped snapshot with no courses at all: no hero, no today rows,
        // but an explicit empty message rather than a blank page.
        val view = homeView(snapshot(), periods, 9, at(1, 9, 0))
        assertNull(view.hero)
        assertTrue(view.today.isEmpty())
        assertTrue(view.emptyMessage.isNotBlank())
    }
}
