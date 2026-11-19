package io.github.betterzslf.ui.schedule

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime
import io.github.betterzslf.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.time.Instant

/** True production Session + Runtime integration. Only academic I/O, storage and native delivery are fakes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleBindingSessionTest {
    private class Backend {
        val term = ScheduleTerm("fixture-term", "Fixture semester", true)
        var authentication = 0
        var fetches = 0
        var closes = 0
        val receivedPasswords = mutableListOf<String>()
        val authenticationGates = mutableListOf<CompletableDeferred<Unit>?>()
        var fetchGate: CompletableDeferred<Unit>? = null
        var fetchFailure = false
        var ignoreAuthenticationCancellation = false
        fun source() = object : AcademicSource {
            override suspend fun authenticate(account: String, password: String): AcademicIdentity {
                val index = authentication++
                receivedPasswords += password
                val gate = authenticationGates.getOrNull(index)
                if (ignoreAuthenticationCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
                return AcademicIdentity(account)
            }
            override suspend fun terms(): List<ScheduleTerm> = listOf(term)
            override suspend fun fetch(term: ScheduleTerm): ScheduleSnapshot {
                fetches++
                fetchGate?.await()
                if (fetchFailure) error("secret fixture upstream response")
                return ScheduleSnapshot(term, emptyList(), weekMondays = mapOf(1 to "2026-10-26"))
            }
            override fun close() { closes++ }
        }
    }
    private class Store : KeyValueStore {
        private val memory = MemoryKeyValueStore()
        var failPut = false
        override fun get(key: String): String? = memory.get(key)
        override fun put(key: String, value: String) {
            if (failPut) error("fixture storage secret")
            memory.put(key, value)
        }
        override fun remove(key: String) = memory.remove(key)
        override fun clear() = memory.clear()
    }
    private class Native : ScheduleNativeBridge {
        override val supported = true
        override val capabilities = MutableStateFlow(ScheduleNativeCapabilities(supported = true))
        override val openRequests = MutableStateFlow<ScheduleNativeOpenRequest?>(null)
        var closes = 0
        override fun refreshCapabilities() = Unit
        override fun requestNotificationPermission() = true
        override fun openNotificationSettings() = true
        override fun openExactAlarmSettings() = true
        override fun openBatteryOptimizationSettings() = true
        override fun openPromotedNotificationSettings() = true
        override fun requestPinWidget(kind: ScheduleWidgetKind) = true
        override fun publish(publication: ScheduleNativePublication) = true
        override fun clear(status: ScheduleNativeStatus, ownerRevision: String?) = Unit
        override fun consumeOpenRequest() { openRequests.value = null }
        override fun close() { closes++ }
    }
    private fun TestScope.runtime(backend: Backend = Backend(), store: KeyValueStore = MemoryKeyValueStore(), native: Native = Native()) =
        ScheduleRuntime(backgroundScope, store, backend::source, native,
            { Instant.parse("2026-10-29T00:00:00Z").toEpochMilliseconds() }).also { it.setOwner("fixture-slot") }
    private fun form(runtime: ScheduleRuntime, account: String = "fixture-academic", password: String = " fixture secret "): ScheduleBindingSession =
        ScheduleBindingSession.open(runtime, updatingPassword = false).also {
            it.accountChanged(it.inputGeneration, account)
            it.passwordChanged(it.inputGeneration, password)
        }
    private suspend fun verified(page: ScheduleBindingSession): ScheduleBindingSession.Verification {
        val request = assertNotNull(page.beginVerification(page.inputGeneration))
        assertTrue(page.verify(request))
        assertTrue(page.controls().canConfirm)
        return request
    }

    @Test fun verifyDoesNotPersistAndOwnConfirmationNavigatesExactlyOnceAfterRevisionAdvance() = runTest {
        val backend = Backend(); val store = Store(); val native = Native()
        val runtime = runtime(backend, store, native)
        val page = form(runtime)
        val before = runtime.ownerRevision
        verified(page)
        assertNull(runtime.state.value.boundAccount)
        assertEquals(listOf(" fixture secret "), backend.receivedPasswords)
        assertEquals("fi••••ic", page.display().verifiedLabel)
        val request = assertNotNull(page.beginConfirmation(page.inputGeneration))
        var callbacks = 0
        assertTrue(page.confirm(request) { callbacks++ })
        assertNotEquals(before, runtime.ownerRevision)
        assertEquals("fixture-academic", runtime.state.value.boundAccount)
        assertEquals(1, callbacks)
        assertFalse(page.confirm(request) { callbacks++ })
        assertEquals(1, callbacks)
        assertEquals("", page.draft.password)
        page.close()
        assertEquals(0, native.closes)
        runtime.close()
    }

    @Test fun mountingANewPageRetiresOldEventsButDoesNotCancelTheOldVerification() = runTest {
        val backend = Backend(); val gate = CompletableDeferred<Unit>()
        backend.authenticationGates += gate
        val runtime = runtime(backend)
        val first = form(runtime)
        val old = assertNotNull(first.beginVerification(first.inputGeneration))
        val result = async { first.verify(old) }
        runCurrent()
        assertTrue(runtime.state.value.verifying)
        val second = form(runtime, "second-academic")
        assertTrue(runtime.state.value.verifying)
        assertFalse(first.back(first.inputGeneration) { fail("Old page back callback") })
        assertNull(first.beginConfirmation(first.inputGeneration))
        assertNull(second.beginVerification(second.inputGeneration)) // Original global busy/spinner contract.
        gate.complete(Unit)
        assertFalse(result.await())
        assertFalse(runtime.state.value.verifying)
        assertTrue(second.controls().canVerify)
        first.close(); second.close(); runtime.close()
    }

    @Test fun oldPageDisposeAndFinallyCannotCancelANewPagesSynchronousClaim() = runTest {
        val backend = Backend(); val runtime = runtime(backend)
        val first = form(runtime)
        verified(first)
        val second = form(runtime, "second-academic")
        val next = assertNotNull(second.beginVerification(second.inputGeneration))
        first.close()
        assertTrue(second.verify(next))
        assertTrue(second.controls().canConfirm)
        assertEquals("second-academic", runtime.state.value.verifiedAccount)
        second.close(); runtime.close()
    }

    @Test fun inputAwayAndBackAndRetainedInputCallbacksNeverRevalidateTheOldAttempt() = runTest {
        val backend = Backend(); val gate = CompletableDeferred<Unit>()
        backend.authenticationGates += gate
        backend.ignoreAuthenticationCancellation = true
        val runtime = runtime(backend); val page = form(runtime)
        val oldGeneration = page.inputGeneration
        val request = assertNotNull(page.beginVerification(oldGeneration))
        val pending = async { page.verify(request) }
        runCurrent()
        page.accountChanged(page.inputGeneration, "other")
        page.accountChanged(page.inputGeneration, "fixture-academic")
        page.passwordChanged(oldGeneration, "retained callback must not apply")
        assertEquals(" fixture secret ", page.draft.password)
        gate.complete(Unit)
        assertFalse(pending.await())
        assertFalse(page.controls().canConfirm)
        assertNull(page.beginConfirmation(page.inputGeneration))
        assertEquals(ScheduleBindingNotice.INPUT_CHANGED, page.display().notice)
        page.close(); runtime.close()
    }

    @Test fun forcedSameOwnerAndAwayBackInvalidateAnOtherwiseIdenticalPage() = runTest {
        val runtime = runtime(); val page = form(runtime)
        verified(page)
        var left = 0
        assertTrue(page.confirm(assertNotNull(page.beginConfirmation(page.inputGeneration))) { left++ })
        assertEquals(1, left)
        page.close()
        val replacement = form(runtime)
        val revision = runtime.ownerRevision
        runtime.setOwner("fixture-slot", force = true)
        assertEquals(revision, runtime.ownerRevision) // Disk restore makes the public string an ABA.
        assertNull(replacement.beginVerification(replacement.inputGeneration))
        val fresh = form(runtime)
        runtime.setOwner(null)
        runtime.setOwner("fixture-slot")
        assertNull(fresh.beginVerification(fresh.inputGeneration))
        replacement.close(); fresh.close(); runtime.close()
    }

    @Test fun confirmationDoesNotNavigateAfterAnExternalForceDuringItsRefresh() = runTest {
        val backend = Backend(); val gate = CompletableDeferred<Unit>()
        backend.fetchGate = gate
        val runtime = runtime(backend); val page = form(runtime)
        verified(page)
        val confirmation = assertNotNull(page.beginConfirmation(page.inputGeneration))
        var callbacks = 0
        val pending = async { page.confirm(confirmation) { callbacks++ } }
        runCurrent()
        assertEquals("fixture-academic", runtime.state.value.boundAccount)
        runtime.setOwner("fixture-slot", force = true)
        gate.complete(Unit)
        try { assertFalse(pending.await()) } catch (_: CancellationException) { /* The retired app sync may cancel its waiter. */ }
        assertEquals(0, callbacks)
        page.close(); runtime.close()
    }

    @Test fun oldConfirmationCannotNavigateAfterANewPageMountDuringItsOwnSuccessfulRefresh() = runTest {
        val backend = Backend(); val gate = CompletableDeferred<Unit>()
        backend.fetchGate = gate
        val runtime = runtime(backend); val first = form(runtime)
        verified(first)
        var callbacks = 0
        val pending = async { first.confirm(assertNotNull(first.beginConfirmation(first.inputGeneration))) { callbacks++ } }
        runCurrent()
        val second = form(runtime)
        gate.complete(Unit)
        assertFalse(pending.await())
        assertEquals(0, callbacks)
        assertEquals("fixture-academic", runtime.state.value.boundAccount)
        first.close()
        assertTrue(second.controls().canVerify)
        second.close(); runtime.close()
    }

    @Test fun savingFailureKeepsInputsAndMakesNoCallbackWhileFiniteMessagesHideDiagnostics() = runTest {
        val store = Store(); val runtime = runtime(store = store); val page = form(runtime)
        verified(page)
        store.failPut = true
        var callbacks = 0
        assertFalse(page.confirm(assertNotNull(page.beginConfirmation(page.inputGeneration))) { callbacks++ })
        assertEquals(0, callbacks)
        assertEquals("fixture-academic", page.draft.account)
        assertEquals(" fixture secret ", page.draft.password)
        assertEquals(ScheduleBindingNotice.SAVE_FAILED, page.display().notice)
        assertEquals(ScheduleBindingMessage.STORAGE_FAILURE, page.display().runtimeMessage)
        assertEquals(ScheduleBindingMessage.READ_FAILED, safeScheduleBindingMessage("secret arbitrary upstream"))
        assertFalse(page.toString().contains(page.draft.password))
        page.close(); runtime.close()
    }

    @Test fun cancelledPageJobDoesNotPublishVerificationOrConsumeItsConfirmationContinuation() = runTest {
        val backend = Backend(); val gate = CompletableDeferred<Unit>()
        backend.authenticationGates += gate
        val runtime = runtime(backend); val page = form(runtime)
        val request = assertNotNull(page.beginVerification(page.inputGeneration))
        val pending = async { page.verify(request) }
        runCurrent(); pending.cancelAndJoin()
        assertFalse(page.controls().canConfirm)
        assertFalse(runtime.state.value.verifying)
        assertNull(runtime.state.value.verifiedAccount)
        gate.complete(Unit)
        page.close()
        val confirming = form(runtime)
        verified(confirming)
        val fetchGate = CompletableDeferred<Unit>()
        backend.fetchGate = fetchGate
        var callbacks = 0
        val confirmation = assertNotNull(confirming.beginConfirmation(confirming.inputGeneration))
        val saving = async { confirming.confirm(confirmation) { callbacks++ } }
        runCurrent()
        assertEquals("fixture-academic", runtime.state.value.boundAccount)
        saving.cancelAndJoin()
        assertEquals(0, callbacks)
        confirming.close()
        fetchGate.complete(Unit)
        runCurrent()
        assertNotNull(runtime.state.value.snapshot) // Page cancellation never closes the app-owned sync.
        runtime.close()
    }

    @Test fun explicitPromptEventsAreVisibleLeaseBoundAndSingleUseWithoutAuthenticating() = runTest {
        val backend = Backend(); val runtime = runtime(backend)
        var opened = 0
        val hidden = ScheduleBindingPromptSession.open(runtime, visible = false)
        assertFalse(hidden.dispatch(ScheduleBindingUiEvent.PROMPT_BIND) { opened++ })
        val old = ScheduleBindingPromptSession.open(runtime, visible = true)
        runtime.setOwner("fixture-slot", force = true)
        assertFalse(old.dispatch(ScheduleBindingUiEvent.PROMPT_BIND) { opened++ })
        // Prompt frequency is persistent; another local slot gets its first prompt.
        runtime.setOwner("another-slot")
        val shown = ScheduleBindingPromptSession.open(runtime, visible = true)
        assertTrue(shown.dispatch(ScheduleBindingUiEvent.PROMPT_BIND) { opened++ })
        assertFalse(shown.dispatch(ScheduleBindingUiEvent.PROMPT_BIND) { opened++ })
        assertEquals(1, opened)
        assertEquals(0, backend.authentication)
        hidden.close(); old.close(); shown.close(); runtime.close()
    }
}
