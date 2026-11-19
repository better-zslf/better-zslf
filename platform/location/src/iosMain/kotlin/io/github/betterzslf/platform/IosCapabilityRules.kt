package io.github.betterzslf.platform

/** Pure location rule kept separate from UIKit/Core Location for offline tests. */
internal class IosLocationSampleGate(samplingSeconds: Int, private val startedAtMillis: Long) {
    private val intervalMillis = samplingSeconds.coerceIn(10, 60) * 1_000L
    private var lastTimestampMillis: Long? = null
    private var lastReceiptUptimeMillis: Long? = null

    fun accept(
        latitude: Double,
        longitude: Double,
        timestampMillis: Long,
        accuracyMeters: Double,
        nowMillis: Long,
        receiptUptimeMillis: Long,
    ): Boolean {
        if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
            !longitude.isFinite() || longitude !in -180.0..180.0 ||
            !accuracyMeters.isFinite() || accuracyMeters !in 0.0..100.0
        ) return false
        if (timestampMillis < startedAtMillis || timestampMillis <= 0L ||
            timestampMillis > nowMillis + 2_000L ||
            nowMillis - timestampMillis > maxOf(30_000L, intervalMillis * 2)
        ) return false
        val previousTimestamp = lastTimestampMillis
        if (previousTimestamp != null && timestampMillis - previousTimestamp < intervalMillis) return false
        val previousReceipt = lastReceiptUptimeMillis
        if (previousReceipt != null && receiptUptimeMillis - previousReceipt < intervalMillis) return false
        lastTimestampMillis = timestampMillis
        lastReceiptUptimeMillis = receiptUptimeMillis
        return true
    }
}
