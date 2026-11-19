package io.github.betterzslf.feature.home.presentation

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import io.github.betterzslf.feature.schedule.ScheduleOccurrence
import io.github.betterzslf.feature.schedule.SchedulePeriod
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.scheduleCurrentOccurrences
import io.github.betterzslf.feature.schedule.scheduleNextOccurrence
import io.github.betterzslf.feature.schedule.scheduleOccurrences
import io.github.betterzslf.feature.schedule.scheduleSchoolDate
import io.github.betterzslf.feature.schedule.scheduleTodayOccurrences
import io.github.betterzslf.feature.schedule.scheduleWeekForDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Copies existing schedule models instead of inventing a second course/domain model. */
class HomeInput(
    snapshot: ScheduleSnapshot?,
    periods: List<SchedulePeriod>,
    val selectedWeek: Int,
    val nowMillis: Long,
    val isBound: Boolean = snapshot != null,
) {
    internal val snapshot = snapshot?.copy(
        courses = SnapshotList(snapshot.courses.map { course ->
            course.copy(periods = SnapshotList(course.periods), weeks = SnapshotList(course.weeks))
        }),
        weekMondays = SnapshotMap(snapshot.weekMondays),
        remarks = SnapshotList(snapshot.remarks), warnings = SnapshotList(snapshot.warnings),
    )
    internal val periods: List<SchedulePeriod> = SnapshotList(periods)
    override fun toString(): String = "HomeInput(bound=$isBound, hasSnapshot=${snapshot != null}, redacted)"
}

@JvmInline
value class HomeOccurrenceId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)) }
}

enum class HomeHeroMode { BEFORE, JUST_BEFORE, IN_CLASS, LONG_GAP, DONE_TODAY, FREE_TODAY, NO_PLAN, UNAVAILABLE }
enum class HomeUnavailableReason { UNBOUND, UNSYNCED, PARTIAL, CALENDAR }
enum class HomeCourseAccent { BLUE, GREEN, ORANGE, PURPLE }
enum class HomeAction { OPEN_TIMETABLE, BIND_SCHEDULE, OPEN_TERMS }

/** Display fields only: no ScheduleCourse/Snapshot, navigation encoding or Compose Color escapes. */
data class HomeCourseUi(
    val occurrenceId: HomeOccurrenceId,
    val courseId: String,
    val title: String,
    val location: String,
    val teacher: String,
    val dayOfWeek: Int,
    val start: String,
    val end: String,
    val periodLabel: String,
    val accent: HomeCourseAccent,
) {
    override fun toString(): String = "HomeCourseUi(redacted)"
}

data class HomeHero(
    val mode: HomeHeroMode,
    val course: HomeCourseUi?,
    val next: HomeCourseUi?,
    val tomorrow: HomeCourseUi?,
    val hours: Long,
    val minutes: Long,
    val totalMinutes: Long,
    val periodLabel: String,
    val timeRange: String,
    val todayCount: Int,
    val lastEnd: String,
    val remainingToday: Int,
    val roomChanged: Boolean,
    val reason: HomeUnavailableReason?,
    /** Real elapsed fraction of this occurrence, not a decorative constant. */
    val progress: Float? = null,
)

data class HomeRow(val occurrence: HomeCourseUi, val isNext: Boolean) {
    val start: String get() = occurrence.start
    val end: String get() = occurrence.end
    val periodLabel: String get() = occurrence.periodLabel
}

sealed interface HomeEvent {
    data class OpenOccurrence(val id: HomeOccurrenceId) : HomeEvent
    data class ActionSelected(val action: HomeAction) : HomeEvent
}

/** Internally constructed and snapshot-backed; consumers cannot mutate its membership by a list cast. */
class HomeUiState internal constructor(
    val dateLine: String,
    val subtitle: String,
    val hero: HomeHero,
    today: List<HomeRow>,
) {
    val today: List<HomeRow> = SnapshotList(today)
    private val visible = SnapshotList((today.map { it.occurrence } +
        listOfNotNull(hero.course, hero.next, hero.tomorrow)).map { it.occurrenceId }.distinct())
    private val actions = when (hero.mode) {
        HomeHeroMode.DONE_TODAY, HomeHeroMode.FREE_TODAY -> listOf(HomeAction.OPEN_TIMETABLE)
        HomeHeroMode.UNAVAILABLE -> listOf(if (hero.reason == HomeUnavailableReason.UNBOUND)
            HomeAction.BIND_SCHEDULE else HomeAction.OPEN_TIMETABLE)
        HomeHeroMode.NO_PLAN -> listOf(HomeAction.OPEN_TERMS, HomeAction.OPEN_TIMETABLE)
        else -> emptyList()
    }

    fun eventFor(id: HomeOccurrenceId): HomeEvent.OpenOccurrence? =
        id.takeIf { it in visible }?.let { HomeEvent.OpenOccurrence(it) }
    fun eventFor(action: HomeAction): HomeEvent.ActionSelected? =
        action.takeIf { it in actions }?.let { HomeEvent.ActionSelected(it) }
    fun accepts(event: HomeEvent): Boolean = when (event) {
        is HomeEvent.OpenOccurrence -> eventFor(event.id) != null
        is HomeEvent.ActionSelected -> eventFor(event.action) != null
    }
    override fun toString(): String = "HomeUiState(mode=${hero.mode}, rows=${today.size}, redacted)"
}

/** Pure display projection. Eight-state precedence remains F → C → B → G → A → D → E → H. */
fun homeUiState(input: HomeInput): HomeUiState {
    val snapshot = input.snapshot
    val periods = input.periods
    val now = input.nowMillis
    val local = Instant.fromEpochMilliseconds(now).toLocalDateTime(TimeZone.of(SCHEDULE_TIME_ZONE_ID))
    val dateLine = AppText.Home.date(local.date.month.ordinal + 1, local.date.day,
        AppText.Home.weekday(local.date.dayOfWeek.ordinal + 1))
    val subtitle = AppText.Home.subtitle(AppText.Home.greeting(local.hour), snapshot?.term?.name.orEmpty(), input.selectedWeek)
    fun view(hero: HomeHero, rows: List<HomeRow> = emptyList()) = HomeUiState(dateLine, subtitle, hero, rows)
    fun unavailable(reason: HomeUnavailableReason) = view(emptyHero(HomeHeroMode.UNAVAILABLE, reason))

    if (!input.isBound) return unavailable(HomeUnavailableReason.UNBOUND)
    if (snapshot == null) return unavailable(HomeUnavailableReason.UNSYNCED)
    if (!snapshot.complete || snapshot.term.id.isBlank()) return unavailable(HomeUnavailableReason.PARTIAL)
    // Ambiguous identities cannot be converted into a clickable, apparently authoritative course.
    if (snapshot.courses.any { it.id.isBlank() || it.id.length > 256 || it.id.any(Char::isISOControl) } ||
        snapshot.courses.map { it.id }.distinct().size != snapshot.courses.size) return unavailable(HomeUnavailableReason.PARTIAL)
    if (scheduleWeekForDate(snapshot, scheduleSchoolDate(now)) == null) return unavailable(HomeUnavailableReason.CALENDAR)

    val occurrences = scheduleOccurrences(snapshot, periods, now - 86_400_000L, now + 14 * 86_400_000L)
    val today = scheduleTodayOccurrences(occurrences, now)
    val running = scheduleCurrentOccurrences(occurrences, now).firstOrNull()
    val next = scheduleNextOccurrence(today, now)
    val todayString = scheduleSchoolDate(now).toString()
    fun firstAfterToday() = occurrences.filter { it.date > todayString && it.startsAtMillis < it.endsAtMillis }
        .sortedWith(compareBy<ScheduleOccurrence> { it.date }.thenBy { it.startsAtMillis }).firstOrNull()
    fun display(o: ScheduleOccurrence) = o.display(periods)
    val rows = today.map { HomeRow(display(it), next?.id == it.id) }
    fun base(mode: HomeHeroMode, course: ScheduleOccurrence?, total: Long,
        extraNext: ScheduleOccurrence? = null, tomorrow: ScheduleOccurrence? = null,
        roomChanged: Boolean = false, remainingToday: Int = 0): HomeHero {
        val shown = course?.let(::display)
        return HomeHero(mode, shown, extraNext?.let(::display), tomorrow?.let(::display),
            total / 60, total % 60, total, shown?.periodLabel.orEmpty(),
            shown?.let { "${it.start}–${it.end}" }.orEmpty(), today.size,
            today.lastOrNull()?.let { display(it).end }.orEmpty(), remainingToday, roomChanged, null,
            progress = if (mode == HomeHeroMode.IN_CLASS && course != null)
                ((now - course.startsAtMillis).toDouble() / (course.endsAtMillis - course.startsAtMillis))
                    .coerceIn(0.0, 1.0).toFloat() else null)
    }

    if (running != null) return view(base(HomeHeroMode.IN_CLASS, running,
        ceilMinutes(running.endsAtMillis - now), extraNext = next), rows)
    if (next != null) {
        val untilStart = ceilMinutes(next.startsAtMillis - now)
        val lastFinished = today.lastOrNull { it.endsAtMillis <= now }
        val roomChanged = lastFinished != null && lastFinished.location.isNotBlank() &&
            next.location.isNotBlank() && lastFinished.location != next.location
        if (untilStart <= 15 || roomChanged) return view(base(HomeHeroMode.JUST_BEFORE, next, untilStart,
            roomChanged = roomChanged), rows)
        if (untilStart >= 120) return view(base(HomeHeroMode.LONG_GAP, next, untilStart,
            remainingToday = today.size - today.indexOf(next)), rows)
        return view(base(HomeHeroMode.BEFORE, next, untilStart), rows)
    }
    if (today.isNotEmpty()) return view(base(HomeHeroMode.DONE_TODAY, null, 0, tomorrow = firstAfterToday()), rows)
    val upcoming = firstAfterToday()
    if (upcoming != null) return view(base(HomeHeroMode.FREE_TODAY, null, 0, extraNext = upcoming), rows)
    return view(emptyHero(HomeHeroMode.NO_PLAN), rows)
}

private fun emptyHero(mode: HomeHeroMode, reason: HomeUnavailableReason? = null) =
    HomeHero(mode, null, null, null, 0, 0, 0, "", "", 0, "", 0, false, reason)

private fun ceilMinutes(remainingMillis: Long): Long =
    if (remainingMillis <= 0L) 0L else (remainingMillis - 1L) / 60_000L + 1L

private fun ScheduleOccurrence.display(periods: List<SchedulePeriod>): HomeCourseUi {
    val valid = this.periods.filter { it in 1..12 }.distinct().sorted()
    val first = periods.firstOrNull { it.number == valid.firstOrNull() }?.start.orEmpty()
    val end = periods.firstOrNull { it.number == valid.lastOrNull() }?.end.orEmpty()
    return HomeCourseUi(HomeOccurrenceId(id), courseId, title, location, teacher, dayOfWeek,
        first, end, if (valid.isEmpty()) "" else AppText.Home.periodRange(valid.first(), valid.last()),
        HomeCourseAccent.entries[courseId.hashCode().mod(HomeCourseAccent.entries.size)])
}

private class SnapshotList<T>(source: Collection<T>) : AbstractList<T>() {
    private val values = source.toList()
    override val size: Int get() = values.size
    override fun get(index: Int): T = values[index]
}
private class SnapshotMap<K, V>(source: Map<K, V>) : AbstractMap<K, V>() {
    private val copiedEntries = source.entries.map { entry -> object : Map.Entry<K, V> {
        override val key = entry.key
        override val value = entry.value
    } }
    override val entries: Set<Map.Entry<K, V>> = object : AbstractSet<Map.Entry<K, V>>() {
        override val size: Int get() = copiedEntries.size
        override fun iterator(): Iterator<Map.Entry<K, V>> = object : Iterator<Map.Entry<K, V>> {
            private var index = 0
            override fun hasNext(): Boolean = index < copiedEntries.size
            override fun next(): Map.Entry<K, V> = copiedEntries[index++]
        }
    }
}
