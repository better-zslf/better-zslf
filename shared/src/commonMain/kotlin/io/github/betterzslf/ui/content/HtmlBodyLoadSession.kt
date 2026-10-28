package io.github.betterzslf.ui.content

import io.github.betterzslf.platform.HtmlPanelLoadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Opaque identity: equal HTML can still be explicitly reloaded without accepting the old document's callbacks. */
internal class HtmlBodyRequest

internal enum class HtmlBodyPhase { LOADING, READY, FAILED, TIMED_OUT, UNAVAILABLE, CLOSED }

internal data class HtmlBodyLoadState(val request: HtmlBodyRequest, val phase: HtmlBodyPhase = HtmlBodyPhase.LOADING)

/** No repository, HTML strings, URLs, native error text, or automatic retries are retained here. */
internal class HtmlBodyLoadSession {
    private val mutable = MutableStateFlow(HtmlBodyLoadState(HtmlBodyRequest()))
    val state: StateFlow<HtmlBodyLoadState> = mutable.asStateFlow()

    fun onPlatformState(request: HtmlBodyRequest, event: HtmlPanelLoadState) {
        mutable.update { current ->
            if (current.request !== request || current.phase == HtmlBodyPhase.CLOSED) current
            else when (event) {
                HtmlPanelLoadState.LOADING -> current
                HtmlPanelLoadState.READY -> if (current.phase == HtmlBodyPhase.LOADING) current.copy(phase = HtmlBodyPhase.READY) else current
                HtmlPanelLoadState.FAILED -> if (current.phase == HtmlBodyPhase.LOADING || current.phase == HtmlBodyPhase.READY)
                    current.copy(phase = HtmlBodyPhase.FAILED) else current
                HtmlPanelLoadState.UNAVAILABLE -> if (current.phase == HtmlBodyPhase.LOADING)
                    current.copy(phase = HtmlBodyPhase.UNAVAILABLE) else current
            }
        }
    }

    fun retry() {
        mutable.update { if (it.phase == HtmlBodyPhase.CLOSED) it else HtmlBodyLoadState(HtmlBodyRequest()) }
    }

    suspend fun awaitTimeout(request: HtmlBodyRequest, timeoutMillis: Long = TIMEOUT_MILLIS) {
        delay(timeoutMillis.coerceAtLeast(0))
        mutable.update { current ->
            if (current.request === request && current.phase == HtmlBodyPhase.LOADING)
                current.copy(phase = HtmlBodyPhase.TIMED_OUT) else current
        }
    }

    fun close() { mutable.update { it.copy(phase = HtmlBodyPhase.CLOSED) } }

    companion object { const val TIMEOUT_MILLIS = 20_000L }
}
