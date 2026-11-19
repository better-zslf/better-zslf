package io.github.betterzslf.schedule.vendor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import io.github.betterzslf.core.text.AppText.NativeSchedule
import io.github.betterzslf.core.text.AppText.Schedule

/**
 * vivo OriginOS 原子通知 / 原子岛 renderer.
 *
 * Unlike the Xiaomi and Meizu paths this one is built only from parameters vivo publishes, so
 * nothing below is guessed. vivo drives the island from a plain notification whose extras carry a
 * `notification.superx.*` contract:
 *
 * - `operation` 0 create, 1 update, 2 finish -- the lifecycle is explicit, not inferred by vivo
 * - `showNotify` true, so a rejected payload degrades to the ordinary notification instead of
 *   showing nothing at all
 * - `template` 2, the progress-visualisation template, because a class has a known start and end
 * - `scene` the scenario vivo keys its permission and user filters on
 * - `baseInfos` / `infos` / `shortInfos` the visible text, the progress bar and the small card
 * - `capsule` / `island` the status-bar pill and the island itself
 *
 * ## The scene is borrowed, and that is a real limitation
 *
 * vivo's published scene list is MOVIE, HEALTH_REGISTER, TAXI, TAKEOUT, DELIEVERY, NAVIGATION,
 * CAR_STATE, **METTING**, TRAIN, FLIGHT and a few more: **there is no education scenario**. A
 * campus class block is a scheduled, time-boxed meeting, so this renderer declares the class as
 * `METTING` (vivo's own spelling, not a typo for "MEETING"). That is a deliberate compromise with
 * two known failure modes: if vivo ever validates that the scene semantically matches the content,
 * or if the user restricts the 会议日程 scenario in vivo's island settings, the island silently
 * stops appearing. Both are handled by [publish] returning false, so the caller keeps the standard
 * notification rather than leaving the user without a card.
 *
 * ## Why no RemoteViews
 *
 * Nothing here sets a custom content view. Android 16 forbids `RemoteViews` inside a promoted
 * ongoing notification, and the standard Live Update path in
 * [io.github.betterzslf.schedule.ScheduleLiveNotifications] must stay free of them, so the vendor
 * payload and the native card are two separate notifications that never share an object.
 */
internal class VivoLiveRenderer : VendorLiveRenderer {

    /**
     * Identity of the stage currently on the island (title + phase deadline). vivo needs an
     * explicit create before updates, and a new stage must start over with `operation = 0`
     * instead of updating the previous class's card.
     */
    private var publishedStage: String? = null

    /** Last published copy, reused for the finish payload so vivo can match the island it ends. */
    private var lastTitle: String = ""
    private var lastContent: String = ""

    override fun isSupported(context: Context): Boolean = isVivoBrand() && canPost(context)

    override fun publish(context: Context, data: VendorLiveData): Boolean {
        // Fail closed: the dispatcher removes the standard card as soon as this returns true, so a
        // payload the system will ignore must be reported as a failure instead.
        if (!isVivoBrand() || !canPost(context)) return false
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching false
            if (!ensureChannel(manager)) return@runCatching false
            val stage = data.title + "@" + data.deadlineAtMillis
            val operation = if (publishedStage == stage) OPERATION_UPDATE else OPERATION_CREATE
            val builder = Notification.Builder(context, channelId())
                .setSmallIcon(iconRes(context))
                .setContentTitle(data.title)
                .setContentText(data.status)
                .setContentIntent(openIntent(context))
                .setCategory(Notification.CATEGORY_EVENT)
                // The island renders our own text on the lock screen touchpoint, but the
                // notification itself still must not name the course there.
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                // The status line already states the remaining time in words, so a second
                // chronometer would only disagree with it.
                .setShowWhen(false)
                .addExtras(superxExtras(context, data, operation))
            // The same expanded card as the other paths. OriginOS otherwise draws only its own
            // single line, so vivo would look plainer than Meizu for no reason the user can see.
            LiveCard.build(context, data)?.let { card ->
                runCatching { card.apply(context, android.widget.FrameLayout(context)) }
                builder.setCustomContentView(card)
            }
            // Safety net for the same reason the native card has one: if the boundary alarm and
            // the foreground refresh are both missed (frozen process, vendor battery manager), the
            // system drops the stale card instead of leaving a countdown past zero.
            builder.setTimeoutAfter((data.deadlineAtMillis - System.currentTimeMillis()).coerceAtLeast(1_000L))
            manager.notify(data.notificationId, builder.build())
            // Only remember the stage once the system accepted it, otherwise the next post would
            // send `update` for an island that was never created.
            publishedStage = stage
            lastTitle = data.title
            lastContent = data.status
            true
        }.getOrDefault(false)
    }

    override fun cancel(context: Context) {
        val stage = publishedStage
        publishedStage = null
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
            // `operation = 2` is what actually retires the island; deleting the notification on
            // its own can leave it behind. There is nothing to end when this process never
            // created one, so that case skips the extra post.
            if (stage != null && ensureChannel(manager)) {
                manager.notify(VENDOR_LIVE_NOTIFICATION_ID, Notification.Builder(context, channelId())
                    .setSmallIcon(iconRes(context))
                    .addExtras(finishExtras())
                    .build())
            }
            manager.cancel(VENDOR_LIVE_NOTIFICATION_ID)
        }
    }

    /** Every documented top-level `notification.superx.*` key, in one place. */
    private fun superxExtras(context: Context, data: VendorLiveData, operation: Int): Bundle = Bundle().apply {
        putInt(EXTRA_OPERATION, operation)
        // Degrade to an ordinary notification rather than to silence if vivo rejects the payload.
        putBoolean(EXTRA_SHOW_NOTIFY, true)
        putInt(EXTRA_TEMPLATE, TEMPLATE_PROGRESS)
        putString(EXTRA_SCENE, SCENE_METTING)
        // Tapping any touchpoint of the island opens the timetable.
        putParcelable(EXTRA_CLICK_RESP, openIntent(context))
        putBundle(EXTRA_BASE_INFOS, baseInfos(context, data))
        putBundle(EXTRA_INFOS, progressInfos(context, data))
        putBundle(EXTRA_SHORT_INFOS, shortInfos(context, data))
        putBundle(EXTRA_CAPSULE, capsule(context, data))
        putBundle(EXTRA_ISLAND, island(context, data))
        // Deliberately absent: `changedRecord`. vivo drops updates whose sequence number is lower
        // than the last one it applied, and a wall-clock counter would go backwards on the
        // TIME_SET broadcast this app reconciles on -- freezing the countdown. Without the key
        // vivo applies every update, which is what a live card needs.
    }

    private fun baseInfos(context: Context, data: VendorLiveData): Bundle = Bundle().apply {
        putCharSequence(KEY_BASE_TITLE, data.title)
        putCharSequence(KEY_BASE_CONTENT, data.status)
        putParcelable(KEY_BASE_ICON, iconOf(context))
    }

    /**
     * Template 2 data. `nodeIcon` and `progress` are both mandatory here, and `nodeIcon` must hold
     * 2..5 icons: a class has exactly two meaningful nodes (start, end), so the app icon is
     * repeated rather than inventing period markers the timetable never published.
     */
    private fun progressInfos(context: Context, data: VendorLiveData): Bundle = Bundle().apply {
        putInt(KEY_INFO_PROGRESS, percentOf(data))
        putParcelableArrayList(KEY_INFO_NODE_ICON, arrayListOf(iconOf(context), iconOf(context)))
        // progressColor / BgColor stay unset: vivo then draws the bar in its own themed colours,
        // which cannot clash with a mode we guessed wrong.
    }

    private fun shortInfos(context: Context, data: VendorLiveData): Bundle = Bundle().apply {
        putParcelable(KEY_SHORT_IMAGE, iconOf(context))
        putParcelable(KEY_SHORT_IMAGE_CLICK, openIntent(context))
        // The small card only qualifies its emphasised line, so the secondary line falls back to
        // the room/period rather than repeating the status verbatim in both slots.
        putString(KEY_SHORT_DESCRIBE, data.status.ifBlank { data.subtitle })
        putString(KEY_SHORT_CORE, chipOf(data))
    }

    private fun capsule(context: Context, data: VendorLiveData): Bundle = Bundle().apply {
        putInt(KEY_CAPSULE_STATE, CAPSULE_SHOWN)
        putParcelable(KEY_CAPSULE_ICON, iconOf(context))
        putString(KEY_CAPSULE_CONTENT, chipOf(data))
        // contentColor / bgColor stay unset so the pill keeps vivo's default contrast in both
        // light and dark mode.
    }

    /** The island proper: an icon plus the short countdown on the left, a progress ring on the right. */
    private fun island(context: Context, data: VendorLiveData): Bundle = Bundle().apply {
        putInt(KEY_ISLAND_LEFT_TEMPLATE, ISLAND_LEFT_ICON_TEXT)
        putBundle(KEY_ISLAND_LEFT_INFO, Bundle().apply {
            putParcelable(KEY_ISLAND_LEFT_ICON, iconOf(context))
            putCharSequence(KEY_ISLAND_LEFT_CONTENT, chipOf(data))
        })
        // Right template 2 is vivo's progress animation and needs no resource bundle, but the key
        // itself is required.
        putInt(KEY_ISLAND_RIGHT_TEMPLATE, ISLAND_RIGHT_PROGRESS)
        putBundle(KEY_ISLAND_RIGHT_INFO, Bundle())
        // Lets the island expand into the same progress card instead of a generic one.
        putInt(KEY_ISLAND_TEMPLATE, TEMPLATE_PROGRESS)
    }

    /**
     * `operation = 2`. `showNotify` is false here on purpose: ending a class must not leave a
     * plain notification in the shade, and `baseInfos` is still mandatory, so the last known copy
     * is sent back with AppText fallbacks when this process never published.
     */
    private fun finishExtras(): Bundle = Bundle().apply {
        putInt(EXTRA_OPERATION, OPERATION_FINISH)
        putBoolean(EXTRA_SHOW_NOTIFY, false)
        putInt(EXTRA_TEMPLATE, TEMPLATE_PROGRESS)
        putString(EXTRA_SCENE, SCENE_METTING)
        putBundle(EXTRA_BASE_INFOS, Bundle().apply {
            putCharSequence(KEY_BASE_TITLE, lastTitle.ifBlank { NativeSchedule.LIVE_TITLE })
            putCharSequence(KEY_BASE_CONTENT, lastContent.ifBlank { NativeSchedule.OPEN_TIMETABLE })
        })
    }

    /**
     * vivo caps `progress` at 100. The countdown phase has no denominator (the gap depends on when
     * the day started), so it reports 0 and lets the text carry the truth instead of faking a
     * percentage the timetable never computed.
     */
    private fun percentOf(data: VendorLiveData): Int =
        ((data.progress ?: 0f).coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)

    /** The capsule is a few glyphs wide; the status text is the honest fallback when Chip is empty. */
    private fun chipOf(data: VendorLiveData): String = data.chip.ifBlank { data.status }

    /** vivo renders this only on the lock screen, where the course name must stay private. */
    private fun publicVersion(context: Context): Notification =
        Notification.Builder(context, channelId())
            .setSmallIcon(iconRes(context))
            .setContentTitle(NativeSchedule.LIVE_TITLE)
            .setContentText(NativeSchedule.OPEN_TIMETABLE)
            .build()

    /** vivo's `clickResp`: tapping the island, capsule or card brings the timetable forward. */
    private fun openIntent(context: Context): PendingIntent {
        val app = context.applicationContext
        val intent = Intent().setClassName(app.packageName, "io.github.betterzslf.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(app, VENDOR_LIVE_NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel(manager: NotificationManager): Boolean = runCatching {
        if (manager.getNotificationChannel(channelId()) != null) return@runCatching true
        // IMPORTANCE_DEFAULT, not LOW: a LOW channel is filed under "Silent", which some launchers
        // collapse entirely. It still makes no sound because it carries neither sound nor vibration.
        val channel = NotificationChannel(channelId(), Schedule.VENDOR_LIVE_CHANNEL_VIVO,
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = Schedule.VENDOR_LIVE_CHANNEL_DESCRIPTION_VIVO
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
        true
    }.getOrDefault(false)

    /**
     * vivo reports its own manufacturer on every OriginOS build, with "iQOO" appearing on the
     * brand line of the gaming sub-brand, so both are matched on the lowercased pair.
     */
    private fun isVivoBrand(): Boolean {
        val id = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return id.contains("vivo") || id.contains("iqoo")
    }

    /**
     * A card the user blocked cannot be delivered, and claiming it anyway would delete the
     * standard notification too. Checked here as well as in [isSupported] because the permission
     * can be revoked between a reconcile and the post.
     */
    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.areNotificationsEnabled()) return false
        val existing = manager.getNotificationChannel(channelId())
        return existing == null || existing.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun iconOf(context: Context): Icon = Icon.createWithResource(context, iconRes(context))

    private fun iconRes(context: Context): Int =
        context.resources.getIdentifier("schedule_ic_notification", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

    private fun channelId(): String = VENDOR_LIVE_CHANNEL_PREFIX + "vivo"

    internal companion object {
        // Top-level keys, verbatim from vivo's 原子通知 parameter table.
        const val EXTRA_OPERATION = "notification.superx.operation"
        const val EXTRA_SHOW_NOTIFY = "notification.superx.showNotify"
        const val EXTRA_TEMPLATE = "notification.superx.template"
        const val EXTRA_SCENE = "notification.superx.scene"
        const val EXTRA_CLICK_RESP = "notification.superx.clickResp"
        const val EXTRA_BASE_INFOS = "notification.superx.baseInfos"
        const val EXTRA_INFOS = "notification.superx.infos"
        const val EXTRA_SHORT_INFOS = "notification.superx.shortInfos"
        const val EXTRA_CAPSULE = "notification.superx.capsule"
        const val EXTRA_ISLAND = "notification.superx.island"

        // Keys inside those bundles carry the full dotted path; that is how vivo reads them.
        const val KEY_BASE_TITLE = "notification.superx.baseInfos.title"
        const val KEY_BASE_CONTENT = "notification.superx.baseInfos.content"
        const val KEY_BASE_ICON = "notification.superx.baseInfos.icon"
        const val KEY_INFO_PROGRESS = "notification.superx.infos.progress"
        const val KEY_INFO_NODE_ICON = "notification.superx.infos.nodeIcon"
        const val KEY_SHORT_IMAGE = "notification.superx.shortInfos.image"
        const val KEY_SHORT_IMAGE_CLICK = "notification.superx.shortInfos.imageClickResp"
        const val KEY_SHORT_DESCRIBE = "notification.superx.shortInfos.describeShort"
        const val KEY_SHORT_CORE = "notification.superx.shortInfos.coreInfoShort"
        const val KEY_CAPSULE_STATE = "notification.superx.capsule.state"
        const val KEY_CAPSULE_ICON = "notification.superx.capsule.icon"
        const val KEY_CAPSULE_CONTENT = "notification.superx.capsule.content"
        const val KEY_ISLAND_LEFT_TEMPLATE = "island.superx.leftTemplate"
        const val KEY_ISLAND_LEFT_INFO = "island.superx.leftInfo"
        const val KEY_ISLAND_LEFT_ICON = "island.superx.leftInfo.icon"
        const val KEY_ISLAND_LEFT_CONTENT = "island.superx.leftInfo.content"
        const val KEY_ISLAND_RIGHT_TEMPLATE = "island.superx.rightTemplate"
        const val KEY_ISLAND_RIGHT_INFO = "island.superx.rightInfo"
        const val KEY_ISLAND_TEMPLATE = "island.superx.template"

        /** 0 create, 1 update, 2 finish. */
        const val OPERATION_CREATE = 0
        const val OPERATION_UPDATE = 1
        const val OPERATION_FINISH = 2

        /** 2 = progress-visualisation template, the only one a bounded class fits. */
        const val TEMPLATE_PROGRESS = 2

        /**
         * vivo's scene whitelist has no education case. A class block is a scheduled meeting, so
         * the timetable borrows `METTING`; see the class comment for the failure modes that
         * implies.
         */
        const val SCENE_METTING = "METTING"

        const val CAPSULE_SHOWN = 1
        const val ISLAND_LEFT_ICON_TEXT = 1
        const val ISLAND_RIGHT_PROGRESS = 2
    }
}
