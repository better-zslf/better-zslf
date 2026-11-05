package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.storage.KeyValueStore

/**
 * Remembers a live session so it can be signed out later.
 *
 * The carrier keeps an account "online" server-side, and a client that dies without signing out
 * leaves it there: the next attempt is then refused as "already signed in elsewhere", or the user
 * sits on a network that says connected while nothing works until the server times the session out.
 *
 * The reference solves this by writing a logout record when a session becomes live and replaying it
 * on the next start. This is that record. It deliberately holds only what a `term.cgi` needs —
 * the ticket, the cipher id, the server and the identity — and never the broadband password: a
 * sign-out does not require it, so storing it would be needless exposure.
 *
 * Everything is stored through the app's encrypted store, and the record is removed as soon as the
 * sign-out is attempted, successful or not: a record that keeps failing would otherwise be replayed
 * forever.
 */
class DormLogoutStore(private val storage: KeyValueStore) {

    /** A session that was live when the process ended, or was ended without signing out. */
    data class Record(
        val serverHost: String,
        val algoId: String,
        val ticket: String,
        val clientId: String,
        val userAgent: String,
        val hostName: String,
        val ipv4: String,
        val mac: String,
        val ostag: String,
        val gwip: String,
    ) {
        override fun toString(): String = "DormLogoutStore.Record(algo=$algoId)"
    }

    fun save(record: Record) {
        runCatching {
            storage.put(KEY_HOST, record.serverHost)
            storage.put(KEY_ALGO, record.algoId)
            storage.put(KEY_TICKET, record.ticket)
            storage.put(KEY_CLIENT_ID, record.clientId)
            storage.put(KEY_USER_AGENT, record.userAgent)
            storage.put(KEY_HOST_NAME, record.hostName)
            storage.put(KEY_IPV4, record.ipv4)
            storage.put(KEY_MAC, record.mac)
            storage.put(KEY_OSTAG, record.ostag)
            storage.put(KEY_GWIP, record.gwip)
        }
    }

    /** The stored record, or `null` when there is none or it is incomplete. */
    fun load(): Record? = runCatching {
        val serverHost = storage.get(KEY_HOST)?.takeIf { it.isNotBlank() } ?: return null
        val algoId = storage.get(KEY_ALGO)?.takeIf { it.isNotBlank() } ?: return null
        val ticket = storage.get(KEY_TICKET)?.takeIf { it.isNotBlank() } ?: return null
        Record(
            serverHost = serverHost,
            algoId = algoId,
            ticket = ticket,
            clientId = storage.get(KEY_CLIENT_ID).orEmpty(),
            userAgent = storage.get(KEY_USER_AGENT).orEmpty().ifBlank { DORM_ANDROID_USER_AGENT },
            hostName = storage.get(KEY_HOST_NAME).orEmpty(),
            ipv4 = storage.get(KEY_IPV4).orEmpty(),
            mac = storage.get(KEY_MAC).orEmpty(),
            ostag = storage.get(KEY_OSTAG).orEmpty(),
            gwip = storage.get(KEY_GWIP).orEmpty(),
        )
    }.getOrNull()

    /**
     * Removes the record.
     *
     * Called before the attempt, not after: if the process dies mid-sign-out the record must not
     * survive to be replayed on every future start against a session that is probably already gone.
     */
    fun clear() {
        runCatching {
            listOf(KEY_HOST, KEY_ALGO, KEY_TICKET, KEY_CLIENT_ID, KEY_USER_AGENT,
                KEY_HOST_NAME, KEY_IPV4, KEY_MAC, KEY_OSTAG, KEY_GWIP).forEach(storage::remove)
        }
    }

    private companion object {
        const val KEY_HOST = "dorm.logout.server"
        const val KEY_ALGO = "dorm.logout.algo"
        const val KEY_TICKET = "dorm.logout.ticket"
        const val KEY_CLIENT_ID = "dorm.logout.clientId"
        const val KEY_USER_AGENT = "dorm.logout.ua"
        const val KEY_HOST_NAME = "dorm.logout.host"
        const val KEY_IPV4 = "dorm.logout.ipv4"
        const val KEY_MAC = "dorm.logout.mac"
        const val KEY_OSTAG = "dorm.logout.ostag"
        const val KEY_GWIP = "dorm.logout.gwip"
    }
}
