package io.github.betterzslf.schedule.vendor

import android.content.Context

/**
 * A vendor-specific "live" notification renderer.
 *
 * Each OEM ships its own undocumented (or semi-documented) island/胶囊 mechanism that has nothing
 * to do with Android 16 Live Updates. They are deliberately kept behind this interface so the
 * standard path in [io.github.betterzslf.schedule.ScheduleLiveNotifications] never gains a
 * RemoteViews dependency: Android 16 forbids custom content views for a promoted ongoing
 * notification, so mixing the two mechanisms in one notification is impossible.
 *
 * Implementations must fail closed. [isSupported] answers "could this work here", and [publish]
 * returning false means the caller falls back to the plain ongoing notification -- a vendor
 * mechanism that silently does nothing is worse than no vendor support at all.
 */
internal interface VendorLiveRenderer {
    /** True when this device and ROM version expose the vendor mechanism. */
    fun isSupported(context: Context): Boolean

    /**
     * Post or update the live notification.
     * @return true when the notification was handed to the system; false means "fall back".
     */
    fun publish(context: Context, data: VendorLiveData): Boolean

    /** Withdraw the notification (class finished, or the live state became inactive). */
    fun cancel(context: Context)
}

/**
 * Vendor-neutral input. Time decisions are already made by
 * [io.github.betterzslf.feature.schedule.scheduleLiveState]; renderers must not re-derive them,
 * or the card and the app would disagree at boundaries.
 */
internal data class VendorLiveData(
    /** Course name; callers guarantee non-blank. */
    val title: String,
    /** Room or period summary. May be blank. */
    val subtitle: String,
    /** Human status such as "剩余 33 分钟"; already localised. */
    val status: String,
    /** 0f..1f progress within the current class; null during a countdown. */
    val progress: Float?,
    /** Very short capsule text, ideally <= 7 characters. */
    val chip: String,
    /** Absolute end of the current phase, used for timers. */
    val deadlineAtMillis: Long,
    /** Instructor for the expanded card's first column. Blank when the source had none. */
    val teacher: String = "",
    /** Room for the second column; same value as [subtitle] when that already holds one. */
    val room: String = "",
    /** Period range such as "3-4节", for the third column. Blank when unknown. */
    val periodText: String = "",
    /** Phase start/end, used for the countdown and the "开始 10:00" column. */
    val startsAtMillis: Long = 0,
    /**
     * Bare course name for the card headline. Falls back to [title] when blank, so a caller that
     * only has the prefixed notification title still renders something sensible.
     */
    val courseName: String = "",
    /**
     * True between classes.
     *
     * Carried explicitly instead of inferring the phase from `progress == null`: breaks have a real
     * fraction now, so "no progress" no longer means "counting down" and the renderer would pick the
     * wrong colour and the wrong third column.
     */
    val betweenClasses: Boolean = false,
    /**
     * Show the course name beside the minutes in the vendor capsule.
     *
     * Off by default: the short form is what Flyme was observed to render, and some builds clip a
     * longer string. Exposed because the pill width is ROM-specific and only a device shows which
     * form fits -- this is the "some models may prefer the long text" case.
     */
    val capsuleLongText: Boolean = false,
    val notificationId: Int = VENDOR_LIVE_NOTIFICATION_ID,
)

/** One id for every vendor path: only one can own the device at a time anyway. */
internal const val VENDOR_LIVE_NOTIFICATION_ID = 0x5C4E01

/**
 * Id reserved for the in-app "test live notification" button.
 *
 * Distinct from the production id because the engine's reconcile runs on every tick and, when the
 * real timetable has no live class, cancels the vendor card. Sharing the production id meant the
 * test card was withdrawn within a second of being posted -- so the button looked broken while the
 * matrix probes, which use their own id range, stayed visible.
 *
 * [VendorLiveRenderer.cancel] must never touch this id: it belongs to the user's explicit action,
 * not to the derived production state.
 */
internal const val VENDOR_LIVE_TEST_NOTIFICATION_ID = 0x5C4E11

/** Channel id prefix; the suffix is the vendor name so their settings stay separable. */
internal const val VENDOR_LIVE_CHANNEL_PREFIX = "schedule_vendor_live_"

