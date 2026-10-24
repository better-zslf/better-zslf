package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.platform.RunMapCoordinates
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Route context consists of real server task identifiers, never a guessed track/session id. */
@Serializable
data class RunTaskSelection(val errandId: String, val errandScheduleId: String) {
    init {
        require(validId(errandId) && validId(errandScheduleId)) { "任务与计划标识须为非空服务端字段" }
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
        return copy(task = task.copy(errandId = null, scheduleId = null), warnings = warnings + "详情回显任务/计划与本次查询不一致，暂不开放开始")
    }
    val mayCarryTask = "errandId" !in task.raw
    val mayCarryPlan = listOf("errandScheduleId", "errandSchedule", "scheduleId").none { it in task.raw }
    return copy(task = task.copy(errandId = explicitTask ?: selection.errandId.takeIf { mayCarryTask }, scheduleId = explicitPlan ?: selection.errandScheduleId.takeIf { mayCarryPlan }),
        warnings = warnings + if (explicitTask == null && mayCarryTask || explicitPlan == null && mayCarryPlan) listOf("详情未回显完整标识，沿用本次任务列表选择并用于实际查询的服务端标识") else emptyList())
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
        require(RunTaskSelection.validId(ownerId) && account.isNotBlank() && account.length <= 96 && account.none(Char::isISOControl)) { "登录资料缺少有效 account/userId，暂不生成会话标识" }
        require(nowMillis >= 0) { "系统时间异常" }
        val document = load()
        val reusable = document.links.firstOrNull { link -> link.ownerId == ownerId && link.selection == selection && link.trid !in existingTrids && link.trid == account + link.preparedAtMillis }
        if (reusable != null) return reusable
        val pending = unpersistedPreparation?.takeIf { it.ownerId == ownerId && it.selection == selection && it.trid == account + it.preparedAtMillis && it.trid !in existingTrids }
        val link = pending ?: RunTaskLink(ownerId, account + nowMillis, selection, nowMillis)
        require(link.trid.length <= 128 && link.trid !in existingTrids && document.links.none { it.trid == link.trid }) { "会话标识与已有记录重复，请检查系统时间后重试" }
        require(document.links.size < MAX_LINKS) { "本机任务关联已达上限，已保存轨迹保持不变" }
        unpersistedPreparation = link
        val encoded = json.encodeToString(Document.serializer(), document.copy(links = document.links + link))
        require(encoded.length <= MAX_DOCUMENT_CHARS) { "本机任务关联超过存储上限" }
        storage.put(STORAGE_KEY, encoded)
        unpersistedPreparation = null
        return link
    }

    private fun load(): Document {
        val text = storage.get(STORAGE_KEY) ?: return Document()
        require(text.length <= MAX_DOCUMENT_CHARS) { "本机任务关联超过读取上限，原数据保留" }
        return json.decodeFromString(Document.serializer(), text).also { document ->
            require(document.version == 1 && document.links.size <= MAX_LINKS && document.links.map { it.trid }.distinct().size == document.links.size) { "本机任务关联格式异常，原数据保留" }
            document.links.forEach { link ->
                require(RunTaskSelection.validId(link.ownerId) && link.trid.isNotBlank() && link.trid.length <= 128 && link.trid.none(Char::isISOControl) && link.preparedAtMillis >= 0) { "本机任务关联记录异常，原数据保留" }
            }
        }
    }

    companion object {
        const val STORAGE_KEY = "run.ui.links.v1"
        const val MAX_LINKS = 128
        private const val MAX_DOCUMENT_CHARS = 262_144
    }
}
