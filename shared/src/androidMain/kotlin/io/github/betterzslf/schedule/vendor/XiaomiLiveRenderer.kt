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
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.text.AppText.NativeSchedule
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Xiaomi HyperOS super-island ("超级岛") / focus-notification ("焦点通知") renderer.
 *
 * Mechanism: a perfectly ordinary ongoing notification carries a JSON payload in the
 * [EXTRA_FOCUS_PARAM] extra. HyperOS parses that payload and, when the device has island hardware
 * and the user left this app's focus-notification switch on, renders the notification on the island
 * and in the notification shade instead of the standard card. Nothing else is needed: there is no
 * runtime permission to request and no library to link.
 *
 * Field provenance, because none of this is a stable public API:
 * - Xiaomi documents the payload envelope (`param_v2` + `baseInfo`/`param_island`, and the
 *   `miui.focus.param` extra name) in its HyperOS developer guide for 超级岛 notifications, and the
 *   focus-notification switch in its 常见 Q&A page.
 * - The individual keys used here (`protocol`, `business`, `updatable`, `ticker`, `param_island`,
 *   `islandProperty`, `bigIslandArea`/`smallIslandArea`, `textInfo`) are documented only through
 *   those templates plus community reverse engineering (HyperIsland ToolKit and the MiPush focus
 *   notification samples). **They may be renamed or dropped by any HyperOS release.**
 *
 * Consequence, and the rule this file follows: the extras are a decoration, never a dependency. The
 * notification handed to the system must read correctly on its own (title, status, countdown,
 * progress) with the payload ignored entirely -- on an older ROM, on a device without island
 * hardware, or after Xiaomi changes the schema, the user then still gets a truthful ongoing card
 * rather than an empty one.
 *
 * Deliberately not done here:
 * - No third-party island library (hyperisland_kit and friends). The payload is built with the
 *   [JSONObject] that ships inside Android, so no new dependency, licence or minSdk bump enters the
 *   build for a vendor-only surface.
 * - No [android.widget.RemoteViews]: this stays a stock notification, which is exactly what lets it
 *   be posted from the same code path that also runs on non-Xiaomi devices.
 * - No [io.github.betterzslf.schedule.ScheduleLiveNotifications] call: the Android 16 Live Update
 *   path must not gain a vendor extras dependency, so this renderer owns its channel, its content
 *   intent and its cancellation.
 * - No focus-notification switch probe. Xiaomi's Q&A documents one (`ContentResolver.call` on
 *   `content://miui.statusbar.notification.public` with `canShowFocus`), but the switch is a per-app
 *   vendor grant this app cannot request, it changes only by hand, and the vendor already degrades
 *   to a normal notification when it is off. Probing it on every boundary tick would add a blocking
 *   IPC per update purely to reproduce a decision the system makes anyway, and gating on it would
 *   disable the card outright wherever the switch defaults off.
 */
internal class XiaomiLiveRenderer : VendorLiveRenderer {

    /**
     * True for Xiaomi-family ROMs that expose an island. The manufacturer strings are the only
     * public brand signal (the chipset vendor can appear in `Build.MANUFACTURER`, so both fields are
     * searched), and the island flag is the only public signal for the hardware surface.
     */
    override fun isSupported(context: Context): Boolean =
        isXiaomiFamily() && hasIslandSurface(context)

    override fun publish(context: Context, data: VendorLiveData): Boolean = runCatching {
        if (!isSupported(context)) return false
        // A disabled channel or revoked POST_NOTIFICATIONS cannot be worked around from here; say so
        // instead of pretending the vendor card exists, so the caller can keep its own bookkeeping.
        if (!notificationsAllowed(context)) return false
        val now = System.currentTimeMillis()
        // An already expired phase is a stale card, not content to re-post: let the caller cancel.
        if (data.deadlineAtMillis <= now) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        ensureChannel(manager)
        manager.notify(TAG, data.notificationId, build(context, data, now))
        true
    }.getOrDefault(false)

    override fun cancel(context: Context) {
        // Callers publish with the shared default id, so one id is enough to withdraw the card even
        // if this instance never saw the publish (process restart, replay of an old alarm).
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(TAG, VENDOR_LIVE_NOTIFICATION_ID) }
    }

    private fun build(context: Context, data: VendorLiveData, now: Long): Notification {
        val icon = notificationIcon(context)
        // The shade card keeps the app's existing privacy stance: the lock-screen (public) version
        // never names the course, only the unlocked card does. The island itself is drawn by the
        // system from the extras and follows the user's own lock-screen notification settings.
        val publicVersion = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(NativeSchedule.LIVE_TITLE)
            .setContentText(NativeSchedule.OPEN_TIMETABLE)
            .build()
        val progress = data.progress?.coerceIn(0f, 1f)
        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(data.title)
            .setContentText(data.status)
            .setContentIntent(openIntent(context))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // The countdown is carried by the platform chronometer, so the fallback card stays
            // truthful even if this process is frozen between boundary ticks, and no per-minute
            // re-post is required just to keep the number moving.
            .setShowWhen(true)
            .setWhen(data.deadlineAtMillis)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            // Safety net: if the boundary alarm is missed, the system removes the stale card instead
            // of leaving a countdown past zero on screen.
            .setTimeoutAfter((data.deadlineAtMillis - now).coerceAtLeast(MIN_TIMEOUT_MILLIS))
        if (progress != null) {
            builder.setProgress(PROGRESS_MAX, (progress * PROGRESS_MAX).roundToInt(), false)
        }
        if (Build.VERSION.SDK_INT >= 36) {
            // On API 36 this is the native status-bar capsule; on HyperOS the island usually wins,
            // but the value is the same short chip either way.
            runCatching { builder.setShortCriticalText(data.chip) }
        }
        builder.addExtras(focusExtras(context, data, icon, progress))
        return builder.build()
    }

    /**
     * The vendor payload plus the icon table it references.
     *
     * Xiaomi splits content (JSON) from drawables (a Bundle of [Icon] values keyed by the same
     * `miui.focus.pic_*` names the JSON mentions), which is why the pictures are registered here
     * even though the JSON is the only documented entry point.
     */
    private fun focusExtras(context: Context, data: VendorLiveData, icon: Int, progress: Float?): Bundle {
        val pictures = Bundle().apply {
            runCatching {
                putParcelable(PIC_TICKER, Icon.createWithResource(context, icon))
                putParcelable(PIC_ISLAND, Icon.createWithResource(context, icon))
            }
        }
        return Bundle().apply {
            putString(EXTRA_FOCUS_PARAM, islandPayload(data, progress).toString())
            putBundle(EXTRA_FOCUS_PICS, pictures)
        }
    }

    /**
     * Builds `{"param_v2": {...}}`, the only shape the vendor accepts.
     *
     * `baseInfo` is the focus-notification card (what the shade shows); `param_island` is the
     * island: `bigIslandArea` is the expanded state, `smallIslandArea` the capsule. A capsule that
     * carries a progress ring only exists when the phase has a real fraction -- a countdown has no
     * natural denominator, so it gets a plain icon instead of a faked percentage.
     */
    private fun islandPayload(data: VendorLiveData, progress: Float?): JSONObject {
        val picture = JSONObject().put("type", 1).put("pic", PIC_ISLAND)

        val textInfo = JSONObject()
            .put("frontTitle", data.chip)
            .put("title", data.title)
            .put("content", data.status)
            // The ROM decides whether to tint; the content is short and dark-on-light by design.
            .put("useHighLight", false)
        val bigArea = JSONObject().put(
            "imageTextInfoLeft",
            JSONObject().put("type", 1).put("picInfo", picture).put("textInfo", textInfo),
        )

        val smallArea = if (progress == null) {
            JSONObject().put("picInfo", picture)
        } else {
            val ring = JSONObject()
                .put("progress", (progress * PROGRESS_MAX).roundToInt())
                .put("colorReach", ACCENT_COLOR)
                .put("colorUnReach", TRACK_COLOR)
            JSONObject().put(
                "combinePicInfo",
                JSONObject().put("picInfo", picture).put("progressInfo", ring),
            )
        }
        val island = JSONObject()
            // 1 marks the island as active; any other value is treated as "do not show".
            .put("islandProperty", 1)
            .put("bigIslandArea", bigArea)
            .put("smallIslandArea", smallArea)

        val baseInfo = JSONObject()
            .put("type", 1)
            .put("title", data.title)
            .put("content", data.status)
        if (data.subtitle.isNotBlank()) baseInfo.put("subTitle", data.subtitle)

        return JSONObject().put(
            "param_v2",
            JSONObject()
                // protocol 1 is the OS2/OS3 payload generation; there is no newer documented one.
                .put("protocol", PROTOCOL_VERSION)
                // No education scene exists in Xiaomi's scene list: the value only feeds vendor
                // statistics, so a descriptive one is used rather than borrowing a commerce scene
                // whose copy and analytics the app does not match.
                .put("business", BUSINESS_SCENE)
                // A continuing card: repeated posts with the same id update it instead of stacking.
                .put("updatable", true)
                // Never auto-expand on update -- a once-a-minute class timer would otherwise keep
                // unfolding the island over whatever the user is doing.
                .put("enableFloat", false)
                // Status-bar line. Without `ticker` an OS3 device shows no status-bar entry at all.
                .put("ticker", data.status)
                .put("tickerPic", PIC_TICKER)
                // Always-on-display line: the short chip, because the AOD name field is one token.
                .put("aodTitle", data.chip)
                .put("param_island", island)
                .put("baseInfo", baseInfo),
        )
    }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // IMPORTANCE_DEFAULT, not LOW: LOW files the channel under "Silent", which several HyperOS
        // themes collapse entirely and which the island ignores. It still stays silent because the
        // channel carries no sound and no vibration.
        val channel = NotificationChannel(
            CHANNEL_ID,
            AppText.Schedule.VENDOR_LIVE_CHANNEL_XIAOMI,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            // Named per vendor, exactly like the vivo/Flyme channels, so the user can tell the
            // private island notification apart from the standard live one in system settings.
            description = AppText.Schedule.VENDOR_LIVE_CHANNEL_DESCRIPTION_XIAOMI
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannel(channel)
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Plain launcher-into-app intent, no data URI: the vendor card carries no revision, so it must
     * not pretend to be the deeplink the native card uses (that one is validated against the
     * engine's owner revision). Tapping just brings the app forward.
     */
    private fun openIntent(context: Context): PendingIntent {
        val intent = Intent()
            .setClassName(context.packageName, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            4,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Mirrors the native card's icon lookup: the drawable lives in the app module, not in shared. */
    private fun notificationIcon(context: Context): Int =
        context.resources.getIdentifier("schedule_ic_notification", "drawable", context.packageName)
            .takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info

    private fun isXiaomiFamily(): Boolean {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")
    }

    /**
     * HyperOS publishes the island capability as a system property, not as a public API. Both routes
     * are read-only and either one is enough; a device without the island keeps the standard card.
     */
    private fun hasIslandSurface(context: Context): Boolean =
        islandProperty() || runCatching {
            context.packageManager.hasSystemFeature(ISLAND_FEATURE)
        }.getOrDefault(false)

    /**
     * `android.os.SystemProperties` is hidden API, so it is reached reflectively and every failure
     * degrades to "no island". Reading a property cannot throw in practice; reflection here can, on
     * a ROM that removes or blocks the class.
     */
    private fun islandProperty(): Boolean = runCatching {
        val value = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, ISLAND_FEATURE) as? String
        value == "true" || value == "1"
    }.getOrDefault(false)

    internal companion object {
        /** Suffix of [VENDOR_LIVE_CHANNEL_PREFIX]; per-vendor so their settings stay separable. */
        const val CHANNEL_ID = VENDOR_LIVE_CHANNEL_PREFIX + "xiaomi"

        /** Notification tag; pairs with [VENDOR_LIVE_NOTIFICATION_ID] for cancel-by-tag. */
        const val TAG = "schedule.vendor.xiaomi"

        /** The single documented entry point: one JSON string, read by the system on notify. */
        const val EXTRA_FOCUS_PARAM = "miui.focus.param"

        /** Drawable table the JSON refers to by key; undocumented but needed for any picture. */
        const val EXTRA_FOCUS_PICS = "miui.focus.pics"

        const val PIC_TICKER = "miui.focus.pic_ticker"
        const val PIC_ISLAND = "miui.focus.pic_island"

        /** Xiaomi's island capability flag, both as a property and as a package feature. */
        const val ISLAND_FEATURE = "persist.sys.feature.island"

        /**
         * Scene reported in the payload. Xiaomi documents `business` as free-form vendor analytics,
         * not a whitelist the local (non-MiPush) path is validated against.
         */
        const val BUSINESS_SCENE = "education"

        const val PROTOCOL_VERSION = 1
        const val PROGRESS_MAX = 100

        /** Progress ring colours matching the app's own card; the ROM may ignore both. */
        const val ACCENT_COLOR = "#3382FF"
        const val TRACK_COLOR = "#D6E5FF"

        const val MAIN_ACTIVITY = "io.github.betterzslf.MainActivity"

        /** `setTimeoutAfter` treats 0/negative as "no timeout", so never post below one second. */
        const val MIN_TIMEOUT_MILLIS = 1_000L
    }
}
