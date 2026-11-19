package io.github.betterzslf.feature.schedule

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Instant

class ScheduleDatesTest {
    private val term = ScheduleTerm("FIXTURE_TERM", "Fixture term", true)
    private fun millis(value: String) = Instant.parse(value).toEpochMilliseconds()
    private fun course(periods: List<Int> = listOf(1, 2), weeks: List<Int> = listOf(0), day: Int = 1) =
        ScheduleCourse("fixture-course", "Fixture Course", "Fixture Teacher", "Fixture Room", day, periods, weeks, "fixture")
    private fun snapshot(course: ScheduleCourse = course()) = ScheduleSnapshot(term, listOf(course), mapOf(0 to "2026-08-31", 1 to "2026-09-07"))

    @Test fun defaultTwelvePeriodsExactlyPreserveTheUserConfirmedBreaksAndEvening() {
        val periods = defaultSchedulePeriods()
        assertTrue(validateSchedulePeriods(periods))
        assertEquals((1..12).toList(), periods.map { it.number })
        assertEquals(SchedulePeriod(1, "08:00", "08:45"), periods.first())
        assertEquals(SchedulePeriod(4, "10:55", "11:40"), periods[3])
        assertEquals(SchedulePeriod(5, "14:30", "15:15"), periods[4])
        assertEquals(SchedulePeriod(12, "21:25", "22:10"), periods.last())
        assertNotSame(periods, defaultSchedulePeriods())
    }

    @Test fun periodValidationRejectsMissingReorderedDuplicateOverlappingAndMalformedValues() {
        val defaults = defaultSchedulePeriods()
        assertFalse(validateSchedulePeriods(defaults.dropLast(1)))
        assertFalse(validateSchedulePeriods(defaults.reversed()))
        assertFalse(validateSchedulePeriods(defaults.map { if (it.number == 2) it.copy(number = 1) else it }))
        for (bad in listOf("25:00", "8:00", "08:00:00", "", "08:99")) {
            assertFalse(validateSchedulePeriods(defaults.map { if (it.number == 1) it.copy(start = bad) else it }))
        }
        assertFalse(validateSchedulePeriods(defaults.map { if (it.number == 2) it.copy(start = "08:40") else it }))
        assertFalse(validateSchedulePeriods(defaults.map { if (it.number == 1) it.copy(end = "08:00") else it }))
    }

    @Test fun explicitWeekZeroCrossesTheYearWithoutInventingMissingWeeks() {
        val snapshot = ScheduleSnapshot(term, emptyList(), mapOf(0 to "2025-12-29", 1 to "2026-01-05"))
        assertEquals(LocalDate(2026, 1, 4), scheduleDateForWeek(snapshot, 0, 7))
        assertEquals(0, scheduleWeekForDate(snapshot, LocalDate(2026, 1, 4)))
        assertEquals(1, scheduleWeekForDate(snapshot, LocalDate(2026, 1, 5)))
        assertNull(scheduleDateForWeek(snapshot, 2, 1))
        assertNull(scheduleWeekForDate(snapshot, LocalDate(2026, 1, 12)))
        assertNull(scheduleDateForWeek(snapshot, 0, 8))
    }

    @Test fun duplicateAndNonMondayCalendarAnchorsNeverCreateOccurrences() {
        val duplicate = snapshot().copy(weekMondays = mapOf(0 to "2026-08-31", 1 to "2026-08-31"))
        assertNull(scheduleDateForWeek(duplicate, 0, 1))
        val wrong = snapshot().copy(weekMondays = mapOf(0 to "2026-09-01"))
        assertTrue(scheduleOccurrences(wrong, defaultSchedulePeriods(), 0, Long.MAX_VALUE).isEmpty())
        assertTrue(scheduleOccurrences(duplicate, defaultSchedulePeriods(), 0, Long.MAX_VALUE).isEmpty())
    }

    @Test fun contiguousCourseIsOneOccurrenceAndNoncontiguousCourseKeepsTheGap() {
        val snapshot = snapshot(course(periods = listOf(1, 2, 5)))
        val events = scheduleOccurrences(snapshot, defaultSchedulePeriods(), millis("2026-08-30T16:00:00Z"), millis("2026-08-31T16:00:00Z"))
        assertEquals(2, events.size)
        assertEquals(listOf(1, 2), events[0].periods)
        assertEquals(millis("2026-08-31T00:00:00Z"), events[0].startMillis)
        assertEquals(millis("2026-08-31T01:40:00Z"), events[0].endMillis)
        assertEquals(listOf(5), events[1].periods)
        assertEquals(millis("2026-08-31T06:30:00Z"), events[1].startMillis)
        assertTrue(events.all { it.id.startsWith("occ-") && !it.id.contains("fixture-course") })
    }

    @Test fun overlappingWindowIncludesOngoingClassButExcludesAnExactlyEndedClass() {
        val snapshot = snapshot()
        assertEquals(1, scheduleOccurrences(snapshot, defaultSchedulePeriods(), millis("2026-08-31T00:30:00Z"), millis("2026-08-31T01:00:00Z")).size)
        assertTrue(scheduleOccurrences(snapshot, defaultSchedulePeriods(), millis("2026-08-31T01:40:00Z"), millis("2026-08-31T02:00:00Z")).isEmpty())
        assertTrue(scheduleOccurrences(snapshot, defaultSchedulePeriods(), 100, 100).isEmpty())
    }

    @Test fun sameLogicalOccurrenceIdSurvivesPeriodClockCalibration() {
        val snapshot = snapshot()
        val original = scheduleOccurrences(snapshot, defaultSchedulePeriods(), 0, Long.MAX_VALUE).single()
        val periods = defaultSchedulePeriods().map { if (it.number == 1) it.copy(start = "08:05") else it }
        val recalibrated = scheduleOccurrences(snapshot, periods, 0, Long.MAX_VALUE).single()
        assertEquals(original.id, recalibrated.id)
        assertNotEquals(original.startMillis, recalibrated.startMillis)
    }

    @Test fun missingCalendarUnknownWeeksAndIncompleteParseDoNotFabricateAlarms() {
        val unknown = snapshot(course(weeks = emptyList()))
        assertTrue(scheduleOccurrences(unknown, defaultSchedulePeriods(), 0, Long.MAX_VALUE).isEmpty())
        assertEquals(1, scheduleUndatedCourseCount(unknown))
        assertTrue(scheduleOccurrences(snapshot().copy(weekMondays = emptyMap()), defaultSchedulePeriods(), 0, Long.MAX_VALUE).isEmpty())
        assertTrue(scheduleOccurrences(snapshot().copy(complete = false), defaultSchedulePeriods(), 0, Long.MAX_VALUE).isEmpty())
    }

    @Test fun sundayAndTheTwelfthPeriodUseTheSameSchoolZoneAsWeekdayCourses() {
        val events = scheduleOccurrences(snapshot(course(periods = listOf(12), day = 7)), defaultSchedulePeriods(), 0, Long.MAX_VALUE)
        val event = events.single()
        assertEquals("2026-09-06", event.date)
        assertEquals(millis("2026-09-06T13:25:00Z"), event.startMillis)
        assertEquals(millis("2026-09-06T14:10:00Z"), event.endMillis)
    }

    @Test fun genericNativeProjectionSharesTodayCurrentNextAndMidnightAlgorithms() {
        data class Native(override val id: String, override val date: String, override val startsAtMillis: Long, override val endsAtMillis: Long) : ScheduleTimedOccurrence
        val morning = Native("a", "2026-08-31", millis("2026-08-31T00:00:00Z"), millis("2026-08-31T01:40:00Z"))
        val afternoon = Native("b", "2026-08-31", millis("2026-08-31T06:30:00Z"), millis("2026-08-31T07:15:00Z"))
        val now = millis("2026-08-31T00:30:00Z")
        assertEquals(LocalDate(2026, 8, 31), scheduleSchoolDate(millis("2026-08-30T16:00:00Z")))
        assertEquals(listOf(morning, afternoon), scheduleTodayOccurrences(listOf(afternoon, morning), now))
        assertEquals(listOf(morning), scheduleCurrentOccurrences(listOf(morning, afternoon), now))
        assertEquals(afternoon, scheduleNextOccurrence(listOf(morning, afternoon), now))
        assertEquals(morning.endsAtMillis, scheduleNextBoundaryMillis(listOf(morning, afternoon), now))
        assertEquals(millis("2026-08-31T16:00:00Z"), scheduleNextBoundaryMillis(emptyList<Native>(), now))
        assertNull(scheduleNextOccurrence(listOf(morning), now))
    }

    @Test fun normalizedSnapshotAndOccurrenceAreSerializableWithoutCredentialState() {
        val snapshot = snapshot()
        val event = scheduleOccurrences(snapshot, defaultSchedulePeriods(), 0, Long.MAX_VALUE).single()
        assertEquals(snapshot, Json.decodeFromString<ScheduleSnapshot>(Json.encodeToString(snapshot)))
        assertEquals(event, Json.decodeFromString<ScheduleOccurrence>(Json.encodeToString(event)))
        assertFalse(event.toString().contains("Fixture"))
        assertFalse(snapshot.toString().contains("Fixture"))
        assertFalse(Json.encodeToString(snapshot).contains("password", ignoreCase = true))
    }
}
