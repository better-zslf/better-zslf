package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import kotlin.test.Test
import kotlin.test.assertEquals

/** Application copy and settings contracts are not dependencies of the design-system module. */
class ApplicationUiContractsTest {
    @Test fun aboutCopyKeepsTheFinalThirdPartyContext() {
        assertEquals("电子科技大学中山学院第三方校园客户端，与学校及官方客户端无隶属关系。",
            AppText.App.INDEPENDENT_CLIENT_DESCRIPTION)
    }

    @Test fun aboutVersionIsOnlyAVersionAndDebugFilterLabelsStayCompact() {
        assertEquals("1.0.0-rc4-pro", AppText.Content.aboutVersion("1.0.0-rc4-pro"))
        assertEquals(listOf("全部", "警告", "错误"), listOf(
            AppText.DevTools.FILTER_ALL,
            AppText.DevTools.FILTER_ISSUES,
            AppText.DevTools.FILTER_ERRORS,
        ))
    }

    @Test fun samplingMenuDoesNotMisrepresentAnExistingPreference() {
        assertEquals(listOf(10, 15, 30, 60), samplingChoices(10))
        assertEquals(listOf(10, 15, 30, 42, 60), samplingChoices(42))
        assertEquals(1, samplingChoices(30).count { it == 30 })
    }
}
