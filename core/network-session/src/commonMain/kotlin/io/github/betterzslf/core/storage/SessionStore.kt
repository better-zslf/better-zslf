package io.github.betterzslf.core.storage

import io.github.betterzslf.core.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Internal request identity captured from one authoritative in-memory value, never two flow reads. */
internal class SessionSnapshot internal constructor(val session: Session?, private val epoch: Long) {
    val owner: String? = session?.userId
    val token: String? = session?.token
    fun matches(other: SessionSnapshot): Boolean = epoch == other.epoch && owner == other.owner && token == other.token
    fun successor(session: Session?): SessionSnapshot = SessionSnapshot(session, epoch + 1)
    fun publishRevision(target: MutableStateFlow<Long>) { target.value = epoch }
    override fun toString(): String = "SessionSnapshot(redacted)"
}

/**
 * Internal LOGIN claim identity: one client authority and one attempt token at one store-wide
 * nonce. It is not a credential record and never leaves the store's private claim slot; the
 * snapshot is the claim-time store state, advanced to the claim's own successor on acceptance.
 */
internal class LoginClaimRecord internal constructor(
    internal val nonce: Long,
    private val authority: Any,
    private val attempt: Any,
    internal var snapshot: SessionSnapshot,
) {
    internal var accepted: Boolean = false
    internal fun matches(authority: Any, attempt: Any): Boolean =
        this.authority === authority && this.attempt === attempt
    override fun toString(): String = "LoginClaimRecord(redacted)"
}

/**
 * One versioned JSON record makes token/profile/map settings restore atomically.
 * Call mutations and request admission on the application's state dispatcher (normally UI). All
 * compare-and-clear methods below are non-suspending on that dispatcher; this is not a claim of
 * cross-thread atomicity for arbitrary platform disk IO. A failed write never publishes a new epoch.
 */
class SessionStore(
    private val store: KeyValueStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private var current = SessionSnapshot(restore(), 0L)
    private val mutableSession = MutableStateFlow(current.session)
    val session: StateFlow<Session?> = mutableSession.asStateFlow()
    private val mutableRevision = MutableStateFlow(0L)

    /**
     * Synchronous in-memory mutation epoch, not a count of StateFlow collector notifications.
     * Even saving the same snapshot invalidates old operations; clear/save ABA must not revive them.
     * Mutations retain the state-dispatcher requirement above. The epoch is not persisted.
     */
    val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    fun save(session: Session) {
        require(session.token.isNotBlank()) { "Session token must not be blank" }
        store.put(STORAGE_KEY, json.encodeToString(Session.serializer(), session))
        publish(current.successor(session))
    }

    /** Clears only the session, not unrelated user settings in the same store. */
    fun clear() {
        // Even a failed disk deletion must immediately invalidate the in-memory session.
        try {
            store.remove(STORAGE_KEY)
        } finally {
            publish(current.successor(null))
        }
    }

    /** Opaque internal snapshot; capture never combines an old epoch with a later flow identity. */
    internal fun captureSnapshot(): SessionSnapshot = current

    /** Same dispatcher and no suspension between identity comparison and storage invalidation. */
    internal fun clearIfCurrent(expected: SessionSnapshot): Boolean {
        if (expected.token == null || !expected.matches(current)) return false
        clear()
        return true
    }

    // ---- Store-wide LOGIN claim registry -----------------------------------------------
    // One latest claim nonce retires every earlier claim and accepted continuation across EVERY
    // client over this store; a claim is only ever the explicit SUBMIT below. Like the mutations
    // above, every registry operation is a synchronous state-dispatcher operation.

    private var loginClaim: LoginClaimRecord? = null
    private var loginClaimNonce: Long = 0L

    /** Store-wide claim generation; advances on every explicit SUBMIT, never on capture. */
    internal fun loginClaimGeneration(): Long = loginClaimNonce

    /**
     * Explicit SUBMIT only: installs this attempt as the latest store-wide claim (nonce + 1) over
     * the store snapshot current right now, retiring any earlier claim and any accepted
     * continuation, including another client's over this store. Capture itself never reaches here.
     */
    internal fun claimLogin(authority: Any, attempt: Any) {
        loginClaimNonce += 1
        loginClaim = LoginClaimRecord(loginClaimNonce, authority, attempt, captureSnapshot())
    }

    /** Passive currency check for one client's attempt. */
    internal fun isLoginClaimCurrent(authority: Any, attempt: Any): Boolean {
        val claim = loginClaim ?: return false
        return claim.matches(authority, attempt)
    }

    /**
     * Owner-first cancel: only the attempt that still owns the current claim (claimed or
     * accepted) clears it; anything else is a false with no mutation of the claim, the session,
     * the epoch or the backing storage.
     */
    internal fun cancelLoginClaim(authority: Any, attempt: Any): Boolean {
        if (!isLoginClaimCurrent(authority, attempt)) return false
        loginClaim = null
        return true
    }

    /** The claim-time snapshot for dispatch admission; only a claimed, not-yet-accepted attempt. */
    internal fun loginClaimSnapshot(authority: Any, attempt: Any): SessionSnapshot? =
        loginClaim?.takeIf { it.matches(authority, attempt) && !it.accepted }?.snapshot

    /**
     * Synchronous compare-and-save: proceeds only while this attempt still owns the current claim
     * and the store still holds the claim-time snapshot — an external save or clear in between
     * retires it even when the written value would be identical, because the epoch advanced. A
     * successful save commits its own successor and arms the accepted continuation; a failed
     * storage write propagates without publishing anything.
     */
    internal fun saveLoginIfCurrent(authority: Any, attempt: Any, expected: SessionSnapshot, session: Session): Boolean {
        require(session.token.isNotBlank()) { "Session token must not be blank" }
        val claim = loginClaim ?: return false
        if (!claim.matches(authority, attempt) || claim.accepted || !expected.matches(current)) return false
        store.put(STORAGE_KEY, json.encodeToString(Session.serializer(), session))
        val successor = expected.successor(session)
        publish(successor)
        claim.snapshot = successor
        claim.accepted = true
        return true
    }

    /** Exactly-once consumption of the owning accepted continuation; consumption ends the claim. */
    internal fun consumeLoginContinuation(authority: Any, attempt: Any): Boolean {
        val claim = loginClaim ?: return false
        if (!claim.matches(authority, attempt) || !claim.accepted) return false
        loginClaim = null
        return true
    }

    private fun publish(next: SessionSnapshot) {
        // Invalidate every old binding BEFORE either observer flow resumes. The two public flows
        // remain compatibility views; request authority comes only from this single record.
        current = next
        next.publishRevision(mutableRevision)
        mutableSession.value = next.session
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
