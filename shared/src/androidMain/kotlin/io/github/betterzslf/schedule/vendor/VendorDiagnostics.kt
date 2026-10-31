package io.github.betterzslf.schedule.vendor

import android.app.NotificationManager
import android.content.Context
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.text.AppText.Schedule
import io.github.betterzslf.feature.schedule.ScheduleLivePhase
import io.github.betterzslf.feature.schedule.ScheduleLiveState
import io.github.betterzslf.feature.schedule.scheduleLiveMinutes
import io.github.betterzslf.platform.ScheduleVendor
import android.os.Build

/**
 * In-app probe for the reverse-engineered vendor payloads.
 *
 * The Flyme path is undocumented: which combination of the numeric extras makes its SystemUI host
 * a capsule is unknown, and a wrong guess fails silently. Rather than guess once and ask a user to
 * report "it did not work", this posts **one notification per combination**, each under its own id
 * and with its own label, so a single screenshot shows exactly which variant renders.
 *
 * Every variant is posted through the real renderer, not a mock: the point is to observe the ROM,
 * and a fake notification would answer a different question.
 */
internal object VendorDiagnostics {

    /** Which knob a variant is testing, so the label on the card explains itself. */
    private data class Variant(
        val label: String,
        val capsuleStatus: Int,
        val capsuleType: Int,
        val operation: Int,
        val type: Int,
        val withCapsuleRemoteView: Boolean,
        val withContentRemoteView: Boolean,
    )

    /**
     * The matrix. `capsuleStatus=1` and `is_live=true` are treated as constants because every
     * reference implementation agrees on them; the four numeric fields and the two RemoteViews
     * carry all the uncertainty.
     */
    private val variants: List<Variant> = listOf(
        // Baseline: exactly what the published reverse-engineering demo does.
        Variant(AppText.Schedule.LIVE_MATRIX_LABELS[0], 1, 5, 0, 2, true, true),
        // Only the pill, no expanded card: tests whether contentView is what breaks hosting.
        Variant(AppText.Schedule.LIVE_MATRIX_LABELS[1], 1, 5, 0, 2, true, false),
        // No custom views at all: if THIS shows a capsule, Flyme builds its own from the bundle.
        Variant(AppText.Schedule.LIVE_MATRIX_LABELS[2], 1, 5, 0, 2, false, false),
        // The other demo's capsuleType value.
        Variant(AppText.Schedule.LIVE_MATRIX_LABELS[3], 1, 1, 0, 2, true, true),
        // type=1 / 10 were both seen in the wild; try them.
        Variant(AppText.Schedule.LIVE_MATRIX_LABELS[4], 1, 5, 0, 1, true, true),
        Variant(AppText.Schedule.LIVE_MATRIX_LABELS[5], 1, 5, 0, 10, true, true),
        // operation=1 means "update"; some Flyme builds only host a capsule on update.
        Variant(AppText.Schedule.LIVE_MATRIX_LABELS[6], 1, 5, 1, 2, true, true),
        // A capsuleType of 0 is the "unset" value; included to prove the field matters at all.
        Variant(AppText.Schedule.LIVE_MATRIX_LABELS[7], 1, 0, 0, 2, true, true),
    )

    /** True when this ROM has a vendor probe worth running. */
    fun supported(vendor: ScheduleVendor): Boolean = vendor == ScheduleVendor.MEIZU

    /**
     * Post every variant at once.
     *
     * @return the number of notifications handed to the system, so the UI can say whether the
     *   platform even accepted them (a zero here means the failure is before Flyme sees anything).
     */
    fun runFlymeMatrix(context: Context, minutesLeft: Long = 33): Int {
        val flyme = FlymeLiveRenderer()
        if (!flyme.isSupported(context)) return 0
        val state = ScheduleLiveState(
            phase = ScheduleLivePhase.IN_CLASS,
            title = Schedule.LIVE_TEST_COURSE,
            text = Schedule.liveRemaining(minutesLeft.coerceAtLeast(1)),
            progress = 0.4f,
            startsAtMillis = System.currentTimeMillis() - 20 * 60_000L,
            endsAtMillis = System.currentTimeMillis() + minutesLeft.coerceAtLeast(1) * 60_000L,
            chip = Schedule.liveChipMinutes(minutesLeft),
        )
        val manager = context.getSystemService(NotificationManager::class.java) ?: return 0
        var posted = 0
        variants.forEachIndexed { index, variant ->
            val id = PROBE_ID_BASE + index
            posted += if (flyme.publishVariant(context, state, id, variant.label,
                    variant.capsuleStatus, variant.capsuleType, variant.operation, variant.type,
                    variant.withCapsuleRemoteView, variant.withContentRemoteView)) 1 else 0
        }
        // Also record the state itself so the diagnostics screen can show whether the app side
        // produced anything at all, independent of what Flyme decided to draw.
        lastRunSummary = AppText.Schedule.liveMatrixSummary(posted, variants.size,
            manager.areNotificationsEnabled())
        return posted
    }

    /** Clears every probe notification. */
    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        repeat(variants.size + 1) { manager.cancel(PROBE_ID_BASE + it) }
        FlymeLiveRenderer().cancel(context)
        lastRunSummary = ""
    }

    /** Last run outcome, shown in the settings screen. */
    var lastRunSummary: String = ""
        private set

    /** Labels in posting order, for the on-screen legend. */
    fun labels(): List<String> = variants.map { it.label }

    private const val PROBE_ID_BASE = 0x5C4E80
}
