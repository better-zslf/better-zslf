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
    )

    /**
     * The matrix. `capsuleStatus=1` is constant because every reference agrees on it, and
     * `is_live=true` is the master switch; the four numeric fields and the two RemoteViews carry
     * all the uncertainty.
     *
     * Index order matches [AppText.Schedule.LIVE_MATRIX_LABELS] and decides each variant's
     * notification id, so the label shown on screen always identifies the payload.
     */
    private val variants: List<Variant> = listOf(
        // Baseline: exactly what the published reverse-engineering demo does.
        Variant(1, 5, 0, 2, true, true),
        // Only the pill, no expanded card.
        Variant(1, 5, 0, 2, true, false),
        // No custom views at all: if this one renders, Flyme builds its own from the bundle.
        Variant(1, 5, 0, 2, false, false),
        // The other demo's capsuleType value.
        Variant(1, 1, 0, 2, true, true),
        Variant(1, 5, 0, 1, true, true),
        Variant(1, 5, 0, 10, true, true),
        Variant(1, 5, 1, 2, true, true),
        Variant(1, 0, 0, 2, true, true),
        // ---- Meituan-style probes (I..P): feature.* payload, no RemoteViews ----
        // I: closest transcription of Meituan's call chain for a single-stage card.
        Variant(1, 1, 0, 1, false, false, meituanStyle = true, withLegacyIsLive = false),
        // J: same, but keeps the fake is_live flag to see whether it hurts or is ignored.
        Variant(1, 1, 0, 1, false, false, meituanStyle = true, withLegacyIsLive = true),
        // K: type=4 is Meituan's other observed value (template/c).
        Variant(1, 1, 0, 4, false, false, meituanStyle = true, withLegacyIsLive = false),
        // L: capsuleType 5 with feature payload, isolating the capsule field from the card body.
        Variant(1, 5, 0, 1, false, false, meituanStyle = true, withLegacyIsLive = false),
        // M: feature payload plus the custom content view, to see which body wins.
        Variant(1, 1, 0, 1, false, true, meituanStyle = true, withLegacyIsLive = false),
        // N/O/P: feature payload with the dual-section (first/last) text Meituan also supports.
        Variant(1, 1, 0, 1, false, false, meituanStyle = true, withLegacyIsLive = false),
        Variant(1, 1, 0, 1, false, false, meituanStyle = true, withLegacyIsLive = false),
        Variant(1, 1, 0, 1, false, false, meituanStyle = true, withLegacyIsLive = false),
    )

    fun labels(): List<String> = AppText.Schedule.LIVE_MATRIX_LABELS

    /** True when this ROM has a vendor probe worth running. */
    fun supported(vendor: ScheduleVendor): Boolean = vendor == ScheduleVendor.MEIZU

    /** One synthetic class, identical for every variant so only the payload differs. */
    fun mockState(label: String, minutesLeft: Long = 33): ScheduleLiveState = ScheduleLiveState(
        phase = ScheduleLivePhase.IN_CLASS,
        title = label,
        text = Schedule.liveRemaining(minutesLeft),
        progress = 0.4f,
        startsAtMillis = System.currentTimeMillis() - 20 * 60_000L,
        endsAtMillis = System.currentTimeMillis() + minutesLeft.coerceAtLeast(1) * 60_000L,
        chip = Schedule.liveChipMinutes(minutesLeft),
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
        )
    }

    /** Clears every probe notification, but not the production live card. */
    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        repeat(variants.size) { manager.cancel(PROBE_ID_BASE + it) }
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

    /** True on Android 16+, where a vendor island may also compete with the native card. */
    fun nativeLiveAvailable(): Boolean = Build.VERSION.SDK_INT >= 36
}
