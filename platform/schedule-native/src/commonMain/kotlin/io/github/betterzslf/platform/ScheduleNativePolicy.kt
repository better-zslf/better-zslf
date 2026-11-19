package io.github.betterzslf.platform

import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import io.github.betterzslf.feature.schedule.scheduleUndatedCourseCount
import io.github.betterzslf.feature.schedule.validateSchedulePeriods

internal const val SCHEDULE_NATIVE_DAY_MILLIS = 86_400_000L
internal const val SCHEDULE_NATIVE_WINDOW_MILLIS = 14 * SCHEDULE_NATIVE_DAY_MILLIS
internal const val SCHEDULE_NATIVE_MAX_REMINDERS = 128

internal fun scheduleNativeRevisionValid(value: String): Boolean = value.length in 1..160 &&
    value.none { it.isWhitespace() || it.isISOControl() }

internal fun scheduleNativePublicationValid(value: ScheduleNativePublication): Boolean {
    if (!scheduleNativeRevisionValid(value.ownerRevision) || value.timeZoneId != SCHEDULE_TIME_ZONE_ID ||
        value.reminderLeadMinutes !in 0..120 || value.uncertainCount < 0) return false
    val source = value.snapshot ?: return value.status in setOf(ScheduleNativeStatus.NOT_SYNCED, ScheduleNativeStatus.NEEDS_LOGIN, ScheduleNativeStatus.UNBOUND)
    if (value.status !in setOf(ScheduleNativeStatus.READY, ScheduleNativeStatus.CACHED, ScheduleNativeStatus.NEEDS_LOGIN) ||
        !source.complete || !validateSchedulePeriods(value.periods) || source.courses.size > 1000 || source.weekMondays.size > 100) return false
    if (source.term.id.length > 200 || source.weekMondays.values.any { it.length != 10 }) return false
    return source.courses.all { it.id.length in 1..200 && it.weeks.size <= 100 && it.periods.size <= 12 && it.title.length <= 2000 && it.location.length <= 2000 }
}

internal fun scheduleNativeCanPublish(activeRevision: String?, publication: ScheduleNativePublication): Boolean =
    activeRevision != null && activeRevision == publication.ownerRevision && scheduleNativePublicationValid(publication)

/** Only fields required to regenerate canonical occurrences reach native persistent storage. */
internal fun scheduleNativeRedact(value: ScheduleNativePublication): ScheduleNativePublication {
    val source = value.snapshot
    return value.copy(
        snapshot = source?.copy(
            term = source.term.copy(name = "", isDefault = false),
            courses = source.courses.map { it.copy(teacher = "", weekText = "", title = it.title.take(200), location = it.location.take(200)) },
            remarks = emptyList(), warnings = emptyList(),
        ),
        uncertainCount = maxOf(value.uncertainCount, source?.let(::scheduleUndatedCourseCount) ?: 0),
        // Names are for the on-screen card only; they must not survive into the persisted seed.
        displayTeachers = emptyMap(),
    )
}

internal data class ScheduleNativeReminderPlan(val id: String, val triggerAtMillis: Long)

/** Scheduler policy over the shared domain's occurrences; it does not calculate dates/weeks/periods. */
internal fun scheduleNativeFutureReminders(
    occurrences: List<ScheduleNativeOccurrence>, nowMillis: Long, leadMinutes: Int,
    deliveredIds: Set<String> = emptySet(),
): List<ScheduleNativeReminderPlan> {
    if (leadMinutes !in 0..120) return emptyList()
    return occurrences.asSequence().distinctBy { it.id }.filter {
        it.id !in deliveredIds && it.startsAtMillis < it.endsAtMillis && it.startsAtMillis < nowMillis + SCHEDULE_NATIVE_WINDOW_MILLIS
    }.map { ScheduleNativeReminderPlan(it.id, it.startsAtMillis - leadMinutes * 60_000L) }
        .filter { it.triggerAtMillis > nowMillis }.sortedWith(compareBy<ScheduleNativeReminderPlan> { it.triggerAtMillis }.thenBy { it.id })
        .take(SCHEDULE_NATIVE_MAX_REMINDERS).toList()
}

/**
 * Longest delay of a reminder that is still worth firing.
 *
 * An alarm that should have fired ten minutes ago is not a reminder any more; it is an
 * interruption that arrives when the user opened the app, which is exactly when they were reading
 * the timetable themselves. Past this grace the reminder is treated as missed and dropped.
 */
internal const val SCHEDULE_NATIVE_DELIVERY_GRACE_MILLIS: Long = 90_000L

/** No backfill during refresh. A previously armed zero-lead alarm gets a bounded OS dispatch grace only. */
internal fun scheduleNativeDeliveryDue(occurrence: ScheduleNativeOccurrence, triggerAtMillis: Long, leadMinutes: Int, nowMillis: Long): Boolean {
    if (leadMinutes !in 0..120 || triggerAtMillis != occurrence.startsAtMillis - leadMinutes * 60_000L || nowMillis < triggerAtMillis) return false
    // Only fire close to the moment it was due. Without this a reminder armed for 07:50 would
    // still fire at 07:54 simply because the app was opened -- the user sees a "10 minutes before"
    // alert four minutes late and rightly calls it wrong.
    val graceEnd = triggerAtMillis + SCHEDULE_NATIVE_DELIVERY_GRACE_MILLIS
    if (nowMillis > graceEnd) return false
    val deadline = if (leadMinutes == 0) minOf(occurrence.startsAtMillis + 60_000L, occurrence.endsAtMillis) else occurrence.startsAtMillis
    return nowMillis < deadline
}

/** Preserve already-armed delivery across a same-process boundary race; never rearm past work after boot. */
internal fun scheduleNativeDueArmedIds(
    armed: List<ScheduleNativeReminderPlan>, occurrences: List<ScheduleNativeOccurrence>,
    nowMillis: Long, leadMinutes: Int, deliveredIds: Set<String>, rearming: Boolean,
): Set<String> {
    if (rearming) return emptySet()
    val byId = occurrences.associateBy { it.id }
    return armed.filter { record ->
        record.id !in deliveredIds && byId[record.id]?.let {
            scheduleNativeDeliveryDue(it, record.triggerAtMillis, leadMinutes, nowMillis)
        } == true
    }.map { it.id }.toSet()
}
