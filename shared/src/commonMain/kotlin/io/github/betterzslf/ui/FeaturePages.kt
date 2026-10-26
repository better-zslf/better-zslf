package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.platform.HtmlPanel
import io.github.betterzslf.ui.content.ContentPage
import io.github.betterzslf.ui.booking.BookingPage
import io.github.betterzslf.edition.BuildEdition
import io.github.betterzslf.edition.EditionAccess
import io.github.betterzslf.edition.EditionUnavailablePage
import io.github.betterzslf.ui.run.RunFeaturePage
import io.github.betterzslf.ui.run.RunTaskList
import io.github.betterzslf.ui.campusnet.CampusNetworkFeaturePage
import io.github.betterzslf.ui.update.AppUpdatePage
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
fun ConfirmDialog(show: Boolean, title: String, summary: String, dismiss: () -> Unit, confirm: () -> Unit) {
    OverlayDialog(show = show, title = title, summary = summary, onDismissRequest = dismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(AppText.Common.CANCEL, onClick = dismiss, modifier = Modifier.weight(1f))
            TextButton(AppText.Common.CONFIRM, onClick = confirm, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun FeaturePage(graph: AppGraph, page: Route.Page, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    if (!EditionAccess.allowsPage(page.kind)) {
        EditionUnavailablePage(onBack)
        return
    }
    when (page.kind) {
        "settings" -> SettingsScreen(graph, onOpen, onBack)
        "password" -> PasswordScreen(graph, onBack)
        "resetpassword" -> io.github.betterzslf.ui.accountrecovery.AccountRecoveryPage(graph, onBack)
        "columns", "articles", "article", "document", "history", "notices", "messages", "discover" -> ContentPage(graph, page, onOpen, onBack)
        "venues", "venue", "venuetimes", "venueapply", "myvenues", "venueapplication", "fitness", "fitnessitems", "fitnessenrol" -> BookingPage(graph, page, onOpen, onBack)
        "runs", "rundetail", "runrecords", "runrecord", "runtracker", "runimport" -> RunFeaturePage(graph, page, onOpen, onBack, graph.runRuntime)
        "community" -> CommunityScreen(graph, onOpen)
        "water" -> graph.editionFeatures.Page(graph, page, onOpen, onBack)
        "campusnet" -> CampusNetworkFeaturePage(graph, onBack)
        "feedback" -> FeedbackScreen(graph, onBack)
        "updates" -> AppUpdatePage(graph.platform.versionName, graph.githubUpdates, graph.appUpdater, graph.platform::openUrl, onBack)
        "about" -> AboutScreen(graph, onBack) { onOpen(Route.Page("updates", title = AppText.Updates.VERSION_UPDATE_TITLE)) }
        else -> DataPage(graph, page, onOpen, onBack)
    }
}

@Composable
private fun DataPage(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val repo = graph.repositories
    var refresh by remember { mutableIntStateOf(0) }
    val userId = graph.sessions.session.value?.userId.orEmpty()
    val loader: suspend () -> ApiResult<JsonElement> = when (page.kind) {
        "columns" -> ({ repo.content.columns(page.id) })
        "articles" -> ({ repo.content.articles(page.id) })
        "history" -> ({ repo.content.history() })
        "notices" -> ({ repo.notices.announcements() })
        "messages" -> ({ repo.notices.list(page.id.ifBlank { "1" }) })
        "discover" -> ({ repo.discover.menu() })
        "venues" -> ({ repo.venues.list() })
        "venue" -> ({ repo.venues.info(page.id) })
        "venuetimes" -> ({ repo.venues.times(page.id) })
        "myvenues" -> ({ repo.venues.applications(userId) })
        "fitness" -> ({ repo.fitness.tests() })
        "fitnessitems" -> ({ repo.fitness.items(page.id.ifBlank { null }) })
        else -> ({ ApiResult.Success(JsonNull) })
    }
    Screen(page.title, back, actions = { TextButton(AppText.Common.REFRESH, onClick = { refresh++ }) }) { padding ->
        PageList(padding) {
            if (page.kind == "notices") item { ArrowPreference(AppText.Content.INBOX_NOTIFICATIONS_TITLE, onClick = { open(Route.Page("messages", "1", AppText.Content.INBOX_NOTIFICATIONS_TITLE)) }) }
            if (page.kind == "fitness") item { ArrowPreference(AppText.Booking.FITNESS_ITEMS_TITLE, onClick = { open(Route.Page("fitnessitems", title = AppText.Booking.FITNESS_ITEMS_TITLE)) }) }
            item {
                AsyncContent(page to refresh, loader) { data ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        when (page.kind) {
                            "articles" -> ArticleCards(data.rows("cmsArticleList"), open)
                            "history" -> ArticleCards(data.rows("list"), open)
                            "columns" -> {
                                val one = data.field("cmsColumn")
                                val rows = data.rows("cmsColumnList").ifEmpty { if (one is JsonObject) listOf(one) else emptyList() }
                                if (rows.isEmpty()) EmptyCard()
                                rows.forEach { item -> SectionCard(item.text("name").ifBlank { item.text("title") }.ifBlank { page.title }, onClick = {
                                    open(Route.Page("articles", item.text("id"), item.text("name").ifBlank { page.title }))
                                }) }
                            }
                            "notices" -> {
                                val rows = data.rows("announcementList")
                                if (rows.isEmpty()) EmptyCard()
                                rows.forEach { item -> HtmlCard(item.text("title").ifBlank { AppText.Content.CAMPUS_ANNOUNCEMENTS_TITLE }, item.text("content")) }
                            }
                            "messages" -> {
                                val rows = data.rows("noticeList")
                                if (rows.isEmpty()) EmptyCard(AppText.Content.NO_NOTIFICATIONS)
                                rows.forEach { item -> HtmlCard(item.text("title").ifBlank { AppText.Content.NOTIFICATION_FALLBACK_TITLE }, item.text("content")) }
                            }
                            "discover" -> DiscoverNodes(data.rows("findList"), graph)
                            "venues" -> {
                                val rows = data.rows("siteList")
                                if (rows.isEmpty()) EmptyCard(AppText.Booking.NO_OPEN_VENUES)
                                rows.forEach { item -> SectionCard(labelOf(item, "venueName", "siteName", "name"), detailOf(item, "address", "venueAddress", "description"), {
                                    open(Route.Page("venue", item.text("id"), labelOf(item, "venueName", "siteName", "name")))
                                }) }
                            }
                            "venue" -> {
                                val item = data.field("siteInfo").takeIf { it is JsonObject } ?: data.field("site").takeIf { it is JsonObject } ?: data
                                SectionCard(labelOf(item, "venueName", "siteName", "name"), detailOf(item, "address", "venueAddress", "description", "notice"))
                                ArrowPreference(AppText.Booking.VIEW_BOOKING_SLOTS, onClick = { open(Route.Page("venuetimes", page.id, AppText.Booking.BOOKING_SLOTS_TITLE)) })
                            }
                            "runrecord" -> {
                                val record = data.field("recordMap")
                                if (record !is JsonObject) EmptyCard(AppText.Run.NO_RUN_RECORDS) else SectionCard(AppText.Run.ACTIVITY_RECORD_TITLE, detailOf(record, "runLength", "runTime", "startTime", "endTime", "result", "status"))
                            }
                            "venuetimes" -> {
                                val explanation = data.text("text")
                                if (explanation.isNotBlank()) SectionCard(AppText.Booking.BOOKING_INSTRUCTIONS_TITLE, explanation)
                                val rows = firstRows(data)
                                if (rows.isEmpty() && explanation.isBlank()) EmptyCard(AppText.Booking.NO_AVAILABLE_SLOTS)
                                rows.forEach { item -> SectionCard(labelOf(item, "name", "timeName"), detailOf(item, "date", "startTime", "endTime", "status")) }
                            }
                            "rundetail" -> {
                                val item = data.field("errandDetail")
                                SectionCard(labelOf(item, "name", "errandName", "title"), detailOf(item, "runLength", "minDistance", "startTime", "endTime", "description"))
                                val checkpoints = item.rows("geoCoorList")
                                SectionCard(AppText.Run.ROUTE_TITLE, AppText.Content.checkpointCount((checkpoints.size)) + if (item.field("walkPaths") is JsonArray) AppText.Run.RECOMMENDED_ROUTE_AVAILABLE_SUFFIX else AppText.Run.SERVER_RECOMMENDED_ROUTE_MISSING_SUFFIX)
                                checkpoints.forEachIndexed { index, point -> SectionCard("${index + 1}. ${point.text("name")}", "${point.text("lon")}, ${point.text("lat")}") }
                            }
                            else -> {
                                val rows = firstRows(data)
                                if (rows.isEmpty()) EmptyCard()
                                if (rows.size > 50) SectionCard(AppText.Content.recordCount((rows.size)), AppText.Common.PARTIAL_LIST_PAGINATION_PENDING_HINT)
                                rows.take(50).forEach { item -> SectionCard(labelOf(item, "name", "title", "testName", "physicalName", "itemName", "venueName"), detailOf(item, "date", "startTime", "endTime", "status", "description")) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun firstRows(data: JsonElement): List<JsonElement> = when (data) {
    is JsonArray -> data.toList()
    is JsonObject -> data.values.firstOrNull { it is JsonArray }?.let { (it as JsonArray).toList() }.orEmpty()
    else -> emptyList()
}
private fun labelOf(item: JsonElement, vararg names: String) = names.firstNotNullOfOrNull { item.text(it).takeIf(String::isNotBlank) } ?: AppText.Common.DETAILS_FALLBACK_LABEL
private fun detailOf(item: JsonElement, vararg names: String) = names.map { item.text(it) }.filter(String::isNotBlank).joinToString(" · ")

@Composable
private fun HtmlCard(title: String, html: String) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title, if (expanded) AppText.Common.TAP_TO_COLLAPSE else AppText.Content.TAP_TO_VIEW_CONTENT, { expanded = !expanded })
    if (expanded) HtmlPanel(html, Modifier.fillMaxWidth().height(480.dp))
}

@Composable
private fun DiscoverNodes(nodes: List<JsonElement>, graph: AppGraph, depth: Int = 0) {
    if (nodes.isEmpty() && depth == 0) EmptyCard()
    nodes.take(100).forEach { node ->
        var expanded by remember { mutableStateOf(false) }
        val children = node.rows("children").ifEmpty { node.rows("list") }
        val url = node.text("url").ifBlank { node.text("link") }
        ArrowPreference(labelOf(node, "name", "title"), summary = if (children.isNotEmpty()) AppText.Content.EXPAND_CATEGORY else AppText.Content.CAMPUS_SERVICE_LABEL, onClick = {
            if (children.isNotEmpty()) expanded = !expanded else if (url.startsWith("https://")) graph.platform.openUrl(url)
        }, enabled = children.isNotEmpty() || url.startsWith("https://"))
        if (expanded && depth < 5) Column(Modifier.padding(start = 12.dp)) { DiscoverNodes(children, graph, depth + 1) }
    }
}

@Composable
private fun DocumentScreen(graph: AppGraph, page: Route.Page, back: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var actionMessage by remember { mutableStateOf("") }
    var liking by remember { mutableStateOf(false) }
    Screen(page.title.ifBlank { AppText.Common.DETAILS_FALLBACK_LABEL }, back, actions = {
        if (page.kind == "article") TextButton(AppText.Content.LIKE_SHORT_LABEL, enabled = !liking, onClick = {
            liking = true
            scope.launch {
                try {
                    when (val result = graph.repositories.content.toggleLike(page.id)) {
                        is ApiResult.Success -> { actionMessage = AppText.Common.OPERATION_SUCCEEDED; revision++ }
                        is ApiResult.Failure -> actionMessage = result.error.message
                    }
                } finally { liking = false }
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (actionMessage.isNotBlank()) Text(actionMessage, Modifier.padding(16.dp))
            AsyncContent(page.id to revision, { if (page.kind == "article") graph.repositories.content.article(page.id) else graph.repositories.content.document(page.id) }) { data ->
                val article = data.field("articleDetails")
                val body = article.text("content")
                if (body.isBlank()) EmptyCard(AppText.Content.NO_BODY_CONTENT) else HtmlPanel(body, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun FeedbackScreen(graph: AppGraph, back: () -> Unit) {
    val body = AppText.Content.feedbackBody((graph.platform.versionName), (graph.platform.description))
    Screen(AppText.App.FEEDBACK_TITLE, back) { padding -> PageList(padding) {
        item { SectionCard(AppText.App.FEEDBACK_IMPROVEMENT_TITLE, AppText.App.PUBLIC_FEEDBACK_DATA_SCOPE_HINT) }
        item { Button(onClick = { graph.platform.openUrl("https://github.com/better-zslf/better-zslf/issues/new?title=${AppText.App.FEEDBACK_ISSUE_TITLE_PREFIX.encodeURLParameter()}&body=${body.encodeURLParameter()}") }) { Text(AppText.App.REPORT_ISSUE) } }
        item { TextButton(AppText.App.VIEW_EXISTING_ISSUES, onClick = { graph.platform.openUrl("https://github.com/better-zslf/better-zslf/issues") }) }
    } }
}

@Composable
private fun AboutScreen(graph: AppGraph, back: () -> Unit, openUpdates: () -> Unit) {
    Screen(AppText.App.ABOUT_APP_TITLE, back) { padding -> PageList(padding) {
        item { SectionCard("better-zslf", AppText.Content.aboutVersion((graph.platform.versionName))) }
        item { SectionCard(AppText.App.DATA_USE_WHEN_NEEDED_TITLE, if (BuildEdition.isPro) AppText.App.PRIVACY_AND_OFFLINE_WATER_PROTOCOL_HINT else AppText.Edition.STANDARD_PRIVACY_HINT) }
        item { ArrowPreference(AppText.Updates.PROJECT_RELEASES_AND_UPDATES_LABEL, summary = AppText.Updates.MANUAL_GITHUB_RELEASES_CHECK_SUMMARY, onClick = openUpdates) }
        item { ArrowPreference(AppText.App.PROJECT_SOURCE_LABEL, onClick = { graph.platform.openUrl("https://github.com/better-zslf/better-zslf") }) }
    } }
}

@Composable
fun RunTasksScreen(graph: AppGraph, open: (Route) -> Unit, back: (() -> Unit)? = null) = RunTaskList(graph, open, back)
