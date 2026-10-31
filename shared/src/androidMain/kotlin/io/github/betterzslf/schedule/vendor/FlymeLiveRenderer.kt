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
import android.view.View
import android.widget.RemoteViews
import io.github.betterzslf.core.text.AppText.NativeSchedule
import io.github.betterzslf.core.text.AppText.Schedule
import io.github.betterzslf.feature.schedule.ScheduleLiveClock
import io.github.betterzslf.feature.schedule.ScheduleLiveState
import kotlin.math.roundToInt

/**
 * Meizu Flyme live-notification (实况通知) renderer.
 *
 * **Provenance: this whole path is reverse engineered.** 魅族官方没有为实况通知提供任何公开文档 or
 * SDK; every extra name below was recovered from community demos that inspected Flyme's SystemUI.
 * The demos' own notes mark several numeric fields as "idk, need int", so the values are the ones
 * those demos shipped with rather than values Meizu ever confirmed. The practical consequence is
 * that **this can stop working silently after any Flyme update**: the system drops unknown extras
 * without an error, so the card simply stops appearing and there is no exception, no log and no
 * callback to detect it. [publish] therefore reports only "handed to the system", and the caller
 * keeps the standard Android 16 card as the fallback.
 *
 * Mechanism, mirroring the reference demo:
 * 1. `notification.extras` carries a bundle with `is_live = true` -- the only master switch.
 * 2. A nested `notification.live.capsule` bundle configures the status-bar pill. Its
 *    `notification.live.capsuleStatus` must be `1`; any other value (including 0) makes the pill
 *    invisible, and that is the one parameter the demos are certain about.
 * 3. The expanded card is a [RemoteViews], installed with [Notification.Builder.setCustomContentView].
 *    Flyme renders only that view -- the notification's own title/text are ignored, which is exactly
 *    why this can never be folded into the Android 16 path: a promoted ongoing notification forbids
 *    custom content views, so the two mechanisms have to stay separate notifications.
 *
 * Deliberately not done here:
 * - No write to [io.github.betterzslf.schedule.ScheduleLiveNotifications]. The native card must keep
 *   carrying no RemoteViews dependency at all, so this file owns its channel, its content intent and
 *   its own cancellation.
 * - No timeline animation or per-second re-post. Flyme's pill is a static text slot; the card reposts
 *   at schedule boundaries like the standard one, and the chronometer in the shade keeps the number
 *   truthful if this process is frozen in between.
 * - No dependency on the capsule icon being drawn. `notification.live.capsuleIcon` is included
 *   because the demo includes it, but the pill also renders fine from text alone.
 */
internal class FlymeLiveRenderer : VendorLiveRenderer {

    /** The id [publish] last used, so [cancel] withdraws the card even if the caller changed it. */
    private var lastNotificationId: Int = VENDOR_LIVE_NOTIFICATION_ID

    /**
     * Flyme exists only on Meizu/Mblu hardware, and there is no capability flag to read: the demos
     * detect nothing and simply post. The brand string is therefore the whole check, which keeps
     * this renderer off every other ROM. Notification permission is a separate question and is
     * answered in [publish], because it is about this post, not about the ROM.
     */
    override fun isSupported(context: Context): Boolean = isFlymeFamily()

    override fun publish(context: Context, data: VendorLiveData): Boolean {
        if (!isFlymeFamily()) return false
        // A stale phase would pin a card that already ended; let the caller clear it instead.
        if (data.deadlineAtMillis <= System.currentTimeMillis()) return false
        // Gate on permission here rather than in isSupported: the ROM is still capable, this post
        // simply cannot be shown, and the caller must fall back exactly as if the vendor refused.
        if (!notificationsAllowed(context)) return false
        val views = resolve(context) ?: return false
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            ensureChannel(manager)
            val builder = Notification.Builder(context, channelId())
                .setSmallIcon(notificationIcon(context))
                // Readable fallback for the shade and for lock-screen settings: if Flyme ignores the
                // whole live payload, the user still gets a truthful ongoing card.
                .setContentTitle(data.title)
                .setContentText(data.status)
                .setContentIntent(openIntent(context))
                .setCategory(Notification.CATEGORY_PROGRESS)
                // Same privacy stance as the native card: the public (lock-screen) version never
                // names the course, only the unlocked card does.
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(data.deadlineAtMillis)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                // Safety net: if the boundary alarm is missed, the system removes the stale card
                // instead of leaving a countdown that has gone past zero on screen.
                .setTimeoutAfter((data.deadlineAtMillis - System.currentTimeMillis()).coerceAtLeast(MIN_TIMEOUT_MILLIS))
                .addExtras(liveExtras(context, data, capsuleView(context, views, data)))
            // Required, not optional: without a custom content view Flyme has nothing to expand and
            // only the pill (or nothing at all) is shown.
            val content = contentView(context, views, data)
            // Inflate locally before posting. A RemoteViews that the host cannot apply renders as a
            // completely blank card -- which is indistinguishable from "Flyme ignored the payload"
            // and cost several debug rounds. `apply` here throws on the same faults the host would
            // hit (missing resource, bad id, unsupported method), so a broken layout fails loudly
            // and the caller falls back to the standard notification instead.
            content.apply(context, android.widget.FrameLayout(context))
            builder.setCustomContentView(content)
            manager.notify(data.notificationId, builder.build())
            lastNotificationId = data.notificationId
            true
        }.getOrDefault(false)
    }

    /**
     * Post one diagnostic variant with explicit knob values.
     *
     * Kept next to the real [publish] so both build the notification the same way; the only
     * difference is that these knobs come from the probe's matrix instead of the constants.
     * A failed variant returns false and does not disturb the others.
     */
    internal fun publishVariant(
        context: Context,
        state: ScheduleLiveState,
        id: Int,
        label: String,
        capsuleStatus: Int,
        capsuleType: Int,
        operation: Int,
        liveType: Int,
        withCapsuleRemoteView: Boolean,
        withContentRemoteView: Boolean,
        meituanStyle: Boolean = false,
        withLegacyIsLive: Boolean = true,
        progress: Float? = null,
    ): Boolean {
        if (!isFlymeFamily() || !notificationsAllowed(context)) return false
        val views = resolve(context) ?: return false
        val data = VendorLiveData(
            title = label,
            subtitle = state.title,
            status = state.text,
            progress = progress ?: state.progress,
            chip = state.chip,
            deadlineAtMillis = state.endsAtMillis,
            // The detail columns and start clock have to be copied here explicitly: dropping them
            // was why the three-column card stayed blank even after the mock state was filled.
            teacher = state.teacher,
            room = state.location,
            periodText = state.periodText,
            startsAtMillis = state.startsAtMillis,
            notificationId = id,
        )
        return runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return false
            ensureChannel(manager)
            val capsuleViews = if (withCapsuleRemoteView) capsuleView(context, views, data) else null
            val builder = Notification.Builder(context, channelId())
                .setSmallIcon(notificationIcon(context))
                .setContentTitle(label)
                .setContentText(state.text)
                // Flyme ignores title/text whenever it decides to draw its own card, and draws
                // NOTHING when the payload is one it does not understand -- which produced
                // indistinguishable blank cards in the probe. subText and the ticker survive that
                // path often enough to tell the variants apart in a screenshot.
                .setSubText(label)
                .setTicker(label)
                .setContentIntent(openIntent(context))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(state.endsAtMillis)
                .addExtras(liveExtras(context, data, capsuleViews, capsuleStatus, capsuleType, operation,
                    liveType, meituanStyle, withLegacyIsLive))
            if (withContentRemoteView) builder.setCustomContentView(contentView(context, views, data))
            manager.notify(id, builder.build())
            true
        }.getOrDefault(false)
    }

    override fun cancel(context: Context) {
        // Cancel the id that was actually posted plus the shared default: a replay of an old alarm
        // or a process restart can leave a card behind under either.
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.cancel(lastNotificationId)
            if (lastNotificationId != VENDOR_LIVE_NOTIFICATION_ID) manager.cancel(VENDOR_LIVE_NOTIFICATION_ID)
        }
    }

    /**
     * The reverse-engineered payload.
     *
     * The capsule bundle holds the pill: [EXTRA_CAPSULE_STATUS] is the enable switch, the colour
     * pair only styles the system-drawn chrome around the text, and the RemoteViews is what the pill
     * actually inflates. The outer bundle is the live entry point: `is_live` turns the feature on
     * and the capsule is attached to it by key.
     */
    private fun liveExtras(
        context: Context,
        data: VendorLiveData,
        capsule: RemoteViews?,
        capsuleStatus: Int = CAPSULE_STATUS_ENABLED,
        capsuleType: Int = CAPSULE_TYPE_DEFAULT,
        operation: Int = LIVE_OPERATION_SHOW,
        liveType: Int = LIVE_TYPE_STANDARD,
        meituanStyle: Boolean = false,
        withLegacyIsLive: Boolean = true,
    ): Bundle {
        val capsuleBundle = Bundle().apply {
            // 1 is the only value the demos ever saw render; everything else hides the pill.
            putInt(EXTRA_CAPSULE_STATUS, capsuleStatus)
            // Defaults to the demo's 5. The diagnostics probe varies it deliberately, because the
            // demos disagreed (1 vs 5) and neither explained the enum.
            putInt(EXTRA_CAPSULE_TYPE, capsuleType)
            putString(EXTRA_CAPSULE_CONTENT, chipText(data))
            // Meituan always sets a short title beside the content ("开锁中"); we omitted it
            // entirely, so the pill had to guess. Kept to a couple of glyphs so it fits ~60dp.
            putString(EXTRA_CAPSULE_TITLE, Schedule.VENDOR_MEIZU_CAPSULE_FALLBACK)
            putInt(EXTRA_CAPSULE_BG_COLOR, CAPSULE_BG_COLOR)
            putInt(EXTRA_CAPSULE_CONTENT_COLOR, CAPSULE_TEXT_COLOR)
            // Only the app's own drawable may become an Icon: `Icon.createWithResource` records the
            // caller's package name, so handing it a framework id such as
            // `android.R.drawable.ic_dialog_info` would make SystemUI try to load a resource that
            // does not exist in this app and fail the whole pill. Without an app icon the pill falls
            // back to the demo's text-only form, which is what it already looks like at 60dp.
            appIcon(context)?.let { putParcelable(EXTRA_CAPSULE_ICON, Icon.createWithResource(context, it)) }
            // Null means "let Flyme build its own pill from the bundle" -- one of the probe's
            // questions, since a rejected RemoteViews would otherwise hide the whole capsule.
            capsule?.let { putParcelable(EXTRA_CAPSULE_REMOTE_VIEW, it) }
        }
        return Bundle().apply {
            // `is_live` is NOT part of Meituan's payload (verified by scanning all 12 of its dex
            // files: the key appears only as `is_live_photo`). It is kept as an option because the
            // current working build uses it, and a probe should be able to isolate its effect.
            if (withLegacyIsLive) putBoolean(EXTRA_IS_LIVE, true)
            // 0 = show/create. The demos only ever used 0, and the value was not observed to matter.
            putInt(EXTRA_LIVE_OPERATION, operation)
            // 2 = the demo's "standard live notification" type; same caveat as capsuleType.
            putInt(EXTRA_LIVE_TYPE, liveType)
            putBundle(EXTRA_LIVE_CAPSULE, capsuleBundle)
            if (meituanStyle) putBundle(EXTRA_LIVE_FEATURE, featureBundle(context, data))
        }
    }

    /**
     * The `notification.live.feature.*` block: Flyme's own description of the expanded card.
     *
     * Meituan never supplies a RemoteViews; it fills these keys and lets Flyme draw the card, which
     * is why its cards pick up the system's typography and wallpaper tint while ours looked
     * hand-rolled. Key names below were extracted from Meituan's dex, not guessed.
     */
    private fun featureBundle(context: Context, data: VendorLiveData): Bundle {
        val feature = Bundle().apply {
            putString(EXTRA_FEATURE_TITLE_TEXT, data.title)
            putString(EXTRA_FEATURE_CONTENT_TEXT, data.status)
            val progress = data.progress
            if (progress != null) {
                // progress is expressed as a whole percent; progressType 1 selects the bar.
                putInt(EXTRA_FEATURE_PROGRESS, (progress.coerceIn(0f, 1f) * PROGRESS_MAX).roundToInt())
                putInt(EXTRA_FEATURE_PROGRESS_TYPE, PROGRESS_TYPE_BAR)
                putInt(EXTRA_FEATURE_PROGRESS_COLOR, STATUS_IN_CLASS_COLOR)
            }
            // The three detail columns travel as the dual-section text Meituan also uses:
            // first* is the left pair, last* the right pair, and the middle stays in contentText.
            val details = listOf(data.teacher, data.room, data.periodText).filter { it.isNotBlank() }
            if (details.isNotEmpty()) {
                putString(EXTRA_FEATURE_FIRST_TITLE_TEXT, Schedule.LIVE_FIELD_TEACHER)
                putString(EXTRA_FEATURE_FIRST_CONTENT_TEXT, data.teacher)
            }
            if (data.room.isNotBlank()) {
                putString(EXTRA_FEATURE_LAST_TITLE_TEXT, Schedule.LIVE_FIELD_ROOM)
                putString(EXTRA_FEATURE_LAST_CONTENT_TEXT, data.room)
            }
            putBoolean(EXTRA_FEATURE_SPLIT_LINE_ENABLE, true)
            appIcon(context)?.let { putParcelable(EXTRA_FEATURE_DESC_PIC, Icon.createWithResource(context, it)) }
        }
        return feature
    }

    /** The status-bar pill. One line of chip text, nothing else fits in ~60dp. */
    private fun capsuleView(context: Context, views: ResolvedViews, data: VendorLiveData): RemoteViews =
        RemoteViews(context.packageName, views.capsuleLayout).apply {
            setTextViewText(views.capsuleText, chipText(data))
            // Colours are re-applied from code so the pill stays legible if a Flyme build inflates
            // the layout with a different default text colour. The dark surface itself comes from
            // the XML: setBackgroundColor is a remote method some Flyme versions refuse, and losing
            // that call would otherwise leave white text on the system's own (possibly light) pill.
            setTextColor(views.capsuleText, CAPSULE_TEXT_COLOR)
            // The pill reads as "42分" on screen, which is meaningless spoken aloud on its own.
            setContentDescription(views.capsuleText, Schedule.VENDOR_MEIZU_CAPSULE_CONTENT_DESCRIPTION)
        }

    /**
     * The expanded card: course name, status, progress bar, room.
     *
     * The progress bar is hidden rather than zeroed during a countdown phase, because a countdown
     * has no honest denominator -- the same rule the standard card follows. The subtitle is hidden
     * when the caller has nothing to say instead of leaving an empty line of padding.
     */
    private fun contentView(context: Context, views: ResolvedViews, data: VendorLiveData): RemoteViews =
        RemoteViews(context.packageName, views.contentLayout).apply {
            setTextViewText(views.contentTitle, data.title)
            setTextViewText(views.contentStatus, data.status)
            // The right-hand status doubles as the countdown, so it takes the accent colour while
            // a class is running and a warmer one while waiting for the next one.
            setTextColor(views.contentStatus, if (data.progress != null) STATUS_IN_CLASS_COLOR else STATUS_COUNTDOWN_COLOR)

            val progress = data.progress?.coerceIn(0f, 1f)
            if (progress == null) {
                // No honest percentage before a class starts; hiding beats a bar stuck at zero.
                setViewVisibility(views.contentProgress, View.GONE)
            } else {
                setViewVisibility(views.contentProgress, View.VISIBLE)
                // setInt, NOT setProgressBar.
                //
                // RemoteViews can only invoke methods marked @RemotableViewMethod in the host
                // process. ProgressBar.setProgress is annotated; RemoteViews.setProgressBar
                // itself is not, so the host silently dropped every value -- which is why the bar
                // rendered at whatever the XML default was (full, then an indeterminate ring).
                setInt(views.contentProgress, "setProgress", (progress * PROGRESS_MAX).roundToInt())
            }

            // Three columns. In class they are teacher / room / period; between classes the third
            // becomes the start time, which is the only useful value at that moment.
            val inClass = data.progress != null
            val columns = listOf(
                Schedule.LIVE_FIELD_TEACHER to data.teacher,
                Schedule.LIVE_FIELD_ROOM to data.room,
                (if (inClass) Schedule.LIVE_FIELD_PERIOD else Schedule.LIVE_FIELD_START) to
                    if (inClass) data.periodText else ScheduleLiveClock.hhmm(data.startsAtMillis),
            )
            val ids = listOf(
                Triple(views.label1, views.value1, columns[0]),
                Triple(views.label2, views.value2, columns[1]),
                Triple(views.label3, views.value3, columns[2]),
            )
            ids.forEach { (labelId, valueId, pair) ->
                val (label, value) = pair
                if (value.isBlank()) {
                    // An unknown value stays blank rather than printing a dash: the column simply
                    // has no second line, which reads as "not provided" without a placeholder.
                    setTextViewText(labelId, "")
                    setTextViewText(valueId, "")
                } else {
                    setTextViewText(labelId, label)
                    setTextViewText(valueId, value)
                }
            }
        }

    /**
     * Resolves the two layouts and every view id once per publish.
     *
     * Flyme inflates these in its own process, so a missing resource is not a crash here but an
     * [RemoteViews] the system cannot apply -- and a card that fails to inflate is exactly the
     * silent failure this renderer must not produce. Returning null lets [publish] report false and
     * the caller fall back to the standard notification instead.
     */
    private fun resolve(context: Context): ResolvedViews? {
        fun layout(name: String): Int = context.resources.getIdentifier(name, "layout", context.packageName)
        fun id(name: String): Int = context.resources.getIdentifier(name, "id", context.packageName)
        val capsuleLayout = layout(LAYOUT_CAPSULE)
        val contentLayout = layout(LAYOUT_CONTENT)
        val capsuleText = id(ID_CAPSULE_TEXT)
        val contentTitle = id(ID_CONTENT_TITLE)
        val contentStatus = id(ID_CONTENT_STATUS)
        val contentProgress = id(ID_CONTENT_PROGRESS)
        val label1 = id(ID_LABEL_1); val value1 = id(ID_VALUE_1)
        val label2 = id(ID_LABEL_2); val value2 = id(ID_VALUE_2)
        val label3 = id(ID_LABEL_3); val value3 = id(ID_VALUE_3)
        val ids = listOf(capsuleLayout, contentLayout, capsuleText, contentTitle, contentStatus,
            contentProgress, label1, value1, label2, value2, label3, value3)
        if (ids.any { it == 0 }) return null
        return ResolvedViews(
            capsuleLayout, contentLayout, capsuleText,
            contentTitle, contentStatus, contentProgress,
            label1, value1, label2, value2, label3, value3,
        )
    }

    private class ResolvedViews(
        val capsuleLayout: Int,
        val contentLayout: Int,
        val capsuleText: Int,
        val contentTitle: Int,
        val contentStatus: Int,
        val contentProgress: Int,
        val label1: Int, val value1: Int,
        val label2: Int, val value2: Int,
        val label3: Int, val value3: Int,
    )

    /** The pill is ~60dp of chrome; a chip past a few glyphs is clipped, so keep it short. */
    private fun chipText(data: VendorLiveData): String = data.chip.trim().ifEmpty { Schedule.VENDOR_MEIZU_CAPSULE_FALLBACK }

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(channelId()) != null) return
        // IMPORTANCE_DEFAULT, not LOW: a LOW channel is filed under the "Silent" section, which
        // several Flyme themes collapse entirely -- and a collapsed section hides the pill's host
        // notification. The channel stays silent because it carries no sound and no vibration.
        manager.createNotificationChannel(
            NotificationChannel(
                channelId(),
                Schedule.VENDOR_LIVE_CHANNEL_MEIZU,
                // HIGH, matching the only known-working reference implementation. Flyme decides
                // whether to host a capsule from the channel, so a lower importance risks the pill
                // never being created at all; sound and vibration stay off so it is still silent.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = Schedule.VENDOR_LIVE_CHANNEL_DESCRIPTION_MEIZU
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
                // PUBLIC: the capsule is also meant to show on the lock screen, and a PRIVATE
                // channel visibility suppresses exactly that surface.
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(channelId())?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun publicVersion(context: Context): Notification =
        Notification.Builder(context, channelId())
            .setSmallIcon(notificationIcon(context))
            .setContentTitle(NativeSchedule.LIVE_TITLE)
            .setContentText(NativeSchedule.OPEN_TIMETABLE)
            .build()

    /**
     * Plain launcher-into-app intent, without the native deeplink URI: the vendor card carries no
     * revision, and the engine validates that URI against the current owner revision, so reusing it
     * here would just be rejected.
     */
    private fun openIntent(context: Context): PendingIntent {
        val intent = Intent()
            .setClassName(context.packageName, MAIN_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            OPEN_INTENT_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Mirrors the native card's lookup: the drawable lives in the app module, not in shared. */
    private fun notificationIcon(context: Context): Int = appIcon(context) ?: android.R.drawable.ic_dialog_info

    /** The app's own notification drawable, or null when it is absent; never a framework id. */
    private fun appIcon(context: Context): Int? =
        context.resources.getIdentifier("schedule_ic_notification", "drawable", context.packageName)
            .takeIf { it != 0 }

    /**
     * Meizu ships under both names: `meizu` on its own phones, `mblu` on the Mblu sub-brand. Both
     * `BRAND` and `MANUFACTURER` are searched because carrier builds have historically filled only
     * one of them. No capability probe exists -- Flyme exposes no property or package feature for
     * this mechanism, which is part of why the parameters had to be reverse engineered at all.
     */
    private fun isFlymeFamily(): Boolean {
        val identity = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()
        return identity.contains("meizu") || identity.contains("mblu")
    }

    /** Suffix of [VENDOR_LIVE_CHANNEL_PREFIX]; per-vendor so their settings stay separable. */
    private fun channelId(): String = VENDOR_LIVE_CHANNEL_PREFIX + "flyme"

    internal companion object {
        /** `is_live` is the single switch; without it Flyme treats the notification as ordinary. */
        const val EXTRA_IS_LIVE = "is_live"

        const val EXTRA_LIVE_OPERATION = "notification.live.operation"
        const val EXTRA_LIVE_TYPE = "notification.live.type"
        const val EXTRA_LIVE_CAPSULE = "notification.live.capsule"
        const val EXTRA_LIVE_FEATURE = "notification.live.feature"
        const val EXTRA_FEATURE_TITLE_TEXT = "notification.live.feature.titleText"
        const val EXTRA_FEATURE_CONTENT_TEXT = "notification.live.feature.contentText"
        const val EXTRA_FEATURE_FIRST_TITLE_TEXT = "notification.live.feature.firstTitleText"
        const val EXTRA_FEATURE_FIRST_CONTENT_TEXT = "notification.live.feature.firstContentText"
        const val EXTRA_FEATURE_LAST_TITLE_TEXT = "notification.live.feature.lastTitleText"
        const val EXTRA_FEATURE_LAST_CONTENT_TEXT = "notification.live.feature.lastContentText"
        const val EXTRA_FEATURE_PROGRESS = "notification.live.feature.progress"
        const val EXTRA_FEATURE_PROGRESS_TYPE = "notification.live.feature.progressType"
        const val EXTRA_FEATURE_PROGRESS_COLOR = "notification.live.feature.progressColor"
        const val EXTRA_FEATURE_SPLIT_LINE_ENABLE = "notification.live.feature.splitLineEnable"
        const val EXTRA_FEATURE_DESC_PIC = "notification.live.feature.descPic"
        /** 1 selects a determinate bar in Meituan's usage. */
        const val PROGRESS_TYPE_BAR = 1


        const val EXTRA_CAPSULE_STATUS = "notification.live.capsuleStatus"
        const val EXTRA_CAPSULE_TYPE = "notification.live.capsuleType"
        const val EXTRA_CAPSULE_CONTENT = "notification.live.capsuleContent"
        const val EXTRA_CAPSULE_TITLE = "notification.live.capsuleTitle"
        const val EXTRA_CAPSULE_ICON = "notification.live.capsuleIcon"
        const val EXTRA_CAPSULE_BG_COLOR = "notification.live.capsuleBgColor"
        const val EXTRA_CAPSULE_CONTENT_COLOR = "notification.live.capsuleContentColor"
        const val EXTRA_CAPSULE_REMOTE_VIEW = "notification.live.capsule.content.remote.view"

        /** The only `capsuleStatus` value observed to render; everything else hides the pill. */
        const val CAPSULE_STATUS_ENABLED = 1

        /** Demo defaults for fields whose effect was never observed; changing them would be a guess. */
        const val CAPSULE_TYPE_DEFAULT = 5
        const val LIVE_OPERATION_SHOW = 0
        const val LIVE_TYPE_STANDARD = 2

        /** Kept in step with `schedule_flyme_capsule.xml`, which pins the same dark pill. */
        val CAPSULE_BG_COLOR: Int = 0xFF1F1F24.toInt()
        val CAPSULE_TEXT_COLOR: Int = 0xFFFFFFFF.toInt()

        const val PROGRESS_MAX = 100

        const val LAYOUT_CAPSULE = "schedule_flyme_capsule"
        const val LAYOUT_CONTENT = "schedule_flyme_content"
        const val ID_CAPSULE_TEXT = "schedule_flyme_capsule_text"
        const val ID_CONTENT_TITLE = "schedule_flyme_title"
        const val ID_CONTENT_STATUS = "schedule_flyme_status"
        const val ID_CONTENT_PROGRESS = "schedule_flyme_progress"
        const val ID_LABEL_1 = "schedule_flyme_label_1"
        const val ID_VALUE_1 = "schedule_flyme_value_1"
        const val ID_LABEL_2 = "schedule_flyme_label_2"
        const val ID_VALUE_2 = "schedule_flyme_value_2"
        const val ID_LABEL_3 = "schedule_flyme_label_3"
        const val ID_VALUE_3 = "schedule_flyme_value_3"
        /** Accent colours for the right-hand status line. */
        const val STATUS_IN_CLASS_COLOR = 0xFF3B82F6.toInt()
        const val STATUS_COUNTDOWN_COLOR = 0xFFF59E0B.toInt()

        const val MAIN_ACTIVITY = "io.github.betterzslf.MainActivity"
        const val OPEN_INTENT_REQUEST = 5

        /** `setTimeoutAfter` treats 0/negative as "no timeout", so never post below one second. */
        const val MIN_TIMEOUT_MILLIS = 1_000L
    }
}
