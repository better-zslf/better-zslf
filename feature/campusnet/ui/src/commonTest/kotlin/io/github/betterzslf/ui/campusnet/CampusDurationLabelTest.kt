package io.github.betterzslf.ui.campusnet

import kotlin.test.Test
import kotlin.test.assertEquals

class CampusDurationLabelTest {
    @Test fun durationLabelIsDerivedLocallyWithoutPlatformFormatting() {
        assertEquals("0 分", campusDurationLabel(0))
        assertEquals("59 分", campusDurationLabel(59 * 60_000L))
        assertEquals("2 小时14 分", campusDurationLabel((2 * 60 + 14) * 60_000L))
        assertEquals("0 分", campusDurationLabel(-5))
    }
}
