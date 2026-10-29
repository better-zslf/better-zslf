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
    val periods: List<SchedulePeriod> = defaultSchedulePeriods(),
) {
    override fun toString(): String = "StoredSchedule(schema=$schema, credentials=<redacted>, snapshots=${snapshots.size})"
}

@Serializable
private data class ScheduleRevocation(val owner: String, val epoch: String)

internal class SchedulePersistence(private val storage: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }
    private fun revocation(): ScheduleRevocation? = storage.get(REVOKED_KEY)?.let { json.decodeFromString<ScheduleRevocation>(it) }

    fun restore(owner: String): StoredSchedule? {
        val revoked = revocation()
        val encoded = storage.get(DATA_KEY) ?: return null
        val record = json.decodeFromString<StoredSchedule>(encoded)
        require(record.schema == 1 && record.snapshots.size <= MAX_TERMS && record.dailyAttempts.size <= MAX_ATTEMPTS &&
            record.owner.isNotBlank() && record.account.isNotBlank() && record.password.isNotEmpty() &&
            record.bindingRevision.isNotBlank() && validateSchedulePeriods(record.periods)) { "Invalid timetable cache" }
        if (record.owner != owner) return null
        if (revoked?.owner == owner && record.revocationEpoch != revoked.epoch) return null
        return record
    }

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
