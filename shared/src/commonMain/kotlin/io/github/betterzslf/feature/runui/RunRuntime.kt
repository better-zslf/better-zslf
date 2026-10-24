package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.data.RunRepository
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.platform.NativeLocationTracker
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A transport factory is opt-in. Merely constructing this runtime never sends a request. */
enum class RunUploadUiPhase { IDLE, SENDING, ACKNOWLEDGED, FAILED, UNCERTAIN, UNAVAILABLE, NOT_DUE }
data class RunCommandResult(val accepted: Boolean, val message: String? = null)
data class RunRuntimeState(
    val ownerId: String? = null,
    val session: RunSessionState = RunSessionState(),
    val taskSelection: RunTaskSelection? = null,
    val localSessions: List<RunSessionSummary> = emptyList(),
    val queue: List<RunQueuedBatch> = emptyList(),
    val busy: Boolean = false,
    val upload: RunUploadUiPhase = RunUploadUiPhase.IDLE,
    val message: String? = null,
    val requiresRestore: Boolean = false,
    val transportAvailable: Boolean = false,
    val closed: Boolean = false,
)

/**
 * One app-level instance, not remembered/disposed per page. Owner/token changes stop capture and
 * cancel old sends; raw history remains local. A prior owner's unfinished session is not silently
 * cancelled to make room for another account. Switch back to that owner to handle it explicitly.
 *
 * Supply a factory such as { RunRepositoryTransport(it) } only when enabling explicit final uploads.
 * The factory receives an ATOMICALLY BOUND repository, never the global mutable-session repository.
 */
class RunRuntime(
    scope: CoroutineScope,
    storage: KeyValueStore,
    private val repository: RunRepository,
    tracker: NativeLocationTracker,
    private val nowMillis: () -> Long,
    private val sessions: StateFlow<Session?>,
    private val transportFactory: ((RunRepository) -> RunUploadTransport)? = null,
) {
    private class Identity(val owner: String, private val token: String) {
        fun matches(session: Session?): Boolean = session != null && session.userId == owner && session.token == token
        fun same(other: Identity?): Boolean = other != null && owner == other.owner && token == other.token
        override fun toString(): String = "RunIdentity(bound=true)"
        companion object {
            fun of(session: Session?): Identity? = session?.takeIf { !it.userId.isNullOrBlank() && it.token.isNotBlank() }?.let { Identity(it.userId!!, it.token) }
        }
    }
    private val lifecycle = SupervisorJob(scope.coroutineContext[Job])
    private val appScope = CoroutineScope(scope.coroutineContext + lifecycle)
    private val control = Mutex()
    private val uploadGate = Mutex()
    private val queue = RunUploadQueue(storage)
    private val links = RunTaskLinks(storage)
    private var contextTrid: String? = null
    private val coordinator = RunSessionCoordinator(appScope, tracker, storage, queue, nowMillis)
    private var identity = Identity.of(sessions.value)
    private var generation = 0L
    private var visibleGeneration: Long? = null
    private var uploadJob: Deferred<RunCommandResult>? = null
    private var closed = false
    private val closeCompleted = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(RunRuntimeState(ownerId = identity?.owner, transportAvailable = transportFactory != null))
    val state: StateFlow<RunRuntimeState> = mutableState.asStateFlow()

    init {
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { closeInternal(fromLifecycle = true) } }
        }
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            sessions.collect { session ->
                control.withLock {
                    val next = Identity.of(session)
                    if (!closed && !(identity?.same(next) ?: (next == null))) fence(next)
                }
            }
        }
        appScope.launch(start = CoroutineStart.UNDISPATCHED) {
            coordinator.state.collect { local ->
                control.withLock {
                    val current = identity
                    if (!closed && visibleGeneration == generation && current?.matches(sessions.value) == true && local.metadata?.userId == current.owner) {
                        mutableState.value = mutableState.value.copy(session = visibleSession(local))
                    }
                }
            }
        }
    }

    suspend fun start(detail: RunTaskDetail, uploadConfirmed: Boolean, samplingSeconds: Int): RunCommandResult = command { active ->
        if (uploadJob?.isActive == true) return@command RunCommandResult(false, "请先等待或停止当前已确认记录的上传")
        if (!detail.canStart) return@command RunCommandResult(false, "服务端任务、计划或有序点字段不完整，当前只展示任务")
        if (samplingSeconds !in 10..60) return@command RunCommandResult(false, "采样设置须在 10 至 60 秒之间，请先检查设置")
        if (!uploadConfirmed) return@command RunCommandResult(false, "请先勾选本次真实轨迹上传确认")
        val selection = RunTaskSelection.from(detail.task) ?: return@command RunCommandResult(false, "缺少真实 errandId / errandScheduleId")
        val capturedSession = sessions.value
        if (!active.matches(capturedSession)) return@command RunCommandResult(false, "登录状态已变化，请重新选择任务")
        val user = capturedSession!!.userInfo
        val account = (user["account"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (account.isNullOrBlank()) return@command RunCommandResult(false, "登录资料缺少 account；不会用 userId 或占位值代替会话账号")
        val history = coordinator.sessions(active.owner)
        if (history.any { it.phase in listOf(RunSessionPhase.RECORDING, RunSessionPhase.PAUSED, RunSessionPhase.FINISHING, RunSessionPhase.RECOVERED) }) {
            return@command RunCommandResult(false, "已有未结束的本机记录，请先明确恢复处理；重复点击不会再生成会话标识")
        }
        val link = links.prepare(active.owner, account, selection, nowMillis(), history.map { it.metadata.trid }.toSet())
        contextTrid = link.trid
        mutableState.value = mutableState.value.copy(taskSelection = selection, upload = RunUploadUiPhase.IDLE)
        val metadata = RunMetadata(link.trid, selection.errandScheduleId, active.owner,
            (user["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            (user["avatarUrl"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        // The server fields stay GCJ02 in detail/raw; only this checkpoint copy is native WGS84.
        applyAction(coordinator.start(RunSessionStart(metadata, detail.inNativeWgs84().checkpoints, samplingSeconds, metadata.trid), active.owner, true))
    }

    /** Reading history is explicit and never starts a tracker or worker. */
    suspend fun loadLocalSessions(): RunCommandResult = command { active ->
        val rows = coordinator.sessions(active.owner)
        mutableState.value = mutableState.value.copy(localSessions = rows)
        RunCommandResult(true)
    }

    suspend fun restore(trid: String? = null): RunCommandResult = command { active ->
        if (uploadJob?.isActive == true) RunCommandResult(false, "请先等待或停止当前上传，再恢复查看另一条记录")
        else applyAction(coordinator.restore(active.owner, trid))
    }
    suspend fun pause(): RunCommandResult = command { active -> applyAction(coordinator.pause(active.owner)) }
    suspend fun resume(uploadConfirmed: Boolean): RunCommandResult = command { active -> applyAction(coordinator.resume(active.owner, uploadConfirmed)) }
    suspend fun finish(userConfirmed: Boolean): RunCommandResult = command { active ->
        if (!userConfirmed) RunCommandResult(false, "请先确认结束本次真实记录")
        else applyAction(coordinator.finish(active.owner, uploadConfirmed = false))
    }
    suspend fun cancel(userConfirmed: Boolean): RunCommandResult = command { active ->
        if (!userConfirmed) RunCommandResult(false, "请先确认取消记录并暂停未发送的批次")
        else applyAction(coordinator.cancel(active.owner))
    }
    suspend fun retryPersistence(): RunCommandResult = command { active -> applyAction(coordinator.retryPersistence(active.owner)) }
    suspend fun refreshQueue(): RunCommandResult = command { active -> applyAction(coordinator.refreshUploadState(active.owner)) }

    /**
     * A final, exact-preview confirmation AND an explicit invocation are both required.
     * No timer, location callback, restore, mode switch, or page composition invokes this method.
     */
    suspend fun upload(expectedBatchIds: List<String>, userConfirmed: Boolean): RunCommandResult {
        if (!uploadGate.tryLock()) return RunCommandResult(false, "本次上传已在处理中")
        var delegated = false
        try {
            val prepared = control.withLock {
                if (closed) return@withLock null
                val active = validIdentity() ?: return@withLock null
                val local = coordinator.state.value
                if (!userConfirmed) {
                    message("请先核对批次并确认最终上传")
                    return@withLock null
                }
                if (visibleGeneration != generation || local.metadata?.userId != active.owner || local.phase != RunSessionPhase.FINISHED) {
                    message("请先结束当前账号的真实记录；恢复记录需要明确选择后再上传")
                    return@withLock null
                }
                if (local.acceptedSampleCount < 2) {
                    message("零点或单点记录仅保留在本机，没有可上传的完整轨迹")
                    return@withLock null
                }
                val factory = transportFactory
                if (factory == null) {
                    mutableState.value = mutableState.value.copy(upload = RunUploadUiPhase.UNAVAILABLE, message = "当前运行环境未启用上传传输，真实轨迹仍保留在本机")
                    return@withLock null
                }
                val pending = queue.snapshot().filter { it.batch.trid == local.metadata.trid && it.status != RunBatchStatus.ACKNOWLEDGED }.sortedBy { it.batch.index }
                if (pending.isEmpty() || pending.map { it.batch.batchId } != expectedBatchIds) {
                    message("待上传批次已变化，请刷新并重新预览")
                    return@withLock null
                }
                if (pending.any { it.status in listOf(RunBatchStatus.UNCERTAIN, RunBatchStatus.IN_FLIGHT, RunBatchStatus.FAILED) }) {
                    message("请先核对结果不明、发送中或已失败的批次；此按钮不会自动重试")
                    return@withLock null
                }
                // One repository call captures the owner+token pair. Its request layer rechecks the
                // binding and always uses the captured token, including across a concurrent login.
                val bound = repository.boundToCurrentSession()
                if (bound == null || bound.boundOwnerId != active.owner || !active.matches(sessions.value)) {
                    message("登录状态已变化，请重新选择记录并确认")
                    return@withLock null
                }
                queue.confirmUpload(local.metadata.trid, active.owner, expectedBatchIds)
                val transport = factory(bound)
                val startGeneration = generation
                val trid = local.metadata.trid
                mutableState.value = mutableState.value.copy(upload = RunUploadUiPhase.SENDING, message = "正在发送已确认批次；接收结果与最终成绩分开显示")
                appScope.async(start = CoroutineStart.LAZY) { performUpload(startGeneration, active, trid, transport) }.also { job ->
                    uploadJob = job
                    // Also runs if an account switch cancels the lazy job before its body starts.
                    job.invokeOnCompletion { uploadGate.unlock() }
                }
            }
            if (prepared == null) return RunCommandResult(false, state.value.message ?: "请登录后重新确认上传")
            delegated = true
            prepared.start()
            return prepared.await()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            control.withLock { message("上传准备失败，原轨迹和队列已保留，请检查后刷新") }
            return RunCommandResult(false, "上传准备失败，原轨迹和队列已保留")
        } finally { if (!delegated) uploadGate.unlock() }
    }

    suspend fun stopUpload(): RunCommandResult {
        val stopping = control.withLock { Triple(uploadJob, generation, identity) }
        val job = stopping.first ?: return RunCommandResult(false, "当前没有正在发送的批次")
        job.cancel()
        withContext(NonCancellable) {
            job.join()
            control.withLock {
                val active = stopping.third
                if (!closed && generation == stopping.second && active?.matches(sessions.value) == true) {
                    coordinator.refreshUploadState(active.owner)
                    val local = coordinator.state.value
                    val entries = queue.snapshot().filter { it.batch.trid == local.metadata?.trid && it.batch.payload.userId == active.owner }.sortedBy { it.batch.index }
                    mutableState.value = mutableState.value.copy(session = visibleSession(local), queue = entries,
                        upload = if (entries.any { it.status == RunBatchStatus.UNCERTAIN || it.status == RunBatchStatus.IN_FLIGHT }) RunUploadUiPhase.UNCERTAIN else RunUploadUiPhase.IDLE,
                        message = "已停止后续发送；已经发出的批次仍需核对接收结果")
                }
                if (uploadJob === job) uploadJob = null
            }
        }
        return RunCommandResult(true, "已停止后续发送；已经发出的批次仍需核对接收结果")
    }

    fun isCurrentSession(): Boolean = !closed && identity?.matches(sessions.value) == true
    fun clearMessage() { mutableState.value = mutableState.value.copy(message = null) }

    suspend fun close() = closeInternal(fromLifecycle = false)

    /** Every external caller waits for the SAME cleanup and every owned job before storage clear. */
    private suspend fun closeInternal(fromLifecycle: Boolean) {
        val claim: Pair<Boolean, Deferred<RunCommandResult>?> = control.withLock {
            if (closed) false to null else {
                closed = true
                visibleGeneration = null
                true to uploadJob?.also { it.cancel() }
            }
        }
        if (!claim.first) {
            // The lifecycle's finally is itself one of the jobs an external closer must join.
            if (!fromLifecycle) withContext(NonCancellable) { closeCompleted.await(); lifecycle.join() }
            return
        }
        withContext(NonCancellable) {
            try {
                claim.second?.join()
                coordinator.close() // A Rejected persistence result still closes its capture gate/native tracker.
            } finally {
                lifecycle.cancel()
                if (!fromLifecycle) lifecycle.join()
                control.withLock { mutableState.value = RunRuntimeState(ownerId = identity?.owner, closed = true, transportAvailable = transportFactory != null) }
                closeCompleted.complete(Unit)
            }
        }
    }

    private suspend fun performUpload(startGeneration: Long, active: Identity, trid: String, transport: RunUploadTransport): RunCommandResult {
        try {
            val result = RunUploadWorker(queue, transport).run(trid, active.owner, nowMillis)
            control.withLock {
                if (!closed && generation == startGeneration && active.matches(sessions.value)) {
                    coordinator.refreshUploadState(active.owner)
                    val entries = queue.snapshot().filter { it.batch.trid == trid && it.batch.payload.userId == active.owner }.sortedBy { it.batch.index }
                    val phase = when (result.stop) {
                        RunWorkerStop.COMPLETE, RunWorkerStop.DRAINED -> RunUploadUiPhase.ACKNOWLEDGED
                        RunWorkerStop.UNCERTAIN -> RunUploadUiPhase.UNCERTAIN
                        RunWorkerStop.NOT_DUE -> RunUploadUiPhase.NOT_DUE
                        else -> RunUploadUiPhase.FAILED
                    }
                    mutableState.value = mutableState.value.copy(upload = phase, session = visibleSession(coordinator.state.value), queue = entries, message = result.message)
                }
            }
            return RunCommandResult(result.stop == RunWorkerStop.COMPLETE, result.message)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                val entries = settleInterruptedWorker(trid, active.owner)
                control.withLock {
                    if (!closed && generation == startGeneration && active.matches(sessions.value)) {
                        coordinator.refreshUploadState(active.owner)
                        mutableState.value = mutableState.value.copy(upload = if (entries == null || entries.any { it.status == RunBatchStatus.UNCERTAIN }) RunUploadUiPhase.UNCERTAIN else RunUploadUiPhase.IDLE,
                            session = visibleSession(coordinator.state.value), queue = entries ?: mutableState.value.queue,
                            message = "发送已中断，已发请求的接收结果待核对；不会自动再次发送")
                    }
                }
            }
            throw cancelled
        } catch (_: Exception) {
            withContext(NonCancellable) {
                val entries = settleInterruptedWorker(trid, active.owner)
                control.withLock {
                    if (!closed && generation == startGeneration && active.matches(sessions.value)) {
                        coordinator.refreshUploadState(active.owner)
                        mutableState.value = mutableState.value.copy(upload = if (entries == null || entries.any { it.status == RunBatchStatus.UNCERTAIN }) RunUploadUiPhase.UNCERTAIN else RunUploadUiPhase.FAILED,
                            session = visibleSession(coordinator.state.value), queue = entries ?: mutableState.value.queue,
                            message = "上传过程或本地确认保存失败；原轨迹保留，请刷新队列核对")
                    }
                }
            }
            return RunCommandResult(false, "上传过程或本地确认保存失败，请核对队列")
        } finally {
            withContext(NonCancellable) { control.withLock { uploadJob = null } }
        }
    }

    /** The owned worker has unwound: any orphaned claim is unknown, never a licence to resend. */
    private suspend fun settleInterruptedWorker(trid: String, owner: String): List<RunQueuedBatch>? = try {
        val run = queue.snapshot().filter { it.batch.trid == trid && it.batch.payload.userId == owner }
        run.filter { it.status == RunBatchStatus.IN_FLIGHT }.forEach {
            queue.failed(it.batch.batchId, "发送或本地应答保存中断，接收结果待核对", uncertain = true, allowFailover = false)
        }
        queue.snapshot().filter { it.batch.trid == trid && it.batch.payload.userId == owner }.sortedBy { it.batch.index }
    } catch (_: Exception) { null }

    private fun visibleSession(local: RunSessionState): RunSessionState =
        if (contextTrid != null && contextTrid == local.metadata?.trid) local
        else local.copy(distanceToNextCheckpointMeters = null, nextCheckpointIndex = 0)

    private suspend fun command(block: suspend (Identity) -> RunCommandResult): RunCommandResult = control.withLock {
        if (closed) return@withLock RunCommandResult(false, "校园跑运行环境已关闭")
        val active = validIdentity() ?: return@withLock RunCommandResult(false, "请先登录，再选择当前账号的本地记录")
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        try {
            visibleGeneration = generation
            val result = block(active)
            if (!active.matches(sessions.value)) {
                fence(Identity.of(sessions.value))
                return@withLock RunCommandResult(false, "账号或登录状态已变化，已停止旧记录")
            }
            val local = coordinator.state.value
            val owned = local.metadata?.userId == active.owner
            val link = local.metadata?.takeIf { owned }?.let { links.lookup(active.owner, it.trid) }
            contextTrid = link?.trid
            val entries = if (owned) queue.snapshot().filter { it.batch.trid == local.metadata.trid && it.batch.payload.userId == active.owner }.sortedBy { it.batch.index } else emptyList()
            mutableState.value = mutableState.value.copy(session = if (owned) visibleSession(local) else RunSessionState(), taskSelection = link?.selection, queue = entries,
                busy = false, message = result.message ?: local.problem?.message, requiresRestore = false)
            result
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(busy = false)
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(busy = false, message = "本地会话处理失败，已保存的真实轨迹仍保留")
            RunCommandResult(false, "本地会话处理失败，请刷新检查")
        }
    }

    private suspend fun validIdentity(): Identity? {
        val active = identity
        if (active == null || !active.matches(sessions.value)) {
            fence(Identity.of(sessions.value))
            return null
        }
        return active
    }

    /** Called under control. No worker is started as part of an identity change. */
    private suspend fun fence(next: Identity?) {
        val previous = identity
        generation++
        visibleGeneration = null
        contextTrid = null
        uploadJob?.cancel()
        identity = next
        mutableState.value = RunRuntimeState(ownerId = next?.owner, requiresRestore = true,
            transportAvailable = transportFactory != null, message = "登录状态已变化，旧采样与上传已停止；请明确恢复当前账号记录")
        val local = coordinator.state.value
        if (previous != null && local.metadata?.userId == previous.owner) {
            try {
                if (local.phase in listOf(RunSessionPhase.RECORDING, RunSessionPhase.PAUSED, RunSessionPhase.RECOVERED)) coordinator.pause(previous.owner)
                val entries = queue.snapshot().filter { it.batch.trid == local.metadata.trid && it.batch.payload.userId == previous.owner }
                if (entries.isNotEmpty()) queue.holdRun(local.metadata.trid, previous.owner)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(message = "登录状态已变化；旧记录仍保留，停止或保存结果请回原账号核对")
            }
        }
    }

    private fun applyAction(action: RunSessionAction): RunCommandResult = when (action) {
        is RunSessionAction.Applied -> RunCommandResult(true, action.state.problem?.message)
        is RunSessionAction.Rejected -> RunCommandResult(false, action.problem.message)
    }
    private fun message(text: String) { mutableState.value = mutableState.value.copy(message = text) }
}
