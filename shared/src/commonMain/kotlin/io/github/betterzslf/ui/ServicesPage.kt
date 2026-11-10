package io.github.betterzslf.ui

import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.betterzslf.edition.EditionAccess
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Ardot P46 服务 (44:298): a grouped service directory. Three sections — 校园生活 / 教务与学习 /
 * 应用 — each a rounded card of arrow rows with a coloured leading dot. Every entry maps to an
 * existing in-app route; 校园跑 (P03) and 宿舍热水器 (P31, Pro) are reachable here instead of
 * from a tab. The intro states the design's own rule: unwired services are marked and the page
 * never opens an external link automatically.
 */
@Composable
fun ServicesPage(graph: AppGraph, open: (Route) -> Unit) {
    val waterEnabled = EditionAccess.waterEnabled
    Screen(AppText.Services.TITLE) { padding ->
        PageList(padding) {
            item("services-intro") {
                Text(AppText.Services.INTRO, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            item("services-campus-title") { SectionTitle(AppText.Services.GROUP_CAMPUS_LIFE) }
            item("services-campus") {
                ServiceGroup {
                    ServiceRow(Color(0xFF3382FF), AppText.Services.CAMPUS_RUN) {
                        open(Route.Page("runs", title = AppText.Navigation.CAMPUS_RUN))
                    }
                    ServiceRow(Color(0xFF32C75A), AppText.Services.DORM_WATER,
                        summary = if (waterEnabled) null else AppText.Content.UNLINKED_SERVICE_NO_AUTOMATIC_NAVIGATION_HINT,
                        enabled = waterEnabled) {
                        if (waterEnabled) open(Route.Page("water"))
                    }
                    ServiceRow(Color(0xFFFF9400), AppText.Services.DORM_NETWORK_LOGIN) {
                        open(Route.Page("dormnetwork", title = AppText.Auth.ENTRY_DORM_LINK))
                    }
                }
            }
            item("services-academic-title") { SectionTitle(AppText.Services.GROUP_ACADEMIC) }
            item("services-academic") {
                ServiceGroup {
                    ServiceRow(Color(0xFF5C94FF), AppText.Services.ACADEMIC_MANAGE) {
                        open(Route.Page("schedulemanage", title = AppText.Schedule.MANAGE))
                    }
                    ServiceRow(Color(0xFF5C94FF), AppText.Services.PERIOD_CALIBRATION) {
                        open(Route.Page("scheduleperiods", title = AppText.Schedule.PERIODS_TITLE))
                    }
                }
            }
            item("services-app-title") { SectionTitle(AppText.Services.GROUP_APP) }
            item("services-app") {
                ServiceGroup {
                    ServiceRow(AppUiTokens.NeutralDot, AppText.Services.APP_UPDATE) {
                        open(Route.Page("updates", title = AppText.Updates.VERSION_UPDATE_TITLE))
                    }
                    ServiceRow(AppUiTokens.NeutralDot, AppText.Services.ABOUT_APP) {
                        open(Route.Page("about", title = AppText.App.ABOUT_APP_TITLE))
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceGroup(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
    }
}

@Composable
private fun ColumnScope.ServiceRow(dot: Color, title: String, summary: String? = null,
                                   enabled: Boolean = true, onClick: () -> Unit) {
    ArrowPreference(
        title = title,
        summary = summary,
        enabled = enabled,
        onClick = onClick,
        // Miuix draws its own arrow; the coloured dot is the design's leading marker.
        startAction = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(dot))
            }
        },
    )
}
