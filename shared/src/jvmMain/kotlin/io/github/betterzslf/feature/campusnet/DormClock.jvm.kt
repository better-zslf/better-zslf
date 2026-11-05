package io.github.betterzslf.feature.campusnet

import java.util.TimeZone

actual fun currentEpochMillis(): Long = System.currentTimeMillis()

actual fun localOffsetMinutes(): Int =
    TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
