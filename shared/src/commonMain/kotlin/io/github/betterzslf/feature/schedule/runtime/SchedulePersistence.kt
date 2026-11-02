@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package io.github.betterzslf.feature.schedule.runtime

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.feature.schedule.SchedulePeriod
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.ScheduleTerm
import io.github.betterzslf.feature.schedule.defaultSchedulePeriods
import io.github.betterzslf.feature.schedule.validateSchedulePeriods
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One bounded record is atomically replaced by the platform's encrypted store. */
@Serializable
internal data class StoredSchedule(
    val schema: Int = 1,
    val owner: String,
    val account: String,
    val password: String,
    val bindingRevision: String,
    val revocationEpoch: String? = null,
    val terms: List<ScheduleTerm> = emptyList(),
    val snapshots: List<ScheduleSnapshot> = emptyList(),
    val selectedTermId: String = "",
    val sourceTermId: String = "",
    val dailyAttempts: Map<String, String> = emptyMap(),
    val remindersEnabled: Boolean = false,
    val reminderLeadMinutes: Int = 10,
    /**
     * Whether the vendor capsule shows the course name beside the minutes.
     *
     * Exposed as a setting rather than hard-coded because the pill's width is ROM-specific: some
     * builds render the whole phrase, others clip to a few glyphs, and only the user can see which
     * their device does. Default false -- the short form is the one confirmed to work on Flyme.
     */
    val capsuleLongText: Boolean = false,
    val periods: List<SchedulePeriod> = defaultSchedulePeriods(),
) {
    override fun toString(): String = "StoredSchedule(schema=$schema, credentials=<redacted>, snapshots=${snapshots.size})"
}

@Serializable
private data class ScheduleRevocation(val owner: String, val epoch: String)

internal class SchedulePersistence(private val storage: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun revocation(): ScheduleRevocation? = storage.get(REVOKED_KEY)?.let { json.decodeFromString<ScheduleRevocation>(it) }

    /**
     * Reads the single timetable slot, regardless of who owns it.
     *
     * The slot is one record rather than a per-user partition: `owner` is an attribution stamp
     * used to fence revocation and to decide whose credentials are stored, not a lookup key that
     * hides one user's timetable from another. Callers reason about ownership in
     * [ScheduleRuntime]; this method only validates the record's shape.
     */
    fun peek(): StoredSchedule? {
        val encoded = storage.get(DATA_KEY) ?: return null
        return try {
            val record = json.decodeFromString<StoredSchedule>(encoded)
            require(record.schema == 1 && record.snapshots.size <= MAX_TERMS && record.dailyAttempts.size <= MAX_ATTEMPTS &&
                record.owner.isNotBlank() && record.account.isNotBlank() && record.password.isNotEmpty() &&
                record.bindingRevision.isNotBlank() && validateSchedulePeriods(record.periods)) { "Invalid timetable cache" }
            record
        } catch (_: Exception) {
            null
        }
    }

    /** True when a stored record exists and is not fenced off by a newer revocation epoch. */
    fun restorable(owner: String): Boolean = peek()?.let { record ->
        val revoked = runCatching { revocation() }.getOrNull()
        record.owner == owner && !(revoked?.owner == owner && record.revocationEpoch != revoked.epoch)
    } == true

    fun save(record: StoredSchedule, newBinding: Boolean = false): StoredSchedule {
        require(record.owner.isNotBlank() && record.account.isNotBlank() && record.password.isNotEmpty())
        require(record.snapshots.size <= MAX_TERMS && record.dailyAttempts.size <= MAX_ATTEMPTS)
        val revoked = revocation()?.takeIf { it.owner == record.owner }
        // Only an explicit successful confirmation may acknowledge the current revocation epoch.
        // Its acknowledgement is inside the SAME atomic record write, never a second marker deletion.
        val committed = if (newBinding) record.copy(revocationEpoch = revoked?.epoch) else {
            check(revoked == null || record.revocationEpoch == revoked.epoch) { "Timetable binding was revoked" }
            record
        }
        storage.put(DATA_KEY, json.encodeToString(StoredSchedule.serializer(), committed))
        return committed
    }

    fun revoke(owner: String) {
        // Try both independently. Memory/native state has already been invalidated by the caller.
        runCatching { storage.put(REVOKED_KEY, json.encodeToString(ScheduleRevocation(owner, Uuid.random().toString()))) }
        val removal = runCatching {
            val encoded = storage.get(DATA_KEY)
            if (encoded != null) {
                val record = json.decodeFromString<StoredSchedule>(encoded)
                if (record.owner == owner) storage.remove(DATA_KEY)
            }
        }
        // Keep the marker even after successful removal; it also fences delayed/stale disk records.
        // If deletion fails, the marker blocks restore, and the caller reports incomplete cleanup.
        removal.getOrThrow()
    }

    /**
     * Re-stamps a record written before the slot key was decoupled from the campus user id.
     *
     * Earlier versions stored `owner = userId`, so an existing binding would otherwise look like
     * a foreign record and be dropped on upgrade. The credentials and snapshots are already this
     * device's; only the attribution stamp changes. Returns the migrated record, or null when
     * there is nothing to migrate.
     */
    fun adoptLegacyOwner(slotKey: String): StoredSchedule? {
        // Any read failure must surface to the caller as "storage unavailable", not as a crash on
        // the binding prompt path; `peek` already normalises the record read, but the revocation
        // marker is a second read and is wrapped here so both fail the same way.
        val record = peek() ?: return null
        if (record.owner == slotKey) return null
        val revoked = runCatching { revocation() }.getOrNull()
        // A record fenced by its own revocation must stay dead rather than be revived by migration.
        if (revoked != null && revoked.owner == record.owner && record.revocationEpoch != revoked.epoch) return null
        val migrated = record.copy(owner = slotKey)
        return try {
            storage.put(DATA_KEY, json.encodeToString(StoredSchedule.serializer(), migrated))
            migrated
        } catch (_: Exception) {
            null
        }
    }

    fun hasSeenPrompt(owner: String): Boolean = promptOwners().contains(owner)
    fun markPromptSeen(owner: String) {
        val owners = (promptOwners().filterNot { it == owner } + owner).takeLast(64)
        storage.put(PROMPT_KEY, json.encodeToString(owners))
    }
    private fun promptOwners(): List<String> = storage.get(PROMPT_KEY)?.let { json.decodeFromString<List<String>>(it) }.orEmpty()

    companion object {
        const val MAX_TERMS = 3
        const val MAX_ATTEMPTS = 130 // Catalog (128), unresolved default, and a just-retired selected term.
        const val DATA_KEY = "better-zslf.schedule.encrypted.v1"
        const val REVOKED_KEY = "better-zslf.schedule.revoked.v1"
        const val PROMPT_KEY = "better-zslf.schedule.prompt-owners.v1"
    }
}
