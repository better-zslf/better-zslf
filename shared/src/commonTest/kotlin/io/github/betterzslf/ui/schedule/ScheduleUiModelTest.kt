package io.github.betterzslf.ui.schedule

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.feature.schedule.runtime.ScheduleState
import kotlin.test.*
import kotlin.time.Instant

class ScheduleUiModelTest {
    private val term = ScheduleTerm("current", "Fixture term", true)
    private val discrete = ScheduleCourse("discrete", "A very long fixture title", dayOfWeek = 3, periods = listOf(1, 3), weeks = listOf(9))
    private val overlap = ScheduleCourse("overlap", "Overlap fixture", dayOfWeek = 3, periods = listOf(1, 2), weeks = listOf(9))
    private val saturday = ScheduleCourse("saturday", "Saturday fixture", dayOfWeek = 6, periods = listOf(12), weeks = listOf(9))
    private val sunday = ScheduleCourse("sunday", "Sunday fixture", dayOfWeek = 7, periods = listOf(12), weeks = listOf(9))
    private val snapshot = ScheduleSnapshot(term, listOf(discrete, overlap, saturday, sunday),
        weekMondays = mapOf(0 to "2026-08-24", 9 to "2026-10-26"), remarks = listOf("Long fixture remark"), syncedAtMillis = 1)
    private fun state(snapshot: ScheduleSnapshot? = this.snapshot) = ScheduleState(owner = "fixture-owner", boundAccount = "fixture-account", snapshot = snapshot)

    @Test fun loginRequiredNeverExposesAnOldSnapshot() {
        assertEquals(ScheduleBody.SIGNED_OUT, scheduleBody(state().copy(owner = null)))
        assertEquals(ScheduleBody.UNBOUND, scheduleBody(state().copy(boundAccount = null)))
    }
    @Test fun storageFailureWithoutHealthyDataIsNotAnEmptyTimetable() {
        assertEquals(ScheduleBody.STORAGE_UNAVAILABLE, scheduleBody(state(null).copy(storageFailure = true)))
    }
    @Test fun firstSyncAndNoCacheFailureHaveDifferentBodies() {
        assertEquals(ScheduleBody.FIRST_SYNC, scheduleBody(state(null).copy(busy = true)))
        assertEquals(ScheduleBody.FIRST_SYNC, scheduleBody(state(null).copy(reauthenticating = true)))
        assertEquals(ScheduleBody.NO_CACHE, scheduleBody(state(null).copy(message = "fixture failure")))
    }
    @Test fun allRefreshAndReauthenticationStatesKeepTheHealthyBoard() {
        for (candidate in listOf(state().copy(busy = true), state().copy(reauthenticating = true),
            state().copy(message = "fixture offline"), state().copy(needsCredentials = true), state().copy(storageFailure = true))) {
            assertEquals(ScheduleBody.SCHEDULE, scheduleBody(candidate))
            assertSame(snapshot, candidate.snapshot)
        }
    }
    @Test fun successfulEmptyTermAndOnlyAnEmptyWeekAreDifferent() {
        assertEquals(ScheduleEmpty.TERM, scheduleEmpty(snapshot.copy(courses = emptyList()), 9))
        assertEquals(ScheduleEmpty.WEEK, scheduleEmpty(snapshot, 0))
        assertEquals(ScheduleEmpty.NONE, scheduleEmpty(snapshot, 9))
    }
    @Test fun incompleteParsingNeverClaimsAnEmptyTermOrWeek() {
        assertEquals(ScheduleEmpty.NONE, scheduleEmpty(snapshot.copy(courses = emptyList(), complete = false), 9))
        assertEquals(ScheduleEmpty.NONE, scheduleEmpty(snapshot.copy(complete = false), 0))
    }
    @Test fun boardAlwaysContainsSevenDaysAndAllTwelvePeriods() {
        val rows = scheduleGridRows(snapshot, 9)
        assertEquals((1..12).toList(), rows.map { it.period })
        assertTrue(rows.all { it.days.size == 7 })
        assertEquals(listOf(saturday), rows.last().days[5])
        assertEquals(listOf(sunday), rows.last().days[6])
    }
    @Test fun overlappingCoursesAreNotOverwrittenByACellMap() {
        assertEquals(listOf(discrete, overlap), scheduleGridRows(snapshot, 9).first().days[2])
    }
    @Test fun discretePeriodsDoNotFillTheGapOrDuplicateTheDayList() {
        val rows = scheduleGridRows(snapshot, 9)
        assertTrue(discrete in rows[0].days[2])
        assertFalse(discrete in rows[1].days[2])
        assertTrue(discrete in rows[2].days[2])
        assertEquals(listOf(discrete, overlap), scheduleDayCourses(snapshot, 9, 3))
    }
    @Test fun unknownWeeksAreNotInventedAsEveryWeek() {
        val unknown = discrete.copy(id = "unknown", weeks = emptyList(), weekText = "unknown fixture")
        assertTrue(scheduleGridRows(snapshot.copy(courses = listOf(unknown)), 9).all { row -> row.days.all { it.isEmpty() } })
    }
    @Test fun narrowOrLargeFontDefaultsToTheReachableDayList() {
        assertTrue(preferScheduleDayList(320f, 1f))
        assertTrue(preferScheduleDayList(390f, 1.3f))
        assertFalse(preferScheduleDayList(390f, 1f))
    }
    @Test fun explicitCalendarIncludesWeekZeroAndCrossMonthSunday() {
        assertEquals("2026-08-24", scheduleDateForWeek(snapshot, 0, 1)?.toString())
        assertEquals("2026-10-26", scheduleDateForWeek(snapshot, 9, 1)?.toString())
        assertEquals("2026-11-01", scheduleDateForWeek(snapshot, 9, 7)?.toString())
        assertNull(scheduleDateForWeek(snapshot, 8, 1))
        assertNull(scheduleDateForWeek(snapshot.copy(weekMondays = emptyMap()), 9, 3))
    }
    @Test fun weekPickerContainsZeroAndIntermediateEmptyWeeks() {
        val value = snapshot.copy(courses = listOf(discrete.copy(weeks = listOf(1, 3))), weekMondays = emptyMap())
        assertEquals(listOf(0, 1, 2, 3), scheduleUiWeeks(value, 1))
        assertTrue(scheduleUiWeeks(snapshot, Int.MAX_VALUE).size <= 100)
    }
    @Test fun detailsRoundTripOnlyIdentifiersAndRequestedWeek() {
        val selection = ScheduleDetailSelection("term/\"fixture", listOf("id\"a", "id/b"), 0)
        assertEquals(selection, ScheduleDetailSelection.decode(selection.encode()))
        assertNull(ScheduleDetailSelection.decode("bad fixture"))
        assertNull(ScheduleDetailSelection.decode("{}"))
    }
    @Test fun detailsPreserveTheClickedCourseOrderAndAllOverlaps() {
        val selection = ScheduleDetailSelection(term.id, listOf(overlap.id, discrete.id), 9)
        assertEquals(listOf(overlap, discrete), detailCourses(snapshot, selection))
        assertTrue(detailCourses(snapshot, selection.copy(termId = "another-term")).isEmpty())
    }
    @Test fun nativePlainCourseIdPrefersTheRealSourceNotHistoricalViewingData() {
        val history = snapshot.copy(term = ScheduleTerm("history", "History"), courses = listOf(discrete.copy(title = "Historical fixture")))
        val value = state(history).copy(sourceSnapshot = snapshot)
        assertSame(snapshot, scheduleDetailSnapshot(value, null, discrete.id))
        assertSame(history, scheduleDetailSnapshot(value, ScheduleDetailSelection("history", listOf(discrete.id), 9), ""))
    }
    @Test fun widgetNeverFallsBackToTheViewedHistoricalSnapshot() {
        val historical = snapshot.copy(term = ScheduleTerm("history", "History"))
        assertSame(snapshot, scheduleWidgetSnapshot(state(historical).copy(sourceSnapshot = snapshot)))
        assertNull(scheduleWidgetSnapshot(state(historical)))
        assertNull(scheduleWidgetSnapshot(state().copy(sourceSnapshot = snapshot, owner = null)))
        assertNull(scheduleWidgetSnapshot(state().copy(sourceSnapshot = snapshot, boundAccount = null)))
    }
    @Test fun detailsHideSnapshotsAfterLogoutOrUnbind() {
        assertNull(scheduleDetailSnapshot(state().copy(sourceSnapshot = snapshot, owner = null), null, discrete.id))
        assertNull(scheduleDetailSnapshot(state().copy(sourceSnapshot = snapshot, boundAccount = null), null, discrete.id))
    }
    @Test fun accountMaskDoesNotExposeShortOrLongIdentifiers() {
        assertEquals("••••", maskedAcademicAccount("1234"))
        assertEquals("fi••••nt", maskedAcademicAccount("fixture-account"))
        assertEquals("", maskedAcademicAccount(""))
    }
    @Test fun unknownWeeksEvenOnAnOlderCompleteCacheDoNotMeanNoClass() {
        val unknown = snapshot.copy(courses = listOf(discrete.copy(weeks = emptyList())), complete = true)
        assertEquals(ScheduleEmpty.NONE, scheduleEmpty(unknown, 9))
        assertFalse(scheduleCanConcludeDayEmpty(unknown, 9, 3))
        assertTrue(scheduleCanConcludeDayEmpty(unknown, 9, 4))
    }
    @Test fun invalidOrMissingPlacementRecordsRemainExplicitlyReachable() {
        val courses = listOf(discrete.copy(id = "unknown-week", weeks = emptyList()),
            discrete.copy(id = "unknown-period", periods = emptyList()),
            discrete.copy(id = "unknown-day", dayOfWeek = 0),
            discrete.copy(id = "invalid-period", periods = listOf(1, 99)))
        val value = snapshot.copy(courses = courses)
        assertEquals(courses, scheduleUnplacedCourses(value))
        assertFalse(scheduleCanConcludeDayEmpty(value, 9, 1))
        assertTrue(scheduleUnplacedCourses(snapshot).isEmpty())
    }
    @Test fun dayEmptyNeedsCompleteAndRelevantKnownWeekdayData() {
        assertTrue(scheduleCanConcludeDayEmpty(snapshot, 0, 3))
        assertFalse(scheduleCanConcludeDayEmpty(snapshot.copy(complete = false), 0, 3))
        assertFalse(scheduleCanConcludeDayEmpty(snapshot, 9, 3))
        assertFalse(scheduleCanConcludeDayEmpty(snapshot, 9, 8))
    }
    @Test fun anUndatedWidgetSourceIsReviewableRatherThanZeroConfirmedCourses() {
        val unknown = snapshot.copy(courses = listOf(discrete.copy(weeks = emptyList())), complete = true)
        val source = scheduleWidgetSnapshot(state().copy(sourceSnapshot = unknown))!!
        assertEquals(1, scheduleUndatedCourseCount(source))
        assertFalse(scheduleCanConcludeDayEmpty(source, 9, 3))
    }
    @Test fun unboundAndSignedOutOwnersOnlyViewDefaultPeriodsRatherThanOpenAFailingEditor() {
        assertFalse(scheduleCanEditPeriods(ScheduleState(owner = "fixture-owner")))
        assertFalse(scheduleCanEditPeriods(state().copy(owner = null)))
        assertFalse(scheduleCanEditPeriods(state().copy(busy = true)))
        assertTrue(scheduleCanEditPeriods(state()))
    }
    @Test fun aSingleOrEmptyCellDoesNotShowAnOverlapAction() {
        assertNull(scheduleOverlapLabels(0))
        assertNull(scheduleOverlapLabels(1))
    }
    @Test fun compactOverlapActionKeepsTheTotalAndTheFullAccessibleEntry() {
        for (count in listOf(2, 12, 123)) {
            val labels = assertNotNull(scheduleOverlapLabels(count))
            assertEquals(AppText.Schedule.moreCoursesCompact(count), labels.compact)
            assertEquals(AppText.Schedule.moreCourses(count), labels.accessibility)
            assertTrue(labels.compact.contains(count.toString()))
            assertTrue(labels.accessibility.contains(count.toString()))
            assertTrue(labels.compact.length < labels.accessibility.length)
            assertFalse(labels.compact.startsWith("+"))
        }
    }
    @Test fun syncStampKeepsTheFullSchoolDateAndMinuteWithTheShortZoneLabel() {
        val millis = Instant.parse("2026-10-29T00:51:59Z").toEpochMilliseconds()
        val value = scheduleSyncTime(millis)
        assertEquals(AppText.Schedule.syncedAt("2026-10-29 08:51 · " + AppText.Schedule.TIME_ZONE_SHORT), value)
        assertFalse(value.contains(SCHEDULE_TIME_ZONE_ID))
    }
    @Test fun anUnknownSyncTimeDoesNotInventADateOrUseTheDisplayClock() {
        assertEquals(AppText.Schedule.LAST_SYNC_UNKNOWN, scheduleSyncTime(0))
        assertEquals(AppText.Schedule.LAST_SYNC_UNKNOWN, scheduleSyncTime(-1))
    }
    @Test fun separatedPeriodTimesNeverSuggestAContinuousClass() {
        assertEquals("08:00–08:45 · 10:00–10:45", coursePeriodTimes(discrete, defaultSchedulePeriods()))
    }
}
