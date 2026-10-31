package io.github.betterzslf.schedule

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.text.AppText.NativeSchedule
import io.github.betterzslf.feature.schedule.SCHEDULE_TIME_ZONE_ID
import io.github.betterzslf.feature.schedule.scheduleNextBoundaryMillis
import io.github.betterzslf.feature.schedule.scheduleOccurrences
import io.github.betterzslf.feature.schedule.scheduleUndatedCourseCount
import io.github.betterzslf.feature.schedule.validateSchedulePeriods
import io.github.betterzslf.platform.ScheduleNativeCapabilities
import io.github.betterzslf.platform.ScheduleNativeOccurrence
import io.github.betterzslf.platform.ScheduleNativeOpenRequest
import io.github.betterzslf.platform.ScheduleNativePublication
import io.github.betterzslf.platform.ScheduleNativeStatus
import io.github.betterzslf.platform.ScheduleVendor
import io.github.betterzslf.platform.scheduleNativeCanPublish
import io.github.betterzslf.platform.scheduleNativeDeliveryDue
import io.github.betterzslf.platform.scheduleNativeDueArmedIds
import io.github.betterzslf.platform.ScheduleNativeReminderPlan
import io.github.betterzslf.platform.scheduleNativeFutureReminders
import io.github.betterzslf.platform.scheduleNativeRedact
import io.github.betterzslf.platform.scheduleNativeRevisionValid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val DAY = 86_400_000L
private const val FUTURE_DAYS = 14L
private const val MAX_ALARMS = 128
private const val CHANNEL = "schedule_lessons_v1"
private const val TAG_PREFIX = "schedule.lesson:"
internal const val SCHEDULE_URI_SCHEME = "better-zslf-schedule"
internal const val SCHEDULE_OPEN_PATH = "open"
internal const val SCHEDULE_ALARM_PATH = "remind"
internal const val SCHEDULE_RECONCILE_PATH = "reconcile"

@Serializable
internal data class ScheduleAlarmRecord(val id: String, val triggerAtMillis: Long, val exact: Boolean)

@Serializable
private data class ScheduleNativeStoredState(
    val ownerRevision: String? = null,
    val status: ScheduleNativeStatus = ScheduleNativeStatus.UNAUTHENTICATED,
    val publication: ScheduleNativePublication? = null,
    val alarms: List<ScheduleAlarmRecord> = emptyList(),
    val deliveredIds: List<String> = emptyList(),
)

internal data class ScheduleNativeView(
    val ownerRevision: String?,
    val status: ScheduleNativeStatus,
    val publication: ScheduleNativePublication?,
    val occurrences: List<ScheduleNativeOccurrence>,
    val nowMillis: Long,
    val stale: Boolean,
    val uncertainCount: Int,
)

/** Private per-applicationId state, no HTTP or credential access. All owner-sensitive actions share a lock. */
internal class ScheduleNativeEngine private constructor(private val context: Context) {
    companion object {
        /** Statuses whose cached timetable is trustworthy enough to describe as "now". */
        private val LIVE_STATUSES = setOf(ScheduleNativeStatus.READY, ScheduleNativeStatus.CACHED)
        @Volatile private var instance: ScheduleNativeEngine? = null
        fun from(context: Context): ScheduleNativeEngine = instance ?: synchronized(this) {
            instance ?: ScheduleNativeEngine(context.applicationContext).also { instance = it }
        }
    }
    private val preferences = context.getSharedPreferences("schedule_native_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val alarms = requireNotNull(context.getSystemService(AlarmManager::class.java))
    private val notifications = requireNotNull(context.getSystemService(NotificationManager::class.java))
    private val liveNotifications = ScheduleLiveNotifications(context)
    private val vendorLive = io.github.betterzslf.schedule.vendor.VendorLiveDispatcher()
    /** Debug-only trace of the last live-notification decision. */
    private val liveDebug = kotlinx.coroutines.flow.MutableStateFlow("none")
    private var state = load()
    private var needsRearm = true
    private val mutableCapabilities = MutableStateFlow(ScheduleNativeCapabilities(supported = true))
    val capabilities = mutableCapabilities.asStateFlow()
    private val mutableOpenRequests = MutableStateFlow<ScheduleNativeOpenRequest?>(null)
    val openRequests = mutableOpenRequests.asStateFlow()

    private fun load(): ScheduleNativeStoredState = runCatching {
        val raw = preferences.getString("state", null) ?: return@runCatching ScheduleNativeStoredState()
        if (raw.length > 2_000_000) return@runCatching ScheduleNativeStoredState()
        val saved = json.decodeFromString<ScheduleNativeStoredState>(raw)
        if (saved.ownerRevision?.let(::validRevision) == false || saved.alarms.size > MAX_ALARMS ||
            saved.publication?.ownerRevision?.let { it != saved.ownerRevision } == true) ScheduleNativeStoredState() else saved
    }.getOrDefault(ScheduleNativeStoredState())

    private fun save(next: ScheduleNativeStoredState): Boolean = runCatching {
        if (!preferences.edit().putString("state", json.encodeToString(next)).commit()) return@runCatching false
        state = next
        true
    }.getOrDefault(false)

    @Synchronized fun refreshCapabilities() { mutableCapabilities.value = readCapabilities() }

    private fun ensureChannel() {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, NativeSchedule.REMINDER_TITLE, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = NativeSchedule.REMINDER_CHANNEL_DESCRIPTION
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }
    private fun readCapabilities(): ScheduleNativeCapabilities {
        ensureChannel()
        val power = requireNotNull(context.getSystemService(PowerManager::class.java))
        val activity = requireNotNull(context.getSystemService(ActivityManager::class.java))
        return ScheduleNativeCapabilities(
            supported = true,
            notificationsGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE,
            exactAlarmsAllowed = alarms.canScheduleExactAlarms(),
            batteryRestricted = activity.isBackgroundRestricted || power.isPowerSaveMode || !power.isIgnoringBatteryOptimizations(context.packageName),
            pinWidgetsSupported = AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported,
            vendor = detectVendor(),
        )
    }

    /**
     * Manufacture strings are the only public signal for these battery managers. Matched on the
     * whole lowercase token set because a device can report e.g. "Xiaomi" under brand and a
     * chipset codename under manufacturer.
     */
    private fun detectVendor(): ScheduleVendor {
        val brand = (android.os.Build.BRAND + " " + android.os.Build.MANUFACTURER).lowercase()
        return when {
            brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") -> ScheduleVendor.XIAOMI
            brand.contains("huawei") || brand.contains("honor") -> ScheduleVendor.HUAWEI
            brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") -> ScheduleVendor.OPPO
            brand.contains("vivo") || brand.contains("iqoo") -> ScheduleVendor.VIVO
            brand.contains("meizu") || brand.contains("mblu") -> ScheduleVendor.MEIZU
            brand.contains("samsung") -> ScheduleVendor.SAMSUNG
            else -> ScheduleVendor.AOSP
        }
    }

    @Synchronized fun publish(publication: ScheduleNativePublication): Boolean {
        if (!scheduleNativeCanPublish(state.ownerRevision, publication)) return false
        val safe = scheduleNativeRedact(publication)
        if (!save(state.copy(status = safe.status, publication = safe))) return false
        return reconcile()
    }

    /** In-memory gate is invalidated before any fallible I/O; old pending intents can never post in this process. */
    @Synchronized fun clear(status: ScheduleNativeStatus, ownerRevision: String?) {
        require(status in setOf(ScheduleNativeStatus.UNAUTHENTICATED, ScheduleNativeStatus.UNBOUND, ScheduleNativeStatus.NOT_SYNCED))
        require(ownerRevision == null || validRevision(ownerRevision))
        require(status != ScheduleNativeStatus.UNAUTHENTICATED || ownerRevision == null)
        val previous = state
        val next = ScheduleNativeStoredState(ownerRevision = ownerRevision, status = status)
        state = next
        if (ownerRevision == null || mutableOpenRequests.value?.ownerRevision != ownerRevision) mutableOpenRequests.value = null
        previous.alarms.forEach { cancelAlarm(it.id, previous.ownerRevision) }
        alarms.cancel(reconcileIntent())
        cancelLessonNotifications()
        liveNotifications.clear()
        val persisted = save(next)
        val widgetsCleared = ScheduleWidgets.update(context, view(System.currentTimeMillis()))
        if (!persisted || !widgetsCleared) throw IllegalStateException("Native schedule invalidation could not be completed")
        reconcile()
    }

    @Synchronized fun reconcile(force: Boolean = false): Boolean {
        val rearm = needsRearm || force
        val now = System.currentTimeMillis()
        val projection = view(now)
        val capability = readCapabilities()
        mutableCapabilities.value = capability
        val publication = projection.publication
        val canRemind = publication != null && publication.remindersEnabled && capability.notificationsGranted &&
            projection.status in setOf(ScheduleNativeStatus.READY, ScheduleNativeStatus.CACHED, ScheduleNativeStatus.NEEDS_LOGIN)
        val future = if (canRemind) scheduleNativeFutureReminders(projection.occurrences, now,
            publication!!.reminderLeadMinutes, state.deliveredIds.toSet()).map {
            ScheduleAlarmRecord(it.id, it.triggerAtMillis, capability.exactAlarmsAllowed)
        } else emptyList()
        // A boundary broadcast can acquire the lock before an already-armed reminder broadcast.
        // Keep that same-process due record until its bounded delivery deadline, without arming a
        // past alarm, even on a normal cold process receiving that broadcast. Explicit boot/clock
        // rebuilds drop due records; only future alarms get rearmed after process creation.
        val dueIds = if (canRemind) scheduleNativeDueArmedIds(
            state.alarms.map { ScheduleNativeReminderPlan(it.id, it.triggerAtMillis) }, projection.occurrences,
            now, publication!!.reminderLeadMinutes, state.deliveredIds.toSet(), force,
        ) else emptySet()
        val due = state.alarms.filter { it.id in dueIds }
        val desired = (due + future).distinctBy { it.id }.take(MAX_ALARMS)
        val old = state.alarms.associateBy { it.id }
        val desiredIds = desired.map { it.id }.toSet()
        old.values.filter { it.id !in desiredIds || desired.firstOrNull { next -> next.id == it.id } != it }
            .forEach { cancelAlarm(it.id, state.ownerRevision) }
        var success = true
        val scheduled = desired.mapNotNull { record ->
            if (record.id in dueIds || (!rearm && old[record.id] == record)) record
            else runCatching { schedule(record, state.ownerRevision!!) }.getOrElse { success = false; null }
        }
        val aliveIds = projection.occurrences.map { it.id }.toSet()
        if (!save(state.copy(alarms = scheduled, deliveredIds = state.deliveredIds.filter { it in aliveIds }.takeLast(256)))) success = false
        if (capability.notificationsGranted) refreshVisibleNotifications(projection) else cancelLessonNotifications()
        // The live notification is derived, never invented: it only reflects today's real blocks.
        if (capability.notificationsGranted && projection.status in LIVE_STATUSES) {
            val liveBlocks = liveBlocksFor(projection, now)
            liveDebug.value = "status=" + projection.status + " blocks=" + liveBlocks.size +
                " occ=" + projection.occurrences.size
            liveNotifications.publish(projection.ownerRevision.orEmpty(), liveBlocks)
            liveDebug.value += " reason=" + liveNotifications.lastReason
            // A vendor island and the standard card describe the same class; posting both would
            // show two cards, so the vendor path wins and the standard one stands down.
            val state = liveNotifications.currentState()
            val vendorClaimed = state != null && vendorLive.publish(
                context, state, capability.vendor, capsuleLongText = projection.publication?.capsuleLongText == true)
            if (vendorClaimed) {
                liveNotifications.clear()
                liveDebug.value += " vendor=" + capability.vendor
            } else {
                vendorLive.cancel(context)
            }
        } else {
            liveDebug.value = "skip status=" + projection.status + " granted=" + capability.notificationsGranted
            liveNotifications.clear()
            vendorLive.cancel(context)
        }
        if (!ScheduleWidgets.update(context, projection)) success = false
        if (ScheduleWidgets.hasWidgets(context) || canRemind) {
            scheduleNextBoundaryMillis(projection.occurrences, now)?.let { boundary ->
                runCatching { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, boundary, reconcileIntent()) }.onFailure { success = false }
            }
        } else alarms.cancel(reconcileIntent())
        needsRearm = !success
        return success
    }

    private fun schedule(record: ScheduleAlarmRecord, revision: String): ScheduleAlarmRecord {
        val operation = reminderIntent(record.id, revision)
        if (record.exact) {
            try { alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.triggerAtMillis, operation); return record }
            catch (_: SecurityException) { /* Permission may have been revoked between capability read and dispatch. */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, record.triggerAtMillis, operation)
        return record.copy(exact = false)
    }

    @Synchronized fun onAlarm(intent: Intent) {
        val uri = intent.data ?: return
        if (uri.scheme != SCHEDULE_URI_SCHEME || uri.authority != context.packageName) return
        val parts = uri.pathSegments
        if (parts == listOf(SCHEDULE_RECONCILE_PATH)) { reconcile(); return }
        if (parts.size != 3 || parts[0] != SCHEDULE_ALARM_PATH || parts[1] != state.ownerRevision) return
        val id = parts[2]
        val record = state.alarms.firstOrNull { it.id == id } ?: return
        val projection = view(System.currentTimeMillis())
        val publication = projection.publication ?: return
        val occurrence = projection.occurrences.firstOrNull { it.id == id } ?: return
        val capability = readCapabilities()
        mutableCapabilities.value = capability
        if (!publication.remindersEnabled || !capability.notificationsGranted || id in state.deliveredIds ||
            projection.status !in setOf(ScheduleNativeStatus.READY, ScheduleNativeStatus.CACHED, ScheduleNativeStatus.NEEDS_LOGIN) ||
            !scheduleNativeDeliveryDue(occurrence, record.triggerAtMillis, publication.reminderLeadMinutes, projection.nowMillis)) { reconcile(); return }
        // Persist delivery before posting. A replay never emits another alert for this continuous block.
        if (!save(state.copy(deliveredIds = (state.deliveredIds + id).distinct().takeLast(256)))) return
        postNotification(occurrence, state.ownerRevision!!)
        reconcile()
    }

    private fun view(now: Long): ScheduleNativeView {
        val publication = state.publication?.takeIf { it.ownerRevision == state.ownerRevision }
        val source = publication?.snapshot
        val occurrences = if (source == null || !source.complete) emptyList() else
            scheduleOccurrences(source, publication!!.periods, now - DAY, now + FUTURE_DAYS * DAY).map {
                ScheduleNativeOccurrence(it.id, it.title, it.location, it.date, it.startsAtMillis, it.endsAtMillis)
            }
        return ScheduleNativeView(state.ownerRevision, state.status, publication, occurrences, now,
            state.status in setOf(ScheduleNativeStatus.CACHED, ScheduleNativeStatus.NEEDS_LOGIN) ||
                source?.syncedAtMillis?.let { it > 0 && now - it > DAY } == true,
            publication?.uncertainCount ?: 0)
    }

    private fun validRevision(value: String): Boolean = scheduleNativeRevisionValid(value)

    private fun reminderIntent(id: String, revision: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ScheduleAlarmReceiver::class.java).setData(uri(SCHEDULE_ALARM_PATH, revision, id)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun cancelAlarm(id: String, revision: String?) {
        if (revision == null) return
        val operation = PendingIntent.getBroadcast(context, 0,
            Intent(context, ScheduleAlarmReceiver::class.java).setData(uri(SCHEDULE_ALARM_PATH, revision, id)),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarms.cancel(operation); operation.cancel()
    }
    private fun reconcileIntent(): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, ScheduleAlarmReceiver::class.java).setData(uri(SCHEDULE_RECONCILE_PATH)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun uri(vararg parts: String): Uri = Uri.Builder().scheme(SCHEDULE_URI_SCHEME).authority(context.packageName).apply { parts.forEach(::appendPath) }.build()

    fun openIntent(revision: String?, id: String? = null): PendingIntent {
        val data = uri(SCHEDULE_OPEN_PATH, revision ?: "none", id ?: "root")
        val intent = Intent().setClassName(context.packageName, "io.github.betterzslf.MainActivity")
            .setAction(context.packageName + ".schedule.OPEN").setData(data)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    @Synchronized fun handleOpenIntent(intent: Intent?): Boolean {
        val data = intent?.data ?: return false
        if (intent.action != context.packageName + ".schedule.OPEN" || data.scheme != SCHEDULE_URI_SCHEME || data.authority != context.packageName) return false
        val parts = data.pathSegments
        if (parts.size != 3 || parts[0] != SCHEDULE_OPEN_PATH) return false
        val revision = parts[1].takeUnless { it == "none" }
        if (revision != state.ownerRevision) return false
        val id = parts[2].takeUnless { it == "root" }
        if (id != null && view(System.currentTimeMillis()).occurrences.none { it.id == id }) return false
        mutableOpenRequests.value = ScheduleNativeOpenRequest(revision, id)
        return true
    }
    @Synchronized fun consumeOpenRequest() { mutableOpenRequests.value = null }

    private fun postNotification(occurrence: ScheduleNativeOccurrence, revision: String) {
        ensureChannel()
        val icon = context.resources.getIdentifier("schedule_ic_notification", "drawable", context.packageName).takeIf { it != 0 }
            ?: android.R.drawable.ic_dialog_info
        val text = NativeSchedule.notificationSummary(ScheduleWidgets.clock(occurrence.startsAtMillis), occurrence.location)
        val publicVersion = Notification.Builder(context, CHANNEL).setSmallIcon(icon).setContentTitle(NativeSchedule.REMINDER_TITLE).setContentText(NativeSchedule.OPEN_COURSE).build()
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(icon).setContentTitle(occurrence.title)
            .setContentText(text).setContentIntent(openIntent(revision, occurrence.id)).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_EVENT).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
            .setOnlyAlertOnce(true).setShowWhen(true).setWhen(occurrence.startsAtMillis).build()
        runCatching { notifications.notify(TAG_PREFIX + revision + ":" + occurrence.id, 1, notification) }
    }
    private fun cancelLessonNotifications() { notifications.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }.forEach { notifications.cancel(it.tag, it.id) } }

    /**
     * Today's real class blocks for the live notification, taken from the same occurrence
     * projection the widgets and reminders use. Nothing is extrapolated: an occurrence only
     * appears if feature.schedule could place it on a real date for this publication.
     */


    /**
     * Post a synthetic 90-second "in class" card through the same rendering path a real class uses.
     *
     * This deliberately does not build a special demo notification: the point of a test button is
     * to show what the real mechanism will look like on this ROM, including whether a vendor island
     * accepts the payload. A separate demo object would prove nothing.
     *
     * It bypasses only the schedule lookup, not the rendering: the state is built by
     * feature.schedule's own [io.github.betterzslf.feature.schedule.scheduleLiveState] from a
     * synthetic block, so the progress fraction, chip text and countdown wording are the real ones.
     */
    @Synchronized
    fun testLiveNotification(): Boolean {
        val capability = readCapabilities()
        if (!capability.notificationsGranted) {
            liveDebug.value = "test: notifications not granted"
            return false
        }
        // Build the state with every displayed field populated. Driving this through
        // scheduleLiveState() from a bare block left teacher/location/period empty, so the vendor
        // card rendered with three blank columns -- exactly what the matrix probe showed as fine,
        // because the matrix constructs its state directly.
        val state = io.github.betterzslf.schedule.vendor.VendorDiagnostics.mockState(
            AppText.Schedule.LIVE_TEST_COURSE,
        )
        val vendorClaimed = vendorLive.publish(context, state, capability.vendor)
        if (vendorClaimed) {
            // Do NOT call liveNotifications.clear() here. It cancels the standard card under the
            // same tag, and on a vendor ROM the vendor card is a different notification -- so the
            // clear ran immediately after a successful post and the user saw nothing. The vendor
            // dispatcher already stands the standard card down on its own path.
            liveDebug.value = "test: vendor=" + capability.vendor
            return true
        }
        // No vendor island took it: show the standard card so the button still demonstrates
        // something on Pixel / OPPO / Samsung / Honor.
        val posted = liveNotifications.publishTest(state)
        liveDebug.value = "test: standard posted=" + posted + " reason=" + liveNotifications.lastReason
        return posted
    }

    /** Debug-only: the live-notification decision trace. */
    fun liveTrace(): String = liveDebug.value

    private fun liveBlocksFor(view: ScheduleNativeView, nowMillis: Long): List<io.github.betterzslf.feature.schedule.ScheduleLiveBlock> {
        val today = io.github.betterzslf.feature.schedule.scheduleSchoolDate(nowMillis).toString()
        val publication = view.publication
        // Teachers live beside the snapshot, not inside it, so the redactor can drop them from
        // storage while the card still shows them. Periods come from the course itself.
        val courses = publication?.snapshot?.courses.orEmpty().associateBy { it.id }
        return view.occurrences
            .filter { it.date == today }
            .map { occurrence ->
                val course = courses[occurrence.id] ?: courses.values.firstOrNull { it.title == occurrence.title }
                io.github.betterzslf.feature.schedule.ScheduleLiveBlock(
                    id = occurrence.id, title = occurrence.title, location = occurrence.location,
                    startsAtMillis = occurrence.startsAtMillis, endsAtMillis = occurrence.endsAtMillis,
                    teacher = publication?.displayTeachers?.get(occurrence.id).orEmpty(),
                    periods = course?.periods.orEmpty(),
                )
            }
    }

    private fun refreshVisibleNotifications(view: ScheduleNativeView) {
        notifications.activeNotifications.filter { it.tag?.startsWith(TAG_PREFIX) == true }.forEach { item ->
            val prefix = TAG_PREFIX + (view.ownerRevision ?: "") + ":"
            val id = item.tag?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            val occurrence = view.occurrences.firstOrNull { it.id == id && it.endsAtMillis > view.nowMillis }
            if (occurrence == null || view.publication?.remindersEnabled != true) notifications.cancel(item.tag, item.id)
            else postNotification(occurrence, view.ownerRevision!!)
        }
    }
}

/**
 * Debug-only diagnostic: why the last live-notification reconcile did or did not post.
 * Top-level and public so the debug probe (a separate module) can read it without widening
 * the internal engine type or the release-facing bridge contract.
 */
fun scheduleLiveDiagnostic(context: android.content.Context): String =
    ScheduleNativeEngine.from(context).liveTrace()
