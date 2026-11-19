package io.github.betterzslf.feature.services.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.betterzslf.feature.services.presentation.*
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.PageList
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionTitle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Pure catalog content: no repositories, graph, navigation types, permissions or edition flags. */
@Composable
fun ServicesContent(state: ServicesUiState, onEvent: (ServicesEvent) -> Unit) {
    Screen(state.title) { padding ->
        PageList(padding) {
            item("services-intro") {
                Text(state.introduction, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            state.sections.forEach { section ->
                item("section:${section.id}:title") { SectionTitle(section.title) }
                item("section:${section.id}:entries") {
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            section.entries.forEach { entry ->
                                ArrowPreference(title = entry.title, summary = entry.summary,
                                    enabled = entry.enabled,
                                    onClick = { state.eventFor(entry.id)?.let(onEvent) },
                                    startAction = {
                                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(entry.accent.color()))
                                        }
                                    })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun ServiceAccent.color(): Color = when (this) {
    ServiceAccent.Primary -> Color(0xFF3382FF)
    ServiceAccent.Positive -> Color(0xFF32C75A)
    ServiceAccent.Warning -> Color(0xFFFF9400)
    ServiceAccent.Secondary -> Color(0xFF5C94FF)
    ServiceAccent.Neutral -> AppUiTokens.NeutralDot
}
