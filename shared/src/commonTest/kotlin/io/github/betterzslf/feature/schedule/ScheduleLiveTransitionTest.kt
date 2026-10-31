package io.github.betterzslf.feature.schedule

import kotlin.test.*

/**
 * The live card's phase transitions across a real school day.
 *
 * These pin the behaviour that a device screenshot cannot show in one frame: what happens between
 * periods, across the lunch break, and after the last class. The rules were agreed explicitly:
 *
 * - two consecutive periods of the same course are ONE class, so the short gap between them is not
 *   a break and must not be announced as one;
 * - a long gap (lunch, evening) is not live activity, so no card claims to be tracking anything;
 * - when a class ends and another follows soon, the card counts down rather than disappearing.
 */
class ScheduleLiveTransitionTest {

    private val minute = 60_000L
    private val base = 1_000_000_000_000L // arbitrary fixed instant; only offsets matter

    private fun block(startMin: Long, endMin: Long, title: String, periods: List<Int> = emptyList()) =
        ScheduleLiveBlock(
            id = title + startMin,
            title = title,
            location = "A101",
            startsAtMillis = base + startMin * minute,
            endsAtMillis = base + endMin * minute,
            periods = periods,
        )

    private fun at(min: Long) = base + min * minute

    @Test fun insideAClassReportsInClassWithProgress() {
        val state = scheduleLiveState(listOf(block(0, 90, "数据结构")), at(45))
        assertEquals(ScheduleLivePhase.IN_CLASS, state.phase)
        assertEquals("数据结构", state.courseName)
        assertEquals(0.5f, state.progress)
    }

    @Test fun mergedPeriodsStayOneClassAcrossTheirOwnBreak() {
        // Periods 1-2 run 08:00-09:40 as a single occurrence; the 5-minute gap between them is not
        // a break. Halfway through the second period there must still be one in-class state.
        val state = scheduleLiveState(listOf(block(0, 100, "高等数学", periods = listOf(1, 2))), at(60))
        assertEquals(ScheduleLivePhase.IN_CLASS, state.phase)
        assertEquals("高等数学", state.courseName)
        assertTrue(state.progress != null && state.progress!! > 0.5f)
    }

    @Test fun shortGapCountsDownToTheNextClass() {
        val day = listOf(block(0, 100, "高等数学", listOf(1, 2)), block(110, 200, "数据结构", listOf(3, 4)))
        val state = scheduleLiveState(day, at(105))
        assertEquals(ScheduleLivePhase.BEFORE_NEXT, state.phase)
        assertEquals("数据结构", state.courseName)
        // The bar describes the break, so it runs from the previous class's end to the next start.
        assertNotNull(state.progress)
    }

    @Test fun lunchGapShowsNothing() {
        // Morning class ends at minute 100, afternoon starts at 240 -- a 140-minute gap. Checked
        // ten minutes after the morning class finished, where a short break would still show a card.
        val day = listOf(block(0, 100, "高等数学"), block(240, 340, "数据结构"))
        val state = scheduleLiveState(day, at(110))
        assertEquals(ScheduleLivePhase.NONE, state.phase)
    }

    @Test fun eveningGapAfterTheLastClassShowsNothing() {
        val day = listOf(block(0, 100, "高等数学"))
        // Still "today" and a later block would exist tomorrow, but nothing is happening now.
        val state = scheduleLiveState(day, at(300))
        assertEquals(ScheduleLivePhase.NONE, state.phase)
    }

    @Test fun gapJustInsideTheWindowStillCountsDown() {
        val day = listOf(block(0, 100, "高等数学"), block(190, 240, "数据结构"))
        // 89 minutes after the first class ended: inside the 90-minute window.
        assertEquals(ScheduleLivePhase.BEFORE_NEXT, scheduleLiveState(day, at(189)).phase)
    }

    @Test fun gapJustOutsideTheWindowShowsNothing() {
        // The window measures the gap to the next class START. Here it starts at 195, so at
        // minute 104 the wait is 91 minutes: one past the limit.
        val day = listOf(block(0, 100, "高等数学"), block(195, 240, "数据结构"))
        assertEquals(ScheduleLivePhase.NONE, scheduleLiveState(day, at(104)).phase)
    }

    @Test fun backToBackClassesSwitchWithoutAGap() {
        // Class A ends exactly when B starts: no break, straight from one card to the other.
        val day = listOf(block(0, 100, "高等数学"), block(100, 200, "数据结构"))
        assertEquals("高等数学", scheduleLiveState(day, at(99)).courseName)
        assertEquals("数据结构", scheduleLiveState(day, at(100)).courseName)
        assertEquals(ScheduleLivePhase.IN_CLASS, scheduleLiveState(day, at(100)).phase)
    }

    @Test fun aClassIsNeverClaimedBeforeItStartsOrAfterItEnds() {
        val day = listOf(block(10, 100, "高等数学"))
        assertEquals(ScheduleLivePhase.IN_CLASS, scheduleLiveState(day, at(10)).phase)
        // One second past the end is not "in class".
        assertNotEquals(ScheduleLivePhase.IN_CLASS, scheduleLiveState(day, at(100)).phase)
    }
}
