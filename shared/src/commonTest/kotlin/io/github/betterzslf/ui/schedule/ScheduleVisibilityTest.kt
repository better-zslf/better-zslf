package io.github.betterzslf.ui.schedule

import io.github.betterzslf.feature.schedule.ScheduleTerm
import io.github.betterzslf.feature.settings.AppSettings
import kotlin.test.*

/** Weekday visibility and the term-window trimming rule. */
class ScheduleVisibilityTest {

    @Test fun noHiddenDaysMeansAllSevenInIsoOrder() {
        assertEquals((1..7).toList(), scheduleVisibleDays(emptySet()))
    }

    @Test fun hiddenDaysAreRemovedButOrderIsKept() {
        assertEquals(listOf(1, 2, 3, 4, 5), scheduleVisibleDays(setOf(6, 7)))
        assertEquals(listOf(3), scheduleVisibleDays(setOf(1, 2, 4, 5, 6, 7)))
    }

    @Test fun outOfRangeEntriesAreIgnoredRatherThanShrinkingTheWeek() {
        assertEquals((1..7).toList(), scheduleVisibleDays(setOf(0, -1, 8, 99)))
    }

    @Test fun hidingEveryDayFallsBackToAllSeven() {
        // An empty board would read as "no classes"; showing everything is the honest fallback.
        assertEquals((1..7).toList(), scheduleVisibleDays((1..7).toSet()))
    }

    @Test fun settingsAllowAnEmptyHiddenListByDefault() {
        assertEquals(emptyList(), AppSettings().hiddenWeekdays)
    }

    private fun term(id: String, name: String, default: Boolean = false) =
        ScheduleTerm(id, name, isDefault = default)

    @Test fun termWindowKeepsDefaultAndItsImmediateNeighbours() {
        val terms = listOf(
            term("t1", "2023-2024 第1学期"),
            term("t2", "2023-2024 第2学期"),
            term("t3", "2024-2025 第1学期", default = true),
            term("t4", "2024-2025 第2学期"),
            term("t5", "2025-2026 第1学期"),
        )
        assertEquals(listOf("t2", "t3", "t4"), scheduleVisibleTerms(terms).map { it.id })
    }

    @Test fun termWindowClampsAtBothEnds() {
        val first = listOf(term("t1", "a", default = true), term("t2", "b"), term("t3", "c"))
        assertEquals(listOf("t1", "t2"), scheduleVisibleTerms(first).map { it.id })
        val last = listOf(term("t1", "a"), term("t2", "b"), term("t3", "c", default = true))
        assertEquals(listOf("t2", "t3"), scheduleVisibleTerms(last).map { it.id })
    }

    @Test fun singleTermAndMissingDefaultAreBothSafe() {
        val single = listOf(term("t1", "only", default = true))
        assertEquals(listOf("t1"), scheduleVisibleTerms(single).map { it.id })
        // No default: keep everything rather than guessing a centre and hiding a needed term.
        val noDefault = listOf(term("t1", "a"), term("t2", "b"), term("t3", "c"), term("t4", "d"))
        assertEquals(noDefault.map { it.id }, scheduleVisibleTerms(noDefault).map { it.id })
        assertEquals(emptyList(), scheduleVisibleTerms(emptyList()).map { it.id })
    }

    @Test fun multipleDefaultsUseTheFirstWithoutCrashing() {
        val terms = listOf(term("t1", "a"), term("t2", "b", default = true), term("t3", "c", default = true), term("t4", "d"))
        assertEquals(listOf("t1", "t2", "t3"), scheduleVisibleTerms(terms).map { it.id })
    }
}
