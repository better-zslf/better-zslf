package io.github.betterzslf.ui.schedule

import io.github.betterzslf.feature.schedule.ScheduleCourse
import io.github.betterzslf.feature.schedule.ScheduleOccurrence
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.ScheduleTerm
import io.github.betterzslf.feature.schedule.defaultSchedulePeriods
import io.github.betterzslf.feature.schedule.runtime.ScheduleState
import io.github.betterzslf.feature.schedule.scheduleOccurrences
import io.github.betterzslf.platform.ScheduleNativeOpenRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class ScheduleNativeOpenTargetTest {
    private val slot = "local-timetable"
    private val revision = "fixture-binding-revision"
    private val now = Instant.parse("2026-08-31T01:00:00Z").toEpochMilliseconds()
    private val course = ScheduleCourse("fixture-course", "Fixture course", dayOfWeek = 1,
        periods = listOf(1, 2), weeks = listOf(0, 1))
    private val source = ScheduleSnapshot(ScheduleTerm("fixture-source-term", "Source term"), listOf(course),
        weekMondays = mapOf(0 to "2026-08-31", 1 to "2026-09-07"))
    private fun state(source: ScheduleSnapshot? = this.source) = ScheduleState(
        owner = slot, boundAccount = "fixture-academic-account", sourceSnapshot = source,
        sourceTermId = this.source.term.id, selectedWeek = 77,
        snapshot = this.source.copy(term = ScheduleTerm("fixture-history-term", "Historical term")),
        periods = defaultSchedulePeriods(),
    )
    private fun occurrence(week: Int = 1): ScheduleOccurrence =
        scheduleOccurrences(source, defaultSchedulePeriods(), 0, Long.MAX_VALUE).single { it.week == week }
    private fun resolve(
        value: ScheduleState = state(),
        request: ScheduleNativeOpenRequest = ScheduleNativeOpenRequest(revision, occurrence().id),
        activeRevision: String = revision,
        expectedSlot: String = slot,
        nowMillis: Long = now,
    ) = scheduleNativeOpenTarget(value, activeRevision, request, expectedSlot, nowMillis)

    @Test fun aMountedAcademicBindingNeedsNoCampusSessionToOpenTheExactOccurrence() {
        // No campus user/token/session is accepted by this helper or needed in this fixture.
        val target = assertNotNull(resolve())
        val detail = assertNotNull(target.detail)
        assertEquals(ScheduleDetailSelection(source.term.id, listOf(course.id), 1), detail)
        assertEquals(detail, ScheduleDetailSelection.decode(detail.encode()))
    }

    @Test fun weekZeroAndTheNotificationWeekAreNotReplacedByTheViewedWeekOrTerm() {
        val currentWeek = assertNotNull(resolve(request = ScheduleNativeOpenRequest(revision, occurrence(0).id))).detail
        assertEquals(ScheduleDetailSelection(source.term.id, listOf(course.id), 0), currentWeek)
        val futureWeek = assertNotNull(resolve()).detail
        assertEquals(ScheduleDetailSelection(source.term.id, listOf(course.id), 1), futureWeek)
        assertEquals(77, state().selectedWeek)
    }

    @Test fun nullEmptyOrStaleRequestRevisionsRejectTheWholeRequestIncludingRoot() {
        for (wrong in listOf(null, "", "stale-fixture-revision")) {
            assertNull(resolve(request = ScheduleNativeOpenRequest(wrong, occurrence().id)))
            assertNull(resolve(request = ScheduleNativeOpenRequest(wrong)))
        }
    }

    @Test fun invalidCurrentRevisionNeverCreatesAnAdmissionLease() {
        for (wrong in listOf("", "bad revision", "bad\nrevision", "x".repeat(161))) {
            assertNull(resolve(activeRevision = wrong, request = ScheduleNativeOpenRequest(wrong)))
        }
    }

    @Test fun aDifferentOrUnmountedLocalSlotCannotBeSubstitutedByACampusOwnerId() {
        assertNull(resolve(value = state().copy(owner = null)))
        assertNull(resolve(value = state().copy(owner = "fixture-campus-user-id")))
        assertNull(resolve(expectedSlot = "another-local-slot"))
        assertNull(resolve(expectedSlot = ""))
    }

    @Test fun aSlotWithoutAConfirmedAcademicBindingRejectsEvenAMatchingRequest() {
        for (account in listOf(null, "", " ")) {
            assertNull(resolve(value = state().copy(boundAccount = account)))
            assertNull(resolve(value = state().copy(boundAccount = account), request = ScheduleNativeOpenRequest(revision)))
        }
    }

    @Test fun aRevisionMatchedRootRequestOpensOnlyTimetableWithoutReadingHistory() {
        val empty = state(source = null).copy(snapshot = null, sourceTermId = null)
        assertEquals(ScheduleNativeOpenTarget(), resolve(value = empty, request = ScheduleNativeOpenRequest(revision)))
    }

    @Test fun unknownOrMalformedOccurrenceIdentifiersNeverBecomeCourseIdFallbacks() {
        for (unknown in listOf("unknown-occurrence", course.id, "", "x".repeat(201))) {
            assertEquals(ScheduleNativeOpenTarget(), resolve(request = ScheduleNativeOpenRequest(revision, unknown)))
        }
    }

    @Test fun aMissingSourceDoesNotFallBackToAViewedHistoricalSnapshotWithTheSameCourseId() {
        assertEquals(ScheduleNativeOpenTarget(), resolve(value = state(source = null)))
        val wrongTerm = source.copy(term = ScheduleTerm("another-source", "Another source"))
        assertEquals(ScheduleNativeOpenTarget(), resolve(value = state(wrongTerm)))
        assertEquals(ScheduleNativeOpenTarget(), resolve(value = state().copy(sourceTermId = null)))
    }

    @Test fun partialSourcesAndInvalidPeriodTablesFallBackToRootWithoutInventingADetail() {
        assertEquals(ScheduleNativeOpenTarget(), resolve(value = state(source.copy(complete = false))))
        assertEquals(ScheduleNativeOpenTarget(), resolve(value = state().copy(periods = defaultSchedulePeriods().dropLast(1))))
        val overlap = defaultSchedulePeriods().map { if (it.number == 2) it.copy(start = "08:30") else it }
        assertEquals(ScheduleNativeOpenTarget(), resolve(value = state().copy(periods = overlap)))
    }

    @Test fun malformedDuplicateAndOutOfRangeCalendarsCannotResolveDetails() {
        val calendars = listOf(
            mapOf(0 to "2026-08-31", 1 to "2026-08-31"),
            mapOf(0 to "2026-09-01", 1 to "2026-09-07"),
            mapOf(0 to "2026-8-31", 1 to "2026-09-07"),
            mapOf(0 to "2026-02-30", 1 to "2026-09-07"),
            mapOf(-1 to "2026-08-31", 1 to "2026-09-07"),
            mapOf(100 to "2026-08-31", 1 to "2026-09-07"),
            (0..100).associateWith { "2026-08-31" },
        )
        for (calendar in calendars) {
            assertEquals(ScheduleNativeOpenTarget(), resolve(value = state(source.copy(weekMondays = calendar))))
        }
    }

    @Test fun sourceBoundsAreCheckedBeforeAnyOccurrenceExpansion() {
        val invalid = listOf(
            source.copy(courses = List(1001) { course.copy(id = "fixture-$it") }),
            source.copy(courses = listOf(course, course.copy(title = "Ambiguous duplicate"))),
            source.copy(courses = listOf(course.copy(periods = (1..13).toList()))),
            source.copy(courses = listOf(course.copy(periods = listOf(0, 1)))),
            source.copy(courses = listOf(course.copy(periods = emptyList()))),
            source.copy(courses = listOf(course.copy(weeks = (0..100).toList()))),
            source.copy(courses = listOf(course.copy(weeks = listOf(-1, 0)))),
            source.copy(courses = listOf(course.copy(dayOfWeek = 8))),
            source.copy(courses = listOf(course.copy(id = ""))),
            source.copy(courses = listOf(course.copy(title = ""))),
            source.copy(courses = listOf(course.copy(title = "x".repeat(2001)))),
            source.copy(courses = listOf(course.copy(location = "x".repeat(2001)))),
        )
        for (snapshot in invalid) assertEquals(ScheduleNativeOpenTarget(), resolve(value = state(snapshot)))
        val overlongTerm = source.copy(term = source.term.copy(id = "x".repeat(201)))
        assertEquals(ScheduleNativeOpenTarget(), resolve(value = state(overlongTerm).copy(sourceTermId = overlongTerm.term.id)))
    }

    @Test fun anOccurrenceOutsideTheBoundedLookupWindowStillOpensOnlyTheTimetable() {
        val late = Instant.parse("2026-10-20T01:00:00Z").toEpochMilliseconds()
        assertEquals(ScheduleNativeOpenTarget(), resolve(nowMillis = late))
    }

    @Test fun anExtremeClockDoesNotOverflowIntoAnUnrelatedCalendarWeek() {
        assertEquals(ScheduleNativeOpenTarget(), resolve(nowMillis = Long.MIN_VALUE))
        assertEquals(ScheduleNativeOpenTarget(), resolve(nowMillis = Long.MAX_VALUE))
    }

    @Test fun lookupDoesNotMutateTheStatePeriodsSnapshotOrRequest() {
        val value = state()
        val original = value.copy()
        val request = ScheduleNativeOpenRequest(revision, occurrence().id)
        assertNotNull(resolve(value = value, request = request)?.detail)
        assertEquals(original, value)
        assertEquals(ScheduleNativeOpenRequest(revision, occurrence().id), request)
    }
}
