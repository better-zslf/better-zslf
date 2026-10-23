package io.github.betterzslf.core.storage

import io.github.betterzslf.core.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One versioned JSON record makes token/profile/map settings restore atomically.
 * Call mutations on the application's state dispatcher (normally the UI dispatcher).
 * A platform storage failure is surfaced rather than silently claiming persistence.
 */
class SessionStore(
    private val store: KeyValueStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val mutableSession = MutableStateFlow(restore())
    val session: StateFlow<Session?> = mutableSession.asStateFlow()

    fun save(session: Session) {
        require(session.token.isNotBlank()) { "Session token must not be blank" }
        store.put(STORAGE_KEY, json.encodeToString(Session.serializer(), session))
        mutableSession.value = session
    }

    /** Clears only the session, not unrelated user settings in the same store. */
    fun clear() {
        // Even a failed disk deletion must immediately invalidate the in-memory session.
        try {
            store.remove(STORAGE_KEY)
        } finally {
            mutableSession.value = null
        }
    }

    /** An old request must not log out a newer, different login session. */
    internal fun clearIfTokenMatches(token: String?) {
        if (token != null && mutableSession.value?.token == token) clear()
    }

    private fun restore(): Session? {
        val encoded = store.get(STORAGE_KEY) ?: return null
        val restored = try {
            json.decodeFromString(Session.serializer(), encoded)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (restored == null || restored.token.isBlank()) {
            store.remove(STORAGE_KEY)
            return null
        }
        return restored
    }

    companion object {
        const val STORAGE_KEY: String = "better-zslf.session.v1"
    }
}
