package io.github.betterzslf

/**
 * Lets the shared UI consume the system back gesture before the Activity finishes.
 *
 * The tab stacks live inside a single root route, so the navigation library only owns back
 * when the root stack itself has more than one entry. The Activity installs one platform
 * callback that asks the current screen first and falls through to the platform behaviour
 * (move the task to the background) when nothing was handled.
 */
object AppBackDispatcher {
    private var handler: (() -> Boolean)? = null

    /** Registered by the running composition; null while no screen can consume back. */
    fun register(callback: (() -> Boolean)?) { handler = callback }

    /** True when the shared UI popped something, false when the platform should handle it. */
    fun dispatch(): Boolean = handler?.invoke() ?: false
}
