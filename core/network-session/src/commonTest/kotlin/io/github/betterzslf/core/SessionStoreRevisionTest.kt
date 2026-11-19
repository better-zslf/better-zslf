package io.github.betterzslf.core

import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Mutation epochs are synchronous; no collector or dispatcher turn is required for these tests. */
class SessionStoreRevisionTest {
    @Test fun everySuccessfulSaveAdvancesEvenWhenTheSnapshotIsEqual() {
        val storage = MemoryKeyValueStore()
        val sessions = SessionStore(storage)
        val snapshot = fixtureSession()
        assertEquals(0L, sessions.revision.value)
        sessions.save(snapshot)
        assertEquals(1L, sessions.revision.value)
        assertEquals(snapshot, sessions.session.value)
        sessions.save(snapshot.copy())
        assertEquals(2L, sessions.revision.value)
        assertEquals(snapshot, sessions.session.value)
        assertNotNull(storage.get(SessionStore.STORAGE_KEY))
    }

    @Test fun clearAndSameCredentialRestoreCannotCollapseIntoTheOldEpoch() {
        val sessions = SessionStore(MemoryKeyValueStore())
        val snapshot = fixtureSession()
        sessions.save(snapshot)
        val previousEpoch = sessions.revision.value
        sessions.clear()
        assertEquals(previousEpoch + 1, sessions.revision.value)
        assertNull(sessions.session.value)
        sessions.save(snapshot)
        assertEquals(previousEpoch + 2, sessions.revision.value)
        assertEquals(snapshot, sessions.session.value)
    }

    @Test fun mapConfigurationChangesAdvanceWithoutChangingTheOwnerOrCredential() {
        val sessions = SessionStore(MemoryKeyValueStore()).apply { save(fixtureSession()) }
        val before = sessions.revision.value
        val original = sessions.session.value!!
        val changed = original.copy(mapToken = "replacement-fixture-map", mapWebType = "2")
        sessions.save(changed)
        assertEquals(before + 1, sessions.revision.value)
        assertEquals(original.userId, sessions.session.value?.userId)
        assertEquals(original.token, sessions.session.value?.token)
        assertEquals(changed, sessions.session.value)
    }

    @Test fun unsuccessfulSaveDoesNotPublishAnEpochOrAnUnpersistedSession() {
        val backing = MemoryKeyValueStore()
        var rejectWrite = false
        val storage = object : KeyValueStore by backing {
            override fun put(key: String, value: String) {
                if (rejectWrite) error("fixture write failure")
                backing.put(key, value)
            }
        }
        val sessions = SessionStore(storage).apply { save(fixtureSession()) }
        val before = sessions.session.value
        val epoch = sessions.revision.value
        val persisted = backing.get(SessionStore.STORAGE_KEY)
        rejectWrite = true
        assertFailsWith<IllegalStateException> { sessions.save(fixtureSession("replacement-fixture-token")) }
        assertSame(before, sessions.session.value)
        assertEquals(epoch, sessions.revision.value)
        assertEquals(persisted, backing.get(SessionStore.STORAGE_KEY))
    }

    @Test fun failedDeletionStillAdvancesAndInvalidatesTheInMemorySession() {
        val backing = MemoryKeyValueStore()
        val storage = object : KeyValueStore by backing {
            override fun remove(key: String) { error("fixture delete failure") }
        }
        val sessions = SessionStore(storage).apply { save(fixtureSession()) }
        val before = sessions.revision.value
        assertFailsWith<IllegalStateException> { sessions.clear() }
        assertEquals(before + 1, sessions.revision.value)
        assertNull(sessions.session.value)
    }

    @Test fun clearingAnAlreadySignedOutStoreIsStillAnExplicitInvalidation() {
        val sessions = SessionStore(MemoryKeyValueStore())
        sessions.clear()
        assertEquals(1L, sessions.revision.value)
        sessions.clear()
        assertEquals(2L, sessions.revision.value)
        assertNull(sessions.session.value)
    }

    @Test fun blankCredentialsNeverAdvanceOrTouchStoredState() {
        val storage = MemoryKeyValueStore()
        val sessions = SessionStore(storage)
        assertFailsWith<IllegalArgumentException> { sessions.save(fixtureSession(" ")) }
        assertEquals(0L, sessions.revision.value)
        assertNull(sessions.session.value)
        assertNull(storage.get(SessionStore.STORAGE_KEY))
    }

    @Test fun conditionalInvalidationRejectsAnonymousAndEveryRetiredSnapshotEpoch() {
        for (mutation in 0..4) {
            val storage = MemoryKeyValueStore()
            val sessions = SessionStore(storage)
            val anonymous = sessions.captureSnapshot()
            val original = fixtureSession()
            sessions.save(original)
            val captured = sessions.captureSnapshot()
            when (mutation) {
                0 -> sessions.save(original.copy())
                1 -> { sessions.clear(); sessions.save(original) }
                2 -> sessions.save(original.copy(mapToken = "changed-fixture-map", mapWebType = "2"))
                3 -> sessions.save(fixtureSession("changed-fixture-token"))
                else -> sessions.clear()
            }
            val current = sessions.captureSnapshot()
            val before = sessions.session.value
            val epoch = sessions.revision.value
            val persisted = storage.get(SessionStore.STORAGE_KEY)
            assertEquals(false, sessions.clearIfCurrent(anonymous))
            assertEquals(false, sessions.clearIfCurrent(captured))
            assertSame(current, sessions.captureSnapshot())
            assertSame(before, sessions.session.value)
            assertEquals(epoch, sessions.revision.value)
            assertEquals(persisted, storage.get(SessionStore.STORAGE_KEY))
        }
    }

    @Test fun conditionalInvalidationClearsExactlyItsCurrentSnapshotOnce() {
        val storage = MemoryKeyValueStore()
        val sessions = SessionStore(storage).apply { save(fixtureSession()) }
        val captured = sessions.captureSnapshot()
        val epoch = sessions.revision.value
        assertEquals(true, sessions.clearIfCurrent(captured))
        assertEquals(epoch + 1, sessions.revision.value)
        assertNull(sessions.session.value)
        assertNull(storage.get(SessionStore.STORAGE_KEY))
        assertEquals(false, sessions.clearIfCurrent(captured))
        assertEquals(epoch + 1, sessions.revision.value)
    }

    @Test fun failedSaveRetainsTheAuthoritativeSnapshotAndMatchingClearAuthority() {
        val backing = MemoryKeyValueStore()
        var rejectWrite = false
        val storage = object : KeyValueStore by backing {
            override fun put(key: String, value: String) {
                if (rejectWrite) error("fixture write failure")
                backing.put(key, value)
            }
        }
        val sessions = SessionStore(storage).apply { save(fixtureSession()) }
        val captured = sessions.captureSnapshot()
        val epoch = sessions.revision.value
        rejectWrite = true
        assertFailsWith<IllegalStateException> { sessions.save(fixtureSession("changed-fixture-token")) }
        assertSame(captured, sessions.captureSnapshot())
        assertEquals(epoch, sessions.revision.value)
        assertEquals(true, sessions.clearIfCurrent(captured))
        assertNull(sessions.session.value)
    }

    @Test fun conditionalDeleteFailureStillRetiresItsSnapshotBeforeReportingFailure() {
        val backing = MemoryKeyValueStore()
        val storage = object : KeyValueStore by backing {
            override fun remove(key: String) { error("fixture delete failure") }
        }
        val sessions = SessionStore(storage).apply { save(fixtureSession()) }
        val captured = sessions.captureSnapshot()
        val epoch = sessions.revision.value
        assertFailsWith<IllegalStateException> { sessions.clearIfCurrent(captured) }
        assertNull(sessions.session.value)
        assertEquals(epoch + 1, sessions.revision.value)
        assertEquals(false, captured.matches(sessions.captureSnapshot()))
        assertEquals(false, sessions.clearIfCurrent(captured))
    }

    @Test fun restoringPersistedSessionStartsANewInMemoryEpochWithoutChangingTheRecord() {
        val storage = MemoryKeyValueStore()
        val original = fixtureSession()
        SessionStore(storage).apply { save(original); save(original) }
        val persisted = storage.get(SessionStore.STORAGE_KEY)
        val restored = SessionStore(storage)
        assertEquals(original, restored.session.value)
        assertEquals(0L, restored.revision.value)
        assertEquals(persisted, storage.get(SessionStore.STORAGE_KEY))
    }
}
