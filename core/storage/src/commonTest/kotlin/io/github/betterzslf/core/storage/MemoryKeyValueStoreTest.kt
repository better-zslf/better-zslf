package io.github.betterzslf.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Pure in-memory contract tests only; these do not stand in for Android Keystore/device tests. */
class MemoryKeyValueStoreTest {
    @Test fun missingKeysReadNullAndExistingValuesCanBeOverwritten() {
        val store: KeyValueStore = MemoryKeyValueStore()
        assertNull(store.get("missing"))
        store.put("one", "first")
        assertEquals("first", store.get("one"))
        store.put("one", "replacement")
        assertEquals("replacement", store.get("one"))
        assertNull(store.get("different"))
    }

    @Test fun emptyUnicodeAndControlContainingStringsRoundTripWithoutNormalization() {
        val store: KeyValueStore = MemoryKeyValueStore()
        val cases = listOf("" to "", "key with spaces" to " value with spaces ",
            "课表键" to "中文🚲", "line\nkey" to "line\nvalue\t\u0000", "same-prefix" to "a", "same-prefix-extra" to "b")
        for ((key, value) in cases) store.put(key, value)
        for ((key, value) in cases) assertEquals(value, store.get(key))
        assertNull(store.get("KEY WITH SPACES"))
    }

    @Test fun removalIsIdempotentAndDoesNotDeleteOtherKeys() {
        val store: KeyValueStore = MemoryKeyValueStore()
        store.put("one", "first")
        store.put("two", "second")
        store.remove("one"); store.remove("one"); store.remove("missing")
        assertNull(store.get("one"))
        assertEquals("second", store.get("two"))
    }

    @Test fun clearIsLocalToOneInstanceAndTheStoreRemainsReusable() {
        val first: KeyValueStore = MemoryKeyValueStore()
        val second: KeyValueStore = MemoryKeyValueStore()
        first.put("shared-key", "first")
        first.put("extra", "extra-value")
        second.put("shared-key", "second")
        first.clear(); first.clear()
        assertNull(first.get("shared-key"))
        assertNull(first.get("extra"))
        assertEquals("second", second.get("shared-key"))
        first.put("shared-key", "new-value")
        assertEquals("new-value", first.get("shared-key"))
        assertEquals("second", second.get("shared-key"))
    }
}
