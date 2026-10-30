package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

fun JsonElement.field(name: String): JsonElement = (this as? JsonObject)?.get(name) ?: JsonNull
fun JsonElement.text(name: String): String = (field(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonElement.rows(name: String): List<JsonElement> = (field(name) as? JsonArray)?.toList().orEmpty()
fun JsonElement.stringOrEmpty(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

/** Replay is per request key so a later result cannot erase an unrelated result. */
class NavigationResults {
    private val channels = mutableMapOf<String, MutableSharedFlow<String>>()
    fun observe(key: String) = channel(key).asSharedFlow()
    suspend fun deliver(key: String, value: String) { channel(key).emit(value) }
    fun consume(key: String) { channels.remove(key) }
    private fun channel(key: String) = channels.getOrPut(key) { MutableSharedFlow(replay = 1) }
}

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
internal fun navigationOverlayInset(padding: PaddingValues, barVisible: Boolean): Dp =
    if (barVisible) padding.calculateBottomPadding().coerceAtLeast(0.dp) else 0.dp

@Composable
fun Screen(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    val behavior = MiuixScrollBehavior()
    val backdrop = rememberLayerBackdrop()
    val barModifier = Modifier.textureBlur(
        backdrop = backdrop,
        shape = RoundedCornerShape(0.dp),
        colors = BlurColors(blendColors = listOf(BlendColorEntry(MiuixTheme.colorScheme.background.copy(alpha = 0.88f), BlurBlendMode.SrcOver))),
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
                TopAppBar(title = title, color = MiuixTheme.colorScheme.surface, modifier = barModifier,
                    titlePadding = AppUiTokens.PagePadding, scrollBehavior = behavior, actions = actions)
            } else {
                SmallTopAppBar(title = title, color = MiuixTheme.colorScheme.surface, modifier = barModifier,
                    scrollBehavior = behavior,
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(BackChevron, contentDescription = AppText.Common.BACK, tint = MiuixTheme.colorScheme.onSurface)
                        }
                    }, actions = actions)
            }
        },
    ) { padding ->
        // Capture the actual opaque page, not an empty parent or a static tint rectangle.
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(MiuixTheme.colorScheme.surface)) {
            content(padding)
        }
    }
}

internal fun pageContentPadding(padding: PaddingValues, overlayInset: Dp, direction: LayoutDirection, horizontal: Dp = AppUiTokens.PagePadding): PaddingValues = PaddingValues(
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding),
        onClick = onClick,
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
fun AsyncContent(key: Any?, load: suspend () -> ApiResult<JsonElement>, content: @Composable (JsonElement) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val loader by rememberUpdatedState(load)
    val result by produceState<ApiResult<JsonElement>?>(null, key, attempt) { value = null; value = loader() }
    when (val state = result) {
        null -> Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(AppText.Common.LOADING, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        is ApiResult.Failure -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoBanner(state.error.message, title = AppText.Common.LOAD_FAILED, tone = UiTone.Warning)
            TextButton(text = AppText.Common.RETRY, onClick = { attempt++ })
        }
        is ApiResult.Success -> content(state.data)
    }
}

@Composable
fun EmptyCard(message: String = AppText.Common.NO_CONTENT) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(horizontal = 20.dp, vertical = 28.dp)) {
        Text(message, style = MiuixTheme.textStyles.title4)
        Spacer(Modifier.height(6.dp))
        Text(AppText.Common.REFRESH_LATER_HINT, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
