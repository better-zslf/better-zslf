package io.github.betterzslf.feature.services.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.betterzslf.feature.services.presentation.*
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.PageList
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionTitle
import io.github.betterzslf.ui.EmptyCard
import io.github.betterzslf.ui.appSquircleClip
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.betterzslf.ui.AppCard

/** Pure catalog content: no repositories, graph, navigation types, permissions or edition flags. */
@Composable
fun ServicesContent(state: ServicesUiState, onEvent: (ServicesEvent) -> Unit) {
    // Past a dozen entries, scanning section headings is slower than typing two characters.
    // The filter itself lives in ServicesUiState so it is testable without a UI host.
    var query by remember { mutableStateOf("") }
    val visible = remember(state, query) { state.filtered(query) }
    Screen(state.title) { padding ->
        PageList(padding) {
            item("services-search") {
                TextField(value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), label = state.searchLabel,
                    singleLine = true, cornerRadius = AppUiTokens.FieldRadius)
            }
            if (query.isBlank()) {
                item("services-intro") {
                    Text(state.introduction, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            if (visible.isEmptyCatalog()) {
                item("services-search-empty") { EmptyCard(state.searchEmptyLabel) }
            }
            visible.sections.forEach { section ->
                item("section:${section.id}:title") { SectionTitle(section.title) }
                item("section:${section.id}:entries") {
                    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            section.entries.forEach { entry ->
                                ArrowPreference(title = entry.title, summary = entry.summary,
                                    enabled = entry.enabled,
                                    onClick = { visible.eventFor(entry.id)?.let(onEvent) },
                                    startAction = {
                                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                                            Box(Modifier.size(12.dp).appSquircleClip(6.dp).background(entry.accent.color()))
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
