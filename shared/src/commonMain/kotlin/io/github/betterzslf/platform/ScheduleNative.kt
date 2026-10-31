package io.github.betterzslf.platform

import io.github.betterzslf.feature.schedule.SchedulePeriod
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.ScheduleTimedOccurrence
import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import io.github.betterzslf.feature.schedule.scheduleUndatedCourseCount
import io.github.betterzslf.feature.schedule.validateSchedulePeriods
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** Native never receives an account, password, cookie, HTML, or an authenticated client. */
@Serializable
enum class ScheduleNativeStatus { UNAUTHENTICATED, UNBOUND, NOT_SYNCED, READY, CACHED, NEEDS_LOGIN }

@Serializable
enum class ScheduleWidgetKind { TODAY, NEXT }

/** An already-normalized continuous class block from feature.schedule, not a new timetable model. */
@Serializable
data class ScheduleNativeOccurrence(
    override val id: String,
    val title: String,
    val location: String = "",
    override val date: String,
    override val startsAtMillis: Long,
    override val endsAtMillis: Long,
) : ScheduleTimedOccurrence {
    override fun toString(): String = "ScheduleNativeOccurrence(redacted)"
}

/**
 * A normalized recurrence seed, not credentials. Android redacts teacher/raw week text/remarks and
 * warnings before persisting, then calls feature.schedule for its bounded future occurrence window
 * after boot, midnight, timezone changes and settings changes. It never invents week/date mappings.
 * ownerRevision is a locally generated opaque binding/session revision, NEVER a student/account id.
 */
@Serializable
data class ScheduleNativePublication(
    val ownerRevision: String,
    val status: ScheduleNativeStatus,
    val snapshot: ScheduleSnapshot? = null,
    val periods: List<SchedulePeriod> = emptyList(),
    val timeZoneId: String = SCHEDULE_TIME_ZONE_ID,
    val remindersEnabled: Boolean = false,
    val reminderLeadMinutes: Int = 10,
    val uncertainCount: Int = 0,
    /**
     * Display-only instructor names keyed by course id.
     *
     * Deliberately a separate field rather than relaxing [scheduleNativeRedact]: the native
     * scheduler must keep persisting only what it needs to regenerate occurrences, and a teacher
     * name is not that. A vendor island wants to *show* it, so the runtime hands it over here and
     * the redactor drops this map before the record reaches storage.
     */
    val displayTeachers: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "ScheduleNativePublication(redacted)"
}

data class ScheduleNativeCapabilities(
    val supported: Boolean = false,
    val notificationsGranted: Boolean = false,
    val exactAlarmsAllowed: Boolean = false,
    val batteryRestricted: Boolean = false,
    val pinWidgetsSupported: Boolean = false,
    /**
     * Non-AOSP battery manager family, or [ScheduleVendor.AOSP]. Several vendors freeze background
     * work in ways no public API reports, so the reminder screen names the concrete settings the
     * user has to change instead of leaving them with a generic "restricted" hint.
     */
    val vendor: ScheduleVendor = ScheduleVendor.AOSP,
)

/**
 * The vendor battery-management families whose extra restrictions break ongoing notifications and
 * background refresh. Detected from the manufacturer string because there is no public API.
 */
enum class ScheduleVendor { AOSP, XIAOMI, HUAWEI, OPPO, VIVO, MEIZU, SAMSUNG, ONEPLUS }

/** Only local opaque identifiers; the app rechecks the current owner revision before navigating. */
data class ScheduleNativeOpenRequest(val ownerRevision: String?, val occurrenceId: String? = null)

interface ScheduleNativeBridge {
    val supported: Boolean
    val capabilities: StateFlow<ScheduleNativeCapabilities>
    val openRequests: StateFlow<ScheduleNativeOpenRequest?>
    fun refreshCapabilities()
    /** These return whether the UI/settings request was started, not whether permission was granted. */
    fun requestNotificationPermission(): Boolean
    fun openNotificationSettings(): Boolean
    fun openExactAlarmSettings(): Boolean
    fun openBatteryOptimizationSettings(): Boolean
    fun requestPinWidget(kind: ScheduleWidgetKind): Boolean
    /** Returns false for a stale/missing owner lease or invalid/out-of-bounds publication. */
    fun publish(publication: ScheduleNativePublication): Boolean
    /**
     * Synchronously cancel reminders, notifications and old widget data before a change of owner or
     * binding. To establish the next lease, supply its opaque ownerRevision and UNBOUND/NOT_SYNCED.
     * publish never changes the owner lease; old async publications therefore fail closed.
     */
    fun clear(status: ScheduleNativeStatus = ScheduleNativeStatus.UNAUTHENTICATED, ownerRevision: String? = null)
    fun consumeOpenRequest()
    /**
     * Fire one synthetic 90-second live card so the user can see what the island looks like
     * without waiting for a real class. Returns false when no mechanism can post on this device.
     *
     * Default false keeps the iOS/JVM bridges honest: they have no ongoing-notification surface, so
     * advertising a test hook there would be a lie the UI would have to work around.
     */
    fun testLiveNotification(): Boolean = false
    /**
     * Post one notification per vendor-payload combination so a single screenshot shows which
     * variant the ROM honours. Only meaningful where a vendor island exists; default is a no-op.
     * @return how many notifications the system accepted
     */
    fun testVendorMatrix(): Int = 0
    /** Post exactly one vendor variant by index, clearing the others, for an unambiguous look. */
    fun testVendorVariant(index: Int): Boolean = false
    /** Labels of the vendor variants, in posting order. */
    fun vendorVariantLabels(): List<String> = emptyList()
    /** Withdraw every notification posted by [testVendorMatrix]. */
    fun clearVendorMatrix() = Unit
    /** Releases app-side observers only; healthy published reminders survive app UI disposal. */
    fun close()
}

expect fun createScheduleNativeBridge(): ScheduleNativeBridge

internal class UnsupportedScheduleNativeBridge : ScheduleNativeBridge {
    override val supported: Boolean = false
    override val capabilities = MutableStateFlow(ScheduleNativeCapabilities()).asStateFlow()
    private val requests = MutableStateFlow<ScheduleNativeOpenRequest?>(null)
    override val openRequests = requests.asStateFlow()
    override fun refreshCapabilities() = Unit
    override fun requestNotificationPermission(): Boolean = false
    override fun openNotificationSettings(): Boolean = false
    override fun openExactAlarmSettings(): Boolean = false
    override fun openBatteryOptimizationSettings(): Boolean = false
    override fun requestPinWidget(kind: ScheduleWidgetKind): Boolean = false
    override fun publish(publication: ScheduleNativePublication): Boolean = false
    override fun clear(status: ScheduleNativeStatus, ownerRevision: String?) = Unit
    override fun consumeOpenRequest() { requests.value = null }
    override fun close() = Unit
}

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

/** No backfill during refresh. A previously armed zero-lead alarm gets a bounded OS dispatch grace only. */
internal fun scheduleNativeDeliveryDue(occurrence: ScheduleNativeOccurrence, triggerAtMillis: Long, leadMinutes: Int, nowMillis: Long): Boolean {
    if (leadMinutes !in 0..120 || triggerAtMillis != occurrence.startsAtMillis - leadMinutes * 60_000L || nowMillis < triggerAtMillis) return false
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
