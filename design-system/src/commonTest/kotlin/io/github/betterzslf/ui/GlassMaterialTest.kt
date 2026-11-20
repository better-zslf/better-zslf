package io.github.betterzslf.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.material.EdgePeak
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassShadows

/**
 * The app now takes its glass values from MIUIX's own OS4 presets rather than from the Ardot
 * `24:1` canvas.
 *
 * That canvas was written against the OS3 bloom stroke (`Highlight` / `BloomStroke`, alpha
 * 0.10/0.16). MIUIX 0.9.5-beta01 removed that stack entirely in favour of OS4 glass, which adds
 * refraction, three blended colour layers and dispersion shadows — dimensions the OS3 spec has no
 * field for. So the honest thing to pin is not "did the old numbers survive" (they cannot) but
 * "are we actually on the official presets, and do the few values we still author hold up".
 */
class GlassMaterialTest {
    @Test fun barsUseTheOfficialFloatingGlassPreset() {
        // puredThinGlass: a 20dp blur plus three colour layers. The three layers, not one flat
        // tint, are what give OS4 glass its body -- collapsing them to a single SrcOver colour is
        // the specific regression this pins.
        val light = GlassMaterials.PuredThinGlassLight
        val dark = GlassMaterials.PuredThinGlassDark
        assertEquals(20.dp, light.blurRadius)
        assertEquals(20.dp, dark.blurRadius)
        assertTrue(light.second != null && light.third != null, "light preset must keep all three layers")
        assertTrue(dark.second != null && dark.third != null, "dark preset must keep all three layers")
        // Light and dark must differ; a shared preset would lose one theme's glass.
        assertTrue(light.first.color != dark.first.color)
        assertTrue(light.second?.color != dark.second?.color)
    }

    @Test fun rimComesFromTheOss4PresetFamilyNotAHandTunedStroke() {
        // Every miuix-glass component defaults to GlassEdgeStrokes.forTheme, so using it here is
        // what makes our surfaces and the library's read as one material.
        val light = GlassEdgeStrokes.forTheme(isDark = false)
        val dark = GlassEdgeStrokes.forTheme(isDark = true)
        assertEquals(GlassEdgeStrokes.MiddleLight, light)
        assertEquals(GlassEdgeStrokes.MiddleDark, dark)
        // OS4 rims are 0.8dp with a 1.2dp bevel -- thinner and tighter than the OS3 bloom stroke
        // (1dp / 2dp inner blur) this project used to hand-write.
        assertEquals(0.8.dp, light.width)
        assertEquals(1.2.dp, light.bevel)
        // The OS4 presets gate each light axially (the default); only OS3-style strokes used Dual.
        assertEquals(EdgePeak.Vertical, light.peak)
    }

    @Test fun shadowsAreTheLibraryPresets() {
        // Float under the nav bar, Regular under panels. These are GlassShadow values, which carry
        // a dispersion term the plain Compose shadow has no equivalent for.
        assertEquals(24f, GlassShadows.Float.radius)
        assertEquals(64f, GlassShadows.Regular.radius)
        assertEquals(GlassShadows.Float, AppGlassFloatShadow)
        assertEquals(GlassShadows.Regular, AppGlassPanelShadow)
        assertTrue(GlassShadows.Float.dispersion > 0f)
    }

    @Test fun navigationBarHeightFollowsTheComponentDefault() {
        // GlassNavigationBarDefaults.Height is 54dp, deliberately shorter than the 64dp pill this
        // project hand-rolled. Recording it here makes an accidental divergence obvious.
        assertEquals(54.dp, AppGlassTokens.NavigationHeight)
        assertTrue(AppGlassTokens.NavigationHeight >= 48.dp, "must still clear the touch-target floor")
    }

    @Test fun handAuthoredCardMaterialStillMeetsTheContrastFloor() {
        // Cards sit on an opaque page, not over a backdrop, so they cannot use glassPanel and
        // are the one surface still authored by hand. These are the values that must not drift.
        val ratio = contrast(AppUiTokens.CardDarkRaised, AppUiTokens.BackgroundDark)
        assertTrue(ratio >= 1.25f, "dark card/page contrast was $ratio")
        assertTrue(contrast(AppGlassTokens.CardBorderLight, AppUiTokens.Card) > 1f)
        assertTrue(contrast(AppGlassTokens.CardBorderDark, AppUiTokens.CardDarkRaised) > 1f)
        assertTrue(AppGlassTokens.CardBorderLight != AppGlassTokens.CardBorderDark)
        assertTrue(AppGlassTokens.CardShadowDark >= AppGlassTokens.CardShadowLight)
        assertTrue(AppGlassTokens.CardShadowAlphaDark > AppGlassTokens.CardShadowAlphaLight)
    }

    @Test fun squircleCurvatureStaysInsideTheLibrarysClamp() {
        // Only governs the clips we still draw ourselves; miuix-glass carries its own smoothing.
        assertEquals(1.20f, AppGlassTokens.SquircleExtension)
        assertTrue(AppGlassTokens.SquircleExtension in 1f..2f)
        // Radii are layout tokens and were not part of the material migration.
        assertEquals(16.dp, AppUiTokens.CardRadius)
        assertEquals(30.dp, AppUiTokens.NavigationRadius)
    }

    @Test fun sheenStaysBelowTheTextAndOutOfTheContrastBudget() {
        // 6% white over the first line would quietly eat contrast; capped in alpha and height.
        assertTrue(AppGlassTokens.SheenAlpha <= 0.08f)
        assertTrue(AppGlassTokens.SheenHeight <= 32.dp)
        assertTrue(AppUiTokens.Card.luminance() > 0.9f)
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }
}
