package io.github.betterzslf.core.storage

import io.github.betterzslf.core.net.fixtureSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SessionStoreTest {
    @Test
    fun restoresAllSessionFieldsAcrossStoreInstances() {
        val keyValues = MemoryKeyValueStore()
        val session = fixtureSession()
        SessionStore(keyValues).save(session)
        assertEquals(session, SessionStore(keyValues).session.value)
        assertEquals("fixture-user-id", SessionStore(keyValues).session.value?.userId)
        assertNotNull(keyValues.get(SessionStore.STORAGE_KEY))
    }

    @Test
    fun clearsOnlySessionAndKeepsSettings() {
        val keyValues = MemoryKeyValueStore().apply { put("settings.theme", "dark") }
        val sessions = SessionStore(keyValues).apply { save(fixtureSession()) }
        sessions.clear()
        assertNull(sessions.session.value)
        assertNull(SessionStore(keyValues).session.value)
        assertEquals("dark", keyValues.get("settings.theme"))
    }

    @Test
    fun discardsCorruptAndBlankTokenRecordsWithoutResettingSettings() {
        listOf("broken-json", "{\"token\":\"\",\"userInfo\":{},\"deptInfo\":{}}").forEach { corrupt ->
            val keyValues = MemoryKeyValueStore().apply {
                put(SessionStore.STORAGE_KEY, corrupt)
                put("settings.theme", "light")
            }
            assertNull(SessionStore(keyValues).session.value)
            assertNull(keyValues.get(SessionStore.STORAGE_KEY))
            assertEquals("light", keyValues.get("settings.theme"))
        }
    }

    @Test
    fun ignoresFutureFieldsAndSessionToStringRedactsAllIdentifiers() {
        val keyValues = MemoryKeyValueStore()
        val session = fixtureSession()
        val encoded = Json.encodeToJsonElement(io.github.betterzslf.core.model.Session.serializer(), session) as JsonObject
        keyValues.put(SessionStore.STORAGE_KEY, JsonObject(encoded + ("future" to JsonPrimitive("ok"))).toString())
        assertEquals(session, SessionStore(keyValues).session.value)
        assertFalse(session.toString().contains("fixture-token"))
        assertFalse(session.toString().contains("fixture-user-id"))
        assertFalse(session.toString().contains("fixture-map-token"))
    }

    @Test
    fun storageWriteFailureDoesNotPublishAnUnpersistedLogin() {
        val backing = MemoryKeyValueStore()
        val store = object : KeyValueStore by backing {
            override fun put(key: String, value: String) { error("fixture storage write failure") }
        }
        val sessions = SessionStore(store)
        assertFailsWith<IllegalStateException> { sessions.save(fixtureSession()) }
        assertNull(sessions.session.value)
        assertNull(backing.get(SessionStore.STORAGE_KEY))
    }

    @Test
    fun storageDeletionFailureStillInvalidatesTheObservableSession() {
        val backing = MemoryKeyValueStore()
        val store = object : KeyValueStore by backing {
            override fun remove(key: String) { error("fixture storage deletion failure") }
        }
        val sessions = SessionStore(store).apply { save(fixtureSession()) }
        assertFailsWith<IllegalStateException> { sessions.clear() }
        assertNull(sessions.session.value)
    }

    @Test
    fun rejectsBlankTokensBeforeTouchingPrivateStorage() {
        val backing = MemoryKeyValueStore()
        val sessions = SessionStore(backing)
        assertFailsWith<IllegalArgumentException> { sessions.save(fixtureSession(" ")) }
        assertNull(backing.get(SessionStore.STORAGE_KEY))
    }
}
