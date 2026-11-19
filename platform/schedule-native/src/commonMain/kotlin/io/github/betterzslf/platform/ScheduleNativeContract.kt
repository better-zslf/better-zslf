package io.github.betterzslf.platform

import io.github.betterzslf.feature.schedule.SchedulePeriod
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.ScheduleTimedOccurrence
import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import kotlinx.coroutines.flow.StateFlow
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
    /**
     * Period numbers of this run, carried from the source occurrence.
     *
     * Not derivable afterwards: the occurrence id is a stable hash, while the course map is keyed
     * by the source course id, so looking periods up by occurrence id always missed and the live
     * card silently dropped its 节次 column.
     */
    val periods: List<Int> = emptyList(),
    /** Source course id, the key the teacher map actually uses. */
    val courseId: String = "",
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
    /**
     * Whether the vendor capsule should show the course name beside the minutes.
     *
     * Display-only, like [displayTeachers]: which form fits depends on the ROM's pill width, so it
     * is a user preference rather than something the platform decides.
     */
    val capsuleLongText: Boolean = false,
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
    /**
     * Whether the platform will even consider promoting this app's live notification.
     *
     * `NotificationManager.canPostPromotedNotifications()` on API 36+. False means the user has not
     * granted the promoted-notification appop, which on ColorOS surfaces as an empty entry in
     * 流体云 -- indistinguishable, from the outside, from "this app does not support it".
     */
    val promotedAllowed: Boolean = false,
    /**
     * Whether the last posted live card actually carried promotion characteristics.
     *
     * `Notification.hasPromotableCharacteristics()`. Kept beside [promotedAllowed] because the two
     * failures look identical to a user but need opposite fixes: permission missing versus the
     * notification shape being wrong.
     */
    val lastCardPromotable: Boolean = false,
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
    /**
     * Open the system screen that grants promoted-notification access.
     *
     * On ColorOS this is the per-app 流体云 control; on AOSP it is the app's live-notification
     * permission. Falls back to the ordinary notification settings when the platform has no such
     * screen, so the button never dead-ends on an older device.
     */
    fun openPromotedNotificationSettings(): Boolean
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
    fun testLiveNotification(longCapsule: Boolean = false): Boolean = false
    /**
     * Show the between-classes card so its styling can be compared with the in-class one.
     *
     * @param longCapsule whether the status-bar pill should carry the course name as well.
     */
    fun testBreakCard(longCapsule: Boolean): Boolean = false
    /**
     * Replay class -> break -> class on one notification, one step every two minutes.
     *
     * A transition cannot be judged from a single frame and the real card only reposts on a
     * schedule boundary, so this walks the sequence for the user instead of asking them to wait.
     */
    fun testLiveSequence(longCapsule: Boolean) = Unit
    /** Withdraw every demonstration notification this app posted. */
    fun clearTestNotifications() = Unit
    /** Releases app-side observers only; healthy published reminders survive app UI disposal. */
    fun close()
}

/** Match only a current, bounded opaque lease; timetable navigation keeps its own slot checks. */
fun ScheduleNativeOpenRequest.matchesRevision(revision: String): Boolean =
    scheduleNativeRevisionValid(revision) && ownerRevision == revision
