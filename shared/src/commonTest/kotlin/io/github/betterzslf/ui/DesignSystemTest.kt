package io.github.betterzslf.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class DesignSystemTest {
    @Test fun aboutCopyKeepsTheFinalThirdPartyContext() {
        assertEquals("电子科技大学中山学院第三方校园客户端，与学校及官方客户端无隶属关系。",
            io.github.betterzslf.core.text.AppText.App.INDEPENDENT_CLIENT_DESCRIPTION)
    }

    @Test fun finalArdotPaletteHasIndependentLightAndDarkSurfaces() {
        val light = ardotLightColors()
        val dark = ardotDarkColors()
        assertEquals(Color(0xFFF2F2F5), light.surface)
        assertEquals(Color.White, light.surfaceContainer)
        assertEquals(Color.White, light.secondaryContainer)
        assertEquals(Color(0xFF3382FF), light.primary)
        assertEquals(Color(0xFF757578), light.onSurfaceVariantSummary)
        assertEquals(Color(0xFF0D0F12), dark.surface)
        assertEquals(Color(0xFF1A1C21), dark.surfaceContainer)
        assertEquals(Color(0xFF1A1C21), dark.secondaryContainer)
        assertEquals(Color(0xFF5C94FF), dark.primary)
        assertEquals(Color(0xFF99A1A8), dark.onSurfaceVariantSummary)
        assertEquals(Color(0xFFE5E5EB), light.outline)
        assertEquals(Color(0xFF292E36), dark.outline)
        assertTrue(contrast(light.onSurface, light.surface) >= 7f)
        assertTrue(contrast(dark.onSurface, dark.surface) >= 7f)
        val other = ardotLightColors()
        val customized = light.copy(primary = Color.Magenta)
        assertNotSame(light, other)
        assertNotSame(light, customized)
        assertEquals(Color.Magenta, customized.primary)
        assertEquals(Color(0xFF3382FF), light.primary)
        assertEquals(Color(0xFF3382FF), other.primary)
    }

    @Test fun refreshCompletionDoesNotInventAQuerySuccess() {
        assertEquals(listOf("下拉刷新", "松开刷新", "正在刷新…", ""), appRefreshTexts())
    }

    @Test fun dimensionsAndTypographyMatchTheFinalNativeLayouts() {
        assertEquals(20.dp, AppUiTokens.PagePadding)
        assertEquals(24.dp, AppUiTokens.FormPadding)
        assertEquals(16.dp, AppUiTokens.CardRadius)
        assertEquals(14.dp, AppUiTokens.FieldRadius)
        assertEquals(52.dp, AppUiTokens.ButtonHeight)
        assertEquals(26.dp, AppUiTokens.ButtonRadius)
        val styles = ardotTextStyles()
        assertEquals(28.sp, styles.title1.fontSize)
        assertEquals(18.sp, styles.title3.fontSize)
        assertEquals(17.sp, styles.title4.fontSize)
        assertEquals(FontWeight.Bold, styles.title1.fontWeight)
    }

    @Test fun scrollPaddingPreservesPhysicalCutoutsInBothDirections() {
        val native = PaddingValues.Absolute(left = 30.dp, top = 80.dp, right = 7.dp, bottom = 10.dp)
        for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
            val result = pageContentPadding(native, 96.dp, direction)
            assertEquals(50.dp, result.calculateLeftPadding(direction))
            assertEquals(27.dp, result.calculateRightPadding(direction))
            assertEquals(92.dp, result.calculateTopPadding())
            // The floating bar is an overlay: its measured height wins over the scaffold inset, so
            // the tail row is not covered. Section gap keeps a little breathing room below it.
            assertEquals(116.dp, result.calculateBottomPadding())
        }
        val rtl = pageContentPadding(native, 0.dp, LayoutDirection.Rtl)
        assertEquals(27.dp, rtl.calculateStartPadding(LayoutDirection.Rtl))
        assertEquals(50.dp, rtl.calculateEndPadding(LayoutDirection.Rtl))
    }

    @Test fun samplingMenuDoesNotMisrepresentAnExistingPreference() {
        assertEquals(listOf(10, 15, 30, 60), samplingChoices(10))
        assertEquals(listOf(10, 15, 30, 42, 60), samplingChoices(42))
        assertEquals(1, samplingChoices(30).count { it == 30 })
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }
}
