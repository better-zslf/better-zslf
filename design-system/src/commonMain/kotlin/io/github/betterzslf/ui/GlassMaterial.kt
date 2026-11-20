package io.github.betterzslf.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.material.EdgeStroke
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * OS4 (HyperOS 4) glass material tokens.
 *
 * Design decision: the Ardot `24:1 材质与光影规范` was written against the OS3 bloom stroke
 * (`Highlight` / `BloomStroke`, alpha 0.10/0.16, lights at (0.5, 0.35, -0.5)). MIUIX 0.9.5-beta01
 * **removed that entire stack** in favour of OS4 glass, so those numbers are no longer reachable
 * and no longer meaningful — OS4 adds refraction, three colour layers and dispersion shadows that
 * the OS3 spec has no field for.
 *
 * Rather than re-deriving them, every value here comes from MIUIX's own presets. That is the
 * decision taken with the maintainer: OS4 stock look, official defaults, design canvas demoted
 * from "value source" to "structure reference". Only genuinely project-specific material (card
 * depth on a non-glass surface) is still authored below.
 */
object AppGlassTokens {
    /**
     * Minimum height of the glass navigation bar, from `GlassNavigationBarDefaults.Height`.
     * 54dp, not the 64dp the previous hand-rolled pill used.
     */
    val NavigationHeight: Dp = 54.dp

    /** Card corner radius. Unchanged: the design's 16dp is a layout token, not a material one. */
    val CardRadius: Dp = AppUiTokens.CardRadius

    /**
     * Superellipse curvature for the surfaces that are NOT rendered by `miuix-glass`.
     *
     * `GlassShape` has its own `smoothing` (1f = continuous) and `miuix-glass` applies it itself,
     * so this only governs the self-drawn clips and card rims that remain in our own code.
     * `SquircleDefaults.Extension` is 1.1f; the design canvas' cornerSmoothing 0.6 maps to 1.20f.
     */
    const val SquircleExtension = 1.20f

    /**
     * Card depth on the plain (non-glass) surfaces: page cards sit on an opaque page, not on a
     * backdrop, so they cannot use `glassPanel` — there is nothing behind them to sample. These
     * stay hand-authored and are the only such values left in the app.
     */
    val CardShadowLight = 1.dp
    val CardShadowDark = 2.dp
    val CardBorderLight = Color(0xFFEEF0F4)
    val CardBorderDark = Color(0xFF262A31)
    val CardShadowTintLight = Color(0xFF283246)
    val CardShadowTintDark = Color(0xFF000000)
    const val CardShadowAlphaLight = 0.06f
    const val CardShadowAlphaDark = 0.35f

    /** Top-reflectance band on tappable cards; the glass components do this themselves. */
    val SheenHeight = 24.dp
    const val SheenAlpha = 0.06f
}

/** True when the resolved theme surface is dark; the single source for every material branch. */
@Composable
@ReadOnlyComposable
fun appDarkSurface(): Boolean = MiuixTheme.colorScheme.surface.luminance() < 0.5f

/**
 * The glass body for floating bars: `GlassMaterials.puredThinGlass` — a 20dp blur with three
 * colour layers (`PlusDarker` / `SoftLight` / `HardLight`). This is the official MIUIX preset for
 * floating surfaces; the three blended layers, not one flat tint, are what give OS4 glass its body.
 *
 * `dynamicGlassMaterial` is deliberately NOT used: it only diverges from the preset when
 * `MiuixTheme.isDynamicColor` is on, and this project ships a fixed brand palette (Monet is opt-in
 * and stays on Miuix's own tokens). Calling it anyway would be dead branching.
 */
@Composable
fun appBarGlassMaterial(isDark: Boolean = appDarkSurface()): GlassMaterial =
    GlassMaterials.puredThinGlass(isDark)

/**
 * The lit rim for a glass silhouette. `GlassEdgeStrokes.forTheme` picks `MiddleLight` /
 * `MiddleDark` — the same preset every `miuix-glass` component reaches for by default, so our
 * surfaces and the library's read as one material.
 */
@Composable
fun appGlassStroke(isDark: Boolean = appDarkSurface()): EdgeStroke =
    GlassEdgeStrokes.forTheme(isDark = isDark)

/** Standard floating-control shadow (`GlassShadows.Float`), used under the nav bar. */
val AppGlassFloatShadow get() = GlassShadows.Float

/** Elevated surface shadow (`GlassShadows.Regular`), used under panels. */
val AppGlassPanelShadow get() = GlassShadows.Regular

/** A crisp (non-squircle) glass silhouette for full-bleed bars. */
@Composable
fun barGlassShape(cornerRadius: Dp = AppUiTokens.NavigationRadius): GlassShape =
    remember(cornerRadius) { GlassShape(cornerRadius) }

/** Squircle clip at the design's 1.20f curvature, falling back to a rounded rect without shaders. */
@Composable
fun Modifier.appSquircleClip(cornerRadius: Dp, extension: Float = AppGlassTokens.SquircleExtension): Modifier =
    this.squircleClip(cornerRadius = cornerRadius, extension = extension)

/**
 * Card depth for opaque page cards: drop shadow plus a 1dp rim.
 *
 * `Miuix Card` accepts a single fill colour, so neither can live inside it, and `glassPanel`
 * is not an option here — these cards sit on an opaque page, not over a backdrop.
 *
 * Chain it so the rim paints above the card's own fill.
 */
@Composable
fun Modifier.appCard(
    cornerRadius: Dp = AppUiTokens.CardRadius,
    elevated: Boolean = true,
    borderColor: Color = if (appDarkSurface()) AppGlassTokens.CardBorderDark else AppGlassTokens.CardBorderLight,
): Modifier {
    val isDark = appDarkSurface()
    val shadowShape = RoundedCornerShape(cornerRadius)
    val shadowTint = if (isDark) AppGlassTokens.CardShadowTintDark else AppGlassTokens.CardShadowTintLight
    val shadowAlpha = if (isDark) AppGlassTokens.CardShadowAlphaDark else AppGlassTokens.CardShadowAlphaLight
    val elevatedModifier = if (!elevated) this else this
        // Modifier.shadow repaints a blurred copy of the whole layer, so it must precede the
        // border; reversed, the rim would be inside the shadow's source and smear.
        .shadow(
            elevation = if (isDark) AppGlassTokens.CardShadowDark else AppGlassTokens.CardShadowLight,
            shape = shadowShape,
            clip = false,
            ambientColor = shadowTint.copy(alpha = shadowAlpha),
            spotColor = shadowTint.copy(alpha = shadowAlpha),
        )
    return elevatedModifier.appCardBorder(cornerRadius = cornerRadius, color = borderColor)
}

/**
 * The 1dp rim alone, drawn over the card content. Split out because a few cards already carry
 * their own shadow and only need the edge light.
 */
@Composable
fun Modifier.appCardBorder(
    cornerRadius: Dp = AppUiTokens.CardRadius,
    color: Color,
    width: Dp = 1.dp,
): Modifier {
    val squircleEnabled = isSquircleEnabled()
    return this.drawWithCache {
        val path = Path()
        val strokePx = width.toPx()
        val half = strokePx / 2f
        val innerWidth = size.width - strokePx
        val innerHeight = size.height - strokePx
        val drawable = strokePx > 0f && innerWidth > 0f && innerHeight > 0f
        if (drawable) {
            path.addSquircleRect(
                width = innerWidth,
                height = innerHeight,
                cornerRadius = (cornerRadius.toPx() - half).coerceAtLeast(0f),
                extension = AppGlassTokens.SquircleExtension,
                squircleEnabled = squircleEnabled,
            )
        }
        onDrawWithContent {
            drawContent()
            if (drawable) {
                translate(half, half) { drawPath(path = path, color = color, style = Stroke(width = strokePx)) }
            }
        }
    }
}

/**
 * The top reflectance a tappable card gets: white 6% fading out over 24dp.
 * Painted as an overlay because `CardColors` holds one colour only.
 */
@Composable
fun Modifier.appCardSheen(cornerRadius: Dp = AppUiTokens.CardRadius, height: Dp = AppGlassTokens.SheenHeight): Modifier =
    this.drawWithCache {
        val radiusPx = cornerRadius.toPx()
        val bandPx = height.toPx()
        val brush = Brush.verticalGradient(
            0f to Color.White.copy(alpha = AppGlassTokens.SheenAlpha),
            1f to Color.Transparent,
            startY = 0f,
            endY = bandPx,
        )
        val path = Path()
        path.addSquircleRect(
            width = size.width,
            height = size.height,
            cornerRadius = radiusPx,
            extension = AppGlassTokens.SquircleExtension,
        )
        onDrawWithContent {
            drawContent()
            clipPath(path) {
                drawRect(brush = brush, size = Size(size.width, bandPx))
            }
        }
    }

/**
 * The one card every screen uses: squircle curvature, a 1dp rim and the themed depth shadow.
 *
 * Deliberately a drop-in for `Miuix Card` at every existing call site — it adds material and
 * nothing else, not even a width or margin default.
 *
 * [sheen] adds the top reflectance. It has to be painted *inside* the card, behind the content:
 * 6% white over the first text line would cost contrast, which the design forbids.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = AppUiTokens.CardRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    elevated: Boolean = true,
    sheen: Boolean = false,
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    holdDownState: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.appCard(cornerRadius = cornerRadius, elevated = elevated),
        cornerRadius = cornerRadius,
        insideMargin = insideMargin,
        colors = colors,
        pressFeedbackType = pressFeedbackType,
        showIndication = showIndication,
        holdDownState = holdDownState,
        onClick = onClick,
        onLongPress = onLongPress,
    ) {
        if (!sheen) {
            content()
        } else {
            Box(Modifier.fillMaxWidth().appCardSheen(cornerRadius)) {
                Column { content() }
            }
        }
    }
}
