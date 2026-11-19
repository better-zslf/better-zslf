package io.github.betterzslf.feature.run

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.storage.KeyValueStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** Accessed only under the coordinator Mutex; a single coordinator owns this journal key. */
internal class RunSessionJournal(private val storage: KeyValueStore) {
    @Serializable
    data class Document(val version: Int = 1, val activeTrid: String? = null, val sessions: List<StoredRunSession> = emptyList())

    fun load(): Document = try {
        val encoded = storage.get(STORAGE_KEY)
        if (encoded == null) Document() else {
            require(encoded.length <= MAX_DOCUMENT_CHARS)
            RunBatcher.json.decodeFromString<Document>(encoded).also(::validate)
        }
    } catch (error: Exception) {
        throw RunSessionStorageException(AppText.Run.LOCAL_SESSION_READ_FAILED_ORIGINAL_RETAINED, error)
    }

    fun save(record: StoredRunSession) {
        val document = load()
        val old = document.sessions.firstOrNull { it.trid == record.trid }
        require(old == null || old.request == record.request) { AppText.Run.SESSION_ID_TASK_CONTENT_MISMATCH }
        val sessions = if (old == null) document.sessions + record else document.sessions.map { if (it.trid == record.trid) record else it }
        val active = when (record.phase) {
            StoredRunSessionPhase.RECORDING, StoredRunSessionPhase.PAUSED, StoredRunSessionPhase.FINISHING -> {
                require(document.activeTrid == null || document.activeTrid == record.trid) { AppText.Run.ANOTHER_UNFINISHED_LOCAL_SESSION_EXISTS }
                record.trid
            }
            StoredRunSessionPhase.FINISHED, StoredRunSessionPhase.CANCELLED -> if (document.activeTrid == record.trid) null else document.activeTrid
        }
        val updated = document.copy(activeTrid = active, sessions = sessions)
        try {
            validate(updated)
            val encoded = RunBatcher.json.encodeToString(updated)
            require(encoded.length <= MAX_DOCUMENT_CHARS) { AppText.Run.LOCAL_RECORD_STORAGE_LIMIT_EXCEEDED }
            storage.put(STORAGE_KEY, encoded)
        } catch (error: Exception) {
            throw RunSessionStorageException(AppText.Run.LOCAL_SESSION_SAVE_FAILED_PREVIOUS_RETAINED, error)
        }
    }

    private fun validate(document: Document) {
        require(document.version == 1 && document.sessions.size <= MAX_SESSIONS)
        require(document.sessions.map { it.trid }.distinct().size == document.sessions.size)
        require(document.sessions.map { it.request.batchIdPrefix }.distinct().size == document.sessions.size)
        require(document.sessions.sumOf { it.samples.size } <= MAX_TOTAL_SAMPLES)
        val active = document.sessions.filter { it.phase in listOf(StoredRunSessionPhase.RECORDING, StoredRunSessionPhase.PAUSED, StoredRunSessionPhase.FINISHING) }
        require(active.size <= 1 && active.singleOrNull()?.trid == document.activeTrid)
        for (record in document.sessions) {
            require(record.startedAtMillis >= 0 && record.samples.size <= MAX_SAMPLES_PER_SESSION)
            require(record.lastConsentAtMillis == null || record.lastConsentAtMillis >= record.startedAtMillis)
            if (record.phase == StoredRunSessionPhase.FINISHING || record.phase == StoredRunSessionPhase.FINISHED) {
                require(record.finishedAtMillis != null && record.finishedAtMillis >= record.startedAtMillis)
            } else require(record.finishedAtMillis == null)
            var previous: RunLocationSample? = null
            val filter = RunLocationFilter()
            for (sample in record.samples) {
                require(sample.timestampMillis >= record.startedAtMillis)
                require(record.finishedAtMillis == null || sample.timestampMillis <= record.finishedAtMillis)
                require(filter.evaluate(previous, sample) is LocationFilterResult.Accepted)
                require(previous == null || sample.timestampMillis - previous.timestampMillis >= record.request.samplingSeconds * 1000L)
                previous = sample
            }
        }
    }

    companion object {
        const val STORAGE_KEY = "run.sessions.v1"
        const val MAX_SAMPLES_PER_SESSION = 10_000
        const val MAX_TOTAL_SAMPLES = 50_000
        const val MAX_SESSIONS = 32
        const val MAX_DOCUMENT_CHARS = 16_000_000
    }
}

internal class RunSessionStorageException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)
