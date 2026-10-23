package io.github.betterzslf.platform

import java.util.concurrent.CancellationException

/** Compiles the real pure iosMain rules as JVM Kotlin, not UIKit or Kotlin/Native. No Gradle required. */
private var passed = 0
private fun test(name: String, block: () -> Unit) {
    block()
    passed++
    println("PASS $name")
}
private inline fun <reified T : Throwable> fails(block: () -> Unit) {
    try { block() } catch (error: Throwable) {
        check(error is T) { "Unexpected failure type" }
        return
    }
    error("Expected failure")
}

private fun readFixture(size: Int, knownSize: Long? = null, chunk: Int = 31, limit: Int = 256): Pair<ByteArray, Int> {
    var consumed = 0
    val bytes = readBoundedPickedBytes(limit, knownSize, {}) { buffer, offset, requested ->
        val count = minOf(size - consumed, requested, chunk)
        repeat(count) { buffer[offset + it] = ((consumed + it) % 127).toByte() }
        consumed += count
        count
    }
    return bytes to consumed
}

private const val BASE = 1_000_000L
private fun IosLocationSampleGate.fix(
    time: Long,
    receipt: Long = time,
    accuracy: Double = 10.0,
    now: Long = time,
    latitude: Double = 22.0,
    longitude: Double = 113.0,
): Boolean = accept(latitude, longitude, BASE + time, accuracy, BASE + now, receipt)

fun main() {
    test("empty stream") { check(readFixture(0).first.isEmpty()) }
    test("unknown size partial reads preserve bytes") {
        val bytes = readFixture(128).first
        check(bytes.contentEquals(ByteArray(128) { (it % 127).toByte() }))
    }
    test("exact limit accepted only after EOF probe") {
        val (bytes, consumed) = readFixture(256)
        check(bytes.size == 256 && consumed == 256)
    }
    test("known oversize rejected before allocation or read") {
        var read = false
        fails<PickedFileTooLarge> { readBoundedPickedBytes(256, Long.MAX_VALUE, {}) { _, _, _ -> read = true; 0 } }
        check(!read)
    }
    test("unknown huge stream reads at most limit plus one") {
        var consumed = 0
        fails<PickedFileTooLarge> {
            readBoundedPickedBytes(256, null, {}) { _, _, requested -> consumed += requested; requested }
        }
        check(consumed == 257)
    }
    test("underreported metadata never bypasses bound") {
        fails<PickedFileTooLarge> { readFixture(257, knownSize = 1) }
    }
    test("exact metadata is only a hint and EOF still checked") {
        check(readFixture(64, knownSize = 256).first.size == 64)
    }
    test("single byte over limit fails") { fails<PickedFileTooLarge> { readFixture(257) } }
    test("read error fails") {
        fails<PickedFileReadFailure> { readBoundedPickedBytes(256, null, {}) { _, _, _ -> -1 } }
    }
    test("invalid provider byte count fails") {
        fails<PickedFileReadFailure> { readBoundedPickedBytes(256, null, {}) { _, _, requested -> requested + 1 } }
    }
    test("cancellation stops before next native read") {
        var reads = 0
        fails<CancellationException> {
            readBoundedPickedBytes(256, null, { if (reads == 1) throw CancellationException() }) { _, _, _ -> reads++; 1 }
        }
        check(reads == 1)
    }
    test("real 8 MiB boundary and bounded probe") {
        val limit = 8 * 1024 * 1024
        var consumed = 0
        var largestRequest = 0
        val result = readBoundedPickedBytes(limit, null, {}) { _, _, requested ->
            largestRequest = maxOf(largestRequest, requested)
            val count = minOf(requested, limit - consumed)
            consumed += count
            count
        }
        check(result.size == limit && consumed == limit && largestRequest <= 64 * 1024)
    }
    test("first valid native fix accepted") { check(IosLocationSampleGate(10, BASE).fix(0)) }
    test("100m inclusive accuracy boundary") { check(IosLocationSampleGate(10, BASE).fix(0, accuracy = 100.0)) }
    test("over 100m accuracy rejected") { check(!IosLocationSampleGate(10, BASE).fix(0, accuracy = 100.001)) }
    test("negative accuracy rejected") { check(!IosLocationSampleGate(10, BASE).fix(0, accuracy = -1.0)) }
    test("nonfinite accuracy rejected") {
        check(!IosLocationSampleGate(10, BASE).fix(0, accuracy = Double.NaN))
        check(!IosLocationSampleGate(10, BASE).fix(0, accuracy = Double.POSITIVE_INFINITY))
    }
    test("invalid coordinates rejected") {
        check(!IosLocationSampleGate(10, BASE).fix(0, latitude = 91.0))
        check(!IosLocationSampleGate(10, BASE).fix(0, longitude = -181.0))
        check(!IosLocationSampleGate(10, BASE).fix(0, latitude = Double.NaN))
    }
    test("cached pre-start fix rejected") { check(!IosLocationSampleGate(10, BASE).fix(-1)) }
    test("stale fix rejected") { check(!IosLocationSampleGate(10, BASE).fix(0, now = 30_001)) }
    test("far future fix rejected") { check(!IosLocationSampleGate(10, BASE).fix(2_001, now = 0)) }
    test("duplicate and out of order native timestamps rejected") {
        val gate = IosLocationSampleGate(10, BASE)
        check(gate.fix(10_000))
        check(!gate.fix(10_000, receipt = 20_000))
        check(!gate.fix(9_999, receipt = 30_000))
    }
    test("10s native timestamp throttle") {
        val gate = IosLocationSampleGate(10, BASE)
        check(gate.fix(0))
        check(!gate.fix(9_999))
        check(gate.fix(10_000))
    }
    test("monotonic receipt throttle prevents native batch bursts") {
        val gate = IosLocationSampleGate(10, BASE)
        check(gate.fix(0))
        check(!gate.fix(10_000, receipt = 9_999))
        check(gate.fix(10_000, receipt = 10_000))
    }
    test("invalid fix does not move sampling window") {
        val gate = IosLocationSampleGate(10, BASE)
        check(gate.fix(0))
        check(!gate.fix(10_000, accuracy = 101.0))
        check(gate.fix(10_001))
    }
    test("small interval clamped to 10s") {
        val gate = IosLocationSampleGate(-1, BASE)
        check(gate.fix(0))
        check(!gate.fix(9_999))
        check(gate.fix(10_000))
    }
    test("large interval clamped to 60s") {
        val gate = IosLocationSampleGate(Int.MAX_VALUE, BASE)
        check(gate.fix(0))
        check(!gate.fix(59_999))
        check(gate.fix(60_000))
    }
    test("restart uses a new gate and session start") {
        val first = IosLocationSampleGate(10, BASE)
        check(first.fix(0))
        val resumed = IosLocationSampleGate(10, BASE + 5_000)
        check(!resumed.fix(4_999))
        check(resumed.fix(5_000))
    }
    println("All $passed pure iOS capability-rule JVM tests passed; no UIKit/Core Location/native compilation was performed.")
}
