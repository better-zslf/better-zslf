package io.github.betterzslf.platform

import kotlin.test.*

/** Pure common ports only: never constructs an Android bridge or starts a native publication. */
class ScheduleNativePortTest {
    @Test
    fun unsupportedFactoryReturnsIndependentOwnersAndFlows() {
        val first = newUnsupportedScheduleNativeBridge()
        val second = newUnsupportedScheduleNativeBridge()
        try {
            assertNotSame(first, second)
            assertNotSame(first.capabilities, second.capabilities)
            assertNotSame(first.openRequests, second.openRequests)
            assertFalse(first.supported)
            assertFalse(second.capabilities.value.supported)
            val original = second.capabilities.value
            first.close(); first.close()
            assertEquals(original, second.capabilities.value)
            val fresh = newUnsupportedScheduleNativeBridge()
            try {
                assertNotSame(first, fresh)
                assertFalse(fresh.supported)
                assertNull(fresh.openRequests.value)
            } finally { fresh.close() }
        } finally { first.close(); second.close() }
    }

    @Test
    fun unsupportedOperationsKeepTheOwnerInert() {
        val bridge = newUnsupportedScheduleNativeBridge()
        val original = bridge.capabilities.value
        try {
            bridge.refreshCapabilities()
            assertFalse(bridge.requestNotificationPermission())
            assertFalse(bridge.openNotificationSettings())
            assertFalse(bridge.openExactAlarmSettings())
            assertFalse(bridge.openBatteryOptimizationSettings())
            assertFalse(bridge.openPromotedNotificationSettings())
            assertFalse(bridge.requestPinWidget(ScheduleWidgetKind.NEXT))
            assertFalse(bridge.publish(ScheduleNativePublication("fixture-owner", ScheduleNativeStatus.NOT_SYNCED)))
            assertFalse(bridge.testLiveNotification())
            assertFalse(bridge.testBreakCard(false))
            bridge.testLiveSequence(false)
            bridge.clearTestNotifications()
            bridge.clear(ScheduleNativeStatus.UNBOUND, "fixture-owner")
            bridge.consumeOpenRequest()
            bridge.close(); bridge.close()
            assertEquals(original, bridge.capabilities.value)
            assertNull(bridge.openRequests.value)
        } finally { bridge.close() }
    }

    @Test
    fun typedRevisionMatchRequiresTheSameCurrentLease() {
        val request = ScheduleNativeOpenRequest("fixture-owner")
        assertTrue(request.matchesRevision("fixture-owner"))
        assertFalse(request.matchesRevision("previous-owner"))
        assertFalse(ScheduleNativeOpenRequest(null).matchesRevision("fixture-owner"))
    }

    @Test
    fun typedRevisionMatchPreservesOriginalBoundsAndCharacterValidation() {
        val longest = "r".repeat(160)
        assertTrue(ScheduleNativeOpenRequest("r").matchesRevision("r"))
        assertTrue(ScheduleNativeOpenRequest(longest).matchesRevision(longest))
        for (invalid in listOf("", "r".repeat(161), "has space", "has\ttab", "has\nline", "has\u0000control", "has\u007fcontrol")) {
            assertFalse(ScheduleNativeOpenRequest(invalid).matchesRevision(invalid))
        }
    }

    @Test
    fun typedRevisionMatchDoesNotInterpretAnOccurrenceOrInventNavigationAuthority() {
        for (occurrence in listOf(null, "", "fixture-occurrence", "x".repeat(201))) {
            assertTrue(ScheduleNativeOpenRequest("fixture-owner", occurrence).matchesRevision("fixture-owner"))
            assertFalse(ScheduleNativeOpenRequest("previous-owner", occurrence).matchesRevision("fixture-owner"))
        }
    }
}
