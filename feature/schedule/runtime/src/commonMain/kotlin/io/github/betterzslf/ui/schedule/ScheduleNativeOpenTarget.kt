package io.github.betterzslf.ui.schedule

import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.runtime.ScheduleState
import io.github.betterzslf.feature.schedule.scheduleOccurrences
import io.github.betterzslf.feature.schedule.validateSchedulePeriods
import io.github.betterzslf.platform.ScheduleNativeOpenRequest
import io.github.betterzslf.platform.matchesRevision
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

/** null detail means timetable root, not a guessed course or the currently viewed historical term. */
data class ScheduleNativeOpenTarget(val detail: ScheduleDetailSelection? = null)

/**
 * Pure interpretation only: the mounted application decides when to consume the request/navigate.
 * A campus login is not timetable authority. A matching local slot, binding and opaque revision are.
 * Missing/stale authority rejects the entire request; unresolved current occurrence opens only root.
 */
fun scheduleNativeOpenTarget(
    state: ScheduleState,
    ownerRevision: String,
    request: ScheduleNativeOpenRequest,
    expectedSlot: String,
    nowMillis: Long,
): ScheduleNativeOpenTarget? {
    if (expectedSlot.isBlank() || state.owner != expectedSlot || state.boundAccount.isNullOrBlank() ||
        !request.matchesRevision(ownerRevision)) return null

    val root = ScheduleNativeOpenTarget()
    val occurrenceId = request.occurrenceId?.takeIf { it.length in 1..200 } ?: return root
    val source = state.sourceSnapshot ?: return root
    if (source.term.id != state.sourceTermId || !nativeOpenSourceBounded(source) ||
        !validateSchedulePeriods(state.periods)) return root

    // Preserve the runtime's bounded lookup window, including old notifications and upcoming widgets.
    // Saturation keeps an externally supplied extreme clock from wrapping into an unrelated week.
    val past = 7L * 86_400_000
    val future = 14L * 86_400_000
    val from = if (nowMillis < Long.MIN_VALUE + past) Long.MIN_VALUE else nowMillis - past
    val until = if (nowMillis > Long.MAX_VALUE - future) Long.MAX_VALUE else nowMillis + future
    val occurrence = runCatching { scheduleOccurrences(source, state.periods, from, until) }
        .getOrNull()?.singleOrNull { it.id == occurrenceId } ?: return root
    return ScheduleNativeOpenTarget(ScheduleDetailSelection(source.term.id, listOf(occurrence.courseId), occurrence.week))
}

/** Bound work before calling the domain resolver; no malformed calendar is interpreted as a new date. */
private fun nativeOpenSourceBounded(source: ScheduleSnapshot): Boolean {
    if (!source.complete || source.term.id.isBlank() || source.term.id.length > 200 ||
        source.courses.size > 1000 || source.weekMondays.size > 100 ||
        source.courses.map { it.id }.distinct().size != source.courses.size) return false
    if (source.courses.any { course ->
        course.id.isBlank() || course.id.length > 200 || course.title.isBlank() || course.title.length > 2000 ||
            course.location.length > 2000 || course.dayOfWeek !in 1..7 ||
            course.periods.isEmpty() || course.periods.size > 12 || course.periods.any { it !in 1..12 } ||
            course.weeks.size > 100 || course.weeks.any { it !in 0..99 }
    }) return false
    if (source.weekMondays.values.distinct().size != source.weekMondays.size) return false
    return source.weekMondays.all { (week, raw) ->
        week in 0..99 && raw.length == 10 && runCatching {
            val date = LocalDate.parse(raw)
            date.toString() == raw && date.dayOfWeek == DayOfWeek.MONDAY
        }.getOrDefault(false)
    }
}
