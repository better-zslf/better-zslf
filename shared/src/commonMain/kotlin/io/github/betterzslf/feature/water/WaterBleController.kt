package io.github.betterzslf.feature.water

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/** One clock origin and failure history across controller recreation/reconnects. */
private object WaterBleRuntime {
    val guard = WaterFailureGuard()
    val guardMutex = Mutex()
    private val origin = TimeSource.Monotonic.markNow()
    fun nowMillis(): Long = origin.elapsedNow().inWholeMilliseconds
}

enum class WaterBlePhase { Disconnected, Connecting, Ready, Authenticating, Active, Stopping, Failed, ManualConfirmationRequired, Closed }

data class WaterBleUiState(
    val scanning: Boolean = false,
    val devices: List<WaterBleDevice> = emptyList(),
    val device: WaterBleDevice? = null,
    val phase: WaterBlePhase = WaterBlePhase.Disconnected,
    val protocolState: WaterSessionState = WaterSessionState.Idle,
    val failure: WaterFailure? = null,
    val retryStatus: WaterRetryStatus = WaterRetryStatus(0, false, null),
    val message: String? = null,
    /** True only after this session's normal B4 and graceful resource release. */
    val normalEndCompleted: Boolean = false,
)

/**
 * Explicit scan/connect/begin/end/disconnect. Merely connecting NEVER writes B0.
 * No retries, reconnect loops, account IDs, or implicit permission requests.
 *
 * Share a long-lived controller at app/session scope. The default guard is also
 * process-wide; it is intentionally memory-only (matching the offline protocol).
 * For deterministic tests inject a shared guard AND a consistent monotonic clock.
 * Call [close] before discarding the controller; parent-scope cancellation also
 * cancels scans/sessions and force-releases the peripheral in NonCancellable.
 */
class WaterBleController(
    parentScope: CoroutineScope,
    private val transport: WaterBleTransport,
    private val epilogueContext: () -> WaterEpilogueContext,
    private val failureGuard: WaterFailureGuard = WaterBleRuntime.guard,
    private val nowMillis: () -> Long = WaterBleRuntime::nowMillis,
    private val connectionTimeoutMillis: Long = 15_000,
    private val ioTimeoutMillis: Long = 15_000,
    private val disconnectTimeoutMillis: Long = 5_000,
    private val operationTimeoutMillis: Long = 15_000,
    private val legacyDelayMillis: Long = 500,
) {
    init {
        require(connectionTimeoutMillis > 0 && ioTimeoutMillis > 0 && disconnectTimeoutMillis > 0)
        require(operationTimeoutMillis > legacyDelayMillis && legacyDelayMillis >= 0)
    }

    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val lifecycle = Mutex()
    private val current = MutableStateFlow<Session?>(null)
    private val closed = MutableStateFlow(false)
    private var scanJob: Job? = null
    private val mutableState = MutableStateFlow(WaterBleUiState())
    val state: StateFlow<WaterBleUiState> = mutableState.asStateFlow()

    suspend fun retryStatus(deviceId: String): WaterRetryStatus = WaterBleRuntime.guardMutex.withLock {
        failureGuard.status(deviceId, nowMillis())
    }

    /** Cancels after [durationMillis]; nothing automatically restarts the scan. */
    suspend fun startScan(durationMillis: Long = 15_000) = lifecycle.withLock {
        checkOpen()
        require(durationMillis > 0)
        check(current.value == null) { "Disconnect the current session before scanning" }
        stopScanLocked()
        mutableState.update { it.copy(scanning = true, devices = emptyList(), message = null) }
        scanJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeoutOrNull(durationMillis) {
                    transport.scan().collect { device ->
                        mutableState.update { old ->
                            old.copy(devices = (old.devices.filterNot { it.id == device.id } + device)
                                .sortedByDescending { it.rssi ?: Int.MIN_VALUE }.take(100))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = "扫描未完成，请检查蓝牙与权限后手动重试。") }
            } finally {
                mutableState.update { it.copy(scanning = false) }
            }
        }.also { it.start() }
    }

    suspend fun stopScan() = lifecycle.withLock { stopScanLocked() }

    /**
     * Connects/subscribes only. A confirmation here authorizes ONE later explicit
     * [begin], not automatic authentication. Blocked devices are gated before GATT.
     */
    suspend fun connect(device: WaterBleDevice, manuallyConfirmedRetry: Boolean = false): Boolean {
        val session = lifecycle.withLock {
            checkOpen()
            require(device.id.isNotBlank() && device.name.length >= 5)
            check(current.value == null) { "A water connection already exists" }
            stopScanLocked()
            mutableState.update { it.copy(normalEndCompleted = false) }
            val retry = retryStatus(device.id)
            if (retry.requiresManualConfirmation && !manuallyConfirmedRetry) {
                mutableState.update { it.copy(device = device, phase = WaterBlePhase.ManualConfirmationRequired,
                    protocolState = WaterSessionState.RetryConfirmationRequired, retryStatus = retry,
                    failure = null, message = WaterFailureGuard.LOCKOUT_NOTICE) }
                return false
            }
            val connection = try {
                transport.createConnection(device)
            } catch (_: Exception) {
                mutableState.update { it.copy(device = device, phase = WaterBlePhase.Failed,
                    message = "设备连接准备失败，请重新扫描并选择设备。", failure = null, retryStatus = retry) }
                return false
            }
            Session(device, connection, manuallyConfirmedRetry).also {
                current.value = it
                mutableState.update { old -> old.copy(device = device, phase = WaterBlePhase.Connecting,
                    protocolState = WaterSessionState.Idle, failure = null, retryStatus = retry, message = null) }
                it.start()
            }
        }
        return try {
            session.ready.await()
        } catch (cancelled: CancellationException) {
            session.worker.cancel()
            withContext(NonCancellable) { session.worker.join() }
            throw cancelled
        }
    }

    /** Only this method can initiate B0. Duplicate begin calls never send B0 again. */
    suspend fun begin(manuallyConfirmedRetry: Boolean = false): Boolean = command { result ->
        Event.Begin(manuallyConfirmedRetry, result)
    }

    suspend fun end(): Boolean = command(Event::End)

    /** Interrupts even an in-flight connect/write; does not count as authentication failure. */
    suspend fun disconnect() = lifecycle.withLock {
        stopScanLocked()
        current.value?.worker?.cancelAndJoin()
    }

    suspend fun close() = lifecycle.withLock {
        if (!closed.compareAndSet(false, true)) return@withLock
        stopScanLocked()
        current.value?.worker?.cancelAndJoin()
        ownerJob.cancel()
        mutableState.update { it.copy(scanning = false, phase = WaterBlePhase.Closed, normalEndCompleted = false) }
    }

    private suspend fun command(factory: (CompletableDeferred<Boolean>) -> Event): Boolean {
        if (closed.value || !ownerJob.isActive) return false
        val session = current.value ?: return false
        val result = CompletableDeferred<Boolean>()
        if (session.events.trySend(factory(result)).isFailure) return false
        return result.await()
    }

    private fun checkOpen() { check(!closed.value && ownerJob.isActive) { "Water controller is closed" } }
    private suspend fun stopScanLocked() { scanJob?.cancelAndJoin(); scanJob = null }

    private sealed interface Event {
        class Begin(val confirmed: Boolean, val result: CompletableDeferred<Boolean>) : Event
        class End(val result: CompletableDeferred<Boolean>) : Event
        class Rx(val bytes: ByteArray) : Event
        class Timer(val timer: WaterTimer) : Event
    }

    private inner class Session(
        val device: WaterBleDevice,
        private val connection: WaterBleConnection,
        private var confirmedAtConnect: Boolean,
    ) {
        val ready = CompletableDeferred<Boolean>()
        val events = Channel<Event>(Channel.UNLIMITED)
        private val sessionJob = SupervisorJob(ownerJob)
        private val sessionScope = CoroutineScope(scope.coroutineContext + sessionJob)
        lateinit var worker: Job
        private val machine = WaterSessionMachine(device.id, device.name, epilogueContext, failureGuard,
            operationTimeoutMillis, legacyDelayMillis)
        private val timers = mutableMapOf<Long, Job>()
        private val shuttingDown = MutableStateFlow(false)
        private val aborted = MutableStateFlow(false)
        private var notificationJob: Job? = null
        private var linkJob: Job? = null
        private var pendingResult: CompletableDeferred<Boolean>? = null
        private var running = true
        private var normalEndRequested = false
        private var finalB4Written = false
        private var normalDisconnectRequested = false
        private var gracefulRelease = false

        fun start() {
            // Enter try/finally before connect() returns: cancellation immediately
            // after creation must still release the not-yet-connected handle.
            worker = sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                worker = currentCoroutineContext().job
                worker.invokeOnCompletion { cause -> completeSession(cause) }
                runSession()
            }
        }

        private fun update(change: (WaterBleUiState) -> WaterBleUiState) {
            if (current.value === this) mutableState.update(change)
        }

        private fun abortTransport() {
            if (!shuttingDown.value) {
                aborted.value = true
                worker.cancel(CancellationException("Water transport ended"))
            }
        }

        private suspend fun runSession() {
            val subscribed = CompletableDeferred<Unit>()
            try {
                notificationJob = sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        connection.notifications { subscribed.complete(Unit) }.collect { bytes ->
                            events.send(Event.Rx(bytes.copyOf()))
                        }
                        abortTransport() // An unexpected end of RXD observation is a link failure.
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        abortTransport()
                    }
                }
                linkJob = sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    var seenConnected = false
                    try {
                        connection.states.collect { link ->
                            if (link == WaterBleLinkState.Connected) seenConnected = true
                            if (link == WaterBleLinkState.Disconnected && seenConnected) abortTransport()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) { abortTransport() }
                }
                withTimeout(connectionTimeoutMillis) {
                    connection.connect()
                    subscribed.await() // onSubscription is AFTER notification setup in Kable 0.42.
                    check(connection.isConnected) { "Connection dropped before RXD was ready" }
                }
                update { it.copy(phase = WaterBlePhase.Ready) }
                ready.complete(true)
                while (running) {
                    when (val event = events.receive()) {
                        is Event.Begin -> {
                            pendingResult = event.result
                            if (machine.state == WaterSessionState.Idle) {
                                val confirmed = event.confirmed || confirmedAtConnect
                                confirmedAtConnect = false
                                val effects = reduce { begin(nowMillis(), confirmed) }
                                refresh()
                                execute(effects)
                                event.result.complete(running)
                            } else event.result.complete(false)
                            pendingResult = null
                        }
                        is Event.End -> {
                            pendingResult = event.result
                            if (machine.state == WaterSessionState.Active) {
                                val effects = reduce { end(nowMillis()) }
                                normalEndRequested = machine.state == WaterSessionState.AwaitingEnd
                                refresh()
                                execute(effects)
                                event.result.complete(running)
                            } else event.result.complete(false)
                            pendingResult = null
                        }
                        is Event.Rx -> if (machine.state != WaterSessionState.Idle) {
                            val effects = reduce { onNotification(event.bytes, nowMillis()) }
                            refresh()
                            execute(effects)
                        }
                        is Event.Timer -> {
                            val effects = reduce { onTimer(event.timer, nowMillis()) }
                            refresh()
                            execute(effects)
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (aborted.value || cancelled is kotlinx.coroutines.TimeoutCancellationException) failTransport()
                    else {
                        reduce { cancel() }
                        refresh()
                        update { it.copy(phase = WaterBlePhase.Disconnected) }
                    }
                }
            } catch (_: Exception) {
                withContext(NonCancellable) { failTransport() }
            } finally {
                withContext(NonCancellable) {
                    shuttingDown.value = true
                    val pendingTimers = timers.values.toList()
                    pendingTimers.forEach { it.cancel() }
                    timers.clear()
                    notificationJob?.cancel()
                    linkJob?.cancel()
                    var graceful = true
                    try {
                        withTimeout(disconnectTimeoutMillis) { connection.disconnect() }
                    } catch (_: Exception) {
                        graceful = false
                        update { old -> old.copy(message = old.message ?: "连接已强制关闭。") }
                    } finally {
                        try { connection.close() } catch (_: Exception) {
                            graceful = false
                            update { old -> old.copy(message = old.message ?: "底层连接资源释放异常。") }
                        }
                    }
                    // Collectors/timers must be gone before reporting normal completion.
                    notificationJob?.join()
                    linkJob?.join()
                    pendingTimers.forEach { it.join() }
                    events.close()
                    gracefulRelease = graceful
                }
                // Completing (rather than cancelling) the supervisor preserves the
                // worker's real cancellation cause for completeSession().
                sessionJob.complete()
            }
        }

        /** Runs after the worker and all resource cleanup have actually finished. */
        private fun completeSession(cause: Throwable?) {
            val normal = cause == null && normalEndRequested && finalB4Written &&
                normalDisconnectRequested && gracefulRelease && !aborted.value
            update { it.copy(normalEndCompleted = normal,
                phase = if (it.phase == WaterBlePhase.Stopping) WaterBlePhase.Disconnected else it.phase) }
            current.compareAndSet(this, null)
            // Clear current before resuming callers that may explicitly reconnect.
            ready.complete(false)
            pendingResult?.complete(false)
            while (true) {
                when (val queued = events.tryReceive().getOrNull() ?: break) {
                    is Event.Begin -> queued.result.complete(false)
                    is Event.End -> queued.result.complete(false)
                    else -> Unit
                }
            }
        }

        private suspend fun reduce(block: WaterSessionMachine.() -> List<WaterEffect>): List<WaterEffect> =
            WaterBleRuntime.guardMutex.withLock { machine.block() }

        private suspend fun refresh() {
            val retry = retryStatus(device.id)
            val phase = when (machine.state) {
                WaterSessionState.Idle -> WaterBlePhase.Ready
                WaterSessionState.Active -> WaterBlePhase.Active
                WaterSessionState.AwaitingEnd -> WaterBlePhase.Stopping
                WaterSessionState.Closed -> if (normalEndRequested) WaterBlePhase.Stopping else WaterBlePhase.Disconnected
                WaterSessionState.Failed -> WaterBlePhase.Failed
                WaterSessionState.RetryConfirmationRequired -> WaterBlePhase.ManualConfirmationRequired
                else -> WaterBlePhase.Authenticating
            }
            update { it.copy(protocolState = machine.state, phase = phase,
                failure = machine.lastFailure, retryStatus = retry,
                message = if (retry.requiresManualConfirmation) WaterFailureGuard.LOCKOUT_NOTICE else machine.lastFailure?.message) }
        }

        private suspend fun failTransport() {
            reduce { onTransportFailure(nowMillis()) }
            refresh()
            update { it.copy(phase = WaterBlePhase.Failed, failure = machine.lastFailure ?: WaterFailure(
                WaterFailureKind.TransportFailure, "连接或写入中断，已关闭；请手动操作。")) }
            running = false
        }

        /** An exception aborts this entire batch; no stale Schedule/Write is executed. */
        private suspend fun execute(effects: List<WaterEffect>) {
            for (effect in effects) {
                when (effect) {
                    is WaterEffect.Write -> {
                        check(running) { "Write after disconnect" }
                        val payload = effect.toByteArray()
                        withTimeout(ioTimeoutMillis) { connection.write(payload) }
                        if (normalEndRequested && machine.state == WaterSessionState.Closed &&
                            payload.contentEquals(WaterProtocol.endEpilogue)) finalB4Written = true
                    }
                    is WaterEffect.Schedule -> {
                        timers.remove(effect.timer.id)?.cancel()
                        timers[effect.timer.id] = sessionScope.launch {
                            delay(effect.delayMillis)
                            events.send(Event.Timer(effect.timer))
                        }
                    }
                    is WaterEffect.CancelTimer -> timers.remove(effect.timer.id)?.cancel()
                    is WaterEffect.Disconnect -> {
                        normalDisconnectRequested = normalEndRequested && finalB4Written &&
                            machine.state == WaterSessionState.Closed && machine.lastFailure == null
                        running = false // finally immediately disconnects/closes
                    }
                    is WaterEffect.ReportFailure -> update { it.copy(failure = effect.failure, message = effect.failure.message) }
                    is WaterEffect.RequireManualConfirmation -> update { it.copy(retryStatus = effect.status,
                        message = WaterFailureGuard.LOCKOUT_NOTICE) }
                }
            }
        }
    }
}
