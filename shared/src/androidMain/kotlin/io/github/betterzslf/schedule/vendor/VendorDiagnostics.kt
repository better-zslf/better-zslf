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
     * Replays class -> break -> class over [DEMO_STEP_MILLIS] steps.
     *
     * Exists because a transition cannot be judged from a single frame, and the real card only
     * reposts when a boundary alarm fires -- waiting for a real timetable to move is not a practical
     * check. Each step replaces the previous card, so the user watches one notification change
     * rather than hunting through several.
     */
    fun runSequence(context: Context, longCapsule: Boolean) {
        stopSequence()
        clear(context)
        // Each step uses a distinctly different fraction so the bar visibly moves: with every step
        // at the same value the sequence looked frozen even though the card was being replaced.
        val steps: List<Pair<Long, () -> Unit>> = listOf(
            0L to {
                publish(context, mockState(Schedule.LIVE_TEST_COURSE, progress = 0.15f), DEMO_ID,
                    Schedule.LIVE_TRANSITION_IN_CLASS, longCapsule)
            },
            DEMO_STEP_MILLIS to {
                publish(context, mockBreakState(Schedule.LIVE_TEST_NEXT_COURSE, progress = 0.55f), DEMO_ID,
                    Schedule.LIVE_TRANSITION_BREAK, longCapsule)
            },
            (DEMO_STEP_MILLIS * 2) to {
                publish(context, mockState(Schedule.LIVE_TEST_COURSE, progress = 0.85f),
                    DEMO_ID, Schedule.LIVE_TRANSITION_IN_CLASS, longCapsule)
            },
            (DEMO_STEP_MILLIS * 3) to { clear(context) },
        )
        steps.forEach { (delay, action) -> handler.postDelayed(action, delay) }
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

    /** Two minutes: long enough to watch a card settle, short enough to sit through three steps. */
    const val DEMO_STEP_MILLIS: Long = 60_000L

    private const val TAG = "schedule.probe"
    private const val DEMO_ID = 0x5C4E11
    private const val BREAK_ID = 0x5C4E12

    /** Every id this object can post; "clear all" iterates exactly this. */
    private val IDS = listOf(DEMO_ID, BREAK_ID, VENDOR_LIVE_TEST_NOTIFICATION_ID)
}
