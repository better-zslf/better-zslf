package io.github.betterzslf.ui.schedule

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.ScheduleCourse
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.SchedulePeriod
import io.github.betterzslf.feature.schedule.validateSchedulePeriods
import io.github.betterzslf.feature.schedule.runtime.ScheduleState
import kotlinx.serialization.json.*

internal enum class ScheduleBody { SIGNED_OUT, STORAGE_UNAVAILABLE, UNBOUND, FIRST_SYNC, NO_CACHE, SCHEDULE }

internal fun scheduleBody(state: ScheduleState): ScheduleBody = when {
    state.owner == null -> ScheduleBody.SIGNED_OUT
    state.snapshot != null && state.boundAccount != null -> ScheduleBody.SCHEDULE
    state.storageFailure -> ScheduleBody.STORAGE_UNAVAILABLE
    state.boundAccount == null -> ScheduleBody.UNBOUND
    state.busy || state.reauthenticating -> ScheduleBody.FIRST_SYNC
    else -> ScheduleBody.NO_CACHE
}

/** Passwords live only in this unsaved form. No logs, routes, persistence, or native bridge use it. */
internal data class ScheduleBindingDraft(
    val account: String = "",
    val password: String = "",
    val revision: Long = 0,
    val verifiedRevision: Long? = null,
    val verifiedAccount: String? = null,
) {
    fun accountChanged(value: String) = copy(account = value, revision = revision + 1, verifiedRevision = null, verifiedAccount = null)
    fun passwordChanged(value: String) = copy(password = value, revision = revision + 1, verifiedRevision = null, verifiedAccount = null)
    fun invalidate() = copy(verifiedRevision = null, verifiedAccount = null)
    fun acceptVerification(startedRevision: Long, serverAccount: String?): ScheduleBindingDraft =
        if (startedRevision == revision && serverAccount == account.trim())
            copy(verifiedRevision = revision, verifiedAccount = serverAccount) else this
    fun canConfirm(serverAccount: String?, busy: Boolean): Boolean = !busy && account.isNotBlank() && password.isNotEmpty() &&
        verifiedRevision == revision && verifiedAccount == account.trim() && serverAccount == account.trim()
    override fun toString(): String = "ScheduleBindingDraft(redacted)"
}

internal fun maskedAcademicAccount(account: String): String = when {
    account.isBlank() -> ""
    account.length <= 4 -> "••••"
    else -> account.take(2) + "••••" + account.takeLast(2)
}

internal data class ScheduleOverlapLabels(val compact: String, val accessibility: String)

internal fun scheduleOverlapLabels(totalCount: Int): ScheduleOverlapLabels? = if (totalCount < 2) null else
    ScheduleOverlapLabels(AppText.Schedule.moreCoursesCompact(totalCount), AppText.Schedule.moreCourses(totalCount))

internal data class ScheduleGridRow(val period: Int, val days: List<List<ScheduleCourse>>)

/** A fixed seven-day/twelve-period projection. Discrete periods never become a filled interval. */
internal fun scheduleGridRows(snapshot: ScheduleSnapshot, week: Int): List<ScheduleGridRow> {
    val courses = snapshot.courses.filter { week in it.weeks }
    return (1..12).map { period ->
        ScheduleGridRow(period, (1..7).map { day -> courses.filter { it.dayOfWeek == day && period in it.periods } })
    }
}

internal fun scheduleDayCourses(snapshot: ScheduleSnapshot, week: Int, day: Int): List<ScheduleCourse> =
    snapshot.courses.filter { week in it.weeks && it.dayOfWeek == day }
        .sortedBy { it.periods.minOrNull() ?: Int.MAX_VALUE }

internal fun preferScheduleDayList(widthDp: Float, fontScale: Float): Boolean = widthDp < 360f || fontScale > 1.2f

/** Navigation contains only local selection identifiers, never credentials or whole course payloads. */
internal data class ScheduleDetailSelection(val termId: String, val courseIds: List<String>, val week: Int) {
    fun encode(): String = buildJsonObject {
        put("term", termId)
        put("courses", JsonArray(courseIds.map(::JsonPrimitive)))
        put("week", week)
    }.toString()
    companion object {
        fun decode(value: String): ScheduleDetailSelection? {
            return runCatching {
                val objectValue = Json.parseToJsonElement(value) as? JsonObject ?: return null
                val term = (objectValue["term"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
                val week = (objectValue["week"] as? JsonPrimitive)?.intOrNull ?: return null
                val ids = (objectValue["courses"] as? JsonArray)?.mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                }?.distinct()?.takeIf { it.isNotEmpty() } ?: return null
                ScheduleDetailSelection(term, ids, week)
            }.getOrNull()
        }
    }
}

internal fun detailCourses(snapshot: ScheduleSnapshot?, selection: ScheduleDetailSelection?): List<ScheduleCourse> {
    if (snapshot == null || selection == null || snapshot.term.id != selection.termId) return emptyList()
    return selection.courseIds.flatMap { id -> snapshot.courses.filter { it.id == id } }
}

internal fun scheduleCanEditPeriods(state: ScheduleState): Boolean = state.owner != null && state.boundAccount != null && !state.busy

internal enum class ScheduleEmpty { NONE, TERM, WEEK }

private fun ScheduleCourse.hasUnknownWeeks(): Boolean = weeks.isEmpty() || weeks.any { it !in 0..99 }

internal fun scheduleEmpty(snapshot: ScheduleSnapshot, week: Int): ScheduleEmpty = when {
    !snapshot.complete || snapshot.courses.any { it.hasUnknownWeeks() } -> ScheduleEmpty.NONE
    snapshot.courses.isEmpty() -> ScheduleEmpty.TERM
    snapshot.courses.none { week in it.weeks } -> ScheduleEmpty.WEEK
    else -> ScheduleEmpty.NONE
}

internal fun scheduleCanConcludeDayEmpty(snapshot: ScheduleSnapshot, week: Int, day: Int): Boolean =
    snapshot.complete && week in 0..99 && day in 1..7 && snapshot.courses.none { course ->
        (course.dayOfWeek == day || course.dayOfWeek !in 1..7) && (week in course.weeks || course.hasUnknownWeeks())
    }

internal fun scheduleUnplacedCourses(snapshot: ScheduleSnapshot): List<ScheduleCourse> = snapshot.courses.filter { course ->
    course.hasUnknownWeeks() || course.dayOfWeek !in 1..7 || course.periods.isEmpty() || course.periods.any { it !in 1..12 }
}

internal fun scheduleWidgetSnapshot(state: ScheduleState): ScheduleSnapshot? =
    state.sourceSnapshot?.takeIf { state.owner != null && state.boundAccount != null }

internal fun scheduleDetailSnapshot(state: ScheduleState, selection: ScheduleDetailSelection?, directId: String): ScheduleSnapshot? {
    if (state.owner == null || state.boundAccount == null) return null
    val snapshots = if (selection == null) listOfNotNull(state.sourceSnapshot, state.snapshot) else listOfNotNull(state.snapshot, state.sourceSnapshot)
    return snapshots.firstOrNull { snapshot ->
        if (selection != null) snapshot.term.id == selection.termId && snapshot.courses.any { it.id in selection.courseIds }
        else snapshot.courses.any { it.id == directId }
    }
}

/** Row-local hints only; the domain validator remains the final apply authority. */
internal fun schedulePeriodIssueRows(periods: List<SchedulePeriod>): Set<Int> {
    val clock = Regex("^(?:[01][0-9]|2[0-3]):[0-5][0-9]$")
    val invalid = mutableSetOf<Int>()
    periods.forEachIndexed { index, period ->
        val validStart = clock.matches(period.start)
        val validEnd = clock.matches(period.end)
        if (period.number != index + 1 || !validStart || !validEnd || period.start >= period.end) invalid += index
        val previous = periods.getOrNull(index - 1)
        if (validStart && previous != null && clock.matches(previous.end) && period.start < previous.end) {
            invalid += index - 1
            invalid += index
        }
    }
    if (periods.size != 12) invalid += periods.indices
    return invalid
}

internal data class SchedulePeriodEdit(
    val periods: List<SchedulePeriod>,
    val revision: Long = 0,
    val previewRevision: Long? = null,
    val baseline: List<SchedulePeriod>? = null,
) {
    fun change(index: Int, start: String? = null, end: String? = null) = copy(
        periods = periods.mapIndexed { current, period -> if (current == index)
            period.copy(start = start ?: period.start, end = end ?: period.end) else period },
        revision = revision + 1, previewRevision = null, baseline = null,
    )
    fun preview(effective: List<SchedulePeriod>): SchedulePeriodEdit = if (validateSchedulePeriods(periods))
        copy(previewRevision = revision, baseline = effective.toList()) else copy(previewRevision = null, baseline = null)
    fun canApply(effective: List<SchedulePeriod>): Boolean = previewRevision == revision && baseline == effective && validateSchedulePeriods(periods)
}
