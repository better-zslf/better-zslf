package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.storage.KeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * The logout record.
 *
 * Without it a process that dies while signed in leaves the account online server-side, and the
 * next attempt is refused as "already signed in elsewhere" — a failure the user cannot act on.
 */
class DormLogoutStoreTest {

    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }

    private fun record() = DormLogoutStore.Record(
        serverHost = "14.146.227.141", algoId = "319FC5AB-EC0E-46B9-A252-2285F9DAE813",
        ticket = "TK-123", clientId = "cid", userAgent = "ua", hostName = "h",
        ipv4 = "10.0.0.2", mac = "aa:bb", ostag = "3", gwip = "10.0.0.1")

    @Test fun arecordSurvivesARoundTrip() {
        val store = DormLogoutStore(MemoryStore())
        store.save(record())
        val loaded = assertNotNull(store.load())
        assertEquals("TK-123", loaded.ticket)
        assertEquals("14.146.227.141", loaded.serverHost)
        assertEquals("10.0.0.2", loaded.ipv4)
        assertEquals("aa:bb", loaded.mac)
    }

    @Test fun noRecordLoadsAsNullRatherThanAnEmptySession() {
        assertNull(DormLogoutStore(MemoryStore()).load())
    }

    @Test fun anIncompleteRecordIsRejectedNotPartlyUsed() {
        // A ticket without a cipher id cannot produce a valid sign-out, so the record is unusable.
        val backing = MemoryStore()
        backing.put("dorm.logout.server", "14.146.227.141")
        backing.put("dorm.logout.ticket", "TK")
        assertNull(DormLogoutStore(backing).load())
    }

    @Test fun clearingRemovesEveryKey() {
        val backing = MemoryStore()
        val store = DormLogoutStore(backing)
        store.save(record())
        assertTrue(backing.values.isNotEmpty())
        store.clear()
        assertTrue(backing.values.isEmpty(), backing.values.toString())
        assertNull(store.load())
    }

    @Test fun aStorageFailureIsContained() {
        // A broken store must not crash sign-in: the record is an optimisation, not a requirement.
        val broken = object : KeyValueStore {
            override fun get(key: String): String? = error("broken")
            override fun put(key: String, value: String) = error("broken")
            override fun remove(key: String) = error("broken")
            override fun clear() = error("broken")
        }
        val store = DormLogoutStore(broken)
        store.save(record())   // must not throw
        assertNull(store.load()) // must not throw
        store.clear()          // must not throw
    }

    @Test fun theRecordNeverCarriesTheBroadbandPassword() {
        // A sign-out does not need it, so storing it would be needless exposure. The type has no
        // field for it; this test documents the requirement and would fail if one were added and
        // populated with the password.
        val fields = DormLogoutStore.Record::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("password", ignoreCase = true) }, fields.toString())
    }

    @Test fun theUserAgentFallsBackSoAnOldRecordIsStillUsable() {
        val backing = MemoryStore()
        val store = DormLogoutStore(backing)
        store.save(record())
        backing.remove("dorm.logout.ua")
        val loaded = assertNotNull(store.load())
        assertEquals(DORM_ANDROID_USER_AGENT, loaded.userAgent)
    }
}

/** End-to-end: the controller must record a live session and clean it up. */
class DormLogoutIntegrationTest {

    private val algo = "319FC5AB-EC0E-46B9-A252-2285F9DAE813"
    private val fields = DormXmlFields(
        userAgent = DORM_ANDROID_USER_AGENT, clientId = "cid", localTime = "t",
        hostName = "h", ipv4 = "10.0.0.2", mac = "aa", ostag = "3", gwip = "10.0.0.1")

    private class MemoryStore : KeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }

    private fun gateway(calls: MutableList<String>) = DormTransport { url, _, algoId, _ ->
        calls += url
        when {
            algoId == DORM_NIL_ALGO_ID -> DormRoundResult.CipherUpdate(algo)
            url.contains("ticket.cgi") -> DormRoundResult.Reply("200", "",
                io.github.betterzslf.feature.campusnet.crypto.DormCipher.encryptHex(algo,
                    "<request><ticket>TK</ticket></request>".encodeToByteArray())!!.encodeToByteArray())
            else -> DormRoundResult.Reply("200", "",
                io.github.betterzslf.feature.campusnet.crypto.DormCipher.encryptHex(algo,
                    "<response><keep-retry>600</keep-retry></response>".encodeToByteArray())!!.encodeToByteArray())
        }
    }

    @Test fun signInRecordsTheSessionAndSignOutClearsIt() = runTest {
        val backing = MemoryStore()
        val calls = mutableListOf<String>()
        val c = DormSignInController(gateway(calls), fields = { fields },
            servers = DORM_AUTH_SERVERS, portalAddress = { PortalAddress("10.0.0.2", "10.0.0.1") },
            schoolIdentity = { null }, logoutStore = DormLogoutStore(backing))
        assertTrue(c.signIn("13800138000", "pw"))
        // Recorded, so a crash before sign-out can still be cleaned up next start.
        assertNotNull(DormLogoutStore(backing).load())
        c.signOut()
        assertNull(DormLogoutStore(backing).load())
    }

    @Test fun aPreviousSessionIsSignedOutOnDemand() = runTest {
        val backing = MemoryStore()
        DormLogoutStore(backing).save(DormLogoutStore.Record(
            serverHost = DORM_AUTH_SERVERS.first().host, algoId = algo, ticket = "OLD",
            clientId = "old-cid", userAgent = DORM_ANDROID_USER_AGENT, hostName = "h",
            ipv4 = "10.0.0.2", mac = "aa", ostag = "3", gwip = "10.0.0.1"))
        val calls = mutableListOf<String>()
        val c = DormSignInController(gateway(calls), fields = { fields },
            servers = DORM_AUTH_SERVERS, portalAddress = { null }, schoolIdentity = { null },
            logoutStore = DormLogoutStore(backing))
        c.signOutPreviousSession()
        assertTrue(calls.any { it.contains("term.cgi") }, calls.toString())
        // The record is gone whether or not the sign-out succeeded.
        assertNull(DormLogoutStore(backing).load())
    }

    @Test fun recoveringWithNoRecordDoesNothing() = runTest {
        var posted = 0
        val c = DormSignInController(DormTransport { _, _, _, _ -> posted++; DormRoundResult.Unreachable },
            fields = { fields }, servers = DORM_AUTH_SERVERS,
            portalAddress = { null }, schoolIdentity = { null },
            logoutStore = DormLogoutStore(MemoryStore()))
        assertFalse(c.signOutPreviousSession())
        assertEquals(0, posted)
    }
}
