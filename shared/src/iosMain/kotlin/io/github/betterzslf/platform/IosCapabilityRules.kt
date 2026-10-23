package io.github.betterzslf.platform

/** Pure rules kept separate from UIKit/Core Location so their boundaries can be tested offline. */
internal class PickedFileTooLarge : Exception()
internal class PickedFileReadFailure : Exception()

/** Never asks the source for more than limit + 1 bytes, even if metadata is missing or incorrect. */
internal fun readBoundedPickedBytes(
    limit: Int,
    knownSize: Long?,
    checkCancelled: () -> Unit,
    read: (buffer: ByteArray, offset: Int, length: Int) -> Int,
): ByteArray {
    require(limit in 1 until Int.MAX_VALUE)
    if (knownSize != null && knownSize > limit.toLong()) throw PickedFileTooLarge()
    val buffer = ByteArray(limit + 1)
    var count = 0
    while (count <= limit) {
        checkCancelled()
        val requested = minOf(64 * 1024, buffer.size - count)
        val received = read(buffer, count, requested)
        if (received < 0 || received > requested) throw PickedFileReadFailure()
        if (received == 0) return buffer.copyOf(count)
        count += received
        if (count > limit) throw PickedFileTooLarge()
    }
    throw PickedFileTooLarge()
}

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
