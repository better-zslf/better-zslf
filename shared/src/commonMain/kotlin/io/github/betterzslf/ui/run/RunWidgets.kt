package io.github.betterzslf.ui.run

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.ui.*
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Run-page primitives use the same actual Miuix buttons/cards as the shared shell. */
@Composable
internal fun RunAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = AppUiTokens.ButtonHeight), enabled = enabled,
        cornerRadius = AppUiTokens.ButtonRadius,
        colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors()) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun RunCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth().animateContentSize(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding), content = content)
}

@Composable
internal fun RunStatus(text: String, tone: UiTone = UiTone.Neutral) {
    val color = when (tone) {
        UiTone.Positive -> AppUiTokens.Positive
        UiTone.Warning -> AppUiTokens.Warning
        UiTone.Negative -> AppUiTokens.Negative
        UiTone.Info -> MiuixTheme.colorScheme.primary
        UiTone.Neutral -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Text(text, modifier = Modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(20.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        color = color, fontSize = 12.sp)
}

/** Debug/server fields stay reachable without becoming the primary page content. */
@Composable
internal fun RunDisclosure(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember(title) { mutableStateOf(false) }
    RunCard {
        TextButton(title, onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(vertical = 6.dp, horizontal = 0.dp))
        if (expanded) Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
internal fun RunMetricGrid(metrics: List<UiMetric>) {
    Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
        metrics.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                row.forEach { metric ->
                    RunCard(Modifier.weight(1f)) {
                        Text(metric.value, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(metric.label, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun RunFileGlyph() {
    val color = MiuixTheme.colorScheme.primary
    Box(Modifier.size(64.dp).background(color.copy(alpha = 0.08f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(34.dp)) {
            val stroke = 2.dp.toPx()
            drawRoundRect(color, Offset(size.width * .12f, size.height * .18f), Size(size.width * .76f, size.height * .68f), CornerRadius(4.dp.toPx()), style = Stroke(stroke))
            drawLine(color, Offset(size.width * .5f, size.height * .32f), Offset(size.width * .5f, size.height * .68f), stroke)
            drawLine(color, Offset(size.width * .34f, size.height * .50f), Offset(size.width * .5f, size.height * .68f), stroke)
            drawLine(color, Offset(size.width * .66f, size.height * .50f), Offset(size.width * .5f, size.height * .68f), stroke)
        }
    }
}

@Composable
internal fun RunEmpty(title: String, message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    RunCard {
        Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(message, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (action != null && onAction != null) RunAction(action, onAction, primary = true)
        }
    }
}
