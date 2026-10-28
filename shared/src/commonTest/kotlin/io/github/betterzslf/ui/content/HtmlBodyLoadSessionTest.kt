package io.github.betterzslf.ui.content

import io.github.betterzslf.platform.HtmlPanelLoadState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HtmlBodyLoadSessionTest {
    @Test
    fun newDocumentStartsLoadingAndLoadingCallbacksDoNotInventReadiness() {
        val session = HtmlBodyLoadSession()
        val request = session.state.value.request
        repeat(3) { session.onPlatformState(request, HtmlPanelLoadState.LOADING) }
        assertEquals(HtmlBodyPhase.LOADING, session.state.value.phase)
        session.onPlatformState(request, HtmlPanelLoadState.READY)
        assertEquals(HtmlBodyPhase.READY, session.state.value.phase)
        session.onPlatformState(request, HtmlPanelLoadState.LOADING)
        assertEquals(HtmlBodyPhase.READY, session.state.value.phase)
    }

    @Test
    fun mainFrameFailureIsTerminalUntilAnExplicitRetry() {
        val session = HtmlBodyLoadSession()
        val request = session.state.value.request
        session.onPlatformState(request, HtmlPanelLoadState.FAILED)
        session.onPlatformState(request, HtmlPanelLoadState.READY)
        session.onPlatformState(request, HtmlPanelLoadState.LOADING)
        assertEquals(HtmlBodyPhase.FAILED, session.state.value.phase)
        session.retry()
        val retried = session.state.value.request
        assertNotSame(request, retried)
        assertEquals(HtmlBodyPhase.LOADING, session.state.value.phase)
        session.onPlatformState(retried, HtmlPanelLoadState.READY)
        assertEquals(HtmlBodyPhase.READY, session.state.value.phase)
    }

    @Test
    fun currentDocumentCanReportARealProcessFailureAfterItWasReady() {
        val session = HtmlBodyLoadSession()
        val request = session.state.value.request
        session.onPlatformState(request, HtmlPanelLoadState.READY)
        session.onPlatformState(request, HtmlPanelLoadState.FAILED)
        assertEquals(HtmlBodyPhase.FAILED, session.state.value.phase)
    }

    @Test
    fun unavailableHostDoesNotPretendItRenderedAWebDocument() {
        val session = HtmlBodyLoadSession()
        val request = session.state.value.request
        session.onPlatformState(request, HtmlPanelLoadState.UNAVAILABLE)
        session.onPlatformState(request, HtmlPanelLoadState.READY)
        assertEquals(HtmlBodyPhase.UNAVAILABLE, session.state.value.phase)
    }

    @Test
    fun defaultDeadlineIsTwentySecondsAndItNeverReportsSuccess() = runTest {
        assertEquals(20_000L, HtmlBodyLoadSession.TIMEOUT_MILLIS)
        val session = HtmlBodyLoadSession()
        launch { session.awaitTimeout(session.state.value.request) }
        runCurrent()
        advanceTimeBy(19_999)
        runCurrent()
        assertEquals(HtmlBodyPhase.LOADING, session.state.value.phase)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(HtmlBodyPhase.TIMED_OUT, session.state.value.phase)
    }

    @Test
    fun aLateTimerCannotReplaceRealReadiness() = runTest {
        val session = HtmlBodyLoadSession()
        val request = session.state.value.request
        launch { session.awaitTimeout(request) }
        runCurrent()
        session.onPlatformState(request, HtmlPanelLoadState.READY)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(HtmlBodyPhase.READY, session.state.value.phase)
    }

    @Test
    fun explicitRetryInvalidatesEveryOldCallbackEvenForIdenticalHtml() {
        val session = HtmlBodyLoadSession()
        val old = session.state.value.request
        session.retry()
        val current = session.state.value.request
        assertNotSame(old, current)
        HtmlPanelLoadState.entries.forEach { session.onPlatformState(old, it) }
        assertSame(current, session.state.value.request)
        assertEquals(HtmlBodyPhase.LOADING, session.state.value.phase)
        session.onPlatformState(current, HtmlPanelLoadState.READY)
        assertEquals(HtmlBodyPhase.READY, session.state.value.phase)
    }

    @Test
    fun aStaleDeadlineDoesNotFailTheRetriedDocument() = runTest {
        val session = HtmlBodyLoadSession()
        val old = session.state.value.request
        launch { session.awaitTimeout(old, 100) }
        runCurrent()
        advanceTimeBy(50)
        session.retry()
        val current = session.state.value.request
        advanceTimeBy(50)
        runCurrent()
        assertSame(current, session.state.value.request)
        assertEquals(HtmlBodyPhase.LOADING, session.state.value.phase)
    }

    @Test
    fun timeoutDoesNotAcceptLateNativeSuccessOrFailureBeforeRetry() = runTest {
        val session = HtmlBodyLoadSession()
        val old = session.state.value.request
        launch { session.awaitTimeout(old, 1) }
        runCurrent()
        advanceTimeBy(1)
        runCurrent()
        session.onPlatformState(old, HtmlPanelLoadState.READY)
        session.onPlatformState(old, HtmlPanelLoadState.FAILED)
        assertEquals(HtmlBodyPhase.TIMED_OUT, session.state.value.phase)
        session.retry()
        session.onPlatformState(session.state.value.request, HtmlPanelLoadState.READY)
        assertEquals(HtmlBodyPhase.READY, session.state.value.phase)
    }

    @Test
    fun disposingRejectsCallbacksTimeoutsAndFurtherRetries() = runTest {
        val session = HtmlBodyLoadSession()
        val request = session.state.value.request
        launch { session.awaitTimeout(request, 1) }
        runCurrent()
        session.close()
        session.close()
        HtmlPanelLoadState.entries.forEach { session.onPlatformState(request, it) }
        session.retry()
        advanceTimeBy(1)
        runCurrent()
        assertSame(request, session.state.value.request)
        assertEquals(HtmlBodyPhase.CLOSED, session.state.value.phase)
    }

    @Test
    fun changingDocumentsCannotAdoptThePreviousDocumentCallback() {
        val old = HtmlBodyLoadSession()
        val current = HtmlBodyLoadSession()
        val oldRequest = old.state.value.request
        old.close()
        old.onPlatformState(oldRequest, HtmlPanelLoadState.READY)
        current.onPlatformState(oldRequest, HtmlPanelLoadState.READY)
        assertEquals(HtmlBodyPhase.CLOSED, old.state.value.phase)
        assertEquals(HtmlBodyPhase.LOADING, current.state.value.phase)
        current.onPlatformState(current.state.value.request, HtmlPanelLoadState.READY)
        assertEquals(HtmlBodyPhase.READY, current.state.value.phase)
    }

    @Test
    fun cancelledCompositionTimerDoesNotChangeTheDocument() = runTest {
        val session = HtmlBodyLoadSession()
        val request = session.state.value.request
        val job = launch { session.awaitTimeout(request) }
        runCurrent()
        job.cancel()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(HtmlBodyPhase.LOADING, session.state.value.phase)
        session.onPlatformState(request, HtmlPanelLoadState.READY)
        assertEquals(HtmlBodyPhase.READY, session.state.value.phase)
    }
}
