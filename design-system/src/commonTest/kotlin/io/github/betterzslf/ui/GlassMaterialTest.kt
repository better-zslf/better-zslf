package io.github.betterzslf.ui

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rc10 material is a set of numbers taken from Ardot `24:1 材质与光影规范`. Every value below
 * is copied from that canvas and is the only reason the material looks like the design; a silent
 * edit here changes the app's look without failing anything else, so each is pinned.
 */
class GlassMaterialTest {
    @Test fun highlightMatchesTheSpecifiedDualLightBloom() {
        // Highlight(width = 1.dp, alpha = 浅 0.10 / 深 0.16)
        assertEquals(1.dp, AppGlassTokens.HighlightWidth)
        assertEquals(0.10f, AppGlassTokens.HighlightAlphaLight)
        assertEquals(0.16f, AppGlassTokens.HighlightAlphaDark)
        // innerBlurRadius = 2.dp. The library's six presets are 1.7/2.0/2.3/2.6/2.8/3.5dp, and
        // 2dp is exactly GlassStrokeMiddleDark, which is why this is the one value needing no
        // conversion between canvas and library.
        assertEquals(2.dp, AppGlassTokens.InnerBlurRadius)
        // Both lights must be live, otherwise the "dualPeak" stroke shows a single blob.
        assertEquals(1f, AppGlassTokens.PrimaryLightIntensity)
        assertTrue(AppGlassTokens.SecondaryLightIntensity > 0f)
    }

    @Test fun lightPositionsAreConvertedIntoLibrariesNormalizedSpace() {
        // The shader normalizes (x - 0.5, y - 0.7, z) from a 0..1 UV space. The canvas used a
        // centre-origin -1..1 space, so the two are not interchangeable: a naive copy of the
        // design's (0.0, -0.6) would land at y = 0.1 and clip against the top edge.
        val primary = AppGlassTokens.PrimaryLight
        assertEquals(0.5f, primary.x, "primary light stays horizontally centred")
        assertTrue(primary.y < 0.7f, "y below the 0.7 origin reads as 'above centre'")
        assertTrue(primary.z < 0f, "negative z places the light behind the surface plane")
        // The design labels the secondary light "lower right"; because y < 0.7 renders *above*
        // centre, only y > 0.7 actually lands below it. The canvas annotation states the opposite
        // of the library semantics, so this assertion is the correction, not a transcription.
        val secondary = AppGlassTokens.SecondaryLight
        assertTrue(secondary.x > 0.5f, "secondary light sits right of centre")
        assertTrue(secondary.y > 0.7f, "secondary light sits below centre")
    }

    @Test fun cardMaterialMeetsTheDarkSurfaceContrastFloor() {
        // §24:1: a dark card must clear 1.25:1 against the page or it reads as a hole. The border
        // and shadow are the remaining separation, but the fill alone has to carry the floor.
        val ratio = contrast(AppUiTokens.CardDarkRaised, AppUiTokens.BackgroundDark)
        assertTrue(ratio >= 1.25f, "dark card/page contrast was $ratio")
        // The rim must be visible against the card it sits on, and must differ per theme: one
        // shared grey would disappear on one of the two surfaces.
        assertTrue(contrast(AppGlassTokens.CardBorderLight, AppUiTokens.Card) > 1f)
        assertTrue(contrast(AppGlassTokens.CardBorderDark, AppUiTokens.CardDarkRaised) > 1f)
        assertTrue(AppGlassTokens.CardBorderLight != AppGlassTokens.CardBorderDark)
        // Dark needs a deeper shadow: #22262D on #0D0F12 is a much tighter gap than white on grey.
        assertTrue(AppGlassTokens.CardShadowDark >= AppGlassTokens.CardShadowLight)
        assertTrue(AppGlassTokens.CardShadowAlphaDark > AppGlassTokens.CardShadowAlphaLight)
    }

    @Test fun barTintKeepsGlassReadableWithoutHidingIt() {
        // The tint is blended *inside* the blur, so it is the only thing keeping scrolled text
        // from showing through the labels. Too low and the bar is unreadable, too high and the
        // blur is pointless -- both bounds are the reason this is a token and not a literal.
        assertTrue(AppGlassTokens.BarTintAlphaLight in 0.4f..0.8f)
        assertTrue(AppGlassTokens.BarTintAlphaDark in 0.4f..0.8f)
        assertTrue(AppGlassTokens.BarBlurRadius > 0f)
        // The blur radius is clamped to [0, 150] by the library; a value past that is a silent
        // no-op rather than an error, so it is checked here.
        assertTrue(AppGlassTokens.BarBlurRadius <= 150f)
        assertTrue(AppGlassTokens.EdgeBlurRadius <= 150f)
    }

    @Test fun progressiveEdgeRampIsMonotonicAndFitsTheBand() {
        val gradient = AppGlassTokens.EdgeGradient
        // Full strength at 25%, clear at 100%, along 90 degrees (top to bottom). Reversing these
        // would make the band strongest at the bottom, i.e. a visible seam instead of a fade.
        assertTrue(gradient.startFraction < gradient.endFraction)
        assertEquals(90f, gradient.angle)
        assertEquals(0.25f, gradient.startFraction)
        assertEquals(1f, gradient.endFraction)
        assertTrue(gradient.curve > 0f)
        // The library documents this pass as a full-resolution overlay per frame and warns
        // against large fills; a tall band would be exactly that mistake.
        assertTrue(AppGlassTokens.EdgeBandHeight in 80.dp..120.dp)
    }

    @Test fun squircleCurvatureStaysInsideTheLibrarysClamp() {
        // SquircleDefaults clamps extension to 1f..2f. 1.1f is the library default used by every
        // Miuix component; the spec asks for the canvas' cornerSmoothing 0.6, which maps to 1.20f.
        assertEquals(1.20f, AppGlassTokens.SquircleExtension)
        assertTrue(AppGlassTokens.SquircleExtension in 1f..2f)
        // Only the corner tile size changes -- every radius stays the delivered value.
        assertEquals(16.dp, AppUiTokens.CardRadius)
        assertEquals(30.dp, AppGlassTokens.NavigationRadius)
    }

    @Test fun sheenStaysBelowTheTextAndOutOfTheContrastBudget() {
        // 6% white over the first line would quietly eat contrast, which §3.2 forbids; the band
        // is therefore capped both in alpha and in height.
        assertTrue(AppGlassTokens.SheenAlpha <= 0.08f)
        assertTrue(AppGlassTokens.SheenHeight <= 32.dp)
        // Saturation of pure white over white is zero, so the sheen can never be a visible grey
        // wash on a light card; it only shows up on tinted and dark surfaces.
        assertTrue(io.github.betterzslf.ui.AppUiTokens.Card.luminance() > 0.9f)
    }

    private fun contrast(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }
}
