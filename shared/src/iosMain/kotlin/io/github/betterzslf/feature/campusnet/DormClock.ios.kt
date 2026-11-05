package io.github.betterzslf.feature.campusnet

import platform.Foundation.NSDate
import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970

actual fun currentEpochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun localOffsetMinutes(): Int =
    // secondsFromGMT is evaluated at the current moment, so a DST boundary is handled.
    NSTimeZone.localTimeZone.secondsFromGMT.toInt() / 60
