package io.github.betterzslf.core.storage

/** Platform implementations scope this store to this application's private storage. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun clear()
}

/** Useful for previews and isolated tests; production supplies a platform store. */
class MemoryKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
    override fun clear() { values.clear() }
}
