package io.github.betterzslf.ui

import io.github.betterzslf.Route
import io.github.betterzslf.pushUnique
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.feature.settings.SettingsStore
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.nav.core.NavKey
import kotlin.test.*

class AppContractsTest {
    @Test fun routesRoundTripThroughSealedSupertype() {
        val routes = listOf<Route>(Route.Main, Route.Login, Route.TabRoot(2), Route.Page("article", "sample-id", "标题"))
        routes.forEach { assertEquals(it, Json.decodeFromString<Route>(Json.encodeToString<Route>(it))) }
    }

    @Test fun doubleTapDoesNotCreateDuplicateRoute() {
        val stack = mutableListOf<NavKey>(Route.TabRoot(0))
        val article = Route.Page("article", "sample")
        stack.pushUnique(article)
        stack.pushUnique(article)
        assertEquals(2, stack.size)
        stack.pushUnique(Route.Page("settings"))
        stack.pushUnique(article)
        assertEquals(listOf<NavKey>(Route.TabRoot(0), article), stack)
    }

    @Test fun resultIsReplayedForTheCorrectRequest() = runTest {
        val bus = NavigationResults()
        bus.deliver("time-picker", "selected-time")
        bus.deliver("login", "complete")
        assertEquals("selected-time", async { bus.observe("time-picker").first() }.await())
        assertEquals("complete", async { bus.observe("login").first() }.await())
    }

    @Test fun settingsPersistAndSamplingHasBounds() {
        val memory = MemoryStore()
        SettingsStore(memory).update { it.copy(theme = "Dark", dynamicColor = true, samplingSeconds = 1, skipImportWarning = true) }
        val restored = SettingsStore(memory).settings.value
        assertEquals("Dark", restored.theme)
        assertTrue(restored.dynamicColor)
        assertEquals(10, restored.samplingSeconds)
        assertTrue(restored.skipImportWarning)
        SettingsStore(memory).update { it.copy(samplingSeconds = 300) }
        assertEquals(60, SettingsStore(memory).settings.value.samplingSeconds)
    }

    @Test fun malformedSettingsRecoverToDefaults() {
        val memory = MemoryStore()
        memory.put("settings.v1", "not-json")
        assertEquals("System", SettingsStore(memory).settings.value.theme)
    }

    @Test fun failedSettingsWritePreservesOldStateAndReportsIt() {
        val memory = MemoryStore()
        val store = SettingsStore(memory)
        memory.failWrites = true
        assertFalse(store.update { it.copy(theme = "Dark") })
        assertEquals("System", store.settings.value.theme)
        assertNotNull(store.error.value)
    }

    @Test fun failedSettingsResetDoesNotThrowIntoUi() {
        val memory = MemoryStore()
        val store = SettingsStore(memory)
        store.update { it.copy(theme = "Dark") }
        memory.failWrites = true
        assertFalse(store.reset())
        assertEquals("System", store.settings.value.theme)
        assertNotNull(store.error.value)
    }

    @Test fun weakOrMismatchedPasswordsAreRejectedBeforeNetwork() {
        assertNotNull(passwordValidation("short1!", "short1!"))
        assertNotNull(passwordValidation("longpassword", "longpassword"))
        assertNotNull(passwordValidation("Example123!", "different"))
        assertNotNull(passwordValidation("中文12345!", "中文12345!"))
        assertNotNull(passwordValidation("Abcd 123!", "Abcd 123!"))
        assertNull(passwordValidation("Example123!", "Example123!"))
    }

    private class MemoryStore : KeyValueStore {
        private val data = mutableMapOf<String, String>()
        var failWrites = false
        override fun get(key: String) = data[key]
        override fun put(key: String, value: String) { check(!failWrites); data[key] = value }
        override fun remove(key: String) { check(!failWrites); data.remove(key) }
        override fun clear() { check(!failWrites); data.clear() }
    }
}
