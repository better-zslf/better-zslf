package io.github.betterzslf.schedule.vendor

import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.text.AppText.Schedule
import io.github.betterzslf.feature.schedule.ScheduleLivePhase
import io.github.betterzslf.feature.schedule.ScheduleLiveState
import io.github.betterzslf.platform.ScheduleVendor

/**
 * In-app demonstrations of the live card.
 *
 * The vendor payloads are undocumented and fail silently, so the app ships three buttons that post
 * a real card rather than describing one: a plain demo, the between-classes variant, and a timed
 * sequence that walks class -> break -> class the way a real timetable does.
 *
 * Every demo posts under [VENDOR_LIVE_TEST_NOTIFICATION_ID] or its own probe id, never the
 * production id. Reconcile runs continuously and cancels the production vendor card whenever no
 * class is live, so anything posted under that id is withdrawn about a second after it appears.
 */
internal object VendorDiagnostics {

    /** The one-shot demo. */
    fun runDemoCard(context: Context, longCapsule: Boolean): Boolean {
        val state = mockState(Schedule.LIVE_TEST_COURSE)
        return publish(context, state, DEMO_ID, state.courseName, longCapsule)
    }

    /** The between-classes card, so its amber styling can be compared with the blue one. */
    fun runBreakCard(context: Context, longCapsule: Boolean): Boolean {
        val state = mockBreakState(Schedule.LIVE_TEST_NEXT_COURSE)
        return publish(context, state, BREAK_ID, state.courseName, longCapsule)
    }

    /**
     * Replays class -> break -> class in one minute, with the bar advancing every second.
     *
     * A transition cannot be judged from a single frame, and the real card only reposts when a
     * boundary alarm fires. Reposting once per second makes the bar move continuously instead of
     * jumping between three fractions, which is what the sequence is for: watching the card behave
     * like a live one rather than merely seeing three stills.
     *
     * Sixty seconds total, split evenly: 20s of class, 20s of break, 20s of the next class. The
     * phase boundary is what the demo exists to show, so each phase gets equal time.
     */
    fun runSequence(context: Context, longCapsule: Boolean) {
        stopSequence()
        clear(context)
        val totalTicks = SEQUENCE_MILLIS / SEQUENCE_TICK_MILLIS
        for (tick in 0..totalTicks) {
            handler.postDelayed({
                val elapsed = tick * SEQUENCE_TICK_MILLIS
                when {
                    elapsed >= SEQUENCE_MILLIS -> clear(context)
                    elapsed < SEQUENCE_MILLIS / 3 -> {
                        // First class. Both the bar and the "remaining" text are derived from
                        // elapsed time, so they cannot disagree the way they would if the minutes
                        // were a constant while the bar moved.
                        val fraction = 0.05f + 0.9f * (elapsed.toFloat() / (SEQUENCE_MILLIS / 3f))
                        val minutesLeft = (DEMO_CLASS_MINUTES * (1f - fraction)).toLong().coerceAtLeast(1L)
                        publish(context, mockState(Schedule.LIVE_TEST_COURSE, minutesLeft, fraction),
                            DEMO_ID, Schedule.LIVE_TRANSITION_IN_CLASS, longCapsule)
                    }
                    elapsed < SEQUENCE_MILLIS * 2 / 3 -> {
                        // Break: a separate bar in the phase colour, also advancing.
                        val into = elapsed - SEQUENCE_MILLIS / 3
                        val fraction = 0.05f + 0.9f * (into.toFloat() / (SEQUENCE_MILLIS / 3f))
                        // Counting down to the next class, so the number shrinks as the bar fills.
                        val minutesToStart = (DEMO_BREAK_MINUTES * (1f - fraction)).toLong().coerceAtLeast(1L)
                        publish(context, mockBreakState(Schedule.LIVE_TEST_NEXT_COURSE, minutesToStart, fraction),
                            DEMO_ID, Schedule.LIVE_TRANSITION_BREAK, longCapsule)
                    }
                    else -> {
                        val into = elapsed - SEQUENCE_MILLIS * 2 / 3
                        val fraction = 0.05f + 0.9f * (into.toFloat() / (SEQUENCE_MILLIS / 3f))
                        val minutesLeft = (DEMO_CLASS_MINUTES * (1f - fraction)).toLong().coerceAtLeast(1L)
                        publish(context, mockState(Schedule.LIVE_TEST_COURSE, minutesLeft, fraction),
                            DEMO_ID, Schedule.LIVE_TRANSITION_IN_CLASS, longCapsule)
                    }
                }
            }, tick * SEQUENCE_TICK_MILLIS)
        }
    }

    /** Cancels any pending sequence step. Safe to call when nothing is running. */
    fun stopSequence() {
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * Withdraws everything this object posted.
     *
     * Explicit ids rather than a guessed range: the previous implementation looped over the matrix's
     * index range, so once the matrix was gone it would have cancelled unrelated ids. Each id this
     * object can post is listed here, which is the only way "clear all" can be both complete and
     * safe.
     */
    fun clear(context: Context) {
        stopSequence()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // Cancel without a tag, because that is how they were posted: FlymeLiveRenderer calls
        // notify(id, ...) with no tag. The previous cancel(TAG, id) looked correct and silently
        // matched nothing, which is why "clear all" appeared to do nothing at all.
        IDS.forEach { manager.cancel(it) }
        // The non-vendor fallback posts through the standard path under its own tag.
        io.github.betterzslf.schedule.scheduleLiveClearTest(context)
    }

    /** Removes the standard-path demo card, which lives under a different tag. */
    fun clearStandard(context: Context) {
        stopSequence()
        io.github.betterzslf.schedule.scheduleLiveClearTest(context)
    }

    /** True when a vendor renderer claims this ROM, so the vendor demos make sense. */
    fun vendorSupported(vendor: ScheduleVendor): Boolean = vendor in VendorLivePolicy.claimed

    private fun publish(
        context: Context,
        state: ScheduleLiveState,
        id: Int,
        label: String,
        longCapsule: Boolean,
    ): Boolean {
        val flyme = FlymeLiveRenderer()
        if (!flyme.isSupported(context)) {
            // Non-vendor ROM: fall back to the standard card so the button still demonstrates
            // something rather than appearing broken.
            return io.github.betterzslf.schedule.scheduleLivePostTest(context, state)
        }
        return flyme.publishVariant(
            context = context,
            state = state,
            id = id,
            label = label,
            capsuleStatus = 1,
            capsuleType = 1,
            operation = 0,
            liveType = 2,
            withCapsuleRemoteView = true,
            withContentRemoteView = true,
            longCapsule = longCapsule,
        )
    }

    /**
     * One synthetic class in progress.
     *
     * Every displayed field is filled on purpose: an earlier version left teacher/room/period empty
     * and the three columns rendered blank, which read as "the layout is broken" rather than "the
     * mock has no data".
     */
    fun mockState(label: String, minutesLeft: Long = 33, progress: Float = 0.4f): ScheduleLiveState {
        val now = System.currentTimeMillis()
        return ScheduleLiveState(
            phase = ScheduleLivePhase.IN_CLASS,
            title = label,
            text = Schedule.liveRemaining(minutesLeft),
            progress = progress,
            startsAtMillis = now - 20 * 60_000L,
            endsAtMillis = now + minutesLeft.coerceAtLeast(1) * 60_000L,
            chip = Schedule.liveChipMinutes(minutesLeft),
            teacher = Schedule.LIVE_TEST_TEACHER,
            location = Schedule.LIVE_TEST_ROOM,
            periodText = Schedule.LIVE_TEST_PERIODS,
            courseName = label,
        )
    }

    /** The between-classes state: no progress denominator, start clock in the third column. */
    fun mockBreakState(label: String, minutesToStart: Long = 12, progress: Float = 0.35f): ScheduleLiveState {
        val now = System.currentTimeMillis()
        return ScheduleLiveState(
            phase = ScheduleLivePhase.BEFORE_NEXT,
            title = label,
            text = Schedule.liveStartsIn(minutesToStart),
            // A real break has a measurable fraction (previous class ended -> next class starts).
            progress = progress,
            startsAtMillis = now + minutesToStart.coerceAtLeast(1) * 60_000L,
            endsAtMillis = now + (minutesToStart.coerceAtLeast(1) + 40) * 60_000L,
            chip = Schedule.liveChipMinutes(minutesToStart),
            teacher = Schedule.LIVE_TEST_TEACHER,
            location = Schedule.LIVE_TEST_ROOM,
            periodText = Schedule.LIVE_TEST_PERIODS,
            courseName = label,
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    /** Whole demo length. One minute: long enough to read the card, short enough to re-run. */
    const val SEQUENCE_MILLIS: Long = 60_000L
    /** Repost cadence. One second is the finest granularity worth the notification churn. */
    const val SEQUENCE_TICK_MILLIS: Long = 1_000L
    /**
     * Class length the demo pretends to show.
     *
     * 45 is a real period, so the numbers on screen look like a genuine timetable rather than a
     * made-up range; the demo simply compresses the whole period into twenty seconds.
     */
    private const val DEMO_CLASS_MINUTES: Long = 45L
    private const val DEMO_BREAK_MINUTES: Long = 15L

    private const val TAG = "schedule.probe"
    private const val DEMO_ID = 0x5C4E11
    private const val BREAK_ID = 0x5C4E12

    /** Every id this object can post; "clear all" iterates exactly this. */
    private val IDS = listOf(DEMO_ID, BREAK_ID, VENDOR_LIVE_TEST_NOTIFICATION_ID)
}
