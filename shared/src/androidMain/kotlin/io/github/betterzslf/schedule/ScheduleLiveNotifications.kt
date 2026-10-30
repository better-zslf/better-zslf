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
        if (!state.active || !notificationsEnabled()) {
            lastReason = if (!state.active) "inactive" else "disabled"
            runCatching { notifications.cancel(TAG, NOTIFICATION_ID) }
            return
        }
        // Record the exact platform outcome; a swallowed post failure is undebuggable.
        val result = runCatching { notifications.notify(TAG, NOTIFICATION_ID, build(state, stored.revision)) }
        lastPostFailed = result.isFailure
        lastReason = result.exceptionOrNull()?.let { it::class.java.simpleName + ": " + (it.message ?: "") }
            ?: ("posted:" + state.phase.name)
    }

    /** True when the most recent [reconcile] tried to post and the platform refused it. */
    var lastPostFailed: Boolean = false
        private set

    /** Diagnostic for the probe: why the last reconcile did or did not post. */
    var lastReason: String = "never"
        private set

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
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(state.title)
            .setContentText(state.text)
            .setContentIntent(openIntent(revision))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (state.phase == ScheduleLivePhase.IN_CLASS) {
            // A real countdown to the end of class. The framework ticks it, so the notification
            // stays truthful between boundary alarms instead of freezing "剩余 30 分钟".
            builder.setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setShowWhen(true)
                .setWhen(state.endsAtMillis)
        } else {
            // Counting down to a start time is a positive count-up, which the platform does not
            // offer; show the start time as a static stamp and leave the text as the "in N min"
            // summary computed at reconcile time (refreshed at each boundary).
            builder.setShowWhen(true).setWhen(state.startsAtMillis)
        }
        applyProgressStyle(builder, state)
        return builder.build()
    }

    /**
     * On API 36 the promoted Live Update treatment needs ProgressStyle. Below that the style class
     * does not exist, so the builder keeps the plain ongoing notification; the runtime check is a
     * version test, not a compile-time assumption, because minSdk is 33.
     */
    private fun applyProgressStyle(builder: Notification.Builder, state: ScheduleLiveState) {
        if (Build.VERSION.SDK_INT < 36) return
        val progress = state.progress
        if (progress == null) return
        runCatching {
            val style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress(100)
                .setProgressPoints(
                    listOf(
                        Notification.ProgressStyle.Point(0),
                        Notification.ProgressStyle.Point(100),
                    ),
                )
            // ProgressStyle tracks whole percent, so the fraction is converted once, here.
            val percent = (progress * 100f).toInt().coerceIn(0, 100)
            style.setProgress(percent)
            builder.setStyle(style)
        }
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

    private companion object {
        const val CHANNEL = "schedule_live_v1"
        const val TAG = "schedule.live"
        const val NOTIFICATION_ID = 1
        const val KEY = "live.v1"
    }
}
