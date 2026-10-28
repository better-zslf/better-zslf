package io.github.betterzslf.ui.devtools

import io.github.betterzslf.core.log.LogEntry
import io.github.betterzslf.core.log.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebugLogWindowTest {
    private fun entry(id: Long, level: LogLevel = LogLevel.INFO) =
        LogEntry(level = level, tag = "test", message = "message-$id", timestampMillis = id)

    @Test fun defaultWindowContainsOnlyTheNewestTwoHundredInReverseAppendOrder() {
        val source = List(2_000) { entry(it.toLong()) }
        val shown = visibleEntries(source)
        assertEquals(200, shown.size)
        assertEquals(1_999L, shown.first().timestampMillis)
        assertEquals(1_800L, shown.last().timestampMillis)
        assertEquals(0L, source.first().timestampMillis)
        assertEquals(2_000, source.size)
    }

    @Test fun nonPositiveLimitsAreEmptyInsteadOfThrowing() {
        val source = listOf(entry(1), entry(2))
        assertEquals(emptyList(), visibleEntries(source, 0))
        assertEquals(emptyList(), visibleEntries(source, -1))
        assertEquals(emptyList(), visibleEntries(source, Int.MIN_VALUE))
        assertEquals(emptyList(), debugLogWindow(source, DebugLogFilter.ERRORS, 0))
    }

    @Test fun excessiveRequestsStayBoundedAtTwoHundred() {
        val source = List(500) { entry(it.toLong()) }
        assertEquals(200, visibleEntries(source, Int.MAX_VALUE).size)
        assertEquals(listOf(499L), visibleEntries(source, 1).map { it.timestampMillis })
        assertEquals(listOf(499L, 498L), visibleEntries(source, 2).map { it.timestampMillis })
    }

    @Test fun emptyAndShortListsDoNotCreateRows() {
        assertTrue(visibleEntries(emptyList()).isEmpty())
        val source = listOf(entry(1), entry(2), entry(3))
        assertEquals(listOf(3L, 2L, 1L), visibleEntries(source).map { it.timestampMillis })
    }

    @Test fun theWindowDoesNotRetainABackedViewIntoTheInputList() {
        val source = mutableListOf(entry(1), entry(2), entry(3))
        val shown = visibleEntries(source, 2)
        source.clear()
        assertEquals(listOf(3L, 2L), shown.map { it.timestampMillis })
    }

    @Test fun wallClockRollbackDoesNotReorderAppendHistory() {
        val source = listOf(entry(300), entry(100), entry(200))
        assertEquals(listOf(200L, 100L, 300L), visibleEntries(source).map { it.timestampMillis })
    }

    @Test fun issueAndErrorFiltersPreserveOrderAndRespectTheSameLimit() {
        val source = listOf(entry(1, LogLevel.DEBUG), entry(2), entry(3, LogLevel.WARN),
            entry(4, LogLevel.ERROR), entry(5), entry(6, LogLevel.WARN))
        assertEquals(listOf(6L, 4L, 3L), debugLogWindow(source, DebugLogFilter.ISSUES).map { it.timestampMillis })
        assertEquals(listOf(6L, 4L), debugLogWindow(source, DebugLogFilter.ISSUES, 2).map { it.timestampMillis })
        assertEquals(listOf(4L), debugLogWindow(source, DebugLogFilter.ERRORS).map { it.timestampMillis })
        assertTrue(debugLogWindow(source.filter { it.level == LogLevel.INFO }, DebugLogFilter.ERRORS).isEmpty())
    }

    @Test fun repeatedTimestampsDoNotDeduplicateDifferentMessages() {
        val source = listOf(entry(0).copy(message = "first"), entry(0).copy(message = "second"))
        assertEquals(listOf("second", "first"), visibleEntries(source).map { it.message })
    }

    @Test fun headerUsesTheExportFormatterAndExplicitUtcZone() {
        assertEquals("1970-01-01 00:00:00.000 UTC  test", debugLogHeader(entry(0)))
        assertEquals("2026-10-27 16:02:01.234 UTC  test", debugLogHeader(entry(1_793_116_921_234L)))
    }
}
