package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.edition.EditionAccess
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
fun HomeScreen(graph: AppGraph, open: (Route) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    Screen(AppText.Navigation.HOME, actions = { TextButton(AppText.Content.MESSAGES_TITLE, onClick = { open(Route.Page("notices", title = AppText.Content.MESSAGES_TITLE)) }) }) { padding ->
        PageList(padding) {
            item { SectionCard(AppText.App.CAMPUS_LIFE_WELCOME_TITLE, AppText.App.LESS_WAITING_MORE_FREEDOM_TAGLINE) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionCard(AppText.Navigation.CAMPUS_RUN, AppText.Run.TASKS_AND_ACTIVITY_RECORDS, { open(Route.Page("runs", title = AppText.Navigation.CAMPUS_RUN)) })
                        SectionCard(AppText.Booking.VENUE_BOOKING_TITLE, AppText.Booking.VIEW_OPEN_VENUES, { open(Route.Page("venues", title = AppText.Booking.VENUE_BOOKING_TITLE)) })
                        SectionCard(AppText.CampusNetwork.CAMPUS_NETWORK_TITLE, AppText.CampusNetwork.BROADBAND_ACCOUNT_QUERY_LABEL, { open(Route.Page("campusnet", title = AppText.CampusNetwork.CAMPUS_NETWORK_TITLE)) })
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionCard(AppText.Navigation.COMMUNITY, AppText.Content.NEWS_AND_CAMPUS_DISCOVERY_SUMMARY, { open(Route.Page("community", title = AppText.Navigation.COMMUNITY)) })
                        SectionCard(AppText.Booking.FITNESS_REGISTRATION_TITLE, AppText.Booking.FITNESS_SESSIONS_AND_ITEMS_SUMMARY, { open(Route.Page("fitness", title = AppText.Booking.FITNESS_REGISTRATION_TITLE)) })
                    }
                }
            }
            if (EditionAccess.waterEnabled) item { graph.editionFeatures.HomeEntry(open) }
            item {
                AsyncContent(refresh, { graph.repositories.notices.unread() }) { data ->
                    val count = (data.text("unreadNumber").toIntOrNull() ?: 0) + (data.text("noticeMutualCount").toIntOrNull() ?: 0)
                    ArrowPreference(AppText.Content.MESSAGES_AND_NOTIFICATIONS_TITLE, summary = if (count == 0) AppText.Content.NO_UNREAD_MESSAGES else AppText.Navigation.unreadCount((count)), onClick = { open(Route.Page("notices", title = AppText.Content.MESSAGES_TITLE)) })
                }
            }
            item { Text(AppText.Content.CAMPUS_ACTIVITY_TITLE) }
            item {
                AsyncContent(refresh, { graph.repositories.content.columns("学校动态") }) { columns ->
                    val columnId = columns.field("cmsColumn").text("id")
                    if (columnId.isBlank()) EmptyCard() else {
                        AsyncContent(columnId to refresh, { graph.repositories.content.articles(columnId, limit = 5) }) { data ->
                            ArticleCards(data.rows("cmsArticleList"), open)
                        }
                    }
                }
            }
            item { TextButton(AppText.Common.REFRESH, onClick = { refresh++ }) }
        }
    }
}

@Composable
fun CommunityScreen(graph: AppGraph, open: (Route) -> Unit) {
    Screen(AppText.Navigation.COMMUNITY) { padding ->
        PageList(padding) {
            item { Text(AppText.Content.DISCOVER_TITLE) }
            item { SectionCard(AppText.Content.CAMPUS_DISCOVERY_TITLE, AppText.Content.SERVICES_STUDY_AND_CAMPUS_LIFE_SUMMARY, { open(Route.Page("discover", title = AppText.Content.CAMPUS_DISCOVERY_TITLE)) }) }
            item { Text(AppText.Content.NEWS_SECTION_TITLE) }
            item { SectionCard(AppText.Content.SCHOOL_NEWS_DISPLAY_LABEL, AppText.Content.NEWS_AND_CAMPUS_INFORMATION_SUMMARY, { open(Route.Page("columns", "学校动态", AppText.Content.SCHOOL_NEWS_DISPLAY_LABEL)) }) }
            item { SectionCard(AppText.Content.FRESHMAN_GUIDE_DISPLAY_LABEL, AppText.Content.INTRODUCE_CAMPUS_SUMMARY, { open(Route.Page("columns", "新生攻略", AppText.Content.FRESHMAN_GUIDE_DISPLAY_LABEL)) }) }
            item { SectionCard(AppText.Content.BROWSING_HISTORY_TITLE, onClick = { open(Route.Page("history", title = AppText.Content.BROWSING_HISTORY_TITLE)) }) }
            item { Text(AppText.Content.MESSAGES_TITLE) }
            item { SectionCard(AppText.Content.ANNOUNCEMENTS_AND_NOTIFICATIONS_TITLE, onClick = { open(Route.Page("notices", title = AppText.Content.ANNOUNCEMENTS_AND_NOTIFICATIONS_TITLE)) }) }
            item { Text(AppText.Booking.VENUES_AND_FITNESS_SECTION_TITLE) }
            item { SectionCard(AppText.Booking.VENUE_BOOKING_TITLE, onClick = { open(Route.Page("venues", title = AppText.Booking.VENUE_BOOKING_TITLE)) }) }
            item { SectionCard(AppText.Booking.MY_BOOKINGS_TITLE, onClick = { open(Route.Page("myvenues", title = AppText.Booking.MY_BOOKINGS_TITLE)) }) }
            item { SectionCard(AppText.Booking.FITNESS_REGISTRATION_TITLE, onClick = { open(Route.Page("fitness", title = AppText.Booking.FITNESS_REGISTRATION_TITLE)) }) }
            item { Text(AppText.App.HELP_AND_FEEDBACK_SECTION_TITLE) }
            item { SectionCard(AppText.App.FEEDBACK_TITLE, AppText.App.GITHUB_FEEDBACK_SUMMARY, { open(Route.Page("feedback", title = AppText.App.FEEDBACK_TITLE)) }) }
            item { SectionCard(AppText.App.USER_GUIDE_DISPLAY_LABEL, onClick = { open(Route.Page("document", "使用帮助", AppText.App.USER_GUIDE_DISPLAY_LABEL)) }) }
        }
    }
}

@Composable
fun ProfileScreen(graph: AppGraph, open: (Route) -> Unit) {
    io.github.betterzslf.ui.profile.ProfileFeatureScreen(graph, open)
}

@Composable
fun ArticleCards(rows: List<JsonElement>, open: (Route) -> Unit) {
    if (rows.isEmpty()) EmptyCard()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { article ->
            SectionCard(article.text("title").ifBlank { AppText.Common.UNTITLED }, article.text("description").take(120), onClick = {
                val id = article.text("id")
                if (id.isNotBlank()) open(Route.Page("article", id, article.text("title")))
            })
        }
    }
}
