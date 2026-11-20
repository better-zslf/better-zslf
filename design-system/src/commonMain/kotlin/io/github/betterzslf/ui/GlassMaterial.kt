package io.github.betterzslf.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.highlight.BloomStroke
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.highlight.LightPosition
import top.yukonga.miuix.kmp.blur.highlight.rememberTiltLight
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/**
 * Material tokens from Ardot `24:1 材质与光影规范 rc10`, named after the MIUIX 0.9.4 parameters
 * they feed so a review can diff design values against code without translation.
 *
 * Sizes and brand colours stay in [AppUiTokens]; nothing here changes a radius or a hue, it only
 * adds light, curvature and depth on top of the delivered layout.
 */
object AppGlassTokens {
    /** `Highlight.width` — the library has one stroke width, there is no inner/outer half split. */
    val HighlightWidth = 1.dp

    /** `Highlight.alpha` — the dark theme needs a stronger rim to read on a darker backdrop. */
    const val HighlightAlphaLight = 0.10f
    const val HighlightAlphaDark = 0.16f

    /**
     * `BloomStroke.innerBlurRadius`. 2dp is exactly the library's `GlassStrokeMiddleDark` preset,
     * and the glide band of the spec's own bleed; the six library presets are 1.7/2.0/2.3/2.6/2.8/3.5dp.
     */
    val InnerBlurRadius = 2.dp

    /**
     * Primary light. The design canvas uses a centre-origin -1..1 space; the shader normalises
     * `(x - 0.5, y - 0.7, z)`, so a centred top light is (0.5, 0.35). The secondary light is
     * designed at "lower right", which in this space means y *above* the 0.7 origin reference,
     * so it must move to 0.85 rather than staying near 0.5.
     */
    val PrimaryLight = LightPosition(0.5f, 0.35f, -0.5f)
    val SecondaryLight = LightPosition(0.85f, 0.85f, -0.35f)
    const val PrimaryLightIntensity = 1f
    const val SecondaryLightIntensity = 0.35f

    /** Gyroscope parallax, Android only; every other platform keeps the light anchored. */
    const val TiltSensitivity = 0.15f

    /** `noiseCoefficient` for gradient faces. The library default is 0.0045f. */
    const val NoiseCoefficient = 0.03f

    /** Progressive edge band under the app bar (§区3): 52dp of bar is too short for a 0.25→1.0 ramp. */
    val EdgeBandHeight = 96.dp
    const val EdgeBlurRadius = 20f
    val EdgeGradient = ProgressiveBlur(angle = 90f, startFraction = 0.25f, endFraction = 1.0f, curve = 2.0f)

    /**
     * Superellipse curvature. `SquircleDefaults.Extension` is 1.1f (library default, used by every
     * Miuix component); the spec asks for the canvas' cornerSmoothing 0.6, which maps to 1.20f.
     * Radii are untouched — only the corner tile size changes.
     */
    const val SquircleExtension = 1.20f

    /**
     * Card depth. `Miuix Card` exposes only `CardColors` (a single fill), so shadow and border are
     * layered outside it. Light cards cast a 1dp whisper, dark cards need 2dp to separate the
     * `#22262D` surface from the `#0D0F12` page (measured 1.26:1, the floor for "raised").
     */
    val CardShadowLight = 1.dp
    val CardShadowDark = 2.dp
    val CardBorderLight = Color(0xFFEEF0F4)
    val CardBorderDark = Color(0xFF262A31)
    val CardShadowTintLight = Color(0xFF283246)
    val CardShadowTintDark = Color(0xFF000000)
    const val CardShadowAlphaLight = 0.06f
    const val CardShadowAlphaDark = 0.35f

    /** Top-reflectance band on tappable cards; `CardColors` is a single colour, so this is overlay. */
    val SheenHeight = 24.dp
    const val SheenAlpha = 0.06f

    /** Bar tint over the blur. High enough to keep 13sp labels legible, low enough to keep the glass. */
    const val BarTintAlphaLight = 0.62f
    const val BarTintAlphaDark = 0.55f

    const val BarBlurRadius = 24f

    val NavigationRadius = AppUiTokens.NavigationRadius
}

/** True when the resolved theme surface is dark; the single source for every material branch. */
@Composable
@ReadOnlyComposable
fun appDarkSurface(): Boolean = MiuixTheme.colorScheme.surface.luminance() < 0.5f

/**
 * The dual-light edge bloom of the spec: `Highlight(width = 1.dp, alpha = 浅 0.10 / 深 0.16,
 * style = BloomStroke(innerBlurRadius = 2.dp, dualPeak = true, 两个 LightSource))`.
 *
 * Both lights are fed through [rememberTiltLight], so tilting the phone slides the rim highlight
 * across the glass — that is the "dynamic" part of the material, not a static stroke. Platforms
 * without a rotation sensor keep the light anchored at the designed position.
 */
@Composable
fun rememberGlassHighlight(isDark: Boolean = appDarkSurface()): Highlight {
    val primary = rememberTiltLight(
        basePosition = AppGlassTokens.PrimaryLight,
        intensity = AppGlassTokens.PrimaryLightIntensity,
        sensitivity = AppGlassTokens.TiltSensitivity,
    )
    val secondary = rememberTiltLight(
        basePosition = AppGlassTokens.SecondaryLight,
        intensity = AppGlassTokens.SecondaryLightIntensity,
        sensitivity = AppGlassTokens.TiltSensitivity,
    )
    return remember(isDark, primary, secondary) {
        Highlight(
            width = AppGlassTokens.HighlightWidth,
            alpha = if (isDark) AppGlassTokens.HighlightAlphaDark else AppGlassTokens.HighlightAlphaLight,
            style = BloomStroke(
                innerBlurRadius = AppGlassTokens.InnerBlurRadius,
                primaryLight = primary,
                secondaryLight = secondary,
                dualPeak = true,
            ),
        )
    }
}

/**
 * A blurred, edge-lit material for one surface over a captured [backdrop].
 *
 * [tintColor] is folded into the blur's own blend stack rather than painted as a sibling
 * `background(...)`: a separate opaque fill would sit on top of the blur and erase it, which is
 * exactly how the old top bar ended up looking like a flat band.
 *
 * [fallbackColor] is painted *behind* the blur. The effect needs a compilable `RuntimeShader`, and
 * a device or emulator that fails to compile it draws nothing at all — with a transparent caller
 * fill that turns the bar into a black hole rather than a surface. The fallback is the same
 * `surfaceContainer` the bar used before the material existed, so a failed shader degrades to the
 * previous look instead of a visible defect. This is not hypothetical: the API 36 emulator's
 * SwiftShader rejects the blur shader and renders the pill black without it.
 */
@Composable
fun glassMaterial(
    backdrop: Backdrop,
    shape: Shape,
    isDark: Boolean = appDarkSurface(),
    tintColor: Color = MiuixTheme.colorScheme.background,
    tintAlpha: Float = if (isDark) AppGlassTokens.BarTintAlphaDark else AppGlassTokens.BarTintAlphaLight,
    blurRadius: Float = AppGlassTokens.BarBlurRadius,
    fallbackColor: Color = MiuixTheme.colorScheme.surfaceContainer,
    highlight: Highlight? = rememberGlassHighlight(isDark),
): Modifier {
    val colors = remember(tintColor, tintAlpha) {
        BlurColors(blendColors = listOf(BlendColorEntry(tintColor.copy(alpha = tintAlpha), BlurBlendMode.SrcOver)))
    }
    // Chain order is paint order: the fallback fill first, the blur on top of it.
    return Modifier.background(fallbackColor, shape).textureBlur(
        backdrop = backdrop,
        shape = shape,
        blurRadius = blurRadius,
        noiseCoefficient = AppGlassTokens.NoiseCoefficient,
        colors = colors,
        highlight = highlight,
    )
}

/**
 * The gradient band that lets scrolled content dissolve into the bar above it
 * (`progressiveTextureBlur(blurRadius = 20f, gradient = ProgressiveBlur(90f, 0.25f, 1.0f, 2.0f))`).
 *
 * It is a band of its own rather than a parameter on the 52dp bar: the ramp needs 80~120dp of
 * height to read, and the library notes this pass costs a full-resolution overlay per frame, so
 * it must stay on a short edge band and never on a large fill.
 *
 * [tintColor]/[tintAlpha] must match the bar's own tint. The band paints on top of the bar's
 * bottom edge, and without the tint it reads as a lighter stripe glued under the bar instead of a
 * continuation of it — the library fades these colours out together with the blur, which is what
 * makes the seam disappear. Without a tint the band is a visible tone step, not a fade.
 */
@Composable
fun Modifier.progressiveGlassEdge(
    backdrop: Backdrop,
    shape: Shape,
    gradient: ProgressiveBlur = AppGlassTokens.EdgeGradient,
    blurRadius: Float = AppGlassTokens.EdgeBlurRadius,
    tintColor: Color = MiuixTheme.colorScheme.background,
    tintAlpha: Float = if (appDarkSurface()) AppGlassTokens.BarTintAlphaDark else AppGlassTokens.BarTintAlphaLight,
): Modifier {
    val colors = remember(tintColor, tintAlpha) {
        BlurColors(blendColors = listOf(BlendColorEntry(tintColor.copy(alpha = tintAlpha), BlurBlendMode.SrcOver)))
    }
    return this.progressiveTextureBlur(
        backdrop = backdrop,
        shape = shape,
        blurRadius = blurRadius,
        gradient = gradient,
        noiseCoefficient = 0f,
        colors = colors,
    )
}

/** Squircle clip at the spec's 1.20f curvature, falling back to a rounded rect without shaders. */
@Composable
fun Modifier.appSquircleClip(cornerRadius: Dp, extension: Float = AppGlassTokens.SquircleExtension): Modifier =
    this.squircleClip(cornerRadius = cornerRadius, extension = extension)

/**
 * Card depth: the drop shadow and the 1dp rim a `Miuix Card` cannot carry itself.
 *
 * Chain it as the *last* modifier so the rim paints above the card's own fill — `squircleBorder`
 * draws behind its node's content and would be hidden by the opaque surface.
 *
 * `Modifier.shadow` only accepts a Compose [Shape], while the rim is a MIUIX path; the two are
 * reconciled by using the same radius and letting [AppGlassTokens.SquircleExtension] nudge the
 * path's corner tiles, which is invisible at a 1dp stroke.
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
 * their own shadow (the brand mark, the press-feedback rows) and only need the edge light.
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
 * The top reflectance a tappable card gets in the spec (white 6% → transparent over 24dp).
 * Painted as an overlay because [top.yukonga.miuix.kmp.basic.CardColors] holds one colour only.
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
 * The one card every screen uses, carrying the rc10 material: squircle curvature, a 1dp rim and
 * the themed depth shadow. `Miuix Card` cannot express any of that itself — it takes only a
 * single fill — so the material is layered around it here instead of at 52 separate call sites.
 *
 * [sheen] adds the top reflectance the spec gives tappable rows. It has to be painted *inside*
 * the card, behind the content: 6% white over the first text line would cost contrast, which the
 * spec forbids. That is why this wraps the content rather than decorating the outside.
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
        // Deliberately no fillMaxWidth()/insideMargin default of its own: this has to stay a
        // drop-in for Miuix Card at every existing call site, adding only the material.
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
