package io.github.betterzslf.ui

import io.github.betterzslf.feature.home.presentation.HomeAction
import io.github.betterzslf.feature.home.presentation.HomeEvent
import io.github.betterzslf.feature.home.presentation.HomeHeroMode
import io.github.betterzslf.feature.home.presentation.HomeOccurrenceId
import io.github.betterzslf.feature.home.presentation.HomeUnavailableReason
import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import io.github.betterzslf.feature.schedule.ScheduleCourse
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.ScheduleTerm
import io.github.betterzslf.feature.schedule.runtime.ScheduleState
import io.github.betterzslf.ui.schedule.ScheduleDetailSelection
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.*

class HomeScheduleAdapterTest {
    private val revision = "fixture-owner-revision"
    private val term = ScheduleTerm("fixture-term", "Fixture term")
    private fun at(day: Int = 26, hour: Int = 7, minute: Int = 30): Long =
        LocalDateTime(2026, 10, day, hour, minute).toInstant(TimeZone.of(SCHEDULE_TIME_ZONE_ID)).toEpochMilliseconds()
    private fun course(id: String = "course", day: Int = 1, periods: List<Int> = listOf(1)) =
        ScheduleCourse(id, "Fixture course", "Fixture teacher", "Room", day, periods, listOf(9))
    private fun state(vararg courses: ScheduleCourse) = ScheduleState(
        owner = "academic-slot", boundAccount = "fixture-academic-account", selectedWeek = 9,
        snapshot = ScheduleSnapshot(term, courses.toList(), mapOf(9 to "2026-10-26")),
    )

    @Test fun todaysRowResolvesToTheExactTermCourseAndWeek() {
        val state = state(course())
        val adapter = HomeScheduleAdapter(state, revision, at())
        val event = assertNotNull(adapter.uiState.eventFor(adapter.uiState.today.single().occurrence.occurrenceId))
        val target = assertIs<HomeNavigation.Detail>(adapter.resolve(event, state, revision, at()))
        assertEquals(term.id, target.termId)
        assertEquals("course", target.courseId)
        assertEquals(9, target.week)
    }

    @Test fun nextDaySummaryPreservesTermInsteadOfEncodingAnUndecodableEmptyTerm() {
        val state = state(course("monday"), course("tuesday", day = 2))
        val now = at(hour = 20, minute = 56)
        val adapter = HomeScheduleAdapter(state, revision, now)
        assertEquals(HomeHeroMode.DONE_TODAY, adapter.uiState.hero.mode)
        val id = assertNotNull(adapter.uiState.hero.tomorrow).occurrenceId
        val event = assertNotNull(adapter.uiState.eventFor(id))
        val target = assertIs<HomeNavigation.Detail>(adapter.resolve(event, state, revision, now))
        val decoded = assertNotNull(ScheduleDetailSelection.decode(
            ScheduleDetailSelection(target.termId, listOf(target.courseId), target.week).encode()))
        assertEquals(term.id, decoded.termId)
        assertEquals(listOf("tuesday"), decoded.courseIds)
        assertEquals(9, decoded.week)
    }

    @Test fun freeDayNextCourseUsesTheSameValidatedTermIndex() {
        val state = state(course("tuesday", day = 2))
        val adapter = HomeScheduleAdapter(state, revision, at())
        assertEquals(HomeHeroMode.FREE_TODAY, adapter.uiState.hero.mode)
        val event = assertNotNull(adapter.uiState.eventFor(assertNotNull(adapter.uiState.hero.next).occurrenceId))
        val target = assertIs<HomeNavigation.Detail>(adapter.resolve(event, state, revision, at()))
        assertEquals(term.id, target.termId)
        assertEquals("tuesday", target.courseId)
    }

    @Test fun ownerRevisionOrSlotOrBindingChangeSynchronouslyRetiresAnOldClick() {
        val state = state(course())
        val adapter = HomeScheduleAdapter(state, revision, at())
        val event = HomeEvent.OpenOccurrence(adapter.uiState.today.single().occurrence.occurrenceId)
        assertNull(adapter.resolve(event, state, "new-revision", at()))
        assertNull(adapter.resolve(event, state.copy(owner = "new-slot"), revision, at()))
        assertNull(adapter.resolve(event, state.copy(boundAccount = null), revision, at()))
        assertNull(adapter.resolve(event, state.copy(boundAccount = "new-account"), revision, at()))
    }

    @Test fun replacingTermOrSnapshotOrPeriodScheduleRetiresAnOldClick() {
        val state = state(course())
        val adapter = HomeScheduleAdapter(state, revision, at())
        val event = HomeEvent.OpenOccurrence(adapter.uiState.today.single().occurrence.occurrenceId)
        assertNull(adapter.resolve(event, state.copy(snapshot = state.snapshot!!.copy()), revision, at()))
        assertNull(adapter.resolve(event, state.copy(snapshot = state.snapshot!!.copy(term = term.copy(id = "new-term"))), revision, at()))
        assertNull(adapter.resolve(event, state.copy(periods = state.periods.toMutableList().also {
            it[0] = it[0].copy(start = "08:05")
        }), revision, at()))
        assertNull(adapter.resolve(event, state.copy(selectedWeek = 10), revision, at()))
    }

    @Test fun inPlaceMutationCannotRewriteTheCapturedOccurrenceIndex() {
        val courses = mutableListOf(course())
        val original = state().copy(snapshot = ScheduleSnapshot(term, courses, mapOf(9 to "2026-10-26")))
        val adapter = HomeScheduleAdapter(original, revision, at())
        val event = HomeEvent.OpenOccurrence(adapter.uiState.today.single().occurrence.occurrenceId)
        courses[0] = course().copy(title = "Changed")
        assertNull(adapter.resolve(event, original, revision, at()))
    }

    @Test fun unknownOrDuplicateOrInvisibleTargetsDoNotNavigate() {
        val state = state(course())
        val adapter = HomeScheduleAdapter(state, revision, at())
        assertNull(adapter.resolve(HomeEvent.OpenOccurrence(HomeOccurrenceId("unknown")), state, revision, at()))
        assertNull(adapter.resolve(HomeEvent.ActionSelected(HomeAction.OPEN_TERMS), state, revision, at()))
        val duplicate = state(course(), course(day = 2))
        val invalidAdapter = HomeScheduleAdapter(duplicate, revision, at())
        assertEquals(HomeUnavailableReason.PARTIAL, invalidAdapter.uiState.hero.reason)
        assertNull(invalidAdapter.resolve(HomeEvent.OpenOccurrence(adapter.uiState.today.single().occurrence.occurrenceId),
            duplicate, revision, at()))
    }

    @Test fun oldActionIsRevalidatedAtTheMomentOfClickNotJustAtComposition() {
        val state = state(course())
        val adapter = HomeScheduleAdapter(state, revision, at(hour = 20))
        val event = assertNotNull(adapter.uiState.eventFor(HomeAction.OPEN_TIMETABLE))
        assertNotNull(adapter.resolve(event, state, revision, at(hour = 20)))
        // Next week on the same fixture snapshot has no explicit mapping: reason/action change.
        // The occurrence is not visible there, even if the last rendered row still exists.
        val rowEvent = HomeEvent.OpenOccurrence(adapter.uiState.today.single().occurrence.occurrenceId)
        assertNull(adapter.resolve(rowEvent, state, revision, at(day = 27, hour = 9)))
    }

    @Test fun closingTheBinderRetiresActionsAndCourseEvents() {
        val state = state(course())
        val adapter = HomeScheduleAdapter(state, revision, at())
        val event = HomeEvent.OpenOccurrence(adapter.uiState.today.single().occurrence.occurrenceId)
        adapter.close()
        assertNull(adapter.resolve(event, state, revision, at()))
    }

    @Test fun unboundActionUsesAcademicBindingAndNeedsNoCampusSession() {
        val state = ScheduleState(owner = "local-academic-slot", selectedWeek = 9)
        val adapter = HomeScheduleAdapter(state, revision, at())
        assertEquals(HomeUnavailableReason.UNBOUND, adapter.uiState.hero.reason)
        val event = assertNotNull(adapter.uiState.eventFor(HomeAction.BIND_SCHEDULE))
        assertEquals(HomeNavigation.Action(HomeAction.BIND_SCHEDULE), adapter.resolve(event, state, revision, at()))
        adapter.close()
        assertNull(adapter.resolve(event, state, revision, at()))
    }

    @Test fun boundWithoutSyncedSnapshotUsesUnsyncedAction() {
        val state = ScheduleState(owner = "local-academic-slot", boundAccount = "fixture-account", selectedWeek = 9)
        val adapter = HomeScheduleAdapter(state, revision, at())
        assertEquals(HomeUnavailableReason.UNSYNCED, adapter.uiState.hero.reason)
        assertNotNull(adapter.resolve(HomeEvent.ActionSelected(HomeAction.OPEN_TIMETABLE), state, revision, at()))
        assertNull(adapter.resolve(HomeEvent.ActionSelected(HomeAction.BIND_SCHEDULE), state, revision, at()))
    }
}
