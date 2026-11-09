package io.github.betterzslf.schedule.vendor

import android.content.Context
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.ScheduleLivePhase
import io.github.betterzslf.feature.schedule.MAX_BREAK_WINDOW_MILLIS
import io.github.betterzslf.feature.schedule.ScheduleLiveState
import io.github.betterzslf.platform.ScheduleVendor

/**
 * Chooses and drives the vendor-specific live renderer for this device.
 *
 * Two mechanisms coexist on purpose:
 *
 * 1. The standard Android 16 Live Update posted by
 *    [io.github.betterzslf.schedule.ScheduleLiveNotifications]. It stays completely untouched and
 *    keeps working on OPPO/OnePlus/realme, Samsung and Honor, which map the native API onto their
 *    own islands.
 * 2. A vendor-private notification for Xiaomi / vivo / Meizu, whose islands ignore the native API
 *    entirely. These need `RemoteViews` (Flyme) or proprietary `extras`, which Android 16 forbids
 *    inside a promoted ongoing notification -- so they can never be the same notification object.
 *
 * Posting both would show the user two cards for one class, so this dispatcher only runs the
 * vendor path, and only when the vendor renderer says it can actually deliver. Anything else
 * falls back to the standard card, which is why [publish] returns false instead of throwing.
 */
internal class VendorLiveDispatcher(private val renderers: List<VendorLiveRenderer> = defaultRenderers()) {

    private var active: VendorLiveRenderer? = null

    /** True when a vendor renderer claimed this device. */
    fun supportsVendor(context: Context): Boolean = renderers.any { runCatching { it.isSupported(context) }.getOrDefault(false) }

    /** The vendors this dispatcher actually implements; everything else uses the native card. */
    fun claimedVendors(): Set<ScheduleVendor> = VendorLivePolicy.claimed

    /**
     * Push the resolved live state to the vendor island.
     *
     * @return true when a vendor card was posted, meaning the standard card must NOT also post.
     */
    fun publish(
        context: Context,
        state: ScheduleLiveState,
        vendor: ScheduleVendor,
        capsuleLongText: Boolean = false,
    ): Boolean {
        if (!state.active) { cancel(context); return false }
        val renderer = renderers.firstOrNull { runCatching { it.isSupported(context) }.getOrDefault(false) }
        if (renderer == null) {
            active = null
            return false
        }
        // Only claim the card for vendors we actually implement; an unrelated manufacturer that
        // happens to expose a matching system feature must not hijack the standard path.
        if (vendor !in claimedVendors()) {
            active = null
            return false
        }
        val delivered = runCatching {
            renderer.publish(context, VendorLiveData(
                title = state.title,
                subtitle = state.text.takeIf { state.phase == ScheduleLivePhase.IN_CLASS }.orEmpty(),
                // The card's right-hand status is the short countdown, not the state's full text.
                //
                // state.text concatenates room, remaining time and clock because the native card
                // shows all three; the vendor card draws the room in its own column and the clock in
                // another, so reusing the concatenation put "3-102 · 10 分钟后开始 · 08:00 开始" on one
                // line beside the title, overflowing at larger font scales.
                status = shortStatus(state),
                // A break before the day's first class has no measured start, so the state publishes
                // no fraction and the card drew no bar at all -- which read as a broken card rather
                // than as "no denominator". The card still shows a bar there, using the same
                // 90-minute window the state machine treats as the longest plausible break: it fills
                // from near zero toward the bell instead of vanishing.
                progress = state.progress ?: if (state.phase == ScheduleLivePhase.BEFORE_NEXT) {
                    val remaining = state.startsAtMillis - System.currentTimeMillis()
                    if (remaining <= 0L) 1f
                    else (1f - (remaining.toFloat() / MAX_BREAK_WINDOW_MILLIS.toFloat())).coerceIn(0.02f, 1f)
                } else null,
                chip = state.chip,
                deadlineAtMillis = if (state.phase == ScheduleLivePhase.IN_CLASS) state.endsAtMillis else state.startsAtMillis,
                teacher = state.teacher,
                room = state.location,
                periodText = state.periodText,
                startsAtMillis = state.startsAtMillis,
                courseName = state.courseName,
                // Without this the renderer infers the phase from `progress == null`, and breaks
                // have a real fraction now, so a break would be drawn as a class: blue bar, period
                // column instead of start time.
                betweenClasses = state.phase == ScheduleLivePhase.BEFORE_NEXT,
                capsuleLongText = capsuleLongText,
            ))
        }.getOrDefault(false)
        if (!VendorLivePolicy.mayClaim(vendor, rendererSupports = true, rendererDelivered = delivered)) {
            active = null
            return false
        }
        active = renderer
        return true
    }

    /**
     * Countdown alone: the room and the clock already have their own columns on the card.
     *
     * Computed from the deadline rather than parsed out of the chip text, because the chip is
     * truncated for the pill ("33分") while the card can afford the full wording.
     */
    private fun shortStatus(state: ScheduleLiveState, now: Long = System.currentTimeMillis()): String {
        val deadline = if (state.phase == ScheduleLivePhase.IN_CLASS) state.endsAtMillis else state.startsAtMillis
        val minutes = io.github.betterzslf.feature.schedule.scheduleLiveMinutes(deadline - now)
        return if (state.phase == ScheduleLivePhase.IN_CLASS) {
            AppText.Schedule.liveRemaining(minutes)
        } else {
            AppText.Schedule.liveStartsIn(minutes)
        }
    }

    fun cancel(context: Context) {
        active?.let { runCatching { it.cancel(context) } }
        active = null
    }

    private companion object {
        fun defaultRenderers(): List<VendorLiveRenderer> = listOf(
            XiaomiLiveRenderer(),
            VivoLiveRenderer(),
            FlymeLiveRenderer(),
        )
    }
}
