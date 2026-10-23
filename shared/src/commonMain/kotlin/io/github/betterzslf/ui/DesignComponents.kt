package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.darkColorScheme

internal val BackChevron: ImageVector = ImageVector.Builder(
    name = "Back", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f, autoMirror = true,
).apply {
    path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(15f, 18f); lineTo(9f, 12f); lineTo(15f, 6f)
    }
}.build()

@Composable
fun AppBrandMark(modifier: Modifier = Modifier, size: Dp = 84.dp) {
    Box(
        modifier.size(size)
            .shadow(10.dp, RoundedCornerShape(24.dp), ambientColor = AppUiTokens.Primary.copy(alpha = 0.18f), spotColor = AppUiTokens.Primary.copy(alpha = 0.18f))
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF599EFF), Color(0xFF266BF2)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(MiuixIcons.Home, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
    }
}

/** Shared dimensions from the final 390dp Ardot layouts; native insets are applied separately. */
object AppUiTokens {
    val PagePadding = 20.dp
    val FormPadding = 24.dp
    val CardPadding = 16.dp
    val Gap = 12.dp
    val SectionGap = 20.dp
    val CardRadius = 16.dp
    val FieldRadius = 14.dp
    val ButtonRadius = 26.dp
    val ButtonHeight = 52.dp
    val NavigationRadius = 30.dp
    val Primary = Color(0xFF3382FF)
    val PrimaryDark = Color(0xFF5C94FF)
    val Background = Color(0xFFF2F2F5)
    val BackgroundDark = Color(0xFF0D0F12)
    val Card = Color.White
    val CardDark = Color(0xFF1A1C21)
    val Positive = Color(0xFF33C759)
    val PositiveDark = Color(0xFF4DD466)
    val Warning = Color(0xFFFF9400)
    val WarningDark = Color(0xFFFFA11A)
    val Negative = Color(0xFFFA3B3B)
    val NegativeDark = Color(0xFFFF4D59)
}

fun appRefreshTexts(): List<String> = listOf(
    AppText.Common.PULL_TO_REFRESH,
    AppText.Common.RELEASE_TO_REFRESH,
    AppText.Common.REFRESHING,
    "", // Completion is not evidence of a successful request; the owning state reports errors.
)

enum class UiTone { Info, Positive, Warning, Negative, Neutral }

@Composable
private fun UiTone.color(): Color {
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    return when (this) {
        UiTone.Info -> MiuixTheme.colorScheme.primary
        UiTone.Positive -> if (dark) AppUiTokens.PositiveDark else AppUiTokens.Positive
        UiTone.Warning -> if (dark) AppUiTokens.WarningDark else AppUiTokens.Warning
        UiTone.Negative -> if (dark) AppUiTokens.NegativeDark else AppUiTokens.Negative
        UiTone.Neutral -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
}

/** A concise, in-page status. Technical details stay in the owning feature's expanded content. */
@Composable
fun InfoBanner(
    message: String,
    title: String = "",
    tone: UiTone = UiTone.Info,
    modifier: Modifier = Modifier,
) {
    val accent = tone.color()
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(AppUiTokens.FieldRadius))
            .background(accent.copy(alpha = 0.10f))
            .animateContentSize()
            .padding(AppUiTokens.CardPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (title.isNotBlank()) Text(title, color = accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        if (message.isNotBlank()) Text(message, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

/** Labels and values are explicit so callers never accidentally invert an untyped pair. */
data class UiMetric(val value: String, val label: String)

@Composable
fun MetricStrip(metrics: List<UiMetric>, modifier: Modifier = Modifier) {
    if (metrics.isEmpty()) return
    Card(modifier = modifier.fillMaxWidth(), insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
            metrics.forEach { metric ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(metric.value, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                    Text(metric.label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        }
    }
}

/** The primary action uses Miuix's disabled/content colors and native squircle press behavior. */
@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = AppUiTokens.ButtonHeight),
        minHeight = AppUiTokens.ButtonHeight,
        cornerRadius = AppUiTokens.ButtonRadius,
        colors = ButtonDefaults.buttonColorsPrimary(),
        insideMargin = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

fun ardotTextStyles() = defaultTextStyles(
    title1 = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    title2 = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    title3 = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    title4 = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
)

/** The final Ardot tokens remain the default; Monet is an explicit user opt-in. */
fun ardotLightColors(): Colors = lightColorScheme(
    primary = AppUiTokens.Primary,
    primaryVariant = AppUiTokens.Primary,
    secondaryContainer = AppUiTokens.Card,
    onSecondaryContainer = Color(0xFF757578),
    primaryContainer = Color(0xFFE3EDFF),
    onPrimaryContainer = AppUiTokens.Primary,
    tertiaryContainer = Color(0xFFE3EDFF),
    onTertiaryContainer = AppUiTokens.Primary,
    background = AppUiTokens.Card,
    onBackground = Color(0xFF1A1A1A),
    surface = AppUiTokens.Background,
    surfaceVariant = AppUiTokens.Card,
    surfaceContainer = AppUiTokens.Card,
    onSurface = Color(0xFF1A1A1A),
    onSurfaceContainer = Color(0xFF1A1A1A),
    onSurfaceVariantSummary = Color(0xFF757578),
    onSurfaceVariantActions = Color(0xFF757578),
    onSurfaceContainerVariant = Color(0xFF757578),
    outline = Color(0xFFE5E5EB),
    dividerLine = Color(0xFFE5E5EB),
    error = AppUiTokens.Negative,
)

fun ardotDarkColors(): Colors = darkColorScheme(
    primary = AppUiTokens.PrimaryDark,
    primaryVariant = AppUiTokens.PrimaryDark,
    secondaryContainer = AppUiTokens.CardDark,
    onSecondaryContainer = Color(0xFF99A1A8),
    primaryContainer = Color(0xFF1F2940),
    onPrimaryContainer = AppUiTokens.PrimaryDark,
    tertiaryContainer = Color(0xFF1F2940),
    onTertiaryContainer = AppUiTokens.PrimaryDark,
    background = AppUiTokens.CardDark,
    onBackground = Color(0xFFF2F2F5),
    surface = AppUiTokens.BackgroundDark,
    surfaceVariant = AppUiTokens.CardDark,
    surfaceContainer = AppUiTokens.CardDark,
    onSurface = Color(0xFFF2F2F5),
    onSurfaceContainer = Color(0xFFF2F2F5),
    onSurfaceVariantSummary = Color(0xFF99A1A8),
    onSurfaceVariantActions = Color(0xFF99A1A8),
    onSurfaceContainerVariant = Color(0xFF99A1A8),
    outline = Color(0xFF292E36),
    dividerLine = Color(0xFF292E36),
    error = Color(0xFFFF4D59),
)

@Composable
fun SectionTitle(title: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) TextButton(text = actionLabel, onClick = onAction)
    }
}
