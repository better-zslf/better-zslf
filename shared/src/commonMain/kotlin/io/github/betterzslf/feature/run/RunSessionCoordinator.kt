package io.github.betterzslf.feature.run

import io.github.betterzslf.core.text.AppText
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
                        halt(RunSessionProblem(RunSessionProblemCode.LOCATION, native.message ?: AppText.Run.LOCATION_STOPPED_CHECK_PERMISSION_AND_SERVICE))
                    } else publish()
                }
            }
        }
    }

    suspend fun start(request: RunSessionStart, ownerId: String, uploadConfirmed: Boolean): RunSessionAction = action {
        current?.takeIf { it.phase in listOf(StoredRunSessionPhase.RECORDING, StoredRunSessionPhase.PAUSED, StoredRunSessionPhase.FINISHING) }?.let { checkOwner(it.ownerId, ownerId) }
        checkOwner(request.metadata.userId, ownerId)
        ensure(uploadConfirmed, RunSessionProblemCode.CONFIRMATION_REQUIRED, AppText.Run.EXPLICIT_REAL_TRACK_UPLOAD_ACKNOWLEDGEMENT_REQUIRED)
        val document = journal.load()
        document.activeTrid?.let { activeId -> document.sessions.firstOrNull { it.trid == activeId }?.let { checkOwner(it.ownerId, ownerId) } }
        ensure(document.activeTrid == null, RunSessionProblemCode.ACTIVE_SESSION_EXISTS, AppText.Run.UNFINISHED_LOCAL_SESSION_REQUIRES_RECOVERY_FIRST)
        ensure(document.sessions.none { it.trid == request.metadata.trid || it.request.batchIdPrefix == request.batchIdPrefix }, RunSessionProblemCode.INVALID_INPUT, AppText.Run.SESSION_OR_BATCH_ID_ALREADY_USED_NEW_STABLE_ID_REQUIRED)
        ensure(document.sessions.size < MAX_SESSIONS && document.sessions.sumOf { it.samples.size } < MAX_TOTAL_SAMPLES, RunSessionProblemCode.SAMPLE_LIMIT, AppText.Run.LOCAL_SESSION_LIMIT_REACHED_MANAGE_SAVED_RECORDS)
        val existing = queue.snapshot()
        ensure(existing.none { it.batch.trid == request.metadata.trid || it.batch.batchId.startsWith(request.batchIdPrefix + "-") }, RunSessionProblemCode.INVALID_INPUT, AppText.Run.TASK_OR_BATCH_ID_ALREADY_USED_IN_UPLOAD_QUEUE)
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
        ensure(record != null, RunSessionProblemCode.NO_SESSION, AppText.Run.NO_RECOVERABLE_LOCAL_SESSION)
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
        ensure(!record.finishRequested && record.phase !in listOf(StoredRunSessionPhase.FINISHED, StoredRunSessionPhase.CANCELLED), RunSessionProblemCode.INVALID_STATE, AppText.Run.SESSION_ALREADY_ENTERED_FINISH_FLOW)
        stopSampling()
        consentInThisProcess = false
        phase = RunSessionPhase.PAUSED
        val paused = record.copy(phase = StoredRunSessionPhase.PAUSED)
        journal.save(paused)
        current = paused
    }

    suspend fun resume(ownerId: String, uploadConfirmed: Boolean): RunSessionAction = action {
        val record = selected(ownerId)
        ensure(!accepting && !record.finishRequested && record.phase !in listOf(StoredRunSessionPhase.FINISHED, StoredRunSessionPhase.CANCELLED), RunSessionProblemCode.INVALID_STATE, AppText.Run.SESSION_SAMPLING_RESUME_UNSUPPORTED_IN_CURRENT_STATE)
        ensure(unpersistedSample == null, RunSessionProblemCode.STORAGE, AppText.Run.RETRY_PENDING_REAL_SAMPLE_PERSISTENCE_FIRST)
        ensure(record.samples.size < maximumSamplesPerSession && journal.load().sessions.sumOf { it.samples.size } < MAX_TOTAL_SAMPLES, RunSessionProblemCode.SAMPLE_LIMIT, AppText.Run.REAL_SAMPLE_LIMIT_REACHED_END_AND_KEEP_RECORD)
        ensure(uploadConfirmed, RunSessionProblemCode.CONFIRMATION_REQUIRED, AppText.Run.CONFIRM_RECOVERED_REAL_TRACK_UPLOAD_FIRST)
        val now = clock()
        ensure(now >= record.startedAtMillis && (record.samples.lastOrNull()?.timestampMillis ?: now) <= now, RunSessionProblemCode.CLOCK, AppText.Run.CLOCK_BEHIND_SAVED_TRACK_CORRECT_BEFORE_RESUME)
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
        ensure(record.phase != StoredRunSessionPhase.CANCELLED, RunSessionProblemCode.INVALID_STATE, AppText.Run.CANCELLED_SESSION_RETAINED_NO_FINAL_BATCH)
        ensure(unpersistedSample == null, RunSessionProblemCode.STORAGE, AppText.Run.PERSIST_PENDING_REAL_SAMPLE_BEFORE_FINISH)
        if (record.phase == StoredRunSessionPhase.FINISHED) {
            queueView = ownedQueue(record)
            if (uploadConfirmed) confirmEligible(record)
            phase = RunSessionPhase.FINISHED
            return@action
        }
        val allowConfirmation = consentInThisProcess || uploadConfirmed
        val now = clock()
        ensure(now >= record.startedAtMillis && now >= (record.samples.lastOrNull()?.timestampMillis ?: now), RunSessionProblemCode.CLOCK, AppText.Run.CLOCK_BEHIND_SAVED_TRACK_CORRECT_BEFORE_FINISH)
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
        ensure(record.phase != StoredRunSessionPhase.FINISHED && !record.finishRequested, RunSessionProblemCode.INVALID_STATE, AppText.Run.FINISH_REQUESTED_SESSION_REQUIRES_COMPLETION_OR_REVIEW)
        ensure(unpersistedSample == null, RunSessionProblemCode.STORAGE, AppText.Run.PERSIST_PENDING_REAL_SAMPLE_BEFORE_CANCELLATION)
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
        ensure(record.phase != StoredRunSessionPhase.CANCELLED, RunSessionProblemCode.INVALID_STATE, AppText.Run.CANCELLED_SESSION_UPLOAD_CONSENT_WITHDRAWN)
        queueView = ownedQueue(record)
        val pending = queueView.filter { it.status != RunBatchStatus.ACKNOWLEDGED }
        ensure(pending.isNotEmpty() && pending.map { it.batch.batchId } == expectedBatchIds, RunSessionProblemCode.INVALID_INPUT, AppText.Run.UPLOAD_CONTENT_CHANGED_PREVIEW_AGAIN)
        ensure(pending.none { it.status in listOf(RunBatchStatus.IN_FLIGHT, RunBatchStatus.UNCERTAIN, RunBatchStatus.FAILED) }, RunSessionProblemCode.QUEUE, AppText.Run.REVIEW_IN_FLIGHT_FAILED_OR_UNCERTAIN_BATCHES_FIRST)
        queue.confirmUpload(record.trid, ownerId, expectedBatchIds)
        queueView = ownedQueue(record)
    }

    suspend fun refreshUploadState(ownerId: String): RunSessionAction = action { queueView = ownedQueue(selected(ownerId)) }

    /** Retries only the one real sample whose disk write failed; no GPS position/time is synthesized. */
    suspend fun retryPersistence(ownerId: String): RunSessionAction = action {
        val record = selected(ownerId)
        ensure(!record.finishRequested && record.phase !in listOf(StoredRunSessionPhase.FINISHED, StoredRunSessionPhase.CANCELLED), RunSessionProblemCode.INVALID_STATE, AppText.Run.SESSION_SAMPLE_SET_CLOSED)
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
            try { tracker.close() } catch (_: Exception) { if (closeProblem == null) closeProblem = RunSessionProblem(RunSessionProblemCode.LOCATION, AppText.Run.LOCATION_RESOURCE_RELEASE_FAILED_CHECK_PLATFORM) }
            phase = RunSessionPhase.CLOSED
            problem = closeProblem
            publish()
        }
        closeProblem?.let { RunSessionAction.Rejected(it) } ?: RunSessionAction.Applied(state.value)
    }

    private suspend fun action(block: suspend () -> Unit): RunSessionAction = mutex.withLock {
        if (closed) return@withLock RunSessionAction.Rejected(RunSessionProblem(RunSessionProblemCode.CLOSED, AppText.Run.SESSION_COORDINATOR_CLOSED))
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
        nowMillis().also { ensure(it >= 0, RunSessionProblemCode.CLOCK, AppText.Common.INVALID_SYSTEM_TIME) }
    } catch (issue: SessionIssue) { throw issue }
    catch (_: Exception) { throw SessionIssue(RunSessionProblem(RunSessionProblemCode.CLOCK, AppText.Common.SYSTEM_TIME_READ_FAILED)) }

    private fun selected(ownerId: String): StoredRunSession {
        val record = current ?: throw SessionIssue(RunSessionProblem(RunSessionProblemCode.NO_SESSION, AppText.Run.START_OR_RESTORE_LOCAL_SESSION_FIRST))
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
        throw SessionIssue(RunSessionProblem(RunSessionProblemCode.OWNER_MISMATCH, AppText.Run.CURRENT_USER_LOCAL_SESSION_MISMATCH_RECORD_ISOLATED))
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
                mutex.withLock { if (accepting && generation == epoch) halt(RunSessionProblem(RunSessionProblemCode.LOCATION, AppText.Run.LOCATION_STREAM_ENDED_EXPLICIT_RECOVERY_REQUIRED)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutex.withLock { if (accepting && generation == epoch) halt(RunSessionProblem(RunSessionProblemCode.LOCATION, AppText.Run.LOCATION_RECEPTION_FAILED_EXPLICIT_RECOVERY_REQUIRED)) }
            }
        }
        tracker.start(current!!.request.samplingSeconds)
        if (tracker.state.value.phase !in listOf(LocationPhase.Starting, LocationPhase.Tracking)) {
            val failure = RunSessionProblem(RunSessionProblemCode.LOCATION, tracker.state.value.message ?: AppText.Run.LOCATION_NOT_STARTED_CHECK_PERMISSION_AND_SERVICE)
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
            ensure(record.samples.size < maximumSamplesPerSession, RunSessionProblemCode.SAMPLE_LIMIT, AppText.Run.CURRENT_REAL_SAMPLE_LIMIT_REACHED_END_AND_KEEP_RECORD)
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
        ensure(entries.all { it.batch.payload.userId == record.ownerId }, RunSessionProblemCode.OWNER_MISMATCH, AppText.Run.UPLOAD_QUEUE_SESSION_USER_MISMATCH)
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
            } catch (_: Exception) { problem = RunSessionProblem(RunSessionProblemCode.STORAGE, AppText.Run.SESSION_SAVE_FAILED_PREVIOUS_PERSISTED_TRACK_RETAINED) }
        }
        publish()
    }
    private fun classify(error: Exception): RunSessionProblem = when (error) {
        is SessionIssue -> error.problem
        is RunSessionStorageException -> RunSessionProblem(RunSessionProblemCode.STORAGE, error.message ?: AppText.Run.SESSION_STORAGE_FAILED)
        is RunQueueException -> RunSessionProblem(RunSessionProblemCode.QUEUE, error.message ?: AppText.Run.UPLOAD_QUEUE_STORAGE_FAILED)
        is IllegalArgumentException -> RunSessionProblem(RunSessionProblemCode.INVALID_INPUT, AppText.Run.LOCAL_SESSION_OR_QUEUE_VALIDATION_FAILED_KEEP_AND_INSPECT)
        else -> RunSessionProblem(RunSessionProblemCode.LOCATION, AppText.Run.LOCATION_OPERATION_FAILED_EXPLICIT_RECOVERY_REQUIRED)
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
