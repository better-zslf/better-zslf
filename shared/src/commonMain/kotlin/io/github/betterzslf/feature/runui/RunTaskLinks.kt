package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.platform.RunMapCoordinates
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Route context consists of real server task identifiers, never a guessed track/session id. */
@Serializable
data class RunTaskSelection(val errandId: String, val errandScheduleId: String) {
    init {
        require(validId(errandId) && validId(errandScheduleId)) { AppText.Run.TASK_AND_SCHEDULE_REQUIRE_NONEMPTY_SERVER_IDS }
    }
    fun encode(): String = json.encodeToString(serializer(), this)
    companion object {
        private val json = Json { ignoreUnknownKeys = false; isLenient = false }
        internal fun validId(value: String) = value.isNotBlank() && value.length <= 256 && value.none(Char::isISOControl)
        fun decode(value: String): RunTaskSelection? {
            if (value.length > 2048) return null
            return try { json.decodeFromString(serializer(), value) } catch (_: Exception) { null }
        }
        fun from(task: RunTaskItem): RunTaskSelection? = try {
            if (task.errandId.isNullOrBlank() || task.scheduleId.isNullOrBlank()) null else RunTaskSelection(task.errandId, task.scheduleId)
        } catch (_: IllegalArgumentException) { null }
    }
}

/** Keep query provenance when the detail response omits, but does not contradict, requested ids. */
fun RunTaskDetail.withQueryContext(selection: RunTaskSelection): RunTaskDetail {
    val explicitTask = task.errandId
    val explicitPlan = task.scheduleId
    if (explicitTask != null && explicitTask != selection.errandId || explicitPlan != null && explicitPlan != selection.errandScheduleId) {
        return copy(task = task.copy(errandId = null, scheduleId = null), warnings = warnings + AppText.Run.TASK_DETAIL_IDENTITY_MISMATCH_START_DISABLED)
    }
    val mayCarryTask = "errandId" !in task.raw
    val mayCarryPlan = listOf("errandScheduleId", "errandSchedule", "scheduleId").none { it in task.raw }
    return copy(task = task.copy(errandId = explicitTask ?: selection.errandId.takeIf { mayCarryTask }, scheduleId = explicitPlan ?: selection.errandScheduleId.takeIf { mayCarryPlan }),
        warnings = warnings + if (explicitTask == null && mayCarryTask || explicitPlan == null && mayCarryPlan) listOf(AppText.Run.INCOMPLETE_DETAIL_IDENTITY_RETAINS_QUERIED_LIST_SELECTION) else emptyList())
}

/** Only copies at a declared boundary change frame. raw server JSON and every native sample stay intact. */
fun RunTaskDetail.inNativeWgs84(): RunTaskDetail = copy(
    checkpoints = checkpoints.map { it.copy(point = RunMapCoordinates.gcj02ToWgs84(it.point)) },
    walkPaths = walkPaths.map { segment -> segment.map(RunMapCoordinates::gcj02ToWgs84) },
)

@Serializable
internal data class RunTaskLink(
    val ownerId: String,
    val trid: String,
    val selection: RunTaskSelection,
    val preparedAtMillis: Long,
) {
    override fun toString(): String = "RunTaskLink(bound=true)"
}

/**
 * Write-ahead task context for the existing raw-session journal. A prepared link alone is NOT a run
 * and never starts GPS. If the later raw-session write fails, an explicit retry reuses this id.
 */
internal class RunTaskLinks(private val storage: KeyValueStore) {
    @Serializable private data class Document(val version: Int = 1, val links: List<RunTaskLink> = emptyList())
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private var unpersistedPreparation: RunTaskLink? = null

    fun lookup(ownerId: String, trid: String): RunTaskLink? = load().links.firstOrNull { it.ownerId == ownerId && it.trid == trid }

    fun prepare(ownerId: String, account: String, selection: RunTaskSelection, nowMillis: Long, existingTrids: Set<String>): RunTaskLink {
        require(RunTaskSelection.validId(ownerId) && account.isNotBlank() && account.length <= 96 && account.none(Char::isISOControl)) { AppText.Run.LOGIN_ACCOUNT_OR_USER_ID_MISSING_SESSION_ID_NOT_GENERATED }
        require(nowMillis >= 0) { AppText.Common.INVALID_SYSTEM_TIME }
        val document = load()
        val reusable = document.links.firstOrNull { link -> link.ownerId == ownerId && link.selection == selection && link.trid !in existingTrids && link.trid == account + link.preparedAtMillis }
        if (reusable != null) return reusable
        val pending = unpersistedPreparation?.takeIf { it.ownerId == ownerId && it.selection == selection && it.trid == account + it.preparedAtMillis && it.trid !in existingTrids }
        val link = pending ?: RunTaskLink(ownerId, account + nowMillis, selection, nowMillis)
        require(link.trid.length <= 128 && link.trid !in existingTrids && document.links.none { it.trid == link.trid }) { AppText.Run.DUPLICATE_LOCAL_SESSION_ID_CHECK_SYSTEM_TIME }
        require(document.links.size < MAX_LINKS) { AppText.Run.LOCAL_TASK_LINK_LIMIT_REACHED_SAVED_TRACKS_UNCHANGED }
        unpersistedPreparation = link
        val encoded = json.encodeToString(Document.serializer(), document.copy(links = document.links + link))
        require(encoded.length <= MAX_DOCUMENT_CHARS) { AppText.Run.LOCAL_TASK_LINK_STORAGE_LIMIT_EXCEEDED }
        storage.put(STORAGE_KEY, encoded)
        unpersistedPreparation = null
        return link
    }

    private fun load(): Document {
        val text = storage.get(STORAGE_KEY) ?: return Document()
        require(text.length <= MAX_DOCUMENT_CHARS) { AppText.Run.LOCAL_TASK_LINK_READ_LIMIT_EXCEEDED_ORIGINAL_RETAINED }
        return json.decodeFromString(Document.serializer(), text).also { document ->
            require(document.version == 1 && document.links.size <= MAX_LINKS && document.links.map { it.trid }.distinct().size == document.links.size) { AppText.Run.INVALID_LOCAL_TASK_LINK_FORMAT_ORIGINAL_RETAINED }
            document.links.forEach { link ->
                require(RunTaskSelection.validId(link.ownerId) && link.trid.isNotBlank() && link.trid.length <= 128 && link.trid.none(Char::isISOControl) && link.preparedAtMillis >= 0) { AppText.Run.INVALID_LOCAL_TASK_LINK_RECORD_ORIGINAL_RETAINED }
            }
        }
    }

    companion object {
        const val STORAGE_KEY = "run.ui.links.v1"
        const val MAX_LINKS = 128
        private const val MAX_DOCUMENT_CHARS = 262_144
    }
}
