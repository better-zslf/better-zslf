package io.github.betterzslf.schedule.vendor

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.text.AppText.Schedule
import io.github.betterzslf.feature.schedule.ScheduleLivePhase
import io.github.betterzslf.feature.schedule.ScheduleLiveState
import io.github.betterzslf.platform.ScheduleVendor

/**
 * In-app probe for the reverse-engineered vendor payloads.
 *
 * The Flyme path is undocumented, and a wrong guess fails silently. Rather than guess once and
 * ask the user to report "it did not work", this posts **one notification per combination**, each
 * under its own id and with its own label in the title, so one screenshot shows which variant
 * renders and how it looks.
 *
 * The data is deliberately synthetic rather than read from the timetable: the question is purely
 * "what does this ROM draw for this payload", and a real class would make variants hard to compare
 * (different titles, different remaining time).
 */
internal object VendorDiagnostics {

    /** Which knob a variant is testing, so the label on the card explains itself. */
    private data class Variant(
        val capsuleStatus: Int,
        val capsuleType: Int,
        val operation: Int,
        val type: Int,
        val withCapsuleRemoteView: Boolean,
        val withContentRemoteView: Boolean,
        /** Emit the `notification.live.feature.*` block that Meituan uses, and drop RemoteViews. */
        val meituanStyle: Boolean = false,
        /** Emit the fake `is_live` flag; Meituan never does, so probes can compare. */
        val withLegacyIsLive: Boolean = true,
        /** Overrides the mocked fraction, so a single screenshot proves the bar tracks progress. */
        val progress: Float? = null,
        /**
         * Use the phrase form in the capsule instead of the bare minute count.
         *
         * Which one Flyme's pill accepts is unverified: the phrase matches the design reference but
         * may clip, and the short form is what the working build used. Both are posted so the
         * choice can be made from a screenshot rather than an assumption.
         */
        val longCapsule: Boolean = false,
    )

    /**
     * The matrix. `capsuleStatus=1` is constant because every reference agrees on it, and
     * `is_live=true` is the master switch; the four numeric fields and the two RemoteViews carry
     * all the uncertainty.
     *
     * Index order matches [AppText.Schedule.LIVE_MATRIX_LABELS] and decides each variant's
     * notification id, so the label shown on screen always identifies the payload.
     */
    /**
     * The surviving matrix, after the rc19 device screenshots.
     *
     * Everything that produced a ring or a blank card has been removed rather than left in: those
     * variants were diagnostics for a question that is now answered (Flyme 12.6 ignores the
     * `feature.*` payload entirely and draws only our RemoteViews). Keeping them would keep
     * polluting every screenshot with unreadable entries.
     *
     * All survivors share the shape that renders: capsule RemoteViews on, content RemoteViews on.
     * They differ only in the two numeric fields whose effect was still unproven, plus one pair
     * with deliberately small and large progress so the bar can be seen tracking the value.
     */
    private val variants: List<Variant> = listOf(
        // The known-good baseline with the short capsule text ("33分").
        Variant(1, 1, 0, 2, true, true),
        // Same payload, long capsule text ("数据结构 · 剩余 23 分钟"). The two are compared side by
        // side because it is unknown whether Flyme's pill clips a longer string or is happy to
        // grow: the reference design uses the phrase, the working build used the short form.
        Variant(1, 1, 0, 2, true, true, longCapsule = true),
        // In-class and break phases, to confirm the bar switches blue/amber and fills in both.
        Variant(1, 1, 0, 2, true, true, progress = 0.5f),
    )

    fun labels(): List<String> = AppText.Schedule.LIVE_MATRIX_LABELS

    /** True when this ROM has a vendor probe worth running. */
    fun supported(vendor: ScheduleVendor): Boolean = vendor == ScheduleVendor.MEIZU

    /** One synthetic class, identical for every variant so only the payload differs. */
    /**
     * One synthetic class, identical for every variant so only the payload differs.
     *
     * The detail columns are filled with obvious placeholders rather than real data: the probe
     * answers "does this layout render", and made-up values are easier to spot in a screenshot
     * than real ones. Empty strings here were the bug that made the three-column card look blank.
     */
    fun mockState(label: String, minutesLeft: Long = 33): ScheduleLiveState = ScheduleLiveState(
        phase = ScheduleLivePhase.IN_CLASS,
        title = label,
        text = Schedule.liveRemaining(minutesLeft),
        progress = 0.4f,
        startsAtMillis = System.currentTimeMillis() - 20 * 60_000L,
        endsAtMillis = System.currentTimeMillis() + minutesLeft.coerceAtLeast(1) * 60_000L,
        chip = Schedule.liveChipMinutes(minutesLeft),
        teacher = AppText.Schedule.LIVE_TEST_TEACHER,
        location = AppText.Schedule.LIVE_TEST_ROOM,
        periodText = AppText.Schedule.LIVE_TEST_PERIODS,
        courseName = AppText.Schedule.LIVE_TEST_COURSE,
    )

    /**
     * Post every variant at once.
     *
     * @return how many notifications the system accepted. Zero means the failure happens before
     *   Flyme sees anything, which is a different problem from a payload it rejects.
     */
    fun runFlymeMatrix(context: Context): Int {
        val flyme = FlymeLiveRenderer()
        if (!flyme.isSupported(context)) return 0
        var posted = 0
        variants.forEachIndexed { index, variant ->
            if (publishOne(context, flyme, index, variant)) posted++
        }
        lastRunSummary = AppText.Schedule.liveMatrixSummary(posted, variants.size, notificationsOn(context))
        return posted
    }

    /** Post a single variant by index, so the user can compare one card at a time. */
    /**
     * A between-classes state, so the countdown variant can be inspected too.
     *
     * All matrix variants model "in class"; without this the orange countdown branch, the hidden
     * progress bar and the start-time column would never be seen on a device.
     */
    fun mockCountdownState(label: String, minutesToStart: Long = 12): ScheduleLiveState =
        ScheduleLiveState(
            phase = ScheduleLivePhase.BEFORE_NEXT,
            title = label,
            text = Schedule.liveStartsIn(minutesToStart),
            // A real break has a measurable fraction (previous class ended -> next class starts).
            // Leaving this null made the renderer hide the bar, which is why the countdown card
            // showed no progress at all even after the two-bar change.
            progress = 0.35f,
            startsAtMillis = System.currentTimeMillis() + minutesToStart.coerceAtLeast(1) * 60_000L,
            endsAtMillis = System.currentTimeMillis() + (minutesToStart.coerceAtLeast(1) + 40) * 60_000L,
            chip = Schedule.liveChipMinutes(minutesToStart),
            teacher = AppText.Schedule.LIVE_TEST_TEACHER,
            location = AppText.Schedule.LIVE_TEST_ROOM,
            periodText = AppText.Schedule.LIVE_TEST_PERIODS,
            courseName = AppText.Schedule.LIVE_TEST_COURSE,
        )

    /** Post the countdown variant on its own, on the same ids the matrix uses. */
    fun runCountdownProbe(context: Context): Boolean {
        val flyme = FlymeLiveRenderer()
        if (!flyme.isSupported(context)) return false
        clear(context)
        val label = AppText.Schedule.LIVE_COUNTDOWN_PROBE_LABEL
        val ok = flyme.publishVariant(
            context = context,
            state = mockCountdownState(label),
            id = PROBE_ID_BASE + COUNTDOWN_INDEX,
            label = label,
            capsuleStatus = 1,
            capsuleType = 1,
            operation = 0,
            liveType = 2,
            withCapsuleRemoteView = false,
            withContentRemoteView = true,
        )
        lastRunSummary = AppText.Schedule.liveMatrixSummary(if (ok) 1 else 0, 1, notificationsOn(context))
        return ok
    }

    fun runFlymeVariant(context: Context, index: Int): Boolean {
        val flyme = FlymeLiveRenderer()
        val variant = variants.getOrNull(index) ?: return false
        if (!flyme.isSupported(context)) return false
        // Clear the rest first: the point of a single-variant run is an unambiguous screenshot.
        clearExcept(context, index)
        val ok = publishOne(context, flyme, index, variant)
        lastRunSummary = AppText.Schedule.liveMatrixSummary(if (ok) 1 else 0, 1, notificationsOn(context))
        return ok
    }

    private fun publishOne(context: Context, flyme: FlymeLiveRenderer, index: Int, variant: Variant): Boolean {
        val label = labels().getOrElse(index) { "variant $index" }
        return flyme.publishVariant(
            context = context,
            state = mockState(label),
            id = PROBE_ID_BASE + index,
            label = label,
            capsuleStatus = variant.capsuleStatus,
            capsuleType = variant.capsuleType,
            operation = variant.operation,
            liveType = variant.type,
            withCapsuleRemoteView = variant.withCapsuleRemoteView,
            withContentRemoteView = variant.withContentRemoteView,
            meituanStyle = variant.meituanStyle,
            withLegacyIsLive = variant.withLegacyIsLive,
            progress = variant.progress,
            longCapsule = variant.longCapsule,
        )
    }

    /** Clears every probe notification, but not the production live card. */
    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        repeat(variants.size + 1) { manager.cancel(PROBE_ID_BASE + it) }
        lastRunSummary = ""
    }

    private fun clearExcept(context: Context, keep: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        repeat(variants.size) { if (it != keep) manager.cancel(PROBE_ID_BASE + it) }
    }

    private fun notificationsOn(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    /** Last run outcome, shown in the settings screen. */
    var lastRunSummary: String = ""
        private set

    /** Base id for probe notifications. Kept clear of VENDOR_LIVE_NOTIFICATION_ID (0x5C4E01). */
    private const val PROBE_ID_BASE = 0x5C4E80
    /** One past the last matrix variant, reserved for the countdown probe. */
    private const val COUNTDOWN_INDEX = 16

    /** True on Android 16+, where a vendor island may also compete with the native card. */
    fun nativeLiveAvailable(): Boolean = Build.VERSION.SDK_INT >= 36
}
