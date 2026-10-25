package io.github.betterzslf.feature.water

import io.github.betterzslf.core.text.AppText

/** Share one guard across machines/device reconnects; the identity should be a stable BLE ID. */
class WaterFailureGuard(
    val failureWindowMillis: Long = 60 * 60 * 1000L,
    val confirmationThreshold: Int = 2,
) {
    init {
        require(failureWindowMillis > 0)
        require(confirmationThreshold >= 2)
    }

    private val failures = mutableMapOf<String, MutableList<Long>>()

    /** No implicit wall clock: pass one consistently monotonic time base. */
    fun status(deviceId: String, nowMillis: Long): WaterRetryStatus {
        require(deviceId.isNotBlank())
        require(nowMillis >= 0)
        val recent = failures[deviceId] ?: return WaterRetryStatus(0, false, null)
        // A clock rollback keeps records rather than accidentally granting a retry.
        recent.removeAll { nowMillis >= it && nowMillis - it >= failureWindowMillis }
        if (recent.isEmpty()) {
            failures.remove(deviceId)
            return WaterRetryStatus(0, false, null)
        }
        val blocked = recent.size >= confirmationThreshold
        // Enough records must expire to leave strictly fewer than the threshold.
        val releaseIndex = recent.size - confirmationThreshold
        val retryAt = if (blocked) {
            val oldestRelevant = recent.sorted()[releaseIndex]
            if (Long.MAX_VALUE - oldestRelevant < failureWindowMillis) Long.MAX_VALUE
            else oldestRelevant + failureWindowMillis
        } else null
        return WaterRetryStatus(recent.size, blocked, retryAt)
    }

    fun recordFailure(deviceId: String, nowMillis: Long): WaterRetryStatus {
        status(deviceId, nowMillis)
        failures.getOrPut(deviceId) { mutableListOf() }.add(nowMillis)
        return status(deviceId, nowMillis)
    }

    companion object {
        const val LOCKOUT_NOTICE: String = AppText.Water.REPEATED_FAILURE_LOCKOUT_AND_NO_RETRY_NOTICE
    }
}

data class WaterRetryStatus(
    val recentFailures: Int,
    val requiresManualConfirmation: Boolean,
    val retryWithoutConfirmationAtMillis: Long?,
)
