package io.github.betterzslf.feature.runui

import kotlin.test.*

/** Shared real-run display coverage, extracted from the Pro import-controller test file. */
class RunDisplayTest {
    @Test fun millisecondsBecomeSecondsExactlyOnce() {
        assertEquals("0:00", RunDisplay.duration(0))
        assertEquals("1:00", RunDisplay.duration(60_000))
        assertEquals("1:01:01", RunDisplay.duration(3_661_000))
        assertEquals("—", RunDisplay.duration(null))
    }
    @Test fun metricAndImperialConversionsAreDisplayOnly() {
        assertEquals("100.0 米", RunDisplay.distance(100.0, true))
        assertEquals("1.61 公里", RunDisplay.distance(1609.344, true))
        assertEquals("1.00 英里", RunDisplay.distance(1609.344, false))
        assertEquals("328.1 英尺", RunDisplay.distance(100.0, false))
    }
    @Test fun paceUsesMetresAndMillisecondsAndHandlesMissingMotion() {
        assertEquals("5:00 /公里", RunDisplay.pace(1000.0, 300_000, true))
        assertEquals("5:00 /英里", RunDisplay.pace(1609.344, 300_000, false))
        assertEquals("—", RunDisplay.pace(0.0, 300_000, true))
        assertEquals("—", RunDisplay.distance(Double.NaN, true))
    }
}
