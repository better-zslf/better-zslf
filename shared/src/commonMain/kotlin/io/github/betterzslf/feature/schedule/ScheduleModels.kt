package io.github.betterzslf.feature.schedule

import io.ktor.client.HttpClient
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

data class AcademicIdentity(val account: String, val displayName: String = "") {
    override fun toString(): String = "AcademicIdentity(redacted)"
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

/** Fixed, non-server-controlled messages; no account, password, cookies, URL query or response body in errors. */
class AcademicSessionExpiredException : Exception("Academic session expired")
class AcademicAuthenticationException : Exception("Academic authentication failed")
class AcademicProtocolException : Exception("Academic response could not be validated")
class AcademicNetworkException : Exception("Academic request failed")

interface AcademicSource {
    suspend fun authenticate(account: String, password: String): AcademicIdentity
    suspend fun terms(): List<ScheduleTerm>
    suspend fun fetch(term: ScheduleTerm): ScheduleSnapshot
    fun close()
}

/** The supplied fresh client belongs exclusively to this source; no automatic reauthentication is performed. */
fun createAcademicSource(client: HttpClient): AcademicSource = KtorAcademicSource(client)
