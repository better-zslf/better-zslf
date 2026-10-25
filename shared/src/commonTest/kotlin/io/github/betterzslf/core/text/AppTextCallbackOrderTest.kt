package io.github.betterzslf.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure fake-event checks only: no repository, account mutation, clock or real transport. */
class AppTextCallbackOrderTest {
    @Test
    fun constructingAnActionDoesNotRunItsBodyOrItsDisplayFormatting() {
        val events = mutableListOf<String>()
        val action = {
            events += "callback-enter"
            val label = AppText.Booking.contactPhoneLine(run {
                events += "mask"
                "138••••00"
            })
            events += label
            events += "fake-request"
        }
        assertEquals(emptyList(), events)
        action()
        assertEquals(listOf("callback-enter", "mask", "联系电话：138••••00", "fake-request"), events)
    }

    @Test
    fun formattingArgumentsRemainLeftToRightAndExactlyOnce() {
        val events = mutableListOf<String>()
        fun part(event: String, value: String): String { events += event; return value }
        val label = AppText.Run.recordServerMetrics(
            part("status", "服务原文<&>"),
            part("distance", "0 m"),
            part("duration", "00:00"),
        )
        assertEquals(listOf("status", "distance", "duration"), events)
        assertEquals("服务端状态：服务原文<&>\n距离：0 m\n用时：00:00", label)
    }

    @Test
    fun elvisAndNullableBranchesStayLazyAtTheCallsite() {
        var reads = 0
        fun fallback(): String { reads++; return "未提供" }
        val externalIssue: String? = "服务端逐字提示"
        val shown = externalIssue ?: AppText.Profile.personalStatistics(fallback(), fallback(), fallback())
        assertEquals("服务端逐字提示", shown)
        assertEquals(0, reads)
        val absent: Int? = null
        val speed = absent?.let { reads++; AppText.Water.signalDbm(it) } ?: "未知"
        assertEquals("未知", speed)
        assertEquals(0, reads)
    }
}
