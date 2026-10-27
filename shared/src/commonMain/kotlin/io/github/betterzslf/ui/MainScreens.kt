package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.edition.EditionAccess
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
fun HomeScreen(graph: AppGraph, open: (Route) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val session by graph.sessions.session.collectAsState()
    val name = session?.userInfo?.text("name").orEmpty()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    Screen(AppText.Navigation.greeting(name), actions = {
        IconButton(onClick = { open(Route.Page("notices", title = AppText.Content.MESSAGES_TITLE)) }) {
            Icon(MiuixIcons.Messages, contentDescription = AppText.Content.MESSAGES_TITLE, tint = MiuixTheme.colorScheme.onSurface)
        }
    }) { padding ->
        PageList(padding) {
            item { Text(AppText.App.CAMPUS_TODAY_HINT, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
            item {
                AsyncContent(session?.token to refresh, { graph.repositories.notices.unread() }) { data ->
                    val values = listOf(data.text("unreadNumber").toIntOrNull(), data.text("noticeMutualCount").toIntOrNull())
                    val count = if (values.all { it != null && it >= 0 }) values.filterNotNull().sum() else null
                    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.FieldRadius,
                        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primary.copy(alpha = 0.09f)),
                        insideMargin = PaddingValues(14.dp), pressFeedbackType = PressFeedbackType.Sink,
                        onClick = { open(Route.Page("notices", title = AppText.Content.ANNOUNCEMENTS_AND_NOTIFICATIONS_TITLE)) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(MiuixIcons.Messages, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Text(if (count == null) AppText.Content.ANNOUNCEMENTS_AND_NOTIFICATIONS_TITLE
                                else if (count == 0) AppText.Content.NO_UNREAD_MESSAGES else AppText.Navigation.unreadCount(count),
                                fontSize = 13.sp, color = MiuixTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                            Icon(MiuixIcons.ChevronForward, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            item { HomeQuickGrid(graph, open) }
            item { SectionTitle(AppText.Content.CAMPUS_ACTIVITY_TITLE, AppText.Common.MORE, {
                open(Route.Page("columns", "学校动态", AppText.Content.SCHOOL_NEWS_DISPLAY_LABEL))
            }) }
            item {
                AsyncContent(session?.token to refresh, { graph.repositories.content.columns("学校动态") }) { columns ->
                    val columnId = columns.field("cmsColumn").text("id")
                    if (columnId.isBlank()) EmptyCard() else {
                        AsyncContent(Triple(session?.token, columnId, refresh), { graph.repositories.content.articles(columnId, limit = 5) }) { data ->
                            ArticleCards(data.rows("cmsArticleList"), open)
                        }
                    }
                }
            }
            item { TextButton(AppText.Common.REFRESH, onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

private data class HomeShortcut(val title: String, val icon: ImageVector, val color: Color, val route: Route.Page)

@Composable
private fun HomeQuickGrid(graph: AppGraph, open: (Route) -> Unit) {
    val entries = listOf(
        HomeShortcut(AppText.Navigation.CAMPUS_RUN, MiuixIcons.Stopwatch, AppUiTokens.Primary, Route.Page("runs", title = AppText.Navigation.CAMPUS_RUN)),
        HomeShortcut(AppText.Booking.VENUE_BOOKING_TITLE, MiuixIcons.Store, Color(0xFFFF9400), Route.Page("venues", title = AppText.Booking.VENUE_BOOKING_TITLE)),
        HomeShortcut(AppText.Booking.FITNESS_REGISTRATION_TITLE, MiuixIcons.Tasks, Color(0xFF33B878), Route.Page("fitness", title = AppText.Booking.FITNESS_REGISTRATION_TITLE)),
        HomeShortcut(AppText.CampusNetwork.CAMPUS_NETWORK_TITLE, MiuixIcons.Carrier, Color(0xFF655BD6), Route.Page("campusnet", title = AppText.CampusNetwork.CAMPUS_NETWORK_TITLE)),
        HomeShortcut(AppText.Content.CAMPUS_DISCOVERY_TITLE, MiuixIcons.GridView, Color(0xFF16A3A9), Route.Page("discover", title = AppText.Content.CAMPUS_DISCOVERY_TITLE)),
        HomeShortcut(AppText.Booking.MY_BOOKINGS_TITLE, MiuixIcons.Notes, Color(0xFF4B85D4), Route.Page("myvenues", title = AppText.Booking.MY_BOOKINGS_TITLE)),
        HomeShortcut(AppText.Common.MORE, MiuixIcons.More, Color(0xFF7B7E89), Route.Page("community", title = AppText.Navigation.COMMUNITY)),
    )
    val hasEditionEntry = EditionAccess.waterEnabled
    val total = entries.size + if (hasEditionEntry) 1 else 0
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat((total + 3) / 4) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(4) { column ->
                    val cell = row * 4 + column
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        when {
                            cell >= total -> Spacer(Modifier.fillMaxWidth())
                            hasEditionEntry && cell == 3 -> graph.editionFeatures.HomeEntry(open)
                            else -> {
                                val index = cell - if (hasEditionEntry && cell > 3) 1 else 0
                                val entry = entries[index]
                                CampusShortcut(entry.title, entry.icon, entry.color, onClick = { open(entry.route) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Also used by the selected edition facade; no Pro implementation type leaks into common UI. */
@Composable
fun CampusShortcut(title: String, icon: ImageVector, accent: Color, supportingText: String = "", onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        colors = CardDefaults.defaultColors(color = Color.Transparent), insideMargin = PaddingValues(vertical = 4.dp),
        pressFeedbackType = PressFeedbackType.Sink, onClick = onClick) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(27.dp))
            }
            Text(title, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (supportingText.isNotBlank()) Text(supportingText, fontSize = 11.sp, color = MiuixTheme.colorScheme.primary,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun CommunityScreen(graph: AppGraph, open: (Route) -> Unit) = CommunityScreen(graph, open, null)

@Composable
fun CommunityScreen(graph: AppGraph, open: (Route) -> Unit, back: (() -> Unit)?) {
    Screen(AppText.Navigation.COMMUNITY, onBack = back) { padding ->
        PageList(padding) {
            item { CommunityEntry(AppText.Content.CAMPUS_DISCOVERY_TITLE, AppText.Content.SERVICES_STUDY_AND_CAMPUS_LIFE_SUMMARY,
                MiuixIcons.GridView, Color(0xFF16A3A9)) { open(Route.Page("discover", title = AppText.Content.CAMPUS_DISCOVERY_TITLE)) } }
            item { CommunityEntry(AppText.Content.ANNOUNCEMENTS_AND_NOTIFICATIONS_TITLE, AppText.Content.MESSAGES_AND_NOTIFICATIONS_TITLE,
                MiuixIcons.Messages, AppUiTokens.Primary) { open(Route.Page("notices", title = AppText.Content.ANNOUNCEMENTS_AND_NOTIFICATIONS_TITLE)) } }
            item { CommunityEntry(AppText.Booking.VENUE_BOOKING_TITLE, AppText.Booking.VIEW_OPEN_VENUES,
                MiuixIcons.Store, Color(0xFFFF9400)) { open(Route.Page("venues", title = AppText.Booking.VENUE_BOOKING_TITLE)) } }
            item { CommunityEntry(AppText.Booking.FITNESS_REGISTRATION_TITLE, AppText.Booking.FITNESS_SESSIONS_AND_ITEMS_SUMMARY,
                MiuixIcons.Tasks, Color(0xFF33B878)) { open(Route.Page("fitness", title = AppText.Booking.FITNESS_REGISTRATION_TITLE)) } }
            item { CommunityEntry(AppText.Content.BROWSING_HISTORY_TITLE, "", MiuixIcons.Recent, Color(0xFF655BD6)) {
                open(Route.Page("history", title = AppText.Content.BROWSING_HISTORY_TITLE)) } }
            item { CommunityEntry(AppText.App.HELP_AND_FEEDBACK_SECTION_TITLE, AppText.App.GITHUB_FEEDBACK_SUMMARY,
                MiuixIcons.Help, Color(0xFF7B7E89)) { open(Route.Page("feedback", title = AppText.App.FEEDBACK_TITLE)) } }
            item { SectionTitle(AppText.Content.NEWS_SECTION_TITLE) }
            item { Card(insideMargin = PaddingValues(0.dp), modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius) {
                ArrowPreference(AppText.Content.SCHOOL_NEWS_DISPLAY_LABEL, onClick = { open(Route.Page("columns", "学校动态", AppText.Content.SCHOOL_NEWS_DISPLAY_LABEL)) })
                ArrowPreference(AppText.Content.FRESHMAN_GUIDE_DISPLAY_LABEL, onClick = { open(Route.Page("columns", "新生攻略", AppText.Content.FRESHMAN_GUIDE_DISPLAY_LABEL)) })
                ArrowPreference(AppText.Booking.MY_BOOKINGS_TITLE, onClick = { open(Route.Page("myvenues", title = AppText.Booking.MY_BOOKINGS_TITLE)) })
                ArrowPreference(AppText.App.USER_GUIDE_DISPLAY_LABEL, onClick = { open(Route.Page("document", "使用帮助", AppText.App.USER_GUIDE_DISPLAY_LABEL)) })
            } }
        }
    }
}

@Composable
private fun CommunityEntry(title: String, summary: String, icon: ImageVector, accent: Color, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(16.dp),
        onClick = onClick, pressFeedbackType = PressFeedbackType.Sink, showIndication = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(accent.copy(alpha = 0.11f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(25.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                if (summary.isNotBlank()) Text(summary, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Icon(MiuixIcons.ChevronForward, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun ProfileScreen(graph: AppGraph, open: (Route) -> Unit) = io.github.betterzslf.ui.profile.ProfileFeatureScreen(graph, open)

@Composable
fun ArticleCards(rows: List<JsonElement>, open: (Route) -> Unit) {
    if (rows.isEmpty()) { EmptyCard(); return }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { article ->
            val id = article.text("id")
            val title = article.text("title").ifBlank { AppText.Common.UNTITLED }
            val description = article.text("description").replace(Regex("<[^>]*>"), "").trim()
            val date = article.text("createTime").ifBlank { article.text("publishTime") }
            Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(16.dp),
                onClick = if (id.isBlank()) null else ({ open(Route.Page("article", id, title)) }),
                pressFeedbackType = PressFeedbackType.Sink, showIndication = id.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(MiuixTheme.colorScheme.primary.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                        Icon(MiuixIcons.Notes, contentDescription = null, tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(27.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (description.isNotBlank()) Text(description, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (date.isNotBlank()) Text(date, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}
