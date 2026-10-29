package io.github.betterzslf.feature.schedule.runtime

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.feature.schedule.*
import io.github.betterzslf.platform.*
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleRuntimeTest {
    private val term = ScheduleTerm("2026-2027-1", "合成学期", true)
    private fun snapshot(term: ScheduleTerm = this.term) = ScheduleSnapshot(term,
        listOf(ScheduleCourse("fixture-course", "合成课程", dayOfWeek = 4, periods = listOf(1, 2), weeks = listOf(1))),
        weekMondays = mapOf(1 to "2026-10-26"))
    private fun millis(value: String) = Instant.parse(value).toEpochMilliseconds()
    private val start = millis("2026-10-29T00:00:00Z")

    private class Backend(var terms: List<ScheduleTerm>, var snapshot: (ScheduleTerm) -> ScheduleSnapshot) {
        var authCalls = 0
        var fetchCalls = 0
        var termCalls = 0
        var authFailure = false
        var fetchFailure: Exception? = null
        var expireNext = false
        var authGate: CompletableDeferred<Unit>? = null
        var fetchGate: CompletableDeferred<Unit>? = null
        var ignoreCancellation = false
        fun source() = object : AcademicSource {
            override suspend fun authenticate(account: String, password: String): AcademicIdentity {
                authCalls++
                authGate?.await()
                if (authFailure) throw AcademicAuthenticationException()
                return AcademicIdentity(account)
            }
            override suspend fun terms(): List<ScheduleTerm> { termCalls++; return terms }
            override suspend fun fetch(term: ScheduleTerm): ScheduleSnapshot {
                fetchCalls++
                val gate = fetchGate
                if (ignoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
                if (expireNext) { expireNext = false; throw AcademicSessionExpiredException() }
                fetchFailure?.let { throw it }
                return snapshot(term)
            }
            override fun close() = Unit
        }
    }
    private class Native : ScheduleNativeBridge {
        override val supported = true
        override val capabilities = MutableStateFlow(ScheduleNativeCapabilities(supported = true))
        override val openRequests = MutableStateFlow<ScheduleNativeOpenRequest?>(null)
        var lease: String? = null
        var clearFailure = false
        var rejectPublication = false
        val publications = mutableListOf<ScheduleNativePublication>()
        override fun refreshCapabilities() = Unit
        override fun requestNotificationPermission() = true
        override fun openNotificationSettings() = true
        override fun openExactAlarmSettings() = true
        override fun openBatteryOptimizationSettings() = true
        override fun requestPinWidget(kind: ScheduleWidgetKind) = true
        override fun publish(publication: ScheduleNativePublication): Boolean {
            if (publication.ownerRevision != lease || rejectPublication) return false
            publications += publication
            return true
        }
        override fun clear(status: ScheduleNativeStatus, ownerRevision: String?) {
            lease = ownerRevision
            if (clearFailure) throw IllegalStateException("fixture failure")
        }
        override fun consumeOpenRequest() { openRequests.value = null }
        override fun close() = Unit
    }
    private class Store(val memory: MemoryKeyValueStore = MemoryKeyValueStore()) : KeyValueStore by memory {
        var failPut = false
        var failRemove = false
        var failGet = false
        override fun get(key: String): String? {
            if (failGet) throw IllegalStateException("fixture read failure")
            return memory.get(key)
        }
        override fun put(key: String, value: String) {
            if (failPut) throw IllegalStateException("fixture write failure")
            memory.put(key, value)
        }
        override fun remove(key: String) {
            if (failRemove) throw IllegalStateException("fixture removal failure")
            memory.remove(key)
        }
    }
    private fun TestScope.runtime(backend: Backend, store: KeyValueStore = MemoryKeyValueStore(), native: Native = Native(), now: () -> Long = { start }) =
        ScheduleRuntime(backgroundScope, store, backend::source, native, now)
    private suspend fun bind(runtime: ScheduleRuntime, account: String = "academic-fixture") {
        assertTrue(runtime.verifyBinding(account, "fixture-password"))
        assertTrue(runtime.confirmBinding())
    }

    @Test fun verifiedCredentialsAreNotPersistedBeforeConfirmation() = runTest {
        val store = Store()
        val backend = Backend(listOf(term), ::snapshot)
        val runtime = runtime(backend, store)
        runtime.setOwner("owner-a")
        assertTrue(runtime.state.value.showBindingPrompt)
        runtime.dismissBindingPrompt()
        assertTrue(runtime.verifyBinding("academic-fixture", "fixture-password"))
        assertNull(store.get(SchedulePersistence.DATA_KEY))
        assertNull(runtime.state.value.boundAccount)
        runtime.cancelVerification()
        assertFalse(runtime.confirmBinding())
        bind(runtime)
        assertNotNull(store.get(SchedulePersistence.DATA_KEY))
        assertEquals(term.id, runtime.state.value.snapshot?.term?.id)
        runtime.close()
    }

    @Test fun editingInputsInvalidatesLateVerification() = runTest {
        val backend = Backend(listOf(term), ::snapshot)
        backend.authGate = CompletableDeferred()
        val runtime = runtime(backend)
        runtime.setOwner("owner-a")
        var verified: Boolean? = null
        val task = backgroundScope.launch { verified = runtime.verifyBinding("academic-fixture", "fixture-password") }
        runCurrent()
        runtime.cancelVerification()
        backend.authGate!!.complete(Unit)
        task.join()
        assertEquals(false, verified)
        assertNull(runtime.state.value.verifiedAccount)
        assertFalse(runtime.confirmBinding())
        runtime.close()
    }

    @Test fun failedSaveDoesNotReplaceOriginalBindingOrHealthyCache() = runTest {
        val store = Store()
        val runtime = runtime(Backend(listOf(term), ::snapshot), store)
        runtime.setOwner("owner-a")
        bind(runtime)
        val healthy = runtime.state.value.snapshot
        assertTrue(runtime.verifyBinding("new-fixture", "another-fixture-password"))
        store.failPut = true
        assertFalse(runtime.confirmBinding())
        assertEquals("academic-fixture", runtime.state.value.boundAccount)
        assertSame(healthy, runtime.state.value.snapshot)
        assertTrue(runtime.state.value.storageFailure)
        runtime.close()
    }

    @Test fun defaultSemesterUsesSelectedOptionNotFirstOption() = runTest {
        val future = ScheduleTerm("2028-2029-2", "合成未来学期")
        val runtime = runtime(Backend(listOf(future, term), ::snapshot))
        runtime.setOwner("owner-a")
        bind(runtime)
        assertEquals(term.id, runtime.state.value.snapshot?.term?.id)
        assertEquals(term.id, runtime.state.value.sourceTermId)
        runtime.close()
    }

    @Test fun automaticDailyAttemptSurvivesRestartAndUsesSchoolMidnight() = runTest {
        val store = Store()
        val backend = Backend(listOf(term), ::snapshot)
        var now = millis("2026-10-29T15:59:00Z")
        var runtime = runtime(backend, store, now = { now })
        runtime.setOwner("owner-a")
        bind(runtime)
        runtime.onForeground()
        val firstDayCalls = backend.fetchCalls
        repeat(3) { runtime.onForeground() }
        assertEquals(firstDayCalls, backend.fetchCalls)
        runtime.close()
        runtime = runtime(backend, store, now = { now })
        runtime.setOwner("owner-a")
        assertFalse(runtime.state.value.showBindingPrompt)
        runtime.onForeground()
        assertEquals(firstDayCalls, backend.fetchCalls)
        now = millis("2026-10-29T16:01:00Z") // Oct 30 in school timezone, still Oct 29 UTC.
        runtime.onForeground()
        assertEquals(firstDayCalls + 1, backend.fetchCalls)
        runtime.close()
    }

    @Test fun failedDailyAttemptDoesNotLoopButManualRefreshAlwaysWorks() = runTest {
        val backend = Backend(listOf(term), ::snapshot)
        val runtime = runtime(backend)
        runtime.setOwner("owner-a")
        bind(runtime)
        val healthy = runtime.state.value.snapshot
        backend.fetchFailure = AcademicNetworkException()
        runtime.onForeground()
        val failedCalls = backend.fetchCalls
        runtime.onForeground()
        assertEquals(failedCalls, backend.fetchCalls)
        assertSame(healthy, runtime.state.value.snapshot)
        backend.fetchFailure = null
        runtime.refresh()
        assertEquals(failedCalls + 1, backend.fetchCalls)
        runtime.close()
    }

    @Test fun concurrentAutomaticAndManualSyncShareOneTask() = runTest {
        val backend = Backend(listOf(term), ::snapshot)
        val runtime = runtime(backend)
        runtime.setOwner("owner-a")
        bind(runtime)
        val previous = backend.fetchCalls
        backend.fetchGate = CompletableDeferred()
        val auto = backgroundScope.launch { runtime.onForeground() }
        runCurrent()
        val manual = backgroundScope.launch { runtime.refresh() }
        runCurrent()
        assertEquals(previous + 1, backend.fetchCalls)
        backend.fetchGate!!.complete(Unit)
        auto.join(); manual.join()
        assertEquals(previous + 1, backend.fetchCalls)
        runtime.close()
    }

    @Test fun sessionExpiryHasOneMergedLoginAndOneReplay() = runTest {
        val backend = Backend(listOf(term), ::snapshot)
        val runtime = runtime(backend)
        runtime.setOwner("owner-a")
        bind(runtime)
        backend.expireNext = true
        val authBefore = backend.authCalls
        val readsBefore = backend.fetchCalls
        runtime.refresh()
        assertEquals(authBefore + 1, backend.authCalls)
        assertEquals(readsBefore + 2, backend.fetchCalls)
        assertFalse(runtime.state.value.needsCredentials)
        backend.authFailure = true
        backend.expireNext = true
        runtime.refresh()
        assertTrue(runtime.state.value.needsCredentials)
        assertNotNull(runtime.state.value.snapshot)
        runtime.close()
    }

    @Test fun partialResponseNeverReplacesHealthyCacheOrReminderSeed() = runTest {
        val backend = Backend(listOf(term), ::snapshot)
        val native = Native()
        val runtime = runtime(backend, native = native)
        runtime.setOwner("owner-a")
        bind(runtime)
        val healthy = runtime.state.value.snapshot
        backend.snapshot = { snapshot(it).copy(courses = emptyList(), complete = false) }
        runtime.refresh()
        assertSame(healthy, runtime.state.value.snapshot)
        assertEquals(healthy, native.publications.last().snapshot)
        assertNotNull(runtime.state.value.message)
        runtime.close()
    }

    @Test fun delayedOldOwnerReplyCannotWriteToNewOwnerOrNativeLease() = runTest {
        val backend = Backend(listOf(term), ::snapshot)
        val store = Store()
        val native = Native()
        val runtime = runtime(backend, store, native)
        runtime.setOwner("owner-a")
        bind(runtime)
        val saved = store.get(SchedulePersistence.DATA_KEY)
        backend.fetchGate = CompletableDeferred()
        backend.ignoreCancellation = true
        val request = backgroundScope.launch { runtime.refresh() }
        runCurrent()
        runtime.setOwner("owner-b")
        val newLease = native.lease
        backend.fetchGate!!.complete(Unit)
        request.join()
        assertEquals("owner-b", runtime.state.value.owner)
        assertNull(runtime.state.value.snapshot)
        assertNull(runtime.state.value.boundAccount)
        assertEquals(saved, store.get(SchedulePersistence.DATA_KEY))
        assertEquals(newLease, native.lease)
        runtime.close()
    }

    @Test fun temporaryCampusExpiryRetainsEncryptedBindingButExplicitLogoutDeletesIt() = runTest {
        val store = Store()
        val runtime = runtime(Backend(listOf(term), ::snapshot), store)
        runtime.setOwner("owner-a")
        bind(runtime)
        runtime.setOwner(null)
        assertNotNull(store.get(SchedulePersistence.DATA_KEY))
        assertNull(runtime.state.value.snapshot)
        runtime.setOwner("owner-a")
        assertNotNull(runtime.state.value.snapshot)
        assertTrue(runtime.clearForLogout())
        assertNull(store.get(SchedulePersistence.DATA_KEY))
        runtime.setOwner("owner-a")
        assertNull(runtime.state.value.boundAccount)
        runtime.close()
    }

    @Test fun deletionFailurePersistsRevocationAndNeverRestoresPassword() = runTest {
        val store = Store()
        val native = Native()
        var runtime = runtime(Backend(listOf(term), ::snapshot), store, native)
        runtime.setOwner("owner-a")
        bind(runtime)
        store.failRemove = true
        assertFalse(runtime.unbind())
        assertNull(runtime.state.value.boundAccount)
        assertTrue(runtime.state.value.storageFailure)
        assertNotNull(store.get(SchedulePersistence.REVOKED_KEY))
        runtime.close()
        store.failRemove = false
        runtime = runtime(Backend(listOf(term), ::snapshot), store)
        runtime.setOwner("owner-a")
        assertNull(runtime.state.value.boundAccount)
        runtime.close()
    }

    @Test fun nativeCleanupFailureIsReportedAndDoesNotSkipCredentialRemoval() = runTest {
        val store = Store()
        val native = Native()
        val runtime = runtime(Backend(listOf(term), ::snapshot), store, native)
        runtime.setOwner("owner-a")
        bind(runtime)
        native.clearFailure = true
        assertFalse(runtime.clearForLogout())
        assertNull(store.get(SchedulePersistence.DATA_KEY))
        assertNotNull(runtime.state.value.message)
        runtime.close()
    }

    @Test fun historicalTermsNeverReplaceOrEvictReminderSource() = runTest {
        val history = (1..4).map { ScheduleTerm("history-$it", "合成历史$it") }
        val backend = Backend(listOf(term) + history, ::snapshot)
        val native = Native()
        val runtime = runtime(backend, native = native)
        runtime.setOwner("owner-a")
        bind(runtime)
        for (old in history) runtime.selectTerm(old.id)
        assertEquals(history.last().id, runtime.state.value.snapshot?.term?.id)
        assertEquals(term.id, runtime.state.value.sourceSnapshot?.term?.id)
        assertEquals(term.id, native.publications.last().snapshot?.term?.id)
        runtime.close()
    }

    @Test fun periodCalibrationAndRemindersUseOneValidatedSeed() = runTest {
        val native = Native()
        val runtime = runtime(Backend(listOf(term), ::snapshot), native = native)
        runtime.setOwner("owner-a")
        bind(runtime)
        val original = runtime.state.value.periods
        assertFalse(runtime.updatePeriods(original.dropLast(1)))
        assertEquals(original, runtime.state.value.periods)
        val changed = original.map { if (it.number == 1) it.copy(start = "08:01") else it }
        assertTrue(runtime.updatePeriods(changed))
        runtime.setReminders(true)
        assertEquals(changed, native.publications.last().periods)
        assertTrue(native.publications.last().remindersEnabled)
        assertEquals(10, native.publications.last().reminderLeadMinutes)
        runtime.close()
    }

    @Test fun lateExpiredReplyNeverStartsOldAuthenticationOrMutatesNewOwner() = runTest {
        val backend = Backend(listOf(term), ::snapshot)
        val runtime = runtime(backend)
        runtime.setOwner("owner-a")
        bind(runtime)
        val authBefore = backend.authCalls
        backend.fetchGate = CompletableDeferred()
        backend.ignoreCancellation = true
        backend.expireNext = true
        val old = backgroundScope.launch { runtime.refresh() }
        runCurrent()
        runtime.setOwner("owner-b")
        backend.fetchGate!!.complete(Unit)
        old.join()
        assertEquals(authBefore, backend.authCalls)
        assertEquals("owner-b", runtime.state.value.owner)
        assertFalse(runtime.state.value.reauthenticating)
        assertNull(runtime.state.value.snapshot)
        runtime.close()
    }

    @Test fun explicitLogoutAfterTemporaryCampusExpiryStillDeletesBinding() = runTest {
        val store = Store()
        val runtime = runtime(Backend(listOf(term), ::snapshot), store)
        runtime.setOwner("owner-a")
        bind(runtime)
        runtime.setOwner(null)
        assertTrue(runtime.clearForLogout())
        assertNull(store.get(SchedulePersistence.DATA_KEY))
        runtime.setOwner("owner-a")
        assertNull(runtime.state.value.boundAccount)
        runtime.close()
    }

    @Test fun failedLogoutCanBeRetriedWithoutRestoringCampusSession() = runTest {
        val store = Store()
        val runtime = runtime(Backend(listOf(term), ::snapshot), store)
        runtime.setOwner("owner-a")
        bind(runtime)
        store.failRemove = true
        assertFalse(runtime.clearForLogout())
        assertNotNull(store.get(SchedulePersistence.DATA_KEY))
        store.failRemove = false
        assertTrue(runtime.clearForLogout())
        assertNull(store.get(SchedulePersistence.DATA_KEY))
        runtime.close()
    }

    @Test fun explicitRebindAcknowledgesRevocationInOneAtomicDataWrite() = runTest {
        val store = Store()
        var runtime = runtime(Backend(listOf(term), ::snapshot), store)
        runtime.setOwner("owner-a")
        bind(runtime)
        store.failRemove = true
        assertFalse(runtime.unbind())
        runtime.close()
        runtime = runtime(Backend(listOf(term), ::snapshot), store)
        runtime.setOwner("owner-a") // Persisted revocation blocks the old binding after restart.
        assertNull(runtime.state.value.boundAccount)
        assertTrue(runtime.verifyBinding("new-fixture", "new-fixture-password"))
        // Removal still fails, but confirmation never needs a separate marker deletion.
        assertTrue(runtime.confirmBinding())
        runtime.close()
        store.failRemove = false
        runtime = runtime(Backend(listOf(term), ::snapshot), store)
        runtime.setOwner("owner-a")
        assertEquals("new-fixture", runtime.state.value.boundAccount)
        assertNotNull(runtime.state.value.snapshot)
        runtime.close()
    }

    @Test fun rolloverDoesNotMistakeHistoricalManualRefreshForTheNewDefaultTarget() = runTest {
        val native = Native()
        val backend = Backend(listOf(term), ::snapshot)
        val runtime = runtime(backend, native = native)
        runtime.setOwner("owner-a")
        bind(runtime)
        val next = ScheduleTerm("2026-2027-2", "合成新学期", true)
        backend.terms = listOf(term.copy(isDefault = false), next)
        val gate = CompletableDeferred<Unit>()
        backend.fetchGate = gate
        val automatic = async { runtime.onForeground() }
        runCurrent()
        val manual = async { runtime.refresh(term.id) }
        runCurrent()
        gate.complete(Unit)
        automatic.await()
        manual.await()
        assertEquals(3, backend.fetchCalls) // Initial bind, new default, and one coalesced historical read.
        assertEquals(term.id, runtime.state.value.snapshot?.term?.id)
        assertEquals(next.id, native.publications.last().snapshot?.term?.id)
        runtime.close()
    }

    @Test fun defaultSemesterRolloverRefreshesNativeSourceWithoutMovingHistoricalView() = runTest {
        var clock = start
        val native = Native()
        val backend = Backend(listOf(term), ::snapshot)
        val runtime = runtime(backend, native = native, now = { clock })
        runtime.setOwner("owner-a")
        bind(runtime)
        val next = ScheduleTerm("2026-2027-2", "合成新学期", true)
        backend.terms = listOf(term.copy(isDefault = false), next)
        clock += 86_400_000L
        val before = backend.fetchCalls
        runtime.onForeground()
        assertEquals(before + 2, backend.fetchCalls) // New default source plus retained historical view.
        assertEquals(term.id, runtime.state.value.snapshot?.term?.id)
        assertEquals(next.id, runtime.state.value.sourceSnapshot?.term?.id)
        assertEquals(next.id, native.publications.last().snapshot?.term?.id)
        assertEquals(ScheduleNativeStatus.CACHED, native.publications.last().status)
        runtime.onForeground()
        assertEquals(before + 2, backend.fetchCalls)
        runtime.close()
    }

    @Test fun nativeDeliveryFailureDoesNotMisreportCommittedPeriodsAsUnsaved() = runTest {
        val store = Store()
        val native = Native()
        val runtime = runtime(Backend(listOf(term), ::snapshot), store, native)
        runtime.setOwner("owner-a")
        bind(runtime)
        val changed = runtime.state.value.periods.map { if (it.number == 1) it.copy(start = "08:01") else it }
        native.rejectPublication = true
        assertTrue(runtime.updatePeriods(changed))
        assertEquals(changed, runtime.state.value.periods)
        assertNotNull(runtime.state.value.message)
        runtime.close()
        val restored = runtime(Backend(listOf(term), ::snapshot), store)
        restored.setOwner("owner-a")
        assertEquals(changed, restored.state.value.periods)
        restored.close()
    }

    @Test fun failedUnbindNeverResurrectsOldCredentialsWhenStorageRecovers() = runTest {
        val store = Store()
        val backend = Backend(listOf(term), ::snapshot)
        val runtime = runtime(backend, store)
        runtime.setOwner("owner-a")
        bind(runtime)
        val calls = backend.fetchCalls
        store.failPut = true; store.failRemove = true
        assertFalse(runtime.unbind())
        assertNull(runtime.state.value.boundAccount)
        store.failPut = false; store.failRemove = false
        runtime.onForeground()
        assertNull(runtime.state.value.boundAccount)
        assertNull(store.get(SchedulePersistence.DATA_KEY))
        assertFalse(runtime.state.value.storageFailure)
        assertEquals(calls, backend.fetchCalls)
        runtime.close()
    }

    @Test fun markerOnlyUnbindFailureRetriesDeletionBeforeClearingError() = runTest {
        val store = Store()
        val runtime = runtime(Backend(listOf(term), ::snapshot), store)
        runtime.setOwner("owner-a")
        bind(runtime)
        store.failRemove = true
        assertFalse(runtime.unbind())
        runtime.onForeground()
        assertTrue(runtime.state.value.storageFailure)
        assertNotNull(store.get(SchedulePersistence.DATA_KEY))
        store.failRemove = false
        runtime.onForeground()
        assertNull(store.get(SchedulePersistence.DATA_KEY))
        assertNull(runtime.state.value.boundAccount)
        assertFalse(runtime.state.value.storageFailure)
        runtime.close()
    }

    @Test fun foregroundCanRecoverInitiallyUnreadableStoreWithoutConfirmingABinding() = runTest {
        val store = Store()
        store.failGet = true
        val runtime = runtime(Backend(listOf(term), ::snapshot), store)
        runtime.setOwner("owner-a")
        assertTrue(runtime.state.value.storageFailure)
        store.failGet = false
        runtime.onForeground()
        assertFalse(runtime.state.value.storageFailure)
        assertNull(runtime.state.value.boundAccount)
        assertTrue(runtime.verifyBinding("academic-fixture", "fixture-password"))
        runtime.close()
    }

    @Test fun unresolvedDefaultDailyAttemptMigratesToCanonicalTerm() = runTest {
        val backend = Backend(listOf(term), ::snapshot)
        backend.fetchFailure = AcademicNetworkException()
        val runtime = runtime(backend)
        runtime.setOwner("owner-a")
        bind(runtime)
        assertNull(runtime.state.value.snapshot)
        backend.fetchFailure = null
        runtime.onForeground()
        val calls = backend.fetchCalls
        runtime.onForeground()
        assertEquals(calls, backend.fetchCalls)
        assertNotNull(runtime.state.value.snapshot)
        runtime.close()
    }

    @Test fun dailyAttemptLedgerIsIndependentOfThreeSnapshotSlots() = runTest {
        val history = (1..3).map { ScheduleTerm("history-$it", "合成历史$it") }
        val backend = Backend(listOf(term) + history, ::snapshot)
        val runtime = runtime(backend)
        runtime.setOwner("owner-a")
        bind(runtime)
        runtime.onForeground()
        for (old in history) { runtime.selectTerm(old.id); runtime.onForeground() }
        runtime.selectTerm(term.id)
        val calls = backend.fetchCalls
        runtime.onForeground()
        assertEquals(calls, backend.fetchCalls)
        runtime.close()
    }

    @Test fun delayedHistoryResponseCannotEvictCurrentlyVisibleCachedSemester() = runTest {
        val history = (1..3).map { ScheduleTerm("history-$it", "合成历史$it") }
        val backend = Backend(listOf(term) + history, ::snapshot)
        val runtime = runtime(backend)
        runtime.setOwner("owner-a")
        bind(runtime)
        runtime.selectTerm(history[0].id)
        runtime.selectTerm(history[1].id)
        backend.fetchGate = CompletableDeferred()
        val pending = backgroundScope.launch { runtime.selectTerm(history[2].id) }
        runCurrent()
        runtime.selectTerm(history[0].id)
        backend.fetchGate!!.complete(Unit)
        pending.join()
        assertEquals(history[0].id, runtime.state.value.snapshot?.term?.id)
        assertEquals(term.id, runtime.state.value.sourceSnapshot?.term?.id)
        runtime.close()
    }

    @Test fun calendarNetworkFailureRetainsHealthyDatedSnapshotAndItsAge() = runTest {
        val backend = Backend(listOf(term), ::snapshot)
        val native = Native()
        val runtime = runtime(backend, native = native)
        runtime.setOwner("owner-a")
        bind(runtime)
        val healthy = runtime.state.value.snapshot
        backend.snapshot = { snapshot(it).copy(weekMondays = emptyMap(), warnings = listOf("CALENDAR_UNAVAILABLE")) }
        runtime.refresh()
        assertSame(healthy, runtime.state.value.snapshot)
        assertEquals(healthy, native.publications.last().snapshot)
        runtime.close()
    }

    @Test fun sourceConstructionFailureLeavesHealthyCacheAndClearsBusyState() = runTest {
        val store = Store()
        val initial = runtime(Backend(listOf(term), ::snapshot), store)
        initial.setOwner("owner-a")
        bind(initial)
        initial.close()
        val runtime = ScheduleRuntime(backgroundScope, store, { throw IllegalStateException("fixture construction failure") }, Native(), { start })
        runtime.setOwner("owner-a")
        runtime.refresh()
        assertFalse(runtime.state.value.busy)
        assertNotNull(runtime.state.value.snapshot)
        assertNotNull(runtime.state.value.message)
        runtime.close()
    }
}
