package io.github.betterzslf.feature.schedule

import kotlin.test.*
import kotlinx.datetime.toInstant

/**
 * The live-notification state machine. These are the rules that decide what the ongoing
 * notification claims, so each one is pinned without a device.
 */
class ScheduleLiveStateTest {
    // 2026-10-30 is a Friday. Times below are Asia/Shanghai wall clock.
    private fun at(hour: Int, minute: Int = 0): Long =
        kotlinx.datetime.LocalDateTime(2026, 10, 30, hour, minute)
            .toInstant(kotlinx.datetime.TimeZone.of(SCHEDULE_TIME_ZONE_ID))
            .toEpochMilliseconds()

    private fun block(
        id: String = "c1",
        title: String = "高等数学",
        location: String = "A101",
        start: Long = at(8),
        end: Long = at(9, 40),
    ) = ScheduleLiveBlock(id, title, location, start, end)

    @Test fun insideAClassReportsTheCurrentOne() {
        val state = scheduleLiveState(listOf(block()), at(9))
        assertEquals(ScheduleLivePhase.IN_CLASS, state.phase)
        assertTrue(state.title.contains("高等数学"))
        assertTrue(state.text.contains("A101"))
        assertNotNull(state.progress)
    }

    @Test fun classWindowIsHalfOpenSoBoundariesNeverLie() {
        val lecture = block()
        // Exactly at the start: already in class.
        assertEquals(ScheduleLivePhase.IN_CLASS, scheduleLiveState(listOf(lecture), at(8)).phase)
        // Exactly at the end: no longer in class, and it cannot come back as "next".
        val after = scheduleLiveState(listOf(lecture), at(9, 40))
        assertNotEquals(ScheduleLivePhase.IN_CLASS, after.phase)
        assertEquals(ScheduleLivePhase.NONE, after.phase)
    }

    @Test fun progressMovesFromZeroToOneAcrossTheClass() {
        val lecture = block(start = at(8), end = at(10))
        assertEquals(0f, scheduleLiveState(listOf(lecture), at(8)).progress)
        assertEquals(0.5f, scheduleLiveState(listOf(lecture), at(9)).progress)
        assertTrue(scheduleLiveState(listOf(lecture), at(9, 59)).progress!! > 0.98f)
    }

    @Test fun beforeTheFirstClassCountsDownToIt() {
        val state = scheduleLiveState(listOf(block(start = at(10), end = at(11))), at(9))
        assertEquals(ScheduleLivePhase.BEFORE_NEXT, state.phase)
        assertTrue(state.title.contains("高等数学"))
        // A countdown has no honest denominator, so no fake percentage is published.
        assertNull(state.progress)
    }

    @Test fun betweenTwoClassesTheCountdownPointsAtTheLaterOne() {
        val morning = block(id = "a", title = "高数", start = at(8), end = at(9))
        val noon = block(id = "b", title = "数据结构", start = at(10), end = at(11))
        val state = scheduleLiveState(listOf(morning, noon), at(9, 30))
        assertEquals(ScheduleLivePhase.BEFORE_NEXT, state.phase)
        assertTrue(state.title.contains("数据结构"))
    }

    @Test fun afterTheLastClassThereIsNoLiveState() {
        // A permanent "no more classes" ongoing notification would be undismissable noise.
        val state = scheduleLiveState(listOf(block(start = at(8), end = at(9))), at(12))
        assertEquals(ScheduleLivePhase.NONE, state.phase)
        assertFalse(state.active)
    }

    @Test fun anEmptyDayProducesNoNotification() {
        assertEquals(ScheduleLivePhase.NONE, scheduleLiveState(emptyList(), at(10)).phase)
    }

    @Test fun overlappingBlocksPickTheOneThatStartedFirst() {
        // The school publishes overlapping electives; the block already running is the honest answer.
        val first = block(id = "a", title = "先开始", start = at(8), end = at(10))
        val second = block(id = "b", title = "后开始", start = at(9), end = at(11))
        val state = scheduleLiveState(listOf(second, first), at(9, 30))
        assertEquals(ScheduleLivePhase.IN_CLASS, state.phase)
        assertTrue(state.title.contains("先开始"))
    }

    @Test fun blocksAreOrderedIndependentlyOfInputOrder() {
        val night = block(id = "n", title = "晚课", start = at(19), end = at(20))
        val morning = block(id = "m", title = "早课", start = at(8), end = at(9))
        val state = scheduleLiveState(listOf(night, morning), at(7))
        assertEquals(ScheduleLivePhase.BEFORE_NEXT, state.phase)
        assertTrue(state.title.contains("早课"))
    }

    @Test fun zeroLengthBlockDoesNotDivideByZero() {
        val instant = at(9)
        val state = scheduleLiveState(listOf(block(start = instant, end = instant)), at(9))
        // Not "in class" (end is exclusive), and it is not in the future either.
        assertEquals(ScheduleLivePhase.NONE, state.phase)
    }

    @Test fun clockFormattingIsSchoolLocalAndPadded() {
        assertEquals("08:00", ScheduleLiveClock.format(at(8)))
        assertEquals("19:05", ScheduleLiveClock.format(at(19, 5)))
    }

    @Test fun remainingMinutesNeverGoNegative() {
        assertEquals("剩余 0 分钟", io.github.betterzslf.core.text.AppText.Schedule.liveRemaining(-5))
        assertEquals("剩余 30 分钟", io.github.betterzslf.core.text.AppText.Schedule.liveRemaining(30))
    }

    @Test fun minutesRoundUpSoAClassIsNeverReportedAsZeroMinutesLeft() {
        // Truncating would say "0 minutes left" with 59 seconds still to go.
        assertEquals(1L, scheduleLiveMinutes(59_000L))
        assertEquals(1L, scheduleLiveMinutes(1L))
        assertEquals(2L, scheduleLiveMinutes(60_001L))
        assertEquals(2L, scheduleLiveMinutes(120_000L))
        assertEquals(0L, scheduleLiveMinutes(0L))
        assertEquals(0L, scheduleLiveMinutes(-1L))
        // A full 40-minute class reads 40 at its start, then 1 for its final minute.
        assertEquals(40L, scheduleLiveMinutes(40 * 60_000L))
        assertEquals(1L, scheduleLiveMinutes(1_000L))
    }

    @Test fun inClassTextNeverShowsZeroMinutes() {
        val lecture = block(start = at(8), end = at(9))
        val almostOver = scheduleLiveState(listOf(lecture), at(8, 59) + 30_000L)
        assertEquals(ScheduleLivePhase.IN_CLASS, almostOver.phase)
        assertFalse(almostOver.text.contains("剩余 0 分钟"), almostOver.text)
        assertTrue(almostOver.text.contains("剩余 1 分钟"), almostOver.text)
    }

    @Test fun chipIsShortEnoughForTheStatusBarCapsule() {
        // Android suggests <= 7 characters for setShortCriticalText.
        assertEquals("25分", scheduleLiveChip(25))
        assertEquals("1分", scheduleLiveChip(1))
        assertEquals("0分", scheduleLiveChip(0))
        assertTrue(scheduleLiveChip(999).length <= 7)
        assertEquals("99分", scheduleLiveChip(999))
    }

    @Test fun stateCarriesAChipForBothActivePhases() {
        val lecture = block(start = at(8), end = at(10))
        val inClass = scheduleLiveState(listOf(lecture), at(9))
        assertTrue(inClass.chip.isNotBlank())
        val before = scheduleLiveState(listOf(block(start = at(10), end = at(11))), at(9))
        assertTrue(before.chip.isNotBlank())
        assertEquals("", scheduleLiveState(emptyList(), at(9)).chip)
    }
}
