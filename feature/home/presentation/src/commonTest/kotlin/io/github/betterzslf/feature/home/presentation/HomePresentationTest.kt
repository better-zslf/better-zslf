package io.github.betterzslf.feature.home.presentation

import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import io.github.betterzslf.feature.schedule.ScheduleCourse
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.ScheduleTerm
import io.github.betterzslf.feature.schedule.defaultSchedulePeriods
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.*

class HomePresentationTest {
    private val term = ScheduleTerm("fixture-term", "Fixture term")
    private val periods = defaultSchedulePeriods()
    private fun at(day: Int = 26, hour: Int = 7, minute: Int = 30): Long =
        LocalDateTime(2026, 10, day, hour, minute).toInstant(TimeZone.of(SCHEDULE_TIME_ZONE_ID)).toEpochMilliseconds()
    private fun course(id: String = "course", numbers: List<Int> = listOf(1), day: Int = 1) =
        ScheduleCourse(id, "Fixture course", "Fixture teacher", "Room", day, numbers, listOf(9))
    private fun snapshot(vararg courses: ScheduleCourse) = ScheduleSnapshot(term, courses.toList(), mapOf(9 to "2026-10-26"))

    @Test fun boundAcademicSlotWithoutSnapshotIsUnsyncedNotUnbound() {
        val state = homeUiState(HomeInput(null, periods, 9, at(), isBound = true))
        assertEquals(HomeHeroMode.UNAVAILABLE, state.hero.mode)
        assertEquals(HomeUnavailableReason.UNSYNCED, state.hero.reason)
        assertNotNull(state.eventFor(HomeAction.OPEN_TIMETABLE))
        assertNull(state.eventFor(HomeAction.BIND_SCHEDULE))
        assertTrue(state.today.isEmpty())
    }

    @Test fun explicitUnboundDoesNotRenderAStaleSnapshot() {
        val state = homeUiState(HomeInput(snapshot(course()), periods, 9, at(), isBound = false))
        assertEquals(HomeUnavailableReason.UNBOUND, state.hero.reason)
        assertTrue(state.today.isEmpty())
        assertNotNull(state.eventFor(HomeAction.BIND_SCHEDULE))
    }

    @Test fun inputDefensivelyCopiesNestedCourseListsAndCalendarBeforeProjection() {
        val numbers = mutableListOf(1)
        val weeks = mutableListOf(9)
        val courses = mutableListOf(course().copy(periods = numbers, weeks = weeks))
        val calendar = mutableMapOf(9 to "2026-10-26")
        val warnings = mutableListOf("Fixture warning")
        val input = HomeInput(ScheduleSnapshot(term, courses, calendar, warnings = warnings), periods, 9, at())
        numbers.clear(); weeks.clear(); courses.clear(); calendar.clear(); warnings.clear()
        val result = homeUiState(input)
        assertEquals(HomeHeroMode.BEFORE, result.hero.mode)
        assertEquals("course", result.hero.course?.courseId)
        assertEquals("08:00", result.today.single().start)
        assertEquals(1, input.snapshot?.warnings?.size)
        assertFalse(input.snapshot!!.courses is MutableList<*>)
        assertFalse(input.snapshot!!.courses.single().periods is MutableList<*>)
        assertFalse(input.snapshot!!.weekMondays is MutableMap<*, *>)
        assertFalse(input.snapshot!!.weekMondays.entries is MutableSet<*>)
        assertFalse(input.snapshot!!.weekMondays.entries.first() is MutableMap.MutableEntry<*, *>)
    }

    @Test fun stateRowsAreNotMutableEvenThroughCasts() {
        val state = homeUiState(HomeInput(snapshot(course()), periods, 9, at()))
        assertFalse(state.today is MutableList<*>)
        assertFailsWith<ClassCastException> {
            @Suppress("UNCHECKED_CAST")
            (state.today as MutableList<HomeRow>).clear()
        }
        assertEquals(1, state.today.size)
    }

    @Test fun unknownIdsAndInvisibleActionsNeverBecomeEvents() {
        val state = homeUiState(HomeInput(snapshot(course()), periods, 9, at()))
        assertNull(state.eventFor(HomeOccurrenceId("unknown")))
        assertFalse(state.accepts(HomeEvent.OpenOccurrence(HomeOccurrenceId("unknown"))))
        assertNull(state.eventFor(HomeAction.BIND_SCHEDULE))
        assertNull(state.eventFor(HomeAction.OPEN_TERMS))
        assertNotNull(state.eventFor(state.today.single().occurrence.occurrenceId))
    }

    @Test fun duplicateCourseIdentitiesFailClosedRatherThanPickingOneNavigationTarget() {
        val state = homeUiState(HomeInput(snapshot(course(), course(day = 2)), periods, 9, at()))
        assertEquals(HomeUnavailableReason.PARTIAL, state.hero.reason)
        assertTrue(state.today.isEmpty())
        assertNull(state.hero.course)
    }

    @Test fun blankTermAndInvalidIdsNeverCreateClickableOccurrences() {
        val state = homeUiState(HomeInput(snapshot(course()).copy(term = term.copy(id = "")), periods, 9, at()))
        assertEquals(HomeUnavailableReason.PARTIAL, state.hero.reason)
        assertTrue(state.today.isEmpty())
        val invalid = homeUiState(HomeInput(snapshot(course(id = "bad\nid")), periods, 9, at()))
        assertEquals(HomeUnavailableReason.PARTIAL, invalid.hero.reason)
        assertFailsWith<IllegalArgumentException> { HomeOccurrenceId("") }
        assertFailsWith<IllegalArgumentException> { HomeOccurrenceId("\n") }
    }

    @Test fun multiPeriodClassRetainsHoursAndRealElapsedProgress() {
        val input = HomeInput(snapshot(course(numbers = listOf(1, 2, 3))), periods, 9, at(hour = 8, minute = 20))
        val hero = homeUiState(input).hero
        assertEquals(HomeHeroMode.IN_CLASS, hero.mode)
        assertEquals(145L, hero.totalMinutes)
        assertEquals(2L, hero.hours)
        assertEquals(25L, hero.minutes)
        assertEquals(20f / 165f, hero.progress!!, 0.0001f)
    }

    @Test fun progressUsesActualStartAndEndAndClassEndIsExclusive() {
        val schedule = snapshot(course())
        val beginning = homeUiState(HomeInput(schedule, periods, 9, at(hour = 8, minute = 0))).hero
        assertEquals(HomeHeroMode.IN_CLASS, beginning.mode)
        assertEquals(0f, beginning.progress)
        val middle = homeUiState(HomeInput(schedule, periods, 9, at(hour = 8, minute = 15))).hero
        assertEquals(1f / 3f, middle.progress!!, 0.0001f)
        val ending = homeUiState(HomeInput(schedule, periods, 9, at(hour = 8, minute = 45))).hero
        assertEquals(HomeHeroMode.DONE_TODAY, ending.mode)
        assertNull(ending.progress)
    }

    @Test fun positiveFractionalMinuteDoesNotShowZeroBeforeTheEnd() {
        val now = at(hour = 8, minute = 45) - 1
        val hero = homeUiState(HomeInput(snapshot(course()), periods, 9, now)).hero
        assertEquals(HomeHeroMode.IN_CLASS, hero.mode)
        assertEquals(1L, hero.totalMinutes)
        assertTrue(hero.progress!! in 0f..1f)
    }

    @Test fun schoolMidnightReplacesYesterdayWithoutUsingADeviceTimeZone() {
        val schedule = snapshot(course(day = 2))
        val before = homeUiState(HomeInput(schedule, periods, 9, at(hour = 23, minute = 59)))
        val after = homeUiState(HomeInput(schedule, periods, 9, at(day = 27, hour = 0, minute = 0)))
        assertEquals(HomeHeroMode.FREE_TODAY, before.hero.mode)
        assertEquals(HomeHeroMode.LONG_GAP, after.hero.mode)
        assertEquals("10月27日 周二", after.dateLine)
        assertEquals(1, after.today.size)
    }
}
