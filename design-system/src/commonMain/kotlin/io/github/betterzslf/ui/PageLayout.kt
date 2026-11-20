package io.github.betterzslf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.text.AppText
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

/** The floating tab bar overlays content; only scroll content reserves its measured height. */
val LocalNavigationBottomInset = staticCompositionLocalOf { 0.dp }

/**
 * A press target without a ripple, for regions the design draws as flat fills (the week pill,
 * the day strip). It keeps the click semantics so screen readers and keyboards still work.
 */
@Composable
fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this
    .clickable(role = androidx.compose.ui.semantics.Role.Button, indication = null,
        interactionSource = remember { MutableInteractionSource() },
        onClick = onClick)

/** Scaffold supplies the complete bottomBar measurement, including all outside/safe-area padding. */
fun navigationOverlayInset(padding: PaddingValues, barVisible: Boolean): Dp =
    if (barVisible) padding.calculateBottomPadding().coerceAtLeast(0.dp) else 0.dp

@Composable
fun Screen(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    val behavior = MiuixScrollBehavior()
    val backdrop = rememberLayerBackdrop()
    val isDark = appDarkSurface()
    // Ardot 24:1 ③: the bar is a real glass surface with the dual-light rim, not a flat tint.
    // `highlight` is what gives the edge its light; without it the blur alone reads as a dull band.
    // The bar's own fill must be transparent or it paints over the blur, so the material carries
    // the opaque fallback a device without a compilable runtime shader needs.
    val barModifier = glassMaterial(
        backdrop = backdrop,
        shape = RoundedCornerShape(0.dp),
        isDark = isDark,
    )
    Scaffold(
        // Scroll/blur may extend behind the toolbar, never behind the OS status area. Applying
        // and consuming the top inset here also prevents nested top bars from adding it twice.
        modifier = Modifier.background(MiuixTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top))
            .nestedScroll(behavior.nestedScrollConnection),
        containerColor = MiuixTheme.colorScheme.surface,
        topBar = {
            if (onBack == null) {
                TopAppBar(title = title, color = Color.Transparent, modifier = barModifier,
                    titlePadding = AppUiTokens.PagePadding, scrollBehavior = behavior, actions = actions)
            } else {
                SmallTopAppBar(title = title, color = Color.Transparent, modifier = barModifier,
                    scrollBehavior = behavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(BackChevron, contentDescription = AppText.Common.BACK, tint = MiuixTheme.colorScheme.onSurface)
                        }
                    }, actions = actions)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // Capture the actual opaque page, not an empty parent or a static tint rectangle.
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(MiuixTheme.colorScheme.surface)) {
                content(padding)
            }
            // The ramp needs ~96dp to read; hanging it on the 52dp bar would turn it into a hard
            // edge. A sibling of the captured box, not a child: recording the band into the same
            // layer it samples would feed its own blur result back in on the next frame.
            Box(
                Modifier.fillMaxWidth().height(AppGlassTokens.EdgeBandHeight)
                    .progressiveGlassEdge(backdrop, shape = RoundedCornerShape(0.dp), tintColor = MiuixTheme.colorScheme.background),
            )
        }
    }
}

/**
 * Insets for scroll content below [Screen] or a product shell. The measured floating navigation
 * overlay and scaffold bottom inset describe the same occupied edge, so only the larger wins.
 */
fun pageContentPadding(padding: PaddingValues, overlayInset: Dp, direction: LayoutDirection, horizontal: Dp = AppUiTokens.PagePadding): PaddingValues = PaddingValues(
    start = padding.calculateStartPadding(direction) + horizontal,
    end = padding.calculateEndPadding(direction) + horizontal,
    top = padding.calculateTopPadding() + AppUiTokens.Gap,
    // The floating pill covers the bottom of the viewport, so the last row needs its measured
    // height plus breathing room; without the measured value the tail row sits under the bar.
    bottom = maxOf(padding.calculateBottomPadding(), overlayInset) + AppUiTokens.SectionGap,
)

@Composable
fun PageList(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    val overlayInset = LocalNavigationBottomInset.current
    val direction = LocalLayoutDirection.current
    // Insets live in contentPadding: scrolled cards really pass under the Miuix blur app bar.
    LazyColumn(
        modifier = Modifier.fillMaxSize().consumeWindowInsets(padding).imePadding(),
        contentPadding = pageContentPadding(padding, overlayInset, direction),
        verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap),
        content = content,
    )
}

@Composable
fun SectionCard(title: String, summary: String = "", onClick: (() -> Unit)? = null) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding),
        onClick = onClick,
        sheen = onClick != null,
        pressFeedbackType = if (onClick != null) PressFeedbackType.Sink else PressFeedbackType.None,
        showIndication = onClick != null,
    ) {
        Text(title, style = MiuixTheme.textStyles.title4)
        if (summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(summary, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
fun EmptyCard(message: String = AppText.Common.NO_CONTENT) {
    AppCard(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 20.dp, vertical = 28.dp)) {
        Text(message, style = MiuixTheme.textStyles.title4)
        Spacer(Modifier.height(6.dp))
        Text(AppText.Common.REFRESH_LATER_HINT, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
