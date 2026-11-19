package io.github.betterzslf.composition

import io.github.betterzslf.AppGraph
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.run.api.RunPreviewRead
import io.github.betterzslf.feature.run.api.RunPreviewSelection
import io.github.betterzslf.platform.PlatformServices
import io.github.betterzslf.platform.newUnsupportedScheduleNativeBridge
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** In-memory fake owners and MockEngine only; these tests neither compose private UI nor start GPS. */
@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationCompositionLifecycleTest {
    private class Store(val timeline: MutableList<String>) : KeyValueStore {
        private val memory = MemoryKeyValueStore()
        var clearCalls = 0
        var failClear = false
        override fun get(key: String): String? = memory.get(key)
        override fun put(key: String, value: String) = memory.put(key, value)
        override fun remove(key: String) = memory.remove(key)
        override fun clear() {
            clearCalls++
            timeline += "storage:clear"
            if (failClear) error("fixture storage clear failure")
            memory.clear()
        }
    }

    private class Owner(private val timeline: MutableList<String>, private val generation: Int) : ApplicationContributions {
        var gate: CompletableDeferred<Unit>? = null
        var failure: Exception? = null
        var absorbCancellation = false
        var shutdownCalls = 0
        var stopped = false
        val entered = CompletableDeferred<Unit>()

        override suspend fun shutdown() {
            shutdownCalls++
            timeline += "owner:$generation:shutdown"
            entered.complete(Unit)
            try { gate?.await() }
            catch (cancelled: CancellationException) { if (!absorbCancellation) throw cancelled }
            failure?.let { throw it }
            stopped = true
            timeline += "owner:$generation:stopped"
        }
    }

    private class Composition(private val timeline: MutableList<String>) : ProductComposition {
        val owners = mutableListOf<Owner>()
        var prepare: (Owner) -> Unit = {}
        override fun create(scope: CoroutineScope, public: PublicFeatureDependencies): ApplicationContributions =
            Owner(timeline, owners.size).also { prepare(it); owners += it }
    }

    private class Platform(override val storage: Store) : PlatformServices {
        override val versionName = "fixture-version"
        override val description = "In-process lifecycle fixture"
        val clients = mutableListOf<HttpClient>()
        var requests = 0
        override fun httpClient(): HttpClient = HttpClient(MockEngine {
            requests++
            respond("""{"code":200,"data":{"errandDetail":{
                "errandId":"fixture-errand","errandScheduleId":"fixture-schedule",
                "eName":"Fixture route","geoCoorList":[[113.4,22.5]],"walkPaths":[]
            }}}""")
        }).also { clients += it }
        override fun openUrl(url: String) { error("External UI is not part of this fixture") }
        override fun openPortalGateway(url: String): Boolean = false
        override fun copyToClipboard(text: String): Boolean = false
        fun clientsActive(): Boolean = clients.all { it.coroutineContext[Job]?.isActive == true }
        fun clientsStopped(): Boolean = clients.all { it.coroutineContext[Job]?.isActive == false }
    }

    private class Fixture(val store: Store, val platform: Platform, val composition: Composition, val graph: AppGraph)

    private suspend fun TestScope.withGraph(block: suspend Fixture.() -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val timeline = mutableListOf<String>()
        val store = Store(timeline).apply { put("fixture.sentinel", "original-data") }
        val platform = Platform(store)
        val composition = Composition(timeline)
        var graph: AppGraph? = null
        try {
            val owner = AppGraph(platform, composition,
                scheduleNativeFactory = ::newUnsupportedScheduleNativeBridge)
            graph = owner
            owner.sessions.save(fixtureSession())
            runCurrent() // Complete only local schedule/bootstrap work before exercising shutdown.
            Fixture(store, platform, composition, owner).block()
        } finally {
            composition.owners.forEach { it.gate?.complete(Unit); it.failure = null }
            runCatching { graph?.shutdown() }
            runCurrent()
            platform.clients.forEach { it.close() }
            Dispatchers.resetMain()
        }
    }

    @Test fun unusedProductOwnersRemainUninitializedThroughWipeAndShutdown() = runTest {
        withGraph {
            assertTrue(composition.owners.isEmpty())
            assertEquals("standard", graph.productIdentity.id)
            graph.clearLocalData()
            assertTrue(graph.clearingLocalData)
            runCurrent()
            assertFalse(graph.clearingLocalData)
            assertEquals(1, store.clearCalls)
            assertNull(store.get("fixture.sentinel"))
            assertTrue(composition.owners.isEmpty())
            graph.shutdown()
            graph.shutdown()
            assertTrue(composition.owners.isEmpty())
            assertTrue(platform.clientsStopped())
            assertEquals(0, platform.requests)
        }
    }

    @Test fun successfulWipeWaitsForTheExistingOwnerThenStartsANewGenerationOnlyOnDemand() = runTest {
        withGraph {
            val gate = CompletableDeferred<Unit>()
            composition.prepare = { it.gate = gate }
            val first = assertIs<Owner>(graph.contributions)
            assertSame(first, graph.contributions)
            val epoch = graph.sessions.revision.value
            graph.clearLocalData()
            runCurrent()
            assertTrue(first.entered.isCompleted)
            assertEquals(1, first.shutdownCalls)
            assertTrue(graph.clearingLocalData)
            assertEquals(0, store.clearCalls)
            assertEquals("original-data", store.get("fixture.sentinel"))
            assertEquals(epoch, graph.sessions.revision.value)
            assertNotNull(graph.sessions.session.value)
            graph.clearLocalData() // A second tap cannot start another shutdown or wipe.
            runCurrent()
            assertEquals(1, first.shutdownCalls)
            gate.complete(Unit)
            runCurrent()
            assertTrue(first.stopped)
            assertFalse(graph.clearingLocalData)
            assertFalse(graph.contributionRetirementFailed)
            assertEquals(1, store.clearCalls)
            assertNull(store.get("fixture.sentinel"))
            assertNull(graph.sessions.session.value)
            assertEquals(1, composition.owners.size)
            assertTrue(store.timeline.indexOf("owner:0:stopped") < store.timeline.indexOf("storage:clear"))
            val replacement = assertIs<Owner>(graph.contributions)
            assertNotSame(first, replacement)
            assertSame(replacement, graph.contributions)
            assertEquals(2, composition.owners.size)
            assertEquals(0, replacement.shutdownCalls)
            assertEquals(0, platform.requests)
        }
    }

    @Test fun failedRetirementDoesNotWipeOrRecreateAndCanRetryTheSameOwnerWithoutRenderingIt() = runTest {
        withGraph {
            val first = assertIs<Owner>(graph.contributions)
            first.failure = IllegalStateException("fixture retirement failure")
            val epoch = graph.sessions.revision.value
            graph.clearLocalData()
            runCurrent()
            assertFalse(graph.clearingLocalData)
            assertTrue(graph.contributionRetirementFailed)
            assertNotNull(graph.storageNotice)
            assertEquals(0, store.clearCalls)
            assertEquals("original-data", store.get("fixture.sentinel"))
            assertEquals(epoch, graph.sessions.revision.value)
            assertNotNull(graph.sessions.session.value)
            assertFailsWith<IllegalStateException> { graph.contributions }
            assertFailsWith<IllegalStateException> { graph.navigation }
            assertEquals(1, composition.owners.size)
            assertEquals(1, first.shutdownCalls)
            first.failure = null
            graph.clearLocalData()
            runCurrent()
            assertEquals(2, first.shutdownCalls)
            assertTrue(first.stopped)
            assertFalse(graph.contributionRetirementFailed)
            assertEquals(1, store.clearCalls)
            assertEquals(1, composition.owners.size)
            assertNotSame(first, graph.contributions)
            assertEquals(2, composition.owners.size)
        }
    }

    @Test fun aLaterStorageFailureNeverRestoresAnAlreadyStoppedOwnerForRendering() = runTest {
        withGraph {
            val first = assertIs<Owner>(graph.contributions)
            store.failClear = true
            graph.clearLocalData()
            runCurrent()
            assertTrue(first.stopped)
            assertFalse(graph.clearingLocalData)
            assertFalse(graph.contributionRetirementFailed)
            assertNotNull(graph.storageNotice)
            assertEquals(1, store.clearCalls)
            assertEquals("original-data", store.get("fixture.sentinel"))
            assertEquals(1, composition.owners.size)
            val replacement = graph.contributions
            assertNotSame(first, replacement)
            assertEquals(2, composition.owners.size)
            store.failClear = false
        }
    }

    @Test fun closeInvalidatesThePreviewLeaseImmediatelyButWaitsForOwnersBeforeClosingClients() = runTest {
        withGraph {
            val gate = CompletableDeferred<Unit>()
            val owner = assertIs<Owner>(graph.contributions).apply { this.gate = gate }
            val ready = assertIs<RunPreviewRead.Ready>(graph.runPreviewSource.read(
                RunPreviewSelection("fixture-errand", "fixture-schedule")))
            assertTrue(ready.lease.isCurrent())
            assertTrue(platform.clientsActive())
            graph.close()
            assertFalse(ready.lease.isCurrent()) // No scheduler turn needed to invalidate admission.
            runCurrent()
            assertTrue(owner.entered.isCompleted)
            assertFalse(owner.stopped)
            assertTrue(platform.clientsActive())
            assertEquals(0, store.clearCalls)
            assertEquals("original-data", store.get("fixture.sentinel"))
            assertFailsWith<IllegalStateException> { graph.contributions }
            assertFailsWith<IllegalStateException> { graph.runRuntime }
            assertFailsWith<IllegalStateException> { graph.githubUpdates }
            assertFailsWith<IllegalStateException> { graph.appUpdater }
            gate.complete(Unit)
            runCurrent()
            graph.shutdown()
            assertTrue(owner.stopped)
            assertEquals(1, owner.shutdownCalls)
            assertTrue(platform.clientsStopped())
            assertFalse(ready.lease.isCurrent())
            assertNotNull(graph.sessions.session.value)
        }
    }

    @Test fun concurrentAndRepeatedShutdownCallersWaitForOneCleanupAndShareSuccess() = runTest {
        withGraph {
            val gate = CompletableDeferred<Unit>()
            val owner = assertIs<Owner>(graph.contributions).apply { this.gate = gate }
            val first = async { graph.shutdown() }
            val second = async { graph.shutdown() }
            runCurrent()
            assertEquals(1, owner.shutdownCalls)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            assertTrue(platform.clientsActive())
            gate.complete(Unit)
            first.await(); second.await()
            graph.shutdown()
            graph.close()
            runCurrent()
            assertEquals(1, owner.shutdownCalls)
            assertTrue(platform.clientsStopped())
            assertEquals(0, store.clearCalls)
        }
    }

    @Test fun shutdownFailureIsCachedForEveryCallerAndDoesNotSkipRemainingClientCleanup() = runTest {
        withGraph {
            val gate = CompletableDeferred<Unit>()
            val expected = IllegalStateException("fixture terminal owner failure")
            val owner = assertIs<Owner>(graph.contributions).apply { this.gate = gate; failure = expected }
            val first = async { runCatching { graph.shutdown() } }
            val second = async { runCatching { graph.shutdown() } }
            runCurrent()
            assertEquals(1, owner.shutdownCalls)
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            gate.complete(Unit)
            // Coroutine stack-trace recovery may copy an exception; the terminal type/message
            // and exactly-once owner shutdown are the externally meaningful cached result.
            assertEquals(expected.message, assertIs<IllegalStateException>(first.await().exceptionOrNull()).message)
            assertEquals(expected.message, assertIs<IllegalStateException>(second.await().exceptionOrNull()).message)
            assertEquals(expected.message, assertIs<IllegalStateException>(runCatching { graph.shutdown() }.exceptionOrNull()).message)
            assertEquals(1, owner.shutdownCalls)
            assertTrue(platform.clientsStopped())
            assertEquals(0, store.clearCalls)
            assertEquals("original-data", store.get("fixture.sentinel"))
        }
    }

    @Test fun asynchronousCloseReportsFailureWithoutThrowingOntoTheMainDispatcher() = runTest {
        withGraph {
            val expected = IllegalStateException("fixture asynchronous retirement failure")
            val owner = assertIs<Owner>(graph.contributions).apply { failure = expected }
            graph.close()
            runCurrent()
            assertEquals(AppText.App.LOCAL_DATA_CLEAR_BLOCKED_BY_ACTIVITY_HINT, graph.storageNotice)
            assertEquals(expected.message, assertIs<IllegalStateException>(runCatching { graph.shutdown() }.exceptionOrNull()).message)
            assertEquals(1, owner.shutdownCalls)
            assertTrue(platform.clientsStopped())
            assertEquals(0, store.clearCalls)
        }
    }

    @Test fun cancellationOfABlockedWipeCannotEraseStorageEvenIfTheOwnerAbsorbsCancellation() = runTest {
        withGraph {
            val gate = CompletableDeferred<Unit>()
            val owner = assertIs<Owner>(graph.contributions).apply {
                this.gate = gate
                absorbCancellation = true
            }
            graph.clearLocalData()
            runCurrent()
            assertTrue(owner.entered.isCompleted)
            assertEquals(0, store.clearCalls)
            graph.close() // Cancels the wipe's owner scope while its shutdown is suspended.
            runCurrent()
            assertFalse(graph.clearingLocalData)
            assertEquals(0, store.clearCalls)
            assertEquals("original-data", store.get("fixture.sentinel"))
            assertNotNull(graph.sessions.session.value)
            gate.complete(Unit)
            runCurrent()
            graph.shutdown()
            assertEquals(0, store.clearCalls)
            assertTrue(platform.clientsStopped())
        }
    }
}
