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

/**
 * P00 首页「下一节栏」的纯投影。判定顺序固定为 Ardot 94:1 的
 * F → C → B → G → A → D → E → H（第一个命中即停）。
 */
class HomeViewTest {
    private val term = ScheduleTerm("t1", "2026-2027 第1学期", isDefault = true)
    private val periods = defaultSchedulePeriods()

    // Week 9 Monday = 2026-10-26; times are Asia/Shanghai wall clock.
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime(2026, 10, 26 + day - 1, hour, minute)
            .toInstant(TimeZone.of(SCHEDULE_TIME_ZONE_ID)).toEpochMilliseconds()

    private fun course(id: String, title: String, day: Int, period: Int,
                       location: String = "A101", weeks: List<Int> = listOf(9)) =
        ScheduleCourse(id, title, "王老师", location, day, listOf(period), weeks)

    private fun snapshot(vararg courses: ScheduleCourse, complete: Boolean = true) = ScheduleSnapshot(
        term, courses.toList(), mapOf(9 to "2026-10-26"), complete = complete)

    @Test fun aBeforeClassCountsDownToTodaysNextClass() {
        // P1 08:00-08:45, P3 10:00-10:45 on Monday. At 07:30 the next class is 30 min away (> 15),
        // so this is A 课前 — not B.
        val view = homeView(snapshot(course("c1", "高等数学", 1, 1), course("c2", "数据结构", 1, 3)),
            periods, 9, at(1, 7, 30))
        assertEquals(HomeHeroMode.BEFORE, view.hero.mode)
        assertEquals("c1", view.hero.course?.courseId)
        assertEquals(0L, view.hero.hours)
        assertEquals(30L, view.hero.minutes)
        assertEquals(2, view.today.size)
        assertTrue(view.today.first().isNext)
    }

    @Test fun bWithinFifteenMinutesIsJustBefore() {
        // 07:50 -> 10 min to the 08:00 class: B 课间.
        val view = homeView(snapshot(course("c1", "高等数学", 1, 1)), periods, 9, at(1, 7, 50))
        assertEquals(HomeHeroMode.JUST_BEFORE, view.hero.mode)
        assertEquals(10L, view.hero.minutes)
    }

    @Test fun bRoomChangeFromTheClassJustFinishedIsJustBeforeToo() {
        // P1 in A101 ends 08:45; P2 (08:55-09:40) is in B202 -> a room change, so B even at 08:46.
        val view = homeView(snapshot(
            course("c1", "高等数学", 1, 1, location = "A101"),
            course("c2", "数据结构", 1, 2, location = "B202")),
            periods, 9, at(1, 8, 46))
        assertEquals(HomeHeroMode.JUST_BEFORE, view.hero.mode)
        assertTrue(view.hero.roomChanged)
        assertEquals("c2", view.hero.course?.courseId)
    }

    @Test fun cInsideClassShowsRemainingAndTodaysNextAsHint() {
        val view = homeView(snapshot(course("c1", "高等数学", 1, 1), course("c2", "数据结构", 1, 3)),
            periods, 9, at(1, 8, 20))
        assertEquals(HomeHeroMode.IN_CLASS, view.hero.mode)
        assertEquals("c1", view.hero.course?.courseId)
        assertEquals(25L, view.hero.minutes)              // 08:20 -> 08:45
        assertEquals("c2", view.hero.next?.courseId)      // hint line below
        assertEquals("c2", view.today.last().occurrence.courseId)
    }

    @Test fun gLongGapBeatsBeforeClass() {
        // 08:46, next class is P3 at 10:00 = 74 min: that is A (not yet 2 h). Use a later class:
        // P1 ends 08:45, P5 starts 14:30 -> >= 2 h, so G must win over A.
        val view = homeView(snapshot(course("c1", "高等数学", 1, 1), course("c2", "大学英语", 1, 5)),
            periods, 9, at(1, 8, 46))
        assertEquals(HomeHeroMode.LONG_GAP, view.hero.mode)
        assertEquals("c2", view.hero.course?.courseId)
        assertTrue(view.hero.hours >= 2)
    }

    @Test fun bRoomChangeToALaterClassKeepsItsHours() {
        // The reported bug: at 11:43 the next class was 14:30 -- 167 minutes away, in a different
        // room. The room change forced B, and B rendered only the minute remainder, so the hero
        // read "47 分钟后开始" for a wait of nearly three hours.
        // P1 08:00-08:45 in A101, P5 14:30 in B204: at 08:46 the room change selects B even though
        // the gap is far beyond the 15 minutes B is designed for.
        val view = homeView(snapshot(course("c1", "高等数学", 1, 1, location = "A101"),
            course("c2", "军事理论", 1, 5, location = "B204")), periods, 9, at(1, 8, 46))
        assertEquals(HomeHeroMode.JUST_BEFORE, view.hero.mode)
        assertTrue(view.hero.roomChanged)
        // 08:46 -> 14:30 is 344 min: 5 h 44 min, and the total must survive so the hero can show it.
        assertEquals(344L, view.hero.totalMinutes)
        assertEquals(5L, view.hero.hours)
        assertEquals(44L, view.hero.minutes)
    }

    @Test fun dDoneTodayKeepsTheBarWithASummaryAndTomorrow() {
        // 20:56 Monday: today's classes are over, Tuesday has one. D must NOT collapse into E/H.
        val merged = ScheduleSnapshot(term, listOf(
            course("c1", "电路分析基础", 1, 1),
            course("c2", "程序设计基础", 1, 3)),
            mapOf(9 to "2026-10-26"))
        val tue = ScheduleSnapshot(term, listOf(
            course("c1", "电路分析基础", 1, 1),
            course("c2", "程序设计基础", 1, 3),
            course("c3", "英语I", 2, 1, location = "3-102")),
            mapOf(9 to "2026-10-26"))
        val view = homeView(merged, periods, 9, at(1, 20, 56))
        // Sanity: without tomorrow's class there is still D (not E).
        assertEquals(HomeHeroMode.DONE_TODAY, view.hero.mode)
        assertNull(view.hero.tomorrow)
        assertEquals(2, view.hero.todayCount)
        assertEquals(2, view.today.size)
        assertTrue(view.today.none { it.isNext })

        val withTomorrow = homeView(tue, periods, 9, at(1, 20, 56))
        assertEquals(HomeHeroMode.DONE_TODAY, withTomorrow.hero.mode)
        assertEquals("c3", withTomorrow.hero.tomorrow?.courseId)
    }

    @Test fun eFreeTodayShowsTheNextClassInTheWindow() {
        // Monday has no class at all; Tuesday does -> E, with that next class attached.
        val view = homeView(snapshot(course("c9", "英语I", 2, 1, location = "3-102")),
            periods, 9, at(1, 9, 0))
        assertEquals(HomeHeroMode.FREE_TODAY, view.hero.mode)
        assertEquals("c9", view.hero.next?.courseId)
        assertTrue(view.today.isEmpty())
    }

    @Test fun fUnboundIsTheUnavailableStateNotAnEmptyPage() {
        val view = homeView(null, periods, 9, at(1, 9, 0))
        assertEquals(HomeHeroMode.UNAVAILABLE, view.hero.mode)
        assertEquals(HomeUnavailableReason.UNBOUND, view.hero.reason)
        assertTrue(view.today.isEmpty())
    }

    @Test fun fIncompleteSnapshotIsUnavailableRatherThanInventingAClass() {
        val view = homeView(snapshot(course("c1", "高等数学", 1, 1), complete = false), periods, 9, at(1, 9, 0))
        assertEquals(HomeHeroMode.UNAVAILABLE, view.hero.mode)
        assertEquals(HomeUnavailableReason.PARTIAL, view.hero.reason)
        assertTrue(view.today.isEmpty())
    }

    @Test fun fMissingCalendarMappingIsUnavailable() {
        val noDates = ScheduleSnapshot(term, listOf(course("c1", "高等数学", 1, 1)), emptyMap())
        val view = homeView(noDates, periods, 9, at(1, 9, 0))
        assertEquals(HomeHeroMode.UNAVAILABLE, view.hero.mode)
        assertEquals(HomeUnavailableReason.CALENDAR, view.hero.reason)
        assertTrue(view.today.isEmpty())
    }

    @Test fun hNothingInTheWindowIsNoPlanNotAFakeNextClass() {
        // A complete, correctly mapped snapshot with no courses at all in 14 days.
        val view = homeView(snapshot(), periods, 9, at(1, 9, 0))
        assertEquals(HomeHeroMode.NO_PLAN, view.hero.mode)
        assertNull(view.hero.course)
        assertNull(view.hero.next)
        assertNull(view.hero.tomorrow)
        assertTrue(view.today.isEmpty())
    }

    @Test fun dateLineAndSubtitleUseRealTermAndWeek() {
        val view = homeView(snapshot(course("c1", "高等数学", 1, 1)), periods, 9, at(1, 7, 30))
        assertEquals("10月26日 周一", view.dateLine)
        assertTrue(view.subtitle.contains("2026-2027 第1学期"))
        assertTrue(view.subtitle.contains("第9周"))
    }
}
