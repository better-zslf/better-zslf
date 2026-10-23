package io.github.betterzslf.ui

import kotlin.test.*

/** Pure policy only; real Compose resize/rotation and inset rendering require later native UI checks. */
class NavigationLayoutPolicyTest {
    private fun stacks(): List<MutableList<String>> = List(4) { index -> mutableListOf("tab-$index", "detail-$index") }

    @Test fun belowSixHundredUsesTheExistingBottomBar() {
        for (width in listOf(0f, -0f, Float.MIN_VALUE, 320f, 390f, 599f, 599.999f)) {
            assertFalse(NavigationLayoutPolicy.useRail(width))
        }
    }

    @Test fun exactlySixHundredUsesRailWithoutAnOffByOneGap() {
        assertEquals(600f, NavigationLayoutPolicy.RAIL_MIN_WIDTH_DP)
        assertTrue(NavigationLayoutPolicy.useRail(600f))
    }

    @Test fun largerFiniteWidthsUseRail() {
        for (width in listOf(600.001f, 640f, 834f, 1280f, Float.MAX_VALUE)) {
            assertTrue(NavigationLayoutPolicy.useRail(width))
        }
    }

    @Test fun negativeUnknownAndUnboundedWidthsStayConservativelyCompact() {
        for (width in listOf(-1f, -600f, -Float.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(NavigationLayoutPolicy.useRail(width))
        }
    }

    @Test fun invalidSavedSelectionFallsBackWithoutChangingAnyStack() {
        val stacks = stacks()
        val before = stacks.map { it.toList() }
        for (index in listOf(-1, 4, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(0, NavigationLayoutPolicy.tabIndex(index))
            assertSame(stacks[0], NavigationLayoutPolicy.stackFor(stacks, index))
        }
        assertEquals(before, stacks)
    }

    @Test fun requestedSelectionIsValidatedWithoutImplicitReselectPop() {
        for (current in 0..3) {
            assertEquals(current, NavigationLayoutPolicy.selectTab(current, current))
            assertEquals(current, NavigationLayoutPolicy.selectTab(current, -1))
            assertEquals(current, NavigationLayoutPolicy.selectTab(current, 4))
            for (requested in 0..3) assertEquals(requested, NavigationLayoutPolicy.selectTab(current, requested))
        }
        assertEquals(0, NavigationLayoutPolicy.selectTab(Int.MAX_VALUE, -1))
        assertEquals(3, NavigationLayoutPolicy.selectTab(-1, 3))
    }

    @Test fun repeatedTabSwitchesReturnExistingIndependentHistories() {
        val stacks = stacks()
        val before = stacks.map { it.toList() }
        var selected = 0
        for (requested in listOf(3, 1, 2, 0, 0, 3)) {
            selected = NavigationLayoutPolicy.selectTab(selected, requested)
            assertSame(stacks[requested], NavigationLayoutPolicy.stackFor(stacks, selected))
            assertEquals(before, stacks)
        }
        stacks[3].add("profile-child")
        assertEquals(2, stacks[0].size)
        assertEquals(3, stacks[3].size)
    }

    @Test fun foldResizeAndRotationOnlyChangeChromeNotSelectionOrHistoryOwnership() {
        val stacks = stacks()
        val before = stacks.map { it.toList() }
        val selected = 3
        val profile = NavigationLayoutPolicy.stackFor(stacks, selected)
        for (width in listOf(390f, 600f, 834f, 599.9f, 1280f, Float.NaN, 390f)) {
            NavigationLayoutPolicy.useRail(width)
            assertEquals(3, NavigationLayoutPolicy.tabIndex(selected))
            assertSame(profile, NavigationLayoutPolicy.stackFor(stacks, selected))
            assertEquals(before, stacks)
        }
    }

    @Test fun reselectKeepsNestedHistoryRatherThanResettingToTheTabRoot() {
        val stacks = stacks()
        stacks[1].addAll(listOf("run-task", "run-tracker"))
        val existing = stacks[1].toList()
        val selected = NavigationLayoutPolicy.selectTab(1, 1)
        assertSame(stacks[1], NavigationLayoutPolicy.stackFor(stacks, selected))
        assertEquals(existing, stacks[1])
    }

    @Test fun shellRequiresExactlyFourStacksInsteadOfInventingAnEmptyDetailOwner() {
        assertEquals(4, NavigationLayoutPolicy.TAB_COUNT)
        for (size in listOf(0, 1, 3, 5)) {
            assertFailsWith<IllegalArgumentException> { NavigationLayoutPolicy.stackFor(List(size) { mutableListOf("root") }, 0) }
        }
    }
}
