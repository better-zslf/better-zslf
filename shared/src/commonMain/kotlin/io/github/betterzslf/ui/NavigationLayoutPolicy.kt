package io.github.betterzslf.ui

/** Presentation policy only: width changes never own, copy, clear or recreate tab back stacks. */
object NavigationLayoutPolicy {
    const val RAIL_MIN_WIDTH_DP = 600f
    const val TAB_COUNT = 4
    const val SAVED_STATE_SCHEMA = "main-four-tabs-v3"

    /** Unknown/unbounded/invalid measurements stay compact rather than guessing a wide layout. */
    fun useRail(widthDp: Float): Boolean = widthDp.isFinite() && widthDp >= RAIL_MIN_WIDTH_DP

    fun tabIndex(selected: Int): Int = if (selected in 0 until TAB_COUNT) selected else 0

    /** A reselect is a no-op; an invalid requested index keeps the current valid selection. */
    fun selectTab(current: Int, requested: Int): Int =
        if (requested in 0 until TAB_COUNT) requested else tabIndex(current)

    /** Return the existing object, not a copied history. Stack ownership remains above the shell. */
    fun <T> stackFor(stacks: List<T>, selected: Int): T {
        require(stacks.size == TAB_COUNT) { "The main shell requires four independent tab stacks" }
        return stacks[tabIndex(selected)]
    }
}
