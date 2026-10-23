package io.github.betterzslf.feature.run

import io.github.betterzslf.core.storage.KeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
enum class RunUploadEndpoint { PRIMARY, SYNC_SPORT_DATA, REPORT_LOCATION }
@Serializable
enum class RunBatchStatus { STAGED, READY, IN_FLIGHT, ACKNOWLEDGED, FAILED, UNCERTAIN }
@Serializable
data class RunUploadFailure(val endpoint: RunUploadEndpoint, val attempt: Int, val message: String, val uncertain: Boolean)
@Serializable
data class RunQueuedBatch(
    val batch: RunUploadBatch,
    val status: RunBatchStatus = RunBatchStatus.STAGED,
    val endpoint: RunUploadEndpoint = RunUploadEndpoint.PRIMARY,
    val attempts: Int = 0,
    val confirmed: Boolean = false,
    val failures: List<RunUploadFailure> = emptyList(),
)

class RunQueueException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * One instance per application store/key. put() must be durable when it returns for crash guarantees.
 * The entire journal is replaced in one store operation, and local operations are serialized by a Mutex.
 * ACK tombstones are intentionally retained; batchId is not evidence of server-side idempotency.
 */
class RunUploadQueue(private val storage: KeyValueStore, private val storageKey: String = "run.upload.queue.v1") {
    @Serializable
    private data class Document(val version: Int = 1, val entries: List<RunQueuedBatch> = emptyList())
    private val mutex = Mutex()
    private var initialized = false

    suspend fun snapshot(): List<RunQueuedBatch> = mutex.withLock { load().entries.toList() }

    /** Stages only: enqueue never constitutes user confirmation and never performs I/O to a server. */
    suspend fun enqueue(batches: List<RunUploadBatch>) = mutex.withLock {
        if (batches.isEmpty()) return@withLock
        batches.forEach { RunBatcher.validateBatch(it) }
        require(batches.map { it.batchId }.distinct().size == batches.size) { "输入包含重复批次标识" }
        val document = load()
        val entries = document.entries.toMutableList()
        for (batch in batches) {
            val existing = entries.firstOrNull { it.batch.batchId == batch.batchId }
            if (existing != null) {
                require(existing.batch == batch) { "同一 batchId 对应不同内容，原记录已保留" }
                continue
            }
            require(entries.none { it.batch.trid == batch.trid && it.batch.index == batch.index }) { "同一任务块索引已使用其它 batchId" }
            entries += RunQueuedBatch(batch)
        }
        require(entries.size <= 20_000) { "本地上传批次过多，请先处理已有任务" }
        validateRuns(entries)
        persist(document.copy(entries = entries.sortedWith(compareBy({ it.batch.trid }, { it.batch.index }))))
    }

    /** expectedBatchIds comes from the exact pending preview that the user just confirmed. */
    suspend fun confirmUpload(trid: String, ownerId: String, expectedBatchIds: List<String>) = mutex.withLock {
        val document = load()
        val run = ownedRun(document, trid, ownerId)
        val pending = run.filter { it.status != RunBatchStatus.ACKNOWLEDGED }
        require(pending.isNotEmpty()) { "任务没有待上传块" }
        require(pending.map { it.batch.batchId } == expectedBatchIds) { "待上传内容已变更，请重新预览确认" }
        require(pending.none { it.status == RunBatchStatus.IN_FLIGHT || it.status == RunBatchStatus.UNCERTAIN }) { "先核对结果不明的批次，再确认续传" }
        require(pending.all { it.attempts < 100 }) { "批次重试次数过多，请检查失败原因" }
        persist(document.copy(entries = document.entries.map {
            if (it.batch.trid == trid && it.status != RunBatchStatus.ACKNOWLEDGED) it.copy(status = RunBatchStatus.READY, confirmed = true) else it
        }))
    }

    /**
     * Revokes permission for future unsent work. An already in-flight request is not recalled;
     * its acknowledgment/uncertainty and all ACK tombstones remain untouched.
     */
    suspend fun holdRun(trid: String, ownerId: String) = mutex.withLock {
        val document = load()
        ownedRun(document, trid, ownerId)
        persist(document.copy(entries = document.entries.map {
            if (it.batch.trid == trid && it.status in listOf(RunBatchStatus.STAGED, RunBatchStatus.READY, RunBatchStatus.FAILED))
                it.copy(status = RunBatchStatus.STAGED, confirmed = false) else it
        }))
    }

    /** Only after a record/transport reconciliation, not an assumption that a timeout meant rejection. */
    suspend fun reconcile(batchId: String, wasAccepted: Boolean, evidence: String) = mutex.withLock {
        require(evidence.isNotBlank()) { "请先记录核对依据" }
        val document = load()
        val current = document.entries.firstOrNull { it.batch.batchId == batchId } ?: throw RunQueueException("批次不存在")
        require(current.status == RunBatchStatus.UNCERTAIN) { "仅结果不明的批次需要核对" }
        val failure = RunUploadFailure(current.endpoint, current.attempts, "接收结果已核对：$evidence", false)
        replace(document, current.copy(
            status = if (wasAccepted) RunBatchStatus.ACKNOWLEDGED else RunBatchStatus.STAGED,
            confirmed = false,
            failures = current.failures + failure,
        ))
    }

    internal sealed interface Claim {
        data class Acquired(val entry: RunQueuedBatch) : Claim
        data class Blocked(val status: RunBatchStatus, val message: String) : Claim
        data class NotDue(val dueMillis: Long) : Claim
        data class Complete(val finished: Boolean) : Claim
    }

    internal suspend fun claimNext(trid: String, ownerId: String, nowMillis: Long): Claim = mutex.withLock {
        require(nowMillis >= 0)
        val document = load()
        val run = ownedRun(document, trid, ownerId)
        val next = run.firstOrNull { it.status != RunBatchStatus.ACKNOWLEDGED } ?: return@withLock Claim.Complete(run.last().batch.payload.finishType == "1")
        if (next.status != RunBatchStatus.READY || !next.confirmed) return@withLock Claim.Blocked(next.status, when (next.status) {
            RunBatchStatus.STAGED -> "上传内容等待用户确认"
            RunBatchStatus.FAILED -> next.failures.lastOrNull()?.message ?: "上传失败，请检查后重试"
            RunBatchStatus.UNCERTAIN -> "服务端接收结果待核对，已暂停以避免重复"
            RunBatchStatus.IN_FLIGHT -> "此批次正在发送"
            else -> "此批次尚未就绪"
        })
        if (next.batch.payload.endTime > nowMillis) return@withLock Claim.NotDue(next.batch.payload.endTime)
        require(next.attempts < 100) { "批次重试次数过多" }
        val acquired = next.copy(status = RunBatchStatus.IN_FLIGHT, attempts = next.attempts + 1)
        replace(document, acquired) // The send starts only after this durable checkpoint succeeds.
        Claim.Acquired(acquired)
    }

    internal suspend fun accepted(batchId: String, acknowledgedBatchId: String) = mutex.withLock {
        require(batchId == acknowledgedBatchId) { "上传应答与发送批次不匹配" }
        val document = load()
        val current = inFlight(document, batchId)
        replace(document, current.copy(status = RunBatchStatus.ACKNOWLEDGED, confirmed = false))
    }

    internal suspend fun failed(batchId: String, message: String, uncertain: Boolean, allowFailover: Boolean): Boolean = mutex.withLock {
        val document = load()
        val current = inFlight(document, batchId)
        val next = if (allowFailover && !uncertain) RunUploadEndpoint.entries.getOrNull(current.endpoint.ordinal + 1) else null
        replace(document, current.copy(
            status = if (uncertain) RunBatchStatus.UNCERTAIN else if (next != null) RunBatchStatus.READY else RunBatchStatus.FAILED,
            endpoint = next ?: current.endpoint,
            confirmed = next != null,
            failures = current.failures + RunUploadFailure(current.endpoint, current.attempts, message, uncertain),
        ))
        next != null
    }

    private fun inFlight(document: Document, batchId: String): RunQueuedBatch {
        val current = document.entries.firstOrNull { it.batch.batchId == batchId } ?: throw RunQueueException("发送中的批次不存在")
        require(current.status == RunBatchStatus.IN_FLIGHT) { "批次状态已变更" }
        return current
    }
    private fun ownedRun(document: Document, trid: String, ownerId: String): List<RunQueuedBatch> {
        val run = document.entries.filter { it.batch.trid == trid }.sortedBy { it.batch.index }
        require(run.isNotEmpty()) { "本地没有该跑步任务" }
        require(ownerId.isNotBlank() && run.all { it.batch.payload.userId == ownerId }) { "当前用户与本地任务不一致" }
        return run
    }
    private fun replace(document: Document, replacement: RunQueuedBatch) {
        persist(document.copy(entries = document.entries.map { if (it.batch.batchId == replacement.batch.batchId) replacement else it }))
    }
    private fun load(): Document {
        val document = try {
            val stored = storage.get(storageKey)
            if (stored == null) Document() else {
                if (stored.length > 16_000_000) throw RunQueueException("本地上传队列超过读取上限，原数据已保留")
                RunBatcher.json.decodeFromString<Document>(stored).also {
                    require(it.version == 1 && it.entries.size <= 20_000)
                    require(it.entries.map { entry -> entry.batch.batchId }.distinct().size == it.entries.size)
                    validateRuns(it.entries)
                }
            }
        } catch (error: Exception) {
            if (error is RunQueueException) throw error
            throw RunQueueException("本地上传队列读取失败，原数据已保留", error)
        }
        if (initialized) return document
        val recovered = document.copy(entries = document.entries.map {
            if (it.status == RunBatchStatus.IN_FLIGHT) it.copy(
                status = RunBatchStatus.UNCERTAIN,
                confirmed = false,
                failures = it.failures + RunUploadFailure(it.endpoint, it.attempts, "上次发送中断，服务端接收结果待核对", true),
            ) else it
        })
        if (recovered != document) persist(recovered)
        initialized = true
        return recovered
    }
    private fun persist(document: Document) {
        try {
            val encoded = RunBatcher.json.encodeToString(document)
            if (encoded.length > 16_000_000) throw RunQueueException("本地上传队列超过存储上限，原数据已保留")
            storage.put(storageKey, encoded)
        } catch (error: Exception) {
            if (error is RunQueueException) throw error
            throw RunQueueException("本地上传队列保存失败，请保留当前轨迹后重试", error)
        }
    }

    private fun validateRuns(entries: List<RunQueuedBatch>) {
        for (run in entries.groupBy { it.batch.trid }.values) {
            val ordered = run.sortedBy { it.batch.index }
            val first = ordered.first().batch.payload
            var previousCoordinate: RunCoordinate? = null
            var cumulativeDistance = 0.0
            for ((index, entry) in ordered.withIndex()) {
                val payload = entry.batch.payload
                require(entry.batch.index == index) { "任务批次索引须从零连续递增" }
                require(entry.attempts in 0..100) { "批次尝试计数异常" }
                require(payload.userId == first.userId && payload.scheduleId == first.scheduleId && payload.startTime == first.startTime) { "同一任务的用户、计划与起始时间须一致" }
                require(payload.finishType != "1" || index == ordered.lastIndex) { "完成标记只属于末块" }
                val coordinates = RunBatcher.validateBatch(entry.batch)
                for (coordinate in coordinates) {
                    val previous = previousCoordinate
                    if (previous == null) {
                        require(coordinate.locatetime == first.startTime && coordinate.dis == 0.0 && coordinate.count == 0.0) { "首点时间与距离应为起跑状态" }
                    } else {
                        require(coordinate.locatetime > previous.locatetime) { "跨块轨迹时间须严格递增" }
                        fun point(value: RunCoordinate): GeoPoint { val p = value.location.split(','); return GeoPoint(p[0].toDouble(), p[1].toDouble()) }
                        val distance = RunGeo.distanceMeters(point(previous), point(coordinate))
                        require(kotlin.math.abs(distance - coordinate.dis) <= 0.01) { "跨块距离与坐标不一致" }
                        require(distance / ((coordinate.locatetime - previous.locatetime) / 1000.0) <= 5.000001) { "跨块速度超过上限" }
                    }
                    cumulativeDistance += coordinate.dis
                    previousCoordinate = coordinate
                }
                require(kotlin.math.abs(cumulativeDistance - payload.runLength.toDouble()) <= 0.01) { "累计距离与批次轨迹不一致" }
            }
        }
    }
}
