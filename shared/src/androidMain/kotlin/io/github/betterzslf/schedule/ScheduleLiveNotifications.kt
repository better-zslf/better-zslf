package io.github.betterzslf.schedule

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.betterzslf.core.text.AppText.NativeSchedule
import io.github.betterzslf.feature.schedule.ScheduleLiveBlock
import io.github.betterzslf.feature.schedule.ScheduleLivePhase
import io.github.betterzslf.feature.schedule.ScheduleLiveState
import io.github.betterzslf.feature.schedule.scheduleLiveState
import io.github.betterzslf.platform.ScheduleNativeStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The ongoing "live" timetable notification: in class, or counting down to the next class.
 *
 * This is a separate channel and a separate record from the one-shot lesson reminders, because
 * the two have different lifecycles: a reminder fires once and is dismissed, while this stays up
 * for the duration of the class and must be replaced, not re-posted. Keeping them apart also means
 * disabling this feature cannot suppress a reminder the user asked for.
 *
 * Android 16 (API 36) promotes a ProgressStyle notification with POST_PROMOTED_NOTIFICATIONS into
 * the Live Updates surface. That permission is optional to declare, and older versions ignore the
 * style, so the same content renders as an ordinary ongoing notification below API 36.
 */
/**
 * Identity of the current live stage: course title plus start instant. Used to remember that the
 * user dismissed exactly this stage, without persisting anything about the course itself.
 */
internal fun ScheduleLiveState.key(): String = "$startsAtMillis:$phase"

internal class ScheduleLiveNotifications(private val context: Context) {
    private val notifications by lazy { context.getSystemService(NotificationManager::class.java) }
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredLive(val revision: String, val blocks: List<Block>) {
        @Serializable
        data class Block(
            val id: String, val title: String, val location: String,
            val startsAtMillis: Long, val endsAtMillis: Long,
        )
    }

    private val store get() = context.getSharedPreferences("better_zslf_schedule_live", Context.MODE_PRIVATE)

    /** The class blocks of the day being watched, so a later reconcile needs no credentials. */
    fun publish(revision: String, blocks: List<ScheduleLiveBlock>) {
        val payload = StoredLive(revision, blocks.map {
            StoredLive.Block(it.id, it.title, it.location, it.startsAtMillis, it.endsAtMillis)
        })
        store.edit().putString(KEY, json.encodeToString(payload)).commit()
        reconcile(System.currentTimeMillis())
    }

    fun clear() {
        store.edit().remove(KEY).commit()
        runCatching { notifications.cancel(TAG, NOTIFICATION_ID) }
    }

    /** Recompute and post/update/cancel. Safe to call at every boundary tick. */
    fun reconcile(nowMillis: Long) {
        val stored = store.getString(KEY, null)?.let {
            runCatching { json.decodeFromString<StoredLive>(it) }.getOrNull()
        }
        if (stored == null || stored.blocks.isEmpty()) {
            runCatching { notifications.cancel(TAG, NOTIFICATION_ID) }
            return
        }
        val state = scheduleLiveState(stored.blocks.map {
            ScheduleLiveBlock(it.id, it.title, it.location, it.startsAtMillis, it.endsAtMillis)
        }, nowMillis)
        lastState = state.takeIf { it.active }
        if (!state.active || !notificationsEnabled() || dismissed(state)) {
            lastReason = when {
                !state.active -> "inactive"
                !notificationsEnabled() -> "disabled"
                else -> "dismissed"
            }
            runCatching { notifications.cancel(TAG, NOTIFICATION_ID) }
            return
        }
        // Record the exact platform outcome; a swallowed post failure is undebuggable.
        val result = runCatching { notifications.notify(TAG, NOTIFICATION_ID, build(state, stored.revision)) }
        lastPostFailed = result.isFailure
        lastReason = result.exceptionOrNull()?.let { it::class.java.simpleName + ": " + (it.message ?: "") }
            ?: ("posted:" + state.phase.name)
    }

    /**
     * Post a caller-supplied state directly, bypassing the stored-block lookup.
     *
     * Only the in-app test button uses this: it has already resolved a synthetic state through the
     * real state machine, and needs to see the real notification. Nothing else should call it,
     * because it skips the dismissal bookkeeping that [reconcile] maintains.
     */
    fun publishTest(state: ScheduleLiveState): Boolean {
        if (!notificationsEnabled()) {
            lastReason = "test: notifications disabled"
            return false
        }
        val result = runCatching { notifications.notify(TAG, NOTIFICATION_ID, build(state, "test")) }
        lastPostFailed = result.isFailure
        lastReason = result.exceptionOrNull()?.let { it::class.java.simpleName + ": " + (it.message ?: "") }
            ?: ("test-posted:" + state.phase.name)
        return result.isSuccess
    }

    /** True when the most recent [reconcile] tried to post and the platform refused it. */
    var lastPostFailed: Boolean = false
        private set

    /** Diagnostic for the probe: why the last reconcile did or did not post. */
    var lastReason: String = "never"
        private set

    /**
     * The state most recently resolved by [reconcile], or null when nothing is live.
     *
     * Exposed so the vendor dispatcher can render the same class block without re-deriving the
     * schedule: two independent derivations would disagree at boundaries and could show one card
     * saying "in class" while the other counts down.
     */
    fun currentState(): ScheduleLiveState? = lastState

    private var lastState: ScheduleLiveState? = null

    /**
     * True when the user swiped this exact stage away. Clearing happens by comparing keys: once a
     * later stage posts, the remembered key no longer matches and the reminder behaves normally
     * again, so a dismissal never silences tomorrow's class.
     */
    private fun dismissed(state: ScheduleLiveState): Boolean {
        val prefs = context.getSharedPreferences(TAG, Context.MODE_PRIVATE)
        return prefs.getString(DISMISSED_KEY, null) == state.key()
    }

    private fun notificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun build(state: ScheduleLiveState, revision: String): Notification {
        ensureChannel()
        val icon = context.resources.getIdentifier("schedule_ic_notification", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info
        // The public (lock screen) version never names the course: it is only visible as content
        // once the user unlocks, matching the private visibility of the reminder notifications.
        val publicVersion = Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(NativeSchedule.LIVE_TITLE)
            .setContentText(NativeSchedule.OPEN_TIMETABLE)
            .build()
        val inClass = state.phase == ScheduleLivePhase.IN_CLASS
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(state.title)
            .setContentText(state.text)
            .setContentIntent(openIntent(revision))
            .setDeleteIntent(dismissIntent(state))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // The card brings its own ticker to the deadline, so no per-minute re-post is needed
            // and the value stays truthful even if the process is frozen between boundaries.
            .setShowWhen(true)
            .setWhen(if (inClass) state.endsAtMillis else state.startsAtMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
        // Safety net: if both the boundary alarm and the foreground refresh are missed (frozen
        // process, vendor battery manager), the system removes the stale card by itself instead
        // of leaving a countdown that has gone past zero.
        val deadline = if (inClass) state.endsAtMillis else state.startsAtMillis
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1_000L)
        builder.setTimeoutAfter(remaining)
        if (Build.VERSION.SDK_INT >= 36) {
            // API 36: the chip is the status-bar capsule. It has the highest precedence of any
            // chip content, so an explicit value wins over the chronometer's own rendering.
            runCatching { builder.setShortCriticalText(state.chip) }
        }
        applyProgressStyle(builder, state)
        // Promotion is an explicit request, not a side effect of using ProgressStyle: without
        // this extra the notification stays a plain ongoing one even on Android 16.
        builder.addExtras(android.os.Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        })
        return builder.build()
    }

    /**
     * On API 36 the promoted Live Update treatment needs ProgressStyle. Below that the style class
     * does not exist, so the builder keeps the plain ongoing notification; the runtime check is a
     * version test, not a compile-time assumption, because minSdk is 33.
     */
    private fun applyProgressStyle(builder: Notification.Builder, state: ScheduleLiveState) {
        if (Build.VERSION.SDK_INT < 36) return
        val progress = state.progress ?: return
        runCatching {
            val percent = (progress * 100f).toInt().coerceIn(0, 100)
            val done = percent
            val left = 100 - percent
            val night = (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            // Two segments that always sum to 100: the finished part in the accent colour and the
            // remainder in a track that is dimmed in dark mode, otherwise a pale track outshines
            // the progress it is supposed to sit behind.
            val accent = if (night) 0xFF5B9DFF.toInt() else 0xFF3382FF.toInt()
            val track = if (night) 0xFF2A3A55.toInt() else 0xFFD6E5FF.toInt()
            val style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress(done)
                .setProgressSegments(
                    buildList {
                        if (done > 0) add(Notification.ProgressStyle.Segment(done).setColor(accent))
                        if (left > 0) add(Notification.ProgressStyle.Segment(left).setColor(track))
                    },
                )
            builder.setStyle(style)
        }
    }

    /**
     * A dismissal is not a timeout. `setTimeoutAfter` also fires the delete intent, so without
     * telling the two apart a stage change would be recorded as "the user swiped it away" and the
     * matching class would stay silent for its whole duration.
     */
    private fun dismissIntent(state: ScheduleLiveState): PendingIntent {
        val intent = Intent(context, ScheduleLiveDismissReceiver::class.java)
            .putExtra(EXTRA_DISMISS_KEY, state.key())
            .putExtra(EXTRA_DISMISS_DEADLINE, state.endsAtMillis)
        return PendingIntent.getBroadcast(context, 3, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel() {
        if (notifications.getNotificationChannel(CHANNEL) != null) return
        // IMPORTANCE_DEFAULT, not LOW: a LOW channel is filed under the "Silent" section, which
        // some launchers collapse entirely, making an ongoing class notification invisible. The
        // channel still stays silent because it carries no sound and no vibration.
        val channel = NotificationChannel(CHANNEL, NativeSchedule.LIVE_CHANNEL_TITLE,
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = io.github.betterzslf.core.text.AppText.Schedule.LIVE_CHANNEL_DESCRIPTION
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun openIntent(revision: String): PendingIntent {
        val data = Intent().setClassName(context.packageName, "io.github.betterzslf.MainActivity")
            .setAction(context.packageName + ".schedule.OPEN")
            .setData(android.net.Uri.Builder().scheme(SCHEDULE_URI_SCHEME).authority(context.packageName)
                .appendPath(SCHEDULE_OPEN_PATH).appendPath(revision).appendPath("root").build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 2, data,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** True when the feature can post at all; used to decide whether to arm boundary alarms. */
    fun available(): Boolean = Build.VERSION.SDK_INT >= 26

    internal companion object {
        const val CHANNEL = "schedule_live_v1"
        const val TAG = "schedule.live"
        const val NOTIFICATION_ID = 1
        const val KEY = "live.v1"
        const val DISMISSED_KEY = "dismissed.v1"
        /** Broadcast extras shared with the dismiss receiver. */
        const val EXTRA_DISMISS_KEY = "better-zslf.live.dismiss_key"
        const val EXTRA_DISMISS_DEADLINE = "better-zslf.live.dismiss_deadline"
        /** Android 16 promotion request extra; the platform constant is not public API. */
        const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}

/**
 * Records that the user swiped the live card away, but only when it really was a dismissal.
 *
 * `setTimeoutAfter` delivers the same delete intent when the stage expires, so the two cases are
 * separated by comparing the deadline carried in the intent with the clock: an expiry arrives at
 * (or just after) the deadline, a dismissal arrives well before it. Mid-stage dismissals are
 * remembered per stage key, so that class does not come back until the next one starts.
 */
class ScheduleLiveDismissReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(ScheduleLiveNotifications.EXTRA_DISMISS_KEY) ?: return
        val deadline = intent.getLongExtra(ScheduleLiveNotifications.EXTRA_DISMISS_DEADLINE, 0L)
        val timedOut = deadline > 0L && System.currentTimeMillis() >= deadline - TIMEOUT_SLACK_MILLIS
        if (!timedOut) {
            context.getSharedPreferences(ScheduleLiveNotifications.TAG, Context.MODE_PRIVATE)
                .edit().putString(ScheduleLiveNotifications.DISMISSED_KEY, key).apply()
            ScheduleNativeEngine.from(context).reconcile()
        }
    }

    private companion object {
        /** Anything arriving this close to the deadline is an expiry, not a user action. */
        const val TIMEOUT_SLACK_MILLIS = 1_500L
    }
}
