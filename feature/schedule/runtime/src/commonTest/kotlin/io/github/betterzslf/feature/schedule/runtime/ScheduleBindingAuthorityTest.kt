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

/** New authority regressions only. The original ScheduleRuntimeTest remains byte-for-byte frozen. */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleBindingAuthorityTest {
    private val term = ScheduleTerm("2026-2027-1", "Fixture term", true)
    private fun snapshot(term: ScheduleTerm) = ScheduleSnapshot(term,
        listOf(ScheduleCourse("fixture-course", "Fixture course", dayOfWeek = 4, periods = listOf(1, 2), weeks = listOf(1))),
        weekMondays = mapOf(1 to "2026-10-26"))
    private val now = Instant.parse("2026-10-29T00:00:00Z").toEpochMilliseconds()

    private class Backend(private val term: ScheduleTerm, private val snapshot: (ScheduleTerm) -> ScheduleSnapshot) {
        var sources = 0
        var authCalls = 0
        var fetchCalls = 0
        var closeCalls = 0
        var authGate: CompletableDeferred<Unit>? = null
        var fetchGate: CompletableDeferred<Unit>? = null
        var authEntered: CompletableDeferred<Unit>? = null
        var fetchEntered: CompletableDeferred<Unit>? = null
        var authIgnoreCancellation = false
        var fetchFailure = false
        var wrongIdentity = false
        val credentials = mutableListOf<Pair<String, String>>()
        fun source(): AcademicSource {
            sources++
            return object : AcademicSource {
                override suspend fun authenticate(account: String, password: String): AcademicIdentity {
                    authCalls++; credentials += account to password
                    val gate = authGate
                    authEntered?.complete(Unit)
                    if (authIgnoreCancellation) withContext(NonCancellable) { gate?.await() } else gate?.await()
                    return AcademicIdentity(if (wrongIdentity) "wrong-fixture" else account)
                }
                override suspend fun terms(): List<ScheduleTerm> = listOf(term)
                override suspend fun fetch(term: ScheduleTerm): ScheduleSnapshot {
                    fetchCalls++
                    val gate = fetchGate
                    fetchEntered?.complete(Unit)
                    if (gate != null) withContext(NonCancellable) { gate.await() }
                    if (fetchFailure) throw AcademicNetworkException()
                    return snapshot(term)
                }
                override fun close() { closeCalls++ }
            }
        }
    }

    private class Native : ScheduleNativeBridge {
        override val supported = true
        override val capabilities = MutableStateFlow(ScheduleNativeCapabilities(supported = true))
        override val openRequests = MutableStateFlow<ScheduleNativeOpenRequest?>(null)
        var lease: String? = null
        var clears = 0
        var closes = 0
        var publications = 0
        override fun refreshCapabilities() = Unit
        override fun requestNotificationPermission() = true
        override fun openNotificationSettings() = true
        override fun openExactAlarmSettings() = true
        override fun openBatteryOptimizationSettings() = true
        override fun openPromotedNotificationSettings() = true
        override fun requestPinWidget(kind: ScheduleWidgetKind) = true
        override fun publish(publication: ScheduleNativePublication): Boolean {
            if (publication.ownerRevision != lease) return false
            publications++
            return true
        }
        override fun clear(status: ScheduleNativeStatus, ownerRevision: String?) { clears++; lease = ownerRevision }
        override fun consumeOpenRequest() { openRequests.value = null }
        override fun close() { closes++ }
    }

    private class Store(private val memory: MemoryKeyValueStore = MemoryKeyValueStore()) : KeyValueStore by memory {
        var failPut = false
        override fun put(key: String, value: String) {
            if (failPut) throw IllegalStateException("fixture save failure")
            memory.put(key, value)
        }
    }

    private fun TestScope.createRuntime(backend: Backend, store: KeyValueStore = MemoryKeyValueStore(), native: Native = Native()) =
        ScheduleRuntime(backgroundScope, store, backend::source, native) { now }
    private fun attempt(runtime: ScheduleRuntime) = assertNotNull(runtime.beginBindingVerification(assertNotNull(runtime.captureBindingLease())))
    private suspend fun verified(runtime: ScheduleRuntime, account: String = "fixture-account"): ScheduleVerificationAttempt =
        attempt(runtime).also { assertTrue(runtime.verifyBinding(it, account, " fixture-password ")) }
    private suspend fun committed(runtime: ScheduleRuntime): ScheduleBindingCommit = assertNotNull(runtime.confirmBinding(verified(runtime)))

    @Test fun passiveLeasesRejectForeignRuntimeForceAbaAndActualReloadWithoutStealingVerification() = runTest {
        val backend = Backend(term, ::snapshot)
        val native = Native()
        val runtime = createRuntime(backend, native = native)
        val other = createRuntime(Backend(term, ::snapshot))
        runtime.setOwner("fixture-slot"); other.setOwner("fixture-slot")
        try {
            val passive = assertNotNull(runtime.captureBindingLease())
            val state = runtime.state.value; val clears = native.clears
            repeat(3) { assertTrue(runtime.isBindingLeaseCurrent(assertNotNull(runtime.captureBindingLease()))) }
            assertSame(state, runtime.state.value); assertEquals(clears, native.clears)
            assertEquals(0, backend.sources); assertEquals(0, backend.authCalls)
            assertFalse(other.isBindingLeaseCurrent(passive)); assertNull(other.beginBindingVerification(passive))
            committed(runtime)
            val bound = assertNotNull(runtime.captureBindingLease()); val revision = runtime.ownerRevision
            runtime.setOwner("fixture-slot", force = true)
            assertEquals(revision, runtime.ownerRevision); assertFalse(runtime.isBindingLeaseCurrent(bound))
            val beforeAway = assertNotNull(runtime.captureBindingLease())
            runtime.setOwner(null); runtime.setOwner("fixture-slot")
            assertEquals(revision, runtime.ownerRevision); assertFalse(runtime.isBindingLeaseCurrent(beforeAway))

            // A second runtime writes the disk slot while this unbound runtime is verifying.
            val store = MemoryKeyValueStore()
            val consumerBackend = Backend(term, ::snapshot).apply {
                authGate = CompletableDeferred(); authEntered = CompletableDeferred(); authIgnoreCancellation = true
            }
            val consumer = createRuntime(consumerBackend, store)
            val writer = createRuntime(Backend(term, ::snapshot), store)
            consumer.setOwner("reload-slot"); writer.setOwner("reload-slot")
            try {
                val beforeReload = assertNotNull(consumer.captureBindingLease())
                val inFlight = assertNotNull(consumer.beginBindingVerification(beforeReload))
                val pending = async { consumer.verifyBinding(inFlight, "old-fixture", "old-password") }
                consumerBackend.authEntered!!.await()
                assertTrue(consumer.state.value.verifying)
                committed(writer)
                consumer.reloadFromStorage()
                assertFalse(consumer.isBindingLeaseCurrent(beforeReload)); assertFalse(consumer.state.value.verifying)
                assertEquals("fixture-account", consumer.state.value.boundAccount); assertNull(consumer.state.value.verifiedAccount)
                consumerBackend.authGate!!.complete(Unit)
                assertFalse(pending.await()); assertTrue(consumerBackend.closeCalls > 0)
                assertNull(consumer.confirmBinding(inFlight))
                val afterReload = assertNotNull(consumer.captureBindingLease())
                consumer.reloadFromStorage() // No actual adoption: the passive lease is still current.
                assertTrue(consumer.isBindingLeaseCurrent(afterReload))
            } finally { consumerBackend.authGate?.complete(Unit); consumer.close(); writer.close() }
        } finally { runtime.close(); other.close() }
    }

    @Test fun synchronousAttemptOwnershipProtectsReplacementFromOldCancelDisposeAndLateFinally() = runTest {
        val backend = Backend(term, ::snapshot).apply {
            authGate = CompletableDeferred(); authEntered = CompletableDeferred(); authIgnoreCancellation = true
        }
        val native = Native(); val runtime = createRuntime(backend, native = native)
        runtime.setOwner("fixture-slot")
        val firstGate = backend.authGate!!
        try {
            val lease = assertNotNull(runtime.captureBindingLease())
            val oldAttempt = assertNotNull(runtime.beginBindingVerification(lease))
            assertEquals(0, backend.sources) // The caller owns a cancellation handle before any auth starts.
            val oldPending = async { runtime.verifyBinding(oldAttempt, "old-fixture", "old-password") }
            backend.authEntered!!.await()
            val captureOnly = assertNotNull(runtime.captureBindingLease())
            assertTrue(runtime.state.value.verifying); assertFalse(oldPending.isCompleted)
            val closedBeforeReplacement = backend.closeCalls
            val replacement = assertNotNull(runtime.beginBindingVerification(captureOnly))
            backend.authGate = CompletableDeferred(); backend.authEntered = CompletableDeferred()
            val replacementGate = backend.authGate!!
            val newPending = async { runtime.verifyBinding(replacement, "new-fixture", "new-password") }
            backend.authEntered!!.await()
            val clears = native.clears
            runtime.cancelBindingVerification(oldAttempt) // Old-page dispose is a pure no-op.
            firstGate.complete(Unit)
            assertFalse(oldPending.await())
            assertTrue(runtime.state.value.verifying); assertFalse(newPending.isCompleted)
            assertEquals(clears, native.clears); assertEquals(0, native.closes)
            assertEquals(closedBeforeReplacement + 1, backend.closeCalls) // Only the old request's own source closes.
            replacementGate.complete(Unit)
            assertTrue(newPending.await()); assertEquals("new-fixture", runtime.state.value.verifiedAccount)
            runtime.cancelBindingVerification(oldAttempt)
            assertEquals("new-fixture", runtime.state.value.verifiedAccount)
        } finally { firstGate.complete(Unit); backend.authGate?.complete(Unit); runtime.close() }
    }

    @Test fun verificationIsSingleUseAndOwnedCancellationPreservesOriginalIdentityAndWhitespaceRules() = runTest {
        val backend = Backend(term, ::snapshot); val runtime = createRuntime(backend)
        val other = createRuntime(Backend(term, ::snapshot))
        runtime.setOwner("fixture-slot"); other.setOwner("fixture-slot")
        try {
            val owned = attempt(runtime)
            assertFalse(other.verifyBinding(owned, "fixture-account", "fixture-password"))
            other.cancelBindingVerification(owned)
            assertTrue(runtime.verifyBinding(owned, " fixture-account ", " fixture-password "))
            assertEquals(listOf("fixture-account" to " fixture-password "), backend.credentials)
            assertFalse(runtime.verifyBinding(owned, "fixture-account", "replacement-password")); assertEquals(1, backend.authCalls)
            assertNull(other.confirmBinding(owned))
            runtime.cancelBindingVerification(owned)
            assertNull(runtime.confirmBinding(owned)); assertNull(runtime.state.value.verifiedAccount)
            backend.wrongIdentity = true
            assertFalse(runtime.verifyBinding(attempt(runtime), "fixture-account", "fixture-password"))
            assertNull(runtime.state.value.verifiedAccount)
            backend.wrongIdentity = false
            assertTrue(runtime.verifyBinding("fixture-account", "fixture-password")) // Legacy wrapper, same core.
            assertTrue(runtime.confirmBinding()); assertFalse(runtime.confirmBinding())
            val diagnostic = attempt(runtime)
            assertFalse(diagnostic.toString().contains("fixture")); assertFalse(assertNotNull(runtime.captureBindingLease()).toString().contains("fixture"))
        } finally { runtime.close(); other.close() }
    }

    @Test fun ownSuccessfulCommitSurvivesItsInvalidationAndOrdinarySyncFailureButConsumesOnlyOnce() = runTest {
        for (syncFailure in listOf(false, true)) {
            val backend = Backend(term, ::snapshot).apply {
                fetchGate = CompletableDeferred(); fetchEntered = CompletableDeferred(); fetchFailure = syncFailure
            }
            val native = Native(); val runtime = createRuntime(backend, native = native)
            val other = createRuntime(Backend(term, ::snapshot))
            runtime.setOwner("fixture-slot"); other.setOwner("fixture-slot")
            try {
                val lease = assertNotNull(runtime.captureBindingLease())
                val verified = verified(runtime)
                val pending = async { runtime.confirmBinding(verified) }
                backend.fetchEntered!!.await()
                assertFalse(runtime.isBindingLeaseCurrent(lease)) // Own commit changed authority already.
                assertEquals("fixture-account", runtime.state.value.boundAccount)
                val clears = native.clears
                runtime.cancelBindingVerification(verified) // Post-commit page disposal must not cancel sync.
                assertTrue(runtime.state.value.busy); assertFalse(pending.isCompleted); assertEquals(clears, native.clears)
                backend.fetchGate!!.complete(Unit)
                val receipt = assertNotNull(pending.await())
                assertFalse(other.consumeBindingContinuation(receipt))
                assertTrue(runtime.consumeBindingContinuation(receipt)); assertFalse(runtime.consumeBindingContinuation(receipt))
                assertNull(runtime.confirmBinding(verified)); assertFalse(receipt.toString().contains("fixture"))
                if (syncFailure) assertNull(runtime.state.value.snapshot) else assertNotNull(runtime.state.value.snapshot)
            } finally { backend.fetchGate?.complete(Unit); runtime.close(); other.close() }
        }
    }

    @Test fun refreshSuspendedCommitNeverBorrowsAuthorityFromForceAbaNewVerificationOrExternalRebind() = runTest {
        for (mutation in 0..5) {
            val backend = Backend(term, ::snapshot).apply { fetchGate = CompletableDeferred(); fetchEntered = CompletableDeferred() }
            val runtime = createRuntime(backend)
            runtime.setOwner("fixture-slot")
            val originalGate = backend.fetchGate!!
            try {
                val originalLease = assertNotNull(runtime.captureBindingLease())
                val original = verified(runtime)
                val pending = async { runtime.confirmBinding(original) }
                backend.fetchEntered!!.await()
                val committedRevision = runtime.ownerRevision
                when (mutation) {
                    0 -> runtime.setOwner("fixture-slot", force = true)
                    1 -> { runtime.setOwner(null); runtime.setOwner("fixture-slot") }
                    2 -> { attempt(runtime) } // User VERIFY retires a prior continuation even before auth.
                    3 -> {
                        backend.fetchGate = null; backend.fetchEntered = null
                        assertNotNull(runtime.confirmBinding(verified(runtime, "external-fixture")))
                    }
                    4 -> assertTrue(runtime.unbind())
                    else -> runtime.close()
                }
                if (mutation in 0..1) assertEquals(committedRevision, runtime.ownerRevision)
                originalGate.complete(Unit)
                val receipt = try { pending.await() } catch (_: CancellationException) { null }
                if (receipt != null) assertFalse(runtime.consumeBindingContinuation(receipt))
                assertFalse(runtime.isBindingLeaseCurrent(originalLease))
            } finally { originalGate.complete(Unit); runtime.close() }
        }
    }

    @Test fun failedSaveReturnsNoReceiptPreservesHealthyBindingAndDoesNotConsumeVerifiedRetry() = runTest {
        val backend = Backend(term, ::snapshot); val store = Store(); val native = Native()
        val runtime = createRuntime(backend, store, native)
        runtime.setOwner("fixture-slot")
        try {
            committed(runtime)
            val healthy = runtime.state.value.snapshot; val encoded = store.get(SchedulePersistence.DATA_KEY)
            val revision = runtime.ownerRevision; val clears = native.clears; val closes = backend.closeCalls
            val replacement = verified(runtime, "replacement-fixture")
            store.failPut = true
            assertNull(runtime.confirmBinding(replacement))
            assertEquals("fixture-account", runtime.state.value.boundAccount); assertSame(healthy, runtime.state.value.snapshot)
            assertEquals(encoded, store.get(SchedulePersistence.DATA_KEY)); assertEquals(revision, runtime.ownerRevision)
            assertEquals(clears, native.clears); assertEquals(closes, backend.closeCalls)
            assertTrue(runtime.state.value.storageFailure)
            assertFalse(runtime.verifyBinding(replacement, "replacement-fixture", "other-password"))
            store.failPut = false
            val receipt = assertNotNull(runtime.confirmBinding(replacement))
            assertEquals("replacement-fixture", runtime.state.value.boundAccount)
            assertTrue(runtime.consumeBindingContinuation(receipt))
        } finally { store.failPut = false; runtime.close() }
    }
}
