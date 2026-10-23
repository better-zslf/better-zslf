package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.text.AppText
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
        if (uploadJob?.isActive == true) return@command RunCommandResult(false, AppText.Run.WAIT_OR_STOP_CONFIRMED_RECORD_UPLOAD_FIRST)
        if (!detail.canStart) return@command RunCommandResult(false, AppText.Run.SERVER_TASK_SCHEDULE_OR_ORDERED_POINTS_INCOMPLETE_DISPLAY_ONLY)
        if (samplingSeconds !in 10..60) return@command RunCommandResult(false, AppText.Run.SAMPLING_SETTING_OUT_OF_RANGE_CHECK_SETTINGS)
        if (!uploadConfirmed) return@command RunCommandResult(false, AppText.Run.CURRENT_REAL_TRACK_UPLOAD_ACKNOWLEDGEMENT_REQUIRED)
        val selection = RunTaskSelection.from(detail.task) ?: return@command RunCommandResult(false, AppText.Run.REAL_TASK_OR_SCHEDULE_ID_MISSING)
        val capturedSession = sessions.value
        if (!active.matches(capturedSession)) return@command RunCommandResult(false, AppText.Run.LOGIN_CHANGED_RESELECT_TASK)
        val user = capturedSession!!.userInfo
        val account = (user["account"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (account.isNullOrBlank()) return@command RunCommandResult(false, AppText.Run.LOGIN_ACCOUNT_MISSING_NO_SESSION_ACCOUNT_SUBSTITUTION)
        val history = coordinator.sessions(active.owner)
        if (history.any { it.phase in listOf(RunSessionPhase.RECORDING, RunSessionPhase.PAUSED, RunSessionPhase.FINISHING, RunSessionPhase.RECOVERED) }) {
            return@command RunCommandResult(false, AppText.Run.UNFINISHED_LOCAL_RECORD_REQUIRES_EXPLICIT_RECOVERY_NO_NEW_ID)
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
        if (uploadJob?.isActive == true) RunCommandResult(false, AppText.Run.WAIT_OR_STOP_UPLOAD_BEFORE_RESTORING_ANOTHER_RECORD)
        else applyAction(coordinator.restore(active.owner, trid))
    }
    suspend fun pause(): RunCommandResult = command { active -> applyAction(coordinator.pause(active.owner)) }
    suspend fun resume(uploadConfirmed: Boolean): RunCommandResult = command { active -> applyAction(coordinator.resume(active.owner, uploadConfirmed)) }
    suspend fun finish(userConfirmed: Boolean): RunCommandResult = command { active ->
        if (!userConfirmed) RunCommandResult(false, AppText.Run.CONFIRM_END_CURRENT_REAL_RECORD_FIRST)
        else applyAction(coordinator.finish(active.owner, uploadConfirmed = false))
    }
    suspend fun cancel(userConfirmed: Boolean): RunCommandResult = command { active ->
        if (!userConfirmed) RunCommandResult(false, AppText.Run.CONFIRM_CANCEL_RECORD_AND_PAUSE_UNSENT_BATCHES_FIRST)
        else applyAction(coordinator.cancel(active.owner))
    }
    suspend fun retryPersistence(): RunCommandResult = command { active -> applyAction(coordinator.retryPersistence(active.owner)) }
    suspend fun refreshQueue(): RunCommandResult = command { active -> applyAction(coordinator.refreshUploadState(active.owner)) }

    /**
     * A final, exact-preview confirmation AND an explicit invocation are both required.
     * No timer, location callback, restore, mode switch, or page composition invokes this method.
     */
    suspend fun upload(expectedBatchIds: List<String>, userConfirmed: Boolean): RunCommandResult {
        if (!uploadGate.tryLock()) return RunCommandResult(false, AppText.Run.UPLOAD_ALREADY_IN_PROGRESS)
        var delegated = false
        try {
            val prepared = control.withLock {
                if (closed) return@withLock null
                val active = validIdentity() ?: return@withLock null
                val local = coordinator.state.value
                if (!userConfirmed) {
                    message(AppText.Run.REVIEW_BATCHES_AND_CONFIRM_FINAL_UPLOAD_FIRST)
                    return@withLock null
                }
                if (visibleGeneration != generation || local.metadata?.userId != active.owner || local.phase != RunSessionPhase.FINISHED) {
                    message(AppText.Run.END_CURRENT_ACCOUNT_RECORD_AND_EXPLICITLY_SELECT_RECOVERY_FIRST)
                    return@withLock null
                }
                if (local.acceptedSampleCount < 2) {
                    message(AppText.Run.ZERO_OR_SINGLE_POINT_RECORD_LOCAL_ONLY_NO_COMPLETE_TRACK_UPLOAD)
                    return@withLock null
                }
                val factory = transportFactory
                if (factory == null) {
                    mutableState.value = mutableState.value.copy(upload = RunUploadUiPhase.UNAVAILABLE, message = AppText.Run.UPLOAD_TRANSPORT_DISABLED_REAL_TRACK_RETAINED_LOCALLY)
                    return@withLock null
                }
                val pending = queue.snapshot().filter { it.batch.trid == local.metadata.trid && it.status != RunBatchStatus.ACKNOWLEDGED }.sortedBy { it.batch.index }
                if (pending.isEmpty() || pending.map { it.batch.batchId } != expectedBatchIds) {
                    message(AppText.Run.PENDING_UPLOAD_BATCHES_CHANGED_REFRESH_AND_PREVIEW_AGAIN)
                    return@withLock null
                }
                if (pending.any { it.status in listOf(RunBatchStatus.UNCERTAIN, RunBatchStatus.IN_FLIGHT, RunBatchStatus.FAILED) }) {
                    message(AppText.Run.REVIEW_UNCERTAIN_IN_FLIGHT_OR_FAILED_BATCHES_NO_AUTO_RETRY)
                    return@withLock null
                }
                // One repository call captures the owner+token pair. Its request layer rechecks the
                // binding and always uses the captured token, including across a concurrent login.
                val bound = repository.boundToCurrentSession()
                if (bound == null || bound.boundOwnerId != active.owner || !active.matches(sessions.value)) {
                    message(AppText.Run.LOGIN_CHANGED_RESELECT_RECORD_AND_CONFIRM)
                    return@withLock null
                }
                queue.confirmUpload(local.metadata.trid, active.owner, expectedBatchIds)
                val transport = factory(bound)
                val startGeneration = generation
                val trid = local.metadata.trid
                mutableState.value = mutableState.value.copy(upload = RunUploadUiPhase.SENDING, message = AppText.Run.SENDING_CONFIRMED_BATCHES_RECEIPT_SEPARATE_FROM_SCORE)
                appScope.async(start = CoroutineStart.LAZY) { performUpload(startGeneration, active, trid, transport) }.also { job ->
                    uploadJob = job
                    // Also runs if an account switch cancels the lazy job before its body starts.
                    job.invokeOnCompletion { uploadGate.unlock() }
                }
            }
            if (prepared == null) return RunCommandResult(false, state.value.message ?: AppText.Run.LOG_IN_AND_RECONFIRM_UPLOAD)
            delegated = true
            prepared.start()
            return prepared.await()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            control.withLock { message(AppText.Run.UPLOAD_PREPARATION_FAILED_TRACK_AND_QUEUE_RETAINED_REFRESH) }
            return RunCommandResult(false, AppText.Run.UPLOAD_PREPARATION_FAILED_TRACK_AND_QUEUE_RETAINED)
        } finally { if (!delegated) uploadGate.unlock() }
    }

    suspend fun stopUpload(): RunCommandResult {
        val stopping = control.withLock { Triple(uploadJob, generation, identity) }
        val job = stopping.first ?: return RunCommandResult(false, AppText.Run.NO_BATCH_CURRENTLY_SENDING)
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
                        message = AppText.Run.FUTURE_SENDS_STOPPED_SENT_BATCH_RECEIPTS_REQUIRE_REVIEW)
                }
                if (uploadJob === job) uploadJob = null
            }
        }
        return RunCommandResult(true, AppText.Run.FUTURE_SENDS_STOPPED_SENT_BATCH_RECEIPTS_REQUIRE_REVIEW)
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
                            message = AppText.Run.SEND_INTERRUPTED_SENT_REQUEST_RECEIPT_UNCONFIRMED_NO_AUTO_RESEND)
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
                            message = AppText.Run.UPLOAD_OR_ACKNOWLEDGEMENT_SAVE_FAILED_TRACK_RETAINED_REVIEW_QUEUE)
                    }
                }
            }
            return RunCommandResult(false, AppText.Run.UPLOAD_OR_ACKNOWLEDGEMENT_SAVE_FAILED_REVIEW_QUEUE)
        } finally {
            withContext(NonCancellable) { control.withLock { uploadJob = null } }
        }
    }

    /** The owned worker has unwound: any orphaned claim is unknown, never a licence to resend. */
    private suspend fun settleInterruptedWorker(trid: String, owner: String): List<RunQueuedBatch>? = try {
        val run = queue.snapshot().filter { it.batch.trid == trid && it.batch.payload.userId == owner }
        run.filter { it.status == RunBatchStatus.IN_FLIGHT }.forEach {
            queue.failed(it.batch.batchId, AppText.Run.SEND_OR_ACKNOWLEDGEMENT_SAVE_INTERRUPTED_RECEIPT_UNCONFIRMED, uncertain = true, allowFailover = false)
        }
        queue.snapshot().filter { it.batch.trid == trid && it.batch.payload.userId == owner }.sortedBy { it.batch.index }
    } catch (_: Exception) { null }

    private fun visibleSession(local: RunSessionState): RunSessionState =
        if (contextTrid != null && contextTrid == local.metadata?.trid) local
        else local.copy(distanceToNextCheckpointMeters = null, nextCheckpointIndex = 0)

    private suspend fun command(block: suspend (Identity) -> RunCommandResult): RunCommandResult = control.withLock {
        if (closed) return@withLock RunCommandResult(false, AppText.Run.RUNTIME_CLOSED)
        val active = validIdentity() ?: return@withLock RunCommandResult(false, AppText.Run.LOG_IN_BEFORE_SELECTING_CURRENT_ACCOUNT_LOCAL_RECORD)
        mutableState.value = mutableState.value.copy(busy = true, message = null)
        try {
            visibleGeneration = generation
            val result = block(active)
            if (!active.matches(sessions.value)) {
                fence(Identity.of(sessions.value))
                return@withLock RunCommandResult(false, AppText.Run.ACCOUNT_OR_LOGIN_CHANGED_OLD_RECORD_STOPPED)
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
            mutableState.value = mutableState.value.copy(busy = false, message = AppText.Run.LOCAL_SESSION_PROCESSING_FAILED_SAVED_TRACK_RETAINED)
            RunCommandResult(false, AppText.Run.LOCAL_SESSION_PROCESSING_FAILED_REFRESH_AND_INSPECT)
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
            transportAvailable = transportFactory != null, message = AppText.Run.LOGIN_CHANGED_SAMPLING_AND_UPLOAD_STOPPED_EXPLICIT_RECOVERY_REQUIRED)
        val local = coordinator.state.value
        if (previous != null && local.metadata?.userId == previous.owner) {
            try {
                if (local.phase in listOf(RunSessionPhase.RECORDING, RunSessionPhase.PAUSED, RunSessionPhase.RECOVERED)) coordinator.pause(previous.owner)
                val entries = queue.snapshot().filter { it.batch.trid == local.metadata.trid && it.batch.payload.userId == previous.owner }
                if (entries.isNotEmpty()) queue.holdRun(local.metadata.trid, previous.owner)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(message = AppText.Run.LOGIN_CHANGED_OLD_RECORD_RETAINED_REVIEW_WITH_ORIGINAL_ACCOUNT)
            }
        }
    }

    private fun applyAction(action: RunSessionAction): RunCommandResult = when (action) {
        is RunSessionAction.Applied -> RunCommandResult(true, action.state.problem?.message)
        is RunSessionAction.Rejected -> RunCommandResult(false, action.problem.message)
    }
    private fun message(text: String) { mutableState.value = mutableState.value.copy(message = text) }
}
