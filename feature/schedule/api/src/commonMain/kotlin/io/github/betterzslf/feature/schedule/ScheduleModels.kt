package io.github.betterzslf.feature.schedule

import kotlinx.serialization.Serializable

@Serializable
data class ScheduleTerm(val id: String, val name: String, val isDefault: Boolean = false)

@Serializable
data class ScheduleCourse(
    val id: String,
    val title: String,
    val teacher: String = "",
    val location: String = "",
    val dayOfWeek: Int,
    val periods: List<Int>,
    val weeks: List<Int>,
    val weekText: String = "",
) {
    override fun toString(): String = "ScheduleCourse(redacted)"
}

@Serializable
data class SchedulePeriod(val number: Int, val start: String, val end: String)

@Serializable
data class ScheduleSnapshot(
    val term: ScheduleTerm,
    val courses: List<ScheduleCourse>,
    val weekMondays: Map<Int, String> = emptyMap(),
    val remarks: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val syncedAtMillis: Long = 0,
    val complete: Boolean = true,
) {
    override fun toString(): String = "ScheduleSnapshot(redacted)"
}

/** Primitive read-only projection shared with native publications; it contains no authentication state. */
interface ScheduleTimedOccurrence {
    val id: String
    val date: String
    val startsAtMillis: Long
    val endsAtMillis: Long
}

/** Exactly one contiguous run of a course on one explicitly mapped school-calendar date. */
@Serializable
data class ScheduleOccurrence(
    override val id: String,
    val course: ScheduleCourse,
    val week: Int,
    override val date: String,
    val periods: List<Int>,
    val startMillis: Long,
    val endMillis: Long,
) : ScheduleTimedOccurrence {
    override val startsAtMillis: Long get() = startMillis
    override val endsAtMillis: Long get() = endMillis
    val courseId: String get() = course.id
    val title: String get() = course.title
    val teacher: String get() = course.teacher
    val location: String get() = course.location
    val dayOfWeek: Int get() = course.dayOfWeek
    override fun toString(): String = "ScheduleOccurrence(redacted)"
}
