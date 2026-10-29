package io.github.betterzslf.platform

import io.github.betterzslf.feature.schedule.ScheduleCourse
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.ScheduleTerm
import io.github.betterzslf.feature.schedule.defaultSchedulePeriods
import io.github.betterzslf.feature.schedule.scheduleNextBoundaryMillis
import io.github.betterzslf.feature.schedule.scheduleOccurrences
import io.github.betterzslf.feature.schedule.scheduleTodayOccurrences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ScheduleNativeContractTest {
    private val revision = "binding-local-fixture-1"
    private val now = Instant.parse("2026-10-25T23:30:00Z").toEpochMilliseconds() // School Monday 07:30.
    private fun publication() = ScheduleNativePublication(
        ownerRevision = revision, status = ScheduleNativeStatus.READY,
        snapshot = ScheduleSnapshot(
            term = ScheduleTerm("fixture-term", "TERM_LABEL_PRIVATE", true),
            courses = listOf(ScheduleCourse("fixture-course", "数学", "TEACHER_PRIVATE", "A101", 1, listOf(1, 2, 4), listOf(1), "RAW_WEEK_PRIVATE")),
            weekMondays = mapOf(1 to "2026-10-26"), remarks = listOf("REMARK_PRIVATE"), warnings = listOf("WARNING_PRIVATE"),
            syncedAtMillis = now,
        ), periods = defaultSchedulePeriods(), remindersEnabled = true,
    )
    private fun occurrences(value: ScheduleNativePublication = publication()): List<ScheduleNativeOccurrence> =
        scheduleOccurrences(assertNotNull(value.snapshot), value.periods, now - SCHEDULE_NATIVE_DAY_MILLIS, now + SCHEDULE_NATIVE_WINDOW_MILLIS).map {
            ScheduleNativeOccurrence(it.id, it.title, it.location, it.date, it.startsAtMillis, it.endsAtMillis)
        }

    @Test fun publishRequiresCurrentOwnerLeaseAndRejectsLateOldOwner() {
        val value = publication()
        assertFalse(scheduleNativeCanPublish(null, value))
        assertFalse(scheduleNativeCanPublish("binding-local-fixture-2", value))
        assertTrue(scheduleNativeCanPublish(revision, value))
        assertFalse(scheduleNativeCanPublish(revision, value.copy(ownerRevision = "old-binding")))
    }

    @Test fun publicationRejectsPartialInvalidTimeAndDeviceTimezoneOverride() {
        val value = publication()
        assertTrue(scheduleNativePublicationValid(value))
        assertFalse(scheduleNativePublicationValid(value.copy(snapshot = value.snapshot!!.copy(complete = false))))
        assertFalse(scheduleNativePublicationValid(value.copy(periods = value.periods.dropLast(1))))
        assertFalse(scheduleNativePublicationValid(value.copy(timeZoneId = "America/New_York")))
        assertFalse(scheduleNativePublicationValid(value.copy(status = ScheduleNativeStatus.UNBOUND)))
        assertFalse(scheduleNativePublicationValid(value.copy(snapshot = null)))
        assertTrue(scheduleNativePublicationValid(value.copy(snapshot = null, status = ScheduleNativeStatus.NOT_SYNCED)))
    }

    @Test fun zeroLeadAndDisableRemainValidWhileInvalidLeadFails() {
        val value = publication().copy(reminderLeadMinutes = 0)
        assertTrue(scheduleNativePublicationValid(value))
        assertTrue(scheduleNativePublicationValid(value.copy(remindersEnabled = false)))
        assertFalse(scheduleNativePublicationValid(value.copy(reminderLeadMinutes = -1)))
        assertFalse(scheduleNativePublicationValid(value.copy(reminderLeadMinutes = 121)))
    }

    @Test fun persistedSeedDropsNonessentialPrivateTextButKeepsCanonicalInputs() {
        val original = publication()
        val redacted = scheduleNativeRedact(original)
        val raw = Json.encodeToString(redacted)
        for (marker in listOf("TEACHER_PRIVATE", "RAW_WEEK_PRIVATE", "REMARK_PRIVATE", "WARNING_PRIVATE", "TERM_LABEL_PRIVATE")) assertFalse(marker in raw)
        val redactedSource = assertNotNull(redacted.snapshot)
        val originalSource = assertNotNull(original.snapshot)
        assertEquals("数学", redactedSource.courses.single().title)
        assertEquals("A101", redactedSource.courses.single().location)
        assertEquals(originalSource.weekMondays, redactedSource.weekMondays)
        assertEquals(original.periods, redacted.periods)
        assertEquals(occurrences(original), occurrences(redacted))
        assertEquals("ScheduleNativePublication(redacted)", original.toString())
    }

    @Test fun undatedCoursesDoNotInventDatesAndAreCounted() {
        val value = publication().copy(snapshot = publication().snapshot!!.copy(weekMondays = emptyMap()))
        assertTrue(occurrences(value).isEmpty())
        assertEquals(1, scheduleNativeRedact(value).uncertainCount)
    }

    @Test fun continuousBlockOnceAndNoncontiguousBlockSeparatelyUseSharedAlgorithm() {
        val events = occurrences()
        assertEquals(2, events.size) // Periods 1+2 together, then period 4 independently.
        assertEquals(2, scheduleNativeFutureReminders(events, now, 10).size)
        assertEquals(2, scheduleTodayOccurrences(events, now).size)
        val changed = publication().copy(periods = publication().periods.map { if (it.number == 1) it.copy(start = "08:05") else it })
        assertEquals(events.map { it.id }, occurrences(changed).map { it.id })
        assertTrue(events.first().startsAtMillis != occurrences(changed).first().startsAtMillis)
    }

    @Test fun schedulingHasNoBackfillNoDuplicatesAndHonorsDeliveredIds() {
        val events = occurrences()
        val first = events.first()
        val due = first.startsAtMillis - 10 * 60_000L
        assertEquals(2, scheduleNativeFutureReminders(events + events, now, 10).size)
        assertEquals(1, scheduleNativeFutureReminders(events, due, 10).size) // Equality is already past for new work.
        assertEquals(1, scheduleNativeFutureReminders(events, now, 10, setOf(first.id)).size)
        assertTrue(scheduleNativeFutureReminders(events, events.last().endsAtMillis, 10).isEmpty())
    }

    @Test fun finiteWindowAndAlarmCeilingAreEnforced() {
        val many = (1..200).map { index ->
            val start = now + index * 60_000L
            ScheduleNativeOccurrence("id-$index", "Fixture", "", "2026-10-26", start, start + 30_000)
        }
        val outOfWindow = many.first().copy(id = "far", startsAtMillis = now + SCHEDULE_NATIVE_WINDOW_MILLIS, endsAtMillis = now + SCHEDULE_NATIVE_WINDOW_MILLIS + 30_000)
        val result = scheduleNativeFutureReminders(many + outOfWindow, now, 0)
        assertEquals(128, result.size)
        assertFalse(result.any { it.id == "far" })
    }

    @Test fun zeroLeadDeliveryGraceDoesNotTurnRefreshIntoBackfill() {
        val event = occurrences().first()
        assertTrue(scheduleNativeDeliveryDue(event, event.startsAtMillis, 0, event.startsAtMillis))
        assertTrue(scheduleNativeDeliveryDue(event, event.startsAtMillis, 0, event.startsAtMillis + 59_999))
        assertFalse(scheduleNativeDeliveryDue(event, event.startsAtMillis, 0, event.startsAtMillis + 60_000))
        assertFalse(scheduleNativeDeliveryDue(event, event.startsAtMillis, 0, event.startsAtMillis - 1))
        assertTrue(scheduleNativeFutureReminders(listOf(event), event.startsAtMillis, 0).isEmpty())
    }

    @Test fun boundaryBeforeReminderKeepsAlreadyArmedDueRecordButBootDoesNotRearmIt() {
        val event = occurrences().first()
        val armed = listOf(ScheduleNativeReminderPlan(event.id, event.startsAtMillis))
        assertEquals(setOf(event.id), scheduleNativeDueArmedIds(armed, listOf(event), event.startsAtMillis, 0, emptySet(), rearming = false))
        assertTrue(scheduleNativeDueArmedIds(armed, listOf(event), event.startsAtMillis, 0, emptySet(), rearming = true).isEmpty())
        assertTrue(scheduleNativeDueArmedIds(armed, listOf(event), event.startsAtMillis, 0, setOf(event.id), rearming = false).isEmpty())
        assertTrue(scheduleNativeDueArmedIds(armed, listOf(event), event.startsAtMillis + 60_000, 0, emptySet(), rearming = false).isEmpty())
    }

    @Test fun positiveLeadDoesNotDeliverEarlyAfterClassStartsOrWithOldTrigger() {
        val event = occurrences().first()
        val trigger = event.startsAtMillis - 600_000
        assertFalse(scheduleNativeDeliveryDue(event, trigger, 10, trigger - 1))
        assertTrue(scheduleNativeDeliveryDue(event, trigger, 10, trigger))
        assertFalse(scheduleNativeDeliveryDue(event, trigger, 10, event.startsAtMillis))
        assertFalse(scheduleNativeDeliveryDue(event, trigger - 1, 10, trigger))
    }

    @Test fun midnightAndYearBoundaryStillUseFixedSchoolTimezoneEvenWithEmptyCourses() {
        val beforeNewYear = Instant.parse("2026-12-31T15:59:00Z").toEpochMilliseconds()
        assertEquals(Instant.parse("2026-12-31T16:00:00Z").toEpochMilliseconds(), scheduleNextBoundaryMillis(emptyList<ScheduleNativeOccurrence>(), beforeNewYear))
    }

    @Test fun unsupportedPlatformNeverRequestsPermissionOrPublishesNativeData() {
        val bridge = UnsupportedScheduleNativeBridge()
        assertFalse(bridge.supported)
        assertFalse(bridge.capabilities.value.supported)
        assertFalse(bridge.requestNotificationPermission())
        assertFalse(bridge.openExactAlarmSettings())
        assertFalse(bridge.requestPinWidget(ScheduleWidgetKind.TODAY))
        assertFalse(bridge.publish(publication()))
        bridge.clear(ScheduleNativeStatus.UNBOUND, revision)
        bridge.close()
        assertNull(bridge.openRequests.value)
    }
}
