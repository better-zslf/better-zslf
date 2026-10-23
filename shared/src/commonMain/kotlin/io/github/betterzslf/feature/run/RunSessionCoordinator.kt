package io.github.betterzslf.feature.run

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.platform.LocationPhase
import io.github.betterzslf.platform.LocationTrackingState
import io.github.betterzslf.platform.NativeLocationTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * App-lifetime, single-tracker coordinator. Supply the app's main-dispatcher scope for native trackers.
 * No network client/worker is owned here: every operation is GPS, local math, or private storage only.
 * A successful store.put must be durable; use one coordinator and one queue per store/key.
 */
class RunSessionCoordinator(
    scope: CoroutineScope,
    private val tracker: NativeLocationTracker,
    storage: KeyValueStore,
    private val queue: RunUploadQueue,
    private val nowMillis: () -> Long,
    private val maximumSamplesPerSession: Int = MAX_SAMPLES_PER_SESSION,
) {
    private val journal = RunSessionJournal(storage)
    private val mutex = Mutex()
    private val lifecycle = SupervisorJob(scope.coroutineContext[Job])
    private val appScope = CoroutineScope(scope.coroutineContext + lifecycle)
    private val mutableState = MutableStateFlow(RunSessionState(nativeLocation = tracker.state.value))
    val state: StateFlow<RunSessionState> = mutableState.asStateFlow()
    private var current: StoredRunSession? = null
    private var visibleOwnerId: String? = null
    private var phase = RunSessionPhase.IDLE
    private var nativeState = tracker.state.value
    private var sampleJob: Job? = null
    private var accepting = false
    private var epoch = 0L
    private var windowStartMillis = 0L
    private var consentInThisProcess = false
    private var closed = false
    private var queueView = emptyList<RunQueuedBatch>()
    private var rejectedCount = 0
    private var lastRejection: RunSessionSampleRejection? = null
    private var lastFilterRejection: LocationRejection? = null
    private var problem: RunSessionProblem? = null
    private var unpersistedSample: RunLocationSample? = null

    init {
        require(maximumSamplesPerSession in 1..MAX_SAMPLES_PER_SESSION)
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { close() } }
        }
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            tracker.state.collect { native ->
                mutex.withLock {
                    nativeState = native
                    if (accepting && native.phase in listOf(LocationPhase.PermissionRequired, LocationPhase.LocationDisabled, LocationPhase.Failed, LocationPhase.Unavailable)) {
                        halt(RunSessionProblem(RunSessionProblemCode.LOCATION, native.message ?: "定位已停止，请检查定位权限与服务"))
                    } else publish()
                }
            }
        }
    }

    suspend fun start(request: RunSessionStart, ownerId: String, uploadConfirmed: Boolean): RunSessionAction = action {
        current?.takeIf { it.phase in listOf(StoredRunSessionPhase.RECORDING, StoredRunSessionPhase.PAUSED, StoredRunSessionPhase.FINISHING) }?.let { checkOwner(it.ownerId, ownerId) }
        checkOwner(request.metadata.userId, ownerId)
        ensure(uploadConfirmed, RunSessionProblemCode.CONFIRMATION_REQUIRED, "请先明确勾选本次真实轨迹的上传确认")
        val document = journal.load()
        document.activeTrid?.let { activeId -> document.sessions.firstOrNull { it.trid == activeId }?.let { checkOwner(it.ownerId, ownerId) } }
        ensure(document.activeTrid == null, RunSessionProblemCode.ACTIVE_SESSION_EXISTS, "已有未结束的本地会话，请先恢复处理")
        ensure(document.sessions.none { it.trid == request.metadata.trid || it.request.batchIdPrefix == request.batchIdPrefix }, RunSessionProblemCode.INVALID_INPUT, "此会话或批次标识已使用，请使用新的稳定标识")
        ensure(document.sessions.size < MAX_SESSIONS && document.sessions.sumOf { it.samples.size } < MAX_TOTAL_SAMPLES, RunSessionProblemCode.SAMPLE_LIMIT, "本地会话记录已达上限，请先管理已保存记录")
        val existing = queue.snapshot()
        ensure(existing.none { it.batch.trid == request.metadata.trid || it.batch.batchId.startsWith(request.batchIdPrefix + "-") }, RunSessionProblemCode.INVALID_INPUT, "此任务或批次标识已用于本地上传队列")
        val now = clock()
        val record = StoredRunSession(request.copy(checkpoints = request.checkpoints.toList()), StoredRunSessionPhase.RECORDING, now, lastConsentAtMillis = now)
        journal.save(record) // A native start never precedes a durable, owner-bound session record.
        current = record
        queueView = emptyList()
        rejectedCount = 0
        lastRejection = null
        lastFilterRejection = null
        unpersistedSample = null
        consentInThisProcess = true
        phase = RunSessionPhase.RECORDING
        beginSampling(now)
    }

    /** No location start, no new batch, no implicit renewal of a prior process's consent. */
    suspend fun restore(ownerId: String, trid: String? = null): RunSessionAction = action {
        val document = journal.load()
        val record = if (trid != null) document.sessions.firstOrNull { it.trid == trid }
            else document.activeTrid?.let { id -> document.sessions.firstOrNull { it.trid == id } }
                ?: document.sessions.lastOrNull { it.ownerId == ownerId }
        ensure(record != null, RunSessionProblemCode.NO_SESSION, "没有可恢复的本地跑步会话")
        record!!
        checkOwner(record.ownerId, ownerId)
        stopSampling()
        consentInThisProcess = false
        if (current?.trid != record.trid) unpersistedSample = null
        current = record
        phase = when (record.phase) {
            StoredRunSessionPhase.FINISHED -> RunSessionPhase.FINISHED
            StoredRunSessionPhase.CANCELLED -> RunSessionPhase.CANCELLED
            else -> RunSessionPhase.RECOVERED
        }
        queueView = ownedQueue(record)
        if (queueView.isNotEmpty()) queue.holdRun(record.trid, ownerId)
        queueView = ownedQueue(record)
        rejectedCount = 0
        lastRejection = null
        lastFilterRejection = null
    }

    suspend fun pause(ownerId: String): RunSessionAction = action {
        val record = selected(ownerId)
        ensure(!record.finishRequested && record.phase !in listOf(StoredRunSessionPhase.FINISHED, StoredRunSessionPhase.CANCELLED), RunSessionProblemCode.INVALID_STATE, "该会话已进入结束流程")
        stopSampling()
        consentInThisProcess = false
        phase = RunSessionPhase.PAUSED
        val paused = record.copy(phase = StoredRunSessionPhase.PAUSED)
        journal.save(paused)
        current = paused
    }

    suspend fun resume(ownerId: String, uploadConfirmed: Boolean): RunSessionAction = action {
        val record = selected(ownerId)
        ensure(!accepting && !record.finishRequested && record.phase !in listOf(StoredRunSessionPhase.FINISHED, StoredRunSessionPhase.CANCELLED), RunSessionProblemCode.INVALID_STATE, "该会话当前不支持恢复采样")
        ensure(unpersistedSample == null, RunSessionProblemCode.STORAGE, "请先重试保存未持久化的真实采样点")
        ensure(record.samples.size < maximumSamplesPerSession && journal.load().sessions.sumOf { it.samples.size } < MAX_TOTAL_SAMPLES, RunSessionProblemCode.SAMPLE_LIMIT, "真实采样点已达上限，请结束并保留记录")
        ensure(uploadConfirmed, RunSessionProblemCode.CONFIRMATION_REQUIRED, "请先确认恢复后的真实轨迹上传")
        val now = clock()
        ensure(now >= record.startedAtMillis && (record.samples.lastOrNull()?.timestampMillis ?: now) <= now, RunSessionProblemCode.CLOCK, "系统时间早于已保存轨迹，请校正后恢复")
        val resumed = record.copy(phase = StoredRunSessionPhase.RECORDING, lastConsentAtMillis = now)
        journal.save(resumed)
        current = resumed
        consentInThisProcess = true
        phase = RunSessionPhase.RECORDING
        syncQueue(resumed, finished = false, confirm = true)
        beginSampling(now)
    }

    /**
     * Freezes the real sample set BEFORE the final queue write. Repeating finish is idempotent.
     * Zero/single-point sessions are retained as FINISHED without manufacturing a second point.
     * After recovery, uploadConfirmed=false stages the tail; it never renews upload permission.
     */
    suspend fun finish(ownerId: String, uploadConfirmed: Boolean = false): RunSessionAction = action {
        var record = selected(ownerId)
        ensure(record.phase != StoredRunSessionPhase.CANCELLED, RunSessionProblemCode.INVALID_STATE, "已取消的会话保留在本地，不生成结束块")
        ensure(unpersistedSample == null, RunSessionProblemCode.STORAGE, "请先保存未持久化的真实采样点，再结束")
        if (record.phase == StoredRunSessionPhase.FINISHED) {
            queueView = ownedQueue(record)
            if (uploadConfirmed) confirmEligible(record)
            phase = RunSessionPhase.FINISHED
            return@action
        }
        val allowConfirmation = consentInThisProcess || uploadConfirmed
        val now = clock()
        ensure(now >= record.startedAtMillis && now >= (record.samples.lastOrNull()?.timestampMillis ?: now), RunSessionProblemCode.CLOCK, "系统时间早于已保存轨迹，请校正后结束")
        stopSampling()
        consentInThisProcess = false
        phase = RunSessionPhase.FINISHING
        if (!record.finishRequested) {
            record = record.copy(phase = StoredRunSessionPhase.FINISHING, finishedAtMillis = now,
                lastConsentAtMillis = if (uploadConfirmed) now else record.lastConsentAtMillis)
            journal.save(record)
            current = record
        }
        syncQueue(record, finished = true, confirm = allowConfirmation)
        val finished = record.copy(phase = StoredRunSessionPhase.FINISHED)
        journal.save(finished)
        current = finished
        phase = RunSessionPhase.FINISHED
    }

    /** Stops capture and holds unsent work. In-flight/uncertain writes remain visible, never guessed away. */
    suspend fun cancel(ownerId: String): RunSessionAction = action {
        val record = selected(ownerId)
        ensure(record.phase != StoredRunSessionPhase.FINISHED && !record.finishRequested, RunSessionProblemCode.INVALID_STATE, "已请求完成的会话请处理结束或核对结果")
        ensure(unpersistedSample == null, RunSessionProblemCode.STORAGE, "请先保存未持久化的真实采样点，再取消会话")
        stopSampling()
        consentInThisProcess = false
        queueView = ownedQueue(record)
        if (queueView.isNotEmpty()) queue.holdRun(record.trid, ownerId)
        queueView = ownedQueue(record)
        val cancelled = record.copy(phase = StoredRunSessionPhase.CANCELLED)
        journal.save(cancelled)
        current = cancelled
        phase = RunSessionPhase.CANCELLED
    }

    /** Explicit exact-preview confirmation; failed or uncertain blocks require their existing queue workflow. */
    suspend fun confirmPendingUpload(ownerId: String, expectedBatchIds: List<String>): RunSessionAction = action {
        val record = selected(ownerId)
        ensure(record.phase != StoredRunSessionPhase.CANCELLED, RunSessionProblemCode.INVALID_STATE, "已取消会话的上传许可已撤回")
        queueView = ownedQueue(record)
        val pending = queueView.filter { it.status != RunBatchStatus.ACKNOWLEDGED }
        ensure(pending.isNotEmpty() && pending.map { it.batch.batchId } == expectedBatchIds, RunSessionProblemCode.INVALID_INPUT, "待上传内容已变更，请重新预览")
        ensure(pending.none { it.status in listOf(RunBatchStatus.IN_FLIGHT, RunBatchStatus.UNCERTAIN, RunBatchStatus.FAILED) }, RunSessionProblemCode.QUEUE, "请先处理发送中、失败或结果待核对的批次")
        queue.confirmUpload(record.trid, ownerId, expectedBatchIds)
        queueView = ownedQueue(record)
    }

    suspend fun refreshUploadState(ownerId: String): RunSessionAction = action { queueView = ownedQueue(selected(ownerId)) }

    /** Retries only the one real sample whose disk write failed; no GPS position/time is synthesized. */
    suspend fun retryPersistence(ownerId: String): RunSessionAction = action {
        val record = selected(ownerId)
        ensure(!record.finishRequested && record.phase !in listOf(StoredRunSessionPhase.FINISHED, StoredRunSessionPhase.CANCELLED), RunSessionProblemCode.INVALID_STATE, "该会话已关闭采样集")
        val sample = unpersistedSample ?: return@action
        val repaired = record.copy(phase = StoredRunSessionPhase.PAUSED, samples = record.samples + sample)
        journal.save(repaired)
        current = repaired
        unpersistedSample = null
        phase = RunSessionPhase.PAUSED
        syncQueue(repaired, finished = false, confirm = false)
    }

    suspend fun sessions(ownerId: String): List<RunSessionSummary> = mutex.withLock {
        require(ownerId.isNotBlank())
        journal.load().sessions.filter { it.ownerId == ownerId }.map {
            RunSessionSummary(it.request.metadata, when (it.phase) {
                StoredRunSessionPhase.FINISHED -> RunSessionPhase.FINISHED
                StoredRunSessionPhase.CANCELLED -> RunSessionPhase.CANCELLED
                else -> RunSessionPhase.RECOVERED
            }, it.samples.size, it.startedAtMillis, it.finishRequested)
        }
    }

    /** Closes only owned jobs, not the supplied app scope. Native close is never used for pause/resume. */
    suspend fun close(): RunSessionAction = mutex.withLock {
        if (closed) return@withLock RunSessionAction.Applied(state.value)
        var closeProblem: RunSessionProblem? = null
        try {
            stopSampling()
            consentInThisProcess = false
            current?.let { record ->
                queueView = ownedQueue(record)
                if (queueView.isNotEmpty()) queue.holdRun(record.trid, record.ownerId)
                queueView = ownedQueue(record)
                if (record.phase == StoredRunSessionPhase.RECORDING) {
                    val paused = record.copy(phase = StoredRunSessionPhase.PAUSED)
                    journal.save(paused)
                    current = paused
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { closeProblem = classify(error) }
        finally {
            closed = true
            accepting = false
            lifecycle.cancel()
            try { tracker.close() } catch (_: Exception) { if (closeProblem == null) closeProblem = RunSessionProblem(RunSessionProblemCode.LOCATION, "定位资源释放失败，请检查平台状态") }
            phase = RunSessionPhase.CLOSED
            problem = closeProblem
            publish()
        }
        closeProblem?.let { RunSessionAction.Rejected(it) } ?: RunSessionAction.Applied(state.value)
    }

    private suspend fun action(block: suspend () -> Unit): RunSessionAction = mutex.withLock {
        if (closed) return@withLock RunSessionAction.Rejected(RunSessionProblem(RunSessionProblemCode.CLOSED, "此会话协调器已关闭"))
        problem = null
        try {
            block()
            publish()
            RunSessionAction.Applied(state.value)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (issue: SessionIssue) {
            problem = issue.problem
            publish()
            RunSessionAction.Rejected(issue.problem)
        } catch (error: Exception) {
            val failure = classify(error)
            halt(failure)
            RunSessionAction.Rejected(problem ?: failure)
        }
    }

    private fun clock(): Long = try {
        nowMillis().also { ensure(it >= 0, RunSessionProblemCode.CLOCK, "系统时间异常") }
    } catch (issue: SessionIssue) { throw issue }
    catch (_: Exception) { throw SessionIssue(RunSessionProblem(RunSessionProblemCode.CLOCK, "读取系统时间失败")) }

    private fun selected(ownerId: String): StoredRunSession {
        val record = current ?: throw SessionIssue(RunSessionProblem(RunSessionProblemCode.NO_SESSION, "请先开始或恢复本地会话"))
        checkOwner(record.ownerId, ownerId)
        return record
    }
    private fun checkOwner(expected: String, actual: String) {
        visibleOwnerId = actual
        if (actual.isNotBlank() && expected == actual) {
            if (phase == RunSessionPhase.OWNER_MISMATCH) phase = when (current?.phase) {
                StoredRunSessionPhase.FINISHED -> RunSessionPhase.FINISHED
                StoredRunSessionPhase.CANCELLED -> RunSessionPhase.CANCELLED
                else -> RunSessionPhase.RECOVERED
            }
            return
        }
        consentInThisProcess = false
        try { stopSampling() } catch (_: Exception) { /* Capture gate is already closed. */ }
        phase = RunSessionPhase.OWNER_MISMATCH
        throw SessionIssue(RunSessionProblem(RunSessionProblemCode.OWNER_MISMATCH, "当前用户与本地会话不一致，记录已隔离"))
    }
    private fun ensure(condition: Boolean, code: RunSessionProblemCode, message: String) {
        if (!condition) throw SessionIssue(RunSessionProblem(code, message))
    }
    private class SessionIssue(val problem: RunSessionProblem) : IllegalStateException(problem.message)

    private fun beginSampling(startMillis: Long) {
        windowStartMillis = startMillis
        val generation = ++epoch
        accepting = true
        sampleJob?.cancel()
        sampleJob = appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                tracker.samples.collect { sample -> onSample(generation, sample) }
                mutex.withLock { if (accepting && generation == epoch) halt(RunSessionProblem(RunSessionProblemCode.LOCATION, "定位数据流已结束，请检查后显式恢复")) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutex.withLock { if (accepting && generation == epoch) halt(RunSessionProblem(RunSessionProblemCode.LOCATION, "定位数据接收失败，请检查后显式恢复")) }
            }
        }
        tracker.start(current!!.request.samplingSeconds)
        if (tracker.state.value.phase !in listOf(LocationPhase.Starting, LocationPhase.Tracking)) {
            val failure = RunSessionProblem(RunSessionProblemCode.LOCATION, tracker.state.value.message ?: "定位尚未启动，请检查权限与定位服务")
            halt(failure)
            throw SessionIssue(failure)
        }
    }
    private fun stopSampling() {
        accepting = false
        epoch++
        sampleJob?.cancel()
        sampleJob = null
        tracker.stop()
    }

    private suspend fun onSample(generation: Long, sample: RunLocationSample) = mutex.withLock {
        if (closed || !accepting || generation != epoch) return@withLock
        val record = current ?: return@withLock
        try {
            if (sample.timestampMillis < windowStartMillis) return@withLock rejectSample(RunSessionSampleRejection.BEFORE_RECORDING_WINDOW)
            if (sample.timestampMillis > clock()) return@withLock rejectSample(RunSessionSampleRejection.FUTURE_TIME)
            val filtered = RunLocationFilter().evaluate(record.samples.lastOrNull(), sample)
            if (filtered is LocationFilterResult.Rejected) return@withLock rejectSample(RunSessionSampleRejection.FILTER, filtered.reason)
            val previous = record.samples.lastOrNull()
            if (previous != null && sample.timestampMillis - previous.timestampMillis < record.request.samplingSeconds * 1000L) return@withLock rejectSample(RunSessionSampleRejection.TOO_FREQUENT)
            ensure(record.samples.size < maximumSamplesPerSession, RunSessionProblemCode.SAMPLE_LIMIT, "本次真实采样点已达上限，请结束并保留记录")
            val accepted = record.copy(samples = record.samples + sample)
            unpersistedSample = sample
            journal.save(accepted)
            current = accepted
            unpersistedSample = null
            problem = null
            publish() // Only durably written samples are advertised as accepted.
            try { syncQueue(accepted, finished = false, confirm = consentInThisProcess) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { problem = RunSessionProblem(RunSessionProblemCode.QUEUE, classify(error).message) }
            publish()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { halt(classify(error)) }
    }
    private fun rejectSample(reason: RunSessionSampleRejection, filterReason: LocationRejection? = null) {
        rejectedCount++
        lastRejection = reason
        lastFilterRejection = filterReason
        publish()
    }

    private suspend fun ownedQueue(record: StoredRunSession): List<RunQueuedBatch> {
        val entries = queue.snapshot().filter { it.batch.trid == record.trid }.sortedBy { it.batch.index }
        ensure(entries.all { it.batch.payload.userId == record.ownerId }, RunSessionProblemCode.OWNER_MISMATCH, "上传队列与会话用户不一致")
        return entries
    }
    private suspend fun syncQueue(record: StoredRunSession, finished: Boolean, confirm: Boolean) {
        if (record.samples.size >= 2) {
            val track = RunTrackGenerator.fromSamples(record.samples)
            queue.enqueue(RunBatcher.create(record.request.metadata, track, record.request.batchIdPrefix, finished))
        }
        queueView = ownedQueue(record)
        if (confirm) confirmEligible(record)
    }
    private suspend fun confirmEligible(record: StoredRunSession) {
        val pending = queueView.filter { it.status != RunBatchStatus.ACKNOWLEDGED }
        if (pending.isNotEmpty() && pending.all { it.status == RunBatchStatus.STAGED || it.status == RunBatchStatus.READY }) {
            queue.confirmUpload(record.trid, record.ownerId, pending.map { it.batch.batchId })
            queueView = ownedQueue(record)
        }
    }

    private fun halt(failure: RunSessionProblem) {
        consentInThisProcess = false
        try { stopSampling() } catch (_: Exception) { /* Gate closure precedes native stop. */ }
        if (phase != RunSessionPhase.OWNER_MISMATCH && phase !in listOf(RunSessionPhase.FINISHED, RunSessionPhase.CANCELLED, RunSessionPhase.CLOSED)) {
            phase = if (current?.finishRequested == true) RunSessionPhase.FINISHING else RunSessionPhase.PAUSED
        }
        problem = failure
        val record = current
        if (record?.phase == StoredRunSessionPhase.RECORDING) {
            try {
                val paused = record.copy(phase = StoredRunSessionPhase.PAUSED)
                journal.save(paused)
                current = paused
            } catch (_: Exception) { problem = RunSessionProblem(RunSessionProblemCode.STORAGE, "跑步会话保存失败；先前持久化的轨迹仍保留") }
        }
        publish()
    }
    private fun classify(error: Exception): RunSessionProblem = when (error) {
        is SessionIssue -> error.problem
        is RunSessionStorageException -> RunSessionProblem(RunSessionProblemCode.STORAGE, error.message ?: "会话存储失败")
        is RunQueueException -> RunSessionProblem(RunSessionProblemCode.QUEUE, error.message ?: "上传队列存储失败")
        is IllegalArgumentException -> RunSessionProblem(RunSessionProblemCode.INVALID_INPUT, "本地会话或队列内容校验未通过，请保留记录并检查")
        else -> RunSessionProblem(RunSessionProblemCode.LOCATION, "定位操作失败，请检查后显式恢复")
    }

    private fun publish() {
        if (phase == RunSessionPhase.OWNER_MISMATCH || (current != null && current!!.ownerId != visibleOwnerId)) {
            mutableState.value = RunSessionState(phase = if (closed) RunSessionPhase.CLOSED else RunSessionPhase.OWNER_MISMATCH, nativeLocation = nativeState, problem = problem)
            return
        }
        val record = current
        if (record == null) {
            mutableState.value = RunSessionState(phase = phase, nativeLocation = nativeState, problem = problem)
            return
        }
        val samples = record.samples
        val distance = RunGeo.pathLengthMeters(samples.map { it.point })
        val elapsed = if (samples.size < 2) 0L else samples.last().timestampMillis - samples.first().timestampMillis
        val progression = RunCheckpointProgress(record.request.checkpoints)
        samples.forEach { progression.observe(it.point) }
        val pending = queueView.filter { it.status != RunBatchStatus.ACKNOWLEDGED }
        val uploadPhase = when {
            queueView.any { it.status == RunBatchStatus.UNCERTAIN } -> RunSessionUploadPhase.UNCERTAIN
            queueView.any { it.status == RunBatchStatus.IN_FLIGHT } -> RunSessionUploadPhase.IN_FLIGHT
            queueView.any { it.status == RunBatchStatus.FAILED } -> RunSessionUploadPhase.FAILED
            queueView.any { it.status == RunBatchStatus.STAGED } -> RunSessionUploadPhase.STAGED
            queueView.any { it.status == RunBatchStatus.READY } -> RunSessionUploadPhase.CONFIRMED
            queueView.isNotEmpty() -> RunSessionUploadPhase.ACKNOWLEDGED
            record.finishRequested && samples.size < 2 -> RunSessionUploadPhase.INSUFFICIENT_SAMPLES
            else -> RunSessionUploadPhase.NONE
        }
        mutableState.value = RunSessionState(
            phase = phase, metadata = record.request.metadata, acceptedSampleCount = samples.size, acceptedSamples = samples.toList(),
            rejectedSampleCount = rejectedCount, distanceMeters = distance, elapsedMillis = elapsed,
            averageSpeedMetersPerSecond = if (elapsed == 0L) 0.0 else distance / (elapsed / 1000.0),
            nextCheckpointIndex = progression.nextIndex, distanceToNextCheckpointMeters = samples.lastOrNull()?.let { progression.distanceToNext(it.point) },
            startedAtMillis = record.startedAtMillis, finishedAtMillis = record.finishedAtMillis, lastAcceptedSample = samples.lastOrNull(),
            lastSampleRejection = lastRejection, lastFilterRejection = lastFilterRejection,
            uploadPhase = uploadPhase, pendingBatchIds = pending.map { it.batch.batchId },
            queuedSampleCount = queueView.sumOf { RunBatcher.json.decodeFromString<List<RunCoordinate>>(it.batch.payload.coordinate).size },
            uploadConfirmationRequired = pending.any { it.status == RunBatchStatus.STAGED }, finishRequested = record.finishRequested,
            hasUnpersistedSample = unpersistedSample != null, nativeLocation = nativeState, problem = problem,
        )
    }

    companion object {
        const val STORAGE_KEY = RunSessionJournal.STORAGE_KEY
        const val MAX_SAMPLES_PER_SESSION = RunSessionJournal.MAX_SAMPLES_PER_SESSION
        const val MAX_TOTAL_SAMPLES = RunSessionJournal.MAX_TOTAL_SAMPLES
        const val MAX_SESSIONS = RunSessionJournal.MAX_SESSIONS
    }
}
