package io.github.betterzslf.ui

import kotlin.test.*

/** Pure policy only; Compose recreation, insets and resize are also checked on native Android. */
class NavigationLayoutPolicyTest {
    private fun stacks() = List(5) { index -> mutableListOf("tab-$index", "detail-$index") }

    @Test fun compactAndUnknownWidthsKeepTheBottomBar() {
        for (width in listOf(0f, -0f, Float.MIN_VALUE, 320f, 390f, 599.999f,
            -1f, -600f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(NavigationLayoutPolicy.useRail(width))
        }
    }
    @Test fun exactlySixHundredAndLargerFiniteWidthsUseRail() {
        assertEquals(600f, NavigationLayoutPolicy.RAIL_MIN_WIDTH_DP)
        for (width in listOf(600f, 600.001f, 834f, 1280f, Float.MAX_VALUE)) {
            assertTrue(NavigationLayoutPolicy.useRail(width))
        }
    }
    @Test fun invalidSavedSelectionNeverInventsANewStack() {
        val stacks = stacks()
        val before = stacks.map { it.toList() }
        for (index in listOf(-1, 5, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(0, NavigationLayoutPolicy.tabIndex(index))
            assertSame(stacks[0], NavigationLayoutPolicy.stackFor(stacks, index))
        }
        assertEquals(before, stacks)
    }
    @Test fun allFiveSelectionsAndReselectsAreStable() {
        for (current in 0..4) {
            assertEquals(current, NavigationLayoutPolicy.selectTab(current, current))
            assertEquals(current, NavigationLayoutPolicy.selectTab(current, -1))
            assertEquals(current, NavigationLayoutPolicy.selectTab(current, 5))
            for (requested in 0..4) assertEquals(requested, NavigationLayoutPolicy.selectTab(current, requested))
        }
        assertEquals(0, NavigationLayoutPolicy.selectTab(Int.MAX_VALUE, -1))
        assertEquals(4, NavigationLayoutPolicy.selectTab(-1, 4))
    }
    @Test fun timetableThirdTabHasItsOwnHistory() {
        val stacks = stacks()
        val before = stacks.map { it.toList() }
        var selected = 0
        for (requested in listOf(4, 1, 2, 3, 0, 2, 2, 4)) {
            selected = NavigationLayoutPolicy.selectTab(selected, requested)
            assertSame(stacks[requested], NavigationLayoutPolicy.stackFor(stacks, selected))
            assertEquals(before, stacks)
        }
        stacks[2].add("schedule-detail")
        stacks[4].add("profile-child")
        assertEquals(3, stacks[2].size)
        assertEquals(3, stacks[4].size)
        assertEquals(2, stacks[3].size)
    }
    @Test fun resizingDoesNotChangeProfileOwnership() {
        val stacks = stacks()
        val profile = stacks[4]
        val before = stacks.map { it.toList() }
        for (width in listOf(390f, 600f, 834f, 599.9f, 1280f, Float.NaN, 390f)) {
            NavigationLayoutPolicy.useRail(width)
            assertEquals(4, NavigationLayoutPolicy.tabIndex(4))
            assertSame(profile, NavigationLayoutPolicy.stackFor(stacks, 4))
            assertEquals(before, stacks)
        }
    }
    @Test fun reselectKeepsNestedHistory() {
        val stacks = stacks()
        stacks[1].addAll(listOf("run-task", "run-tracker"))
        val before = stacks[1].toList()
        assertSame(stacks[1], NavigationLayoutPolicy.stackFor(stacks, NavigationLayoutPolicy.selectTab(1, 1)))
        assertEquals(before, stacks[1])
    }
    @Test fun requiresExactlyFiveStacksAndHasAnUpgradeStateNamespace() {
        assertEquals(5, NavigationLayoutPolicy.TAB_COUNT)
        assertEquals("main-five-tabs-v2", NavigationLayoutPolicy.SAVED_STATE_SCHEMA)
        for (size in listOf(0, 1, 3, 4, 6)) {
            assertFailsWith<IllegalArgumentException> { NavigationLayoutPolicy.stackFor(List(size) { mutableListOf("root") }, 0) }
        }
    }
}
