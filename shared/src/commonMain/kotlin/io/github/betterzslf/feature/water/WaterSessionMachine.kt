/*
 * Handshake sequencing adapted from waterctl src/bluetooth.ts (MIT).
 * Copyright (c) 2021 celesWuff. See licenses/waterctl-MIT.txt.
 * This pure state machine never scans, connects, writes GATT or starts a timer.
 */
package io.github.betterzslf.feature.water

import io.github.betterzslf.core.text.AppText

enum class WaterSessionState {
    Idle, AwaitingPrologue, AwaitingLegacyDelay, AwaitingAuthentication,
    AwaitingStart, Active, AwaitingEnd, Closed, Failed, RetryConfirmationRequired,
}

enum class WaterFailureKind {
    AuthenticationFailed, UnknownAuthenticationStatus, InvalidNotification,
    UnexpectedNotification, StartDenied, OperationTimedOut, TransportFailure,
}

data class WaterFailure(
    val kind: WaterFailureKind,
    val message: String,
    val authenticationStatus: Int? = null,
    val lockoutNotice: String = WaterFailureGuard.LOCKOUT_NOTICE,
)

enum class WaterTimerKind { OperationTimeout, LegacyEpilogueDelay }
data class WaterTimer(val id: Long, val kind: WaterTimerKind, val dueAtMillis: Long)

enum class WaterDisconnectReason { Completed, Cancelled, Failed, ManualConfirmationRequired }

/** Execute in order. In particular await the final B4 write BEFORE Disconnect. */
sealed interface WaterEffect {
    class Write(bytes: ByteArray) : WaterEffect {
        private val content = bytes.copyOf()
        fun toByteArray(): ByteArray = content.copyOf()
    }
    data class Schedule(val timer: WaterTimer, val delayMillis: Long) : WaterEffect
    data class CancelTimer(val timer: WaterTimer) : WaterEffect
    data class Disconnect(val reason: WaterDisconnectReason) : WaterEffect
    data class ReportFailure(val failure: WaterFailure) : WaterEffect
    data class RequireManualConfirmation(val status: WaterRetryStatus) : WaterEffect
}

/**
 * Single-owner offline protocol reducer. Call [begin] only after notification
 * subscription is ready. The adapter executes returned effects and sends timer
 * tokens back to [onTimer]. Check [WaterFailureGuard.status] before connecting.
 *
 * [epilogueContext] is sampled immediately before B2; it must supply an arbitrary
 * seed 1..9999 and Asia/Shanghai time, not an account ID. Keep the guard alive
 * across reconnects. Nothing retries automatically. There is deliberately no
 * temperature/duration command: upstream has no verified implementation of one.
 */
class WaterSessionMachine(
    val deviceId: String,
    val deviceName: String,
    private val epilogueContext: () -> WaterEpilogueContext,
    private val failureGuard: WaterFailureGuard = WaterFailureGuard(),
    private val operationTimeoutMillis: Long = 15_000,
    private val legacyDelayMillis: Long = 500,
) {
    init {
        require(deviceId.isNotBlank())
        require(deviceName.length >= 5)
        require(operationTimeoutMillis > legacyDelayMillis && legacyDelayMillis >= 0)
    }

    var state: WaterSessionState = WaterSessionState.Idle
        private set
    var lastFailure: WaterFailure? = null
        private set
    private var nextTimerId = 0L
    private var operationTimer: WaterTimer? = null
    private var legacyTimer: WaterTimer? = null

    /** [manuallyConfirmedRetry] applies only to this explicitly requested attempt. */
    fun begin(nowMillis: Long, manuallyConfirmedRetry: Boolean = false): List<WaterEffect> {
        require(nowMillis >= 0)
        check(state in terminalStates) { "A water session is already in progress" }
        lastFailure = null
        val status = failureGuard.status(deviceId, nowMillis)
        if (status.requiresManualConfirmation && !manuallyConfirmedRetry) {
            state = WaterSessionState.RetryConfirmationRequired
            return listOf(WaterEffect.RequireManualConfirmation(status),
                WaterEffect.Disconnect(WaterDisconnectReason.ManualConfirmationRequired))
        }
        state = WaterSessionState.AwaitingPrologue
        return buildList {
            addAll(cancelTimers())
            add(WaterEffect.Write(WaterProtocol.startPrologue))
            add(scheduleOperation(nowMillis))
        }
    }

    fun end(nowMillis: Long): List<WaterEffect> {
        require(nowMillis >= 0)
        check(state == WaterSessionState.Active) { "Only an active session can end" }
        state = WaterSessionState.AwaitingEnd
        return listOf(WaterEffect.Write(WaterProtocol.endPrologue), scheduleOperation(nowMillis))
    }

    /** User cancellation never counts as a failed authentication attempt. */
    fun cancel(): List<WaterEffect> {
        if (state in terminalStates) return emptyList()
        state = WaterSessionState.Closed
        return cancelTimers() + WaterEffect.Disconnect(WaterDisconnectReason.Cancelled)
    }

    /** Also handles a failed final B4 write after the reducer has entered Closed. */
    fun onTransportFailure(nowMillis: Long): List<WaterEffect> {
        require(nowMillis >= 0)
        if (state == WaterSessionState.Idle || state == WaterSessionState.Failed ||
            state == WaterSessionState.RetryConfirmationRequired) return emptyList()
        return fail(WaterFailure(WaterFailureKind.TransportFailure, AppText.Water.CONNECTION_OR_WRITE_INTERRUPTED), nowMillis)
    }

    fun onNotification(input: ByteArray, nowMillis: Long): List<WaterEffect> {
        require(nowMillis >= 0)
        if (state in terminalStates) return emptyList()
        val notification = WaterProtocol.normalizeNotification(input)
        if (notification is WaterNotification.Ignored) return emptyList()
        if (notification is WaterNotification.Malformed) {
            return fail(WaterFailure(WaterFailureKind.InvalidNotification, notification.reason), nowMillis)
        }
        val frame = (notification as WaterNotification.Frame).toByteArray()
        return when (notification.type) {
            0xaa, 0xb5, 0xb8 -> emptyList()
            0xba -> listOf(WaterEffect.Write(WaterProtocol.baAck)) // no user information upload
            0xbc -> listOf(WaterEffect.Write(WaterProtocol.offlinebombFix))
            0xb0, 0xb1 -> when (state) {
                WaterSessionState.AwaitingPrologue -> {
                    state = WaterSessionState.AwaitingLegacyDelay
                    val timer = newTimer(WaterTimerKind.LegacyEpilogueDelay, nowMillis, legacyDelayMillis)
                    legacyTimer = timer
                    listOf(WaterEffect.Schedule(timer, legacyDelayMillis))
                }
                WaterSessionState.AwaitingLegacyDelay -> emptyList() // do not postpone a pending start
                else -> unexpected(nowMillis)
            }
            0xae -> {
                if (state != WaterSessionState.AwaitingPrologue && state != WaterSessionState.AwaitingLegacyDelay) {
                    unexpected(nowMillis) // never answer a repeated challenge automatically
                } else {
                    val response = try {
                        WaterProtocol.makeUnlockResponse(frame, deviceName)
                    } catch (error: IllegalArgumentException) {
                        return fail(WaterFailure(WaterFailureKind.InvalidNotification,
                            error.message ?: "Invalid AE challenge"), nowMillis)
                    }
                    state = WaterSessionState.AwaitingAuthentication
                    buildList {
                        addAll(cancelTimers())
                        add(WaterEffect.Write(response))
                        add(scheduleOperation(nowMillis))
                    }
                }
            }
            0xaf -> {
                if (state != WaterSessionState.AwaitingAuthentication) return unexpected(nowMillis)
                if (frame.size < 6) return fail(WaterFailure(WaterFailureKind.InvalidNotification,
                    "AF result is shorter than six bytes"), nowMillis)
                val result = frame[5].toInt() and 0xff
                when (result) {
                    0x55 -> startEpilogue(nowMillis, true)
                    0x01, 0x02, 0x04 -> fail(WaterFailure(WaterFailureKind.AuthenticationFailed,
                        AppText.Water.AUTHENTICATION_FAILED_DISCONNECTED, result), nowMillis)
                    else -> fail(WaterFailure(WaterFailureKind.UnknownAuthenticationStatus,
                        AppText.Water.UNKNOWN_AUTHENTICATION_RESULT_DISCONNECTED, result), nowMillis)
                }
            }
            0xb2 -> {
                if (state != WaterSessionState.AwaitingStart) return unexpected(nowMillis)
                state = WaterSessionState.Active
                cancelTimers()
            }
            0xb3 -> {
                if (state != WaterSessionState.AwaitingEnd) return unexpected(nowMillis)
                state = WaterSessionState.Closed
                cancelTimers() + listOf(WaterEffect.Write(WaterProtocol.endEpilogue),
                    WaterEffect.Disconnect(WaterDisconnectReason.Completed))
            }
            0xc8 -> fail(WaterFailure(WaterFailureKind.StartDenied, AppText.Water.START_COMMAND_NOT_ACCEPTED_DISCONNECTED), nowMillis)
            else -> unexpected(nowMillis)
        }
    }

    fun onTimer(timer: WaterTimer, nowMillis: Long): List<WaterEffect> {
        require(nowMillis >= 0)
        if (state in terminalStates) return emptyList()
        val current = when (timer.kind) {
            WaterTimerKind.OperationTimeout -> operationTimer
            WaterTimerKind.LegacyEpilogueDelay -> legacyTimer
        }
        if (current != timer) return emptyList() // cancelled/stale timer from an earlier phase
        if (nowMillis < timer.dueAtMillis) return listOf(WaterEffect.Schedule(timer, timer.dueAtMillis - nowMillis))
        return when (timer.kind) {
            WaterTimerKind.OperationTimeout -> fail(WaterFailure(WaterFailureKind.OperationTimedOut,
                AppText.Water.CONTROLLER_RESPONSE_TIMEOUT_DISCONNECTED), nowMillis)
            WaterTimerKind.LegacyEpilogueDelay -> {
                if (state != WaterSessionState.AwaitingLegacyDelay) emptyList()
                else startEpilogue(nowMillis, false)
            }
        }
    }

    private fun startEpilogue(nowMillis: Long, authenticated: Boolean): List<WaterEffect> {
        val payload = try {
            WaterProtocol.makeStartEpilogue(deviceName, authenticated, epilogueContext())
        } catch (error: IllegalArgumentException) {
            return fail(WaterFailure(WaterFailureKind.InvalidNotification,
                error.message ?: "Invalid epilogue context"), nowMillis)
        }
        state = WaterSessionState.AwaitingStart
        return buildList {
            addAll(cancelTimers())
            add(WaterEffect.Write(payload))
            add(scheduleOperation(nowMillis))
        }
    }

    private fun unexpected(nowMillis: Long): List<WaterEffect> = fail(
        WaterFailure(WaterFailureKind.UnexpectedNotification, AppText.Water.UNEXPECTED_PROTOCOL_RESPONSE_DISCONNECTED), nowMillis)

    private fun fail(failure: WaterFailure, nowMillis: Long): List<WaterEffect> {
        if (state in handshakeStates) failureGuard.recordFailure(deviceId, nowMillis)
        state = WaterSessionState.Failed
        lastFailure = failure
        return cancelTimers() + listOf(WaterEffect.Disconnect(WaterDisconnectReason.Failed),
            WaterEffect.ReportFailure(failure))
    }

    private fun scheduleOperation(nowMillis: Long): WaterEffect.Schedule {
        val timer = newTimer(WaterTimerKind.OperationTimeout, nowMillis, operationTimeoutMillis)
        operationTimer = timer
        return WaterEffect.Schedule(timer, operationTimeoutMillis)
    }

    private fun newTimer(kind: WaterTimerKind, nowMillis: Long, delayMillis: Long): WaterTimer {
        require(nowMillis <= Long.MAX_VALUE - delayMillis) { "Timer deadline overflow" }
        return WaterTimer(++nextTimerId, kind, nowMillis + delayMillis)
    }

    private fun cancelTimers(): List<WaterEffect.CancelTimer> {
        val effects = listOfNotNull(operationTimer, legacyTimer).map { WaterEffect.CancelTimer(it) }
        operationTimer = null
        legacyTimer = null
        return effects
    }

    private companion object {
        val terminalStates = setOf(WaterSessionState.Idle, WaterSessionState.Closed,
            WaterSessionState.Failed, WaterSessionState.RetryConfirmationRequired)
        val handshakeStates = setOf(WaterSessionState.AwaitingPrologue, WaterSessionState.AwaitingLegacyDelay,
            WaterSessionState.AwaitingAuthentication, WaterSessionState.AwaitingStart)
    }
}
