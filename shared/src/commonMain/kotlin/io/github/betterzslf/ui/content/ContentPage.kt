package io.github.betterzslf.ui.content

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.feature.content.*
import io.github.betterzslf.platform.HtmlPanel
import io.github.betterzslf.ui.ConfirmDialog
import io.github.betterzslf.ui.EmptyCard
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.*

/** Lead dispatches these kinds here; this file never changes either navigation stack itself. */
@Composable
fun ContentPage(graph: AppGraph, page: Route.Page, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val session by graph.sessions.session.collectAsState()
    val owner = session?.userId.orEmpty()
    val source = remember(graph) { RepositoryContentSource(graph.repositories) }
    when (page.kind) {
        "article", "document" -> ArticlePage(source, owner, page, onBack)
        "columns", "discover" -> TreePage(source, owner, page, onOpen, onBack)
        "articles", "history", "notices", "messages" -> FeedPage(graph, source, owner, page, onOpen, onBack)
        else -> Screen(page.title.ifBlank { AppText.Content.CONTENT_TITLE }, onBack) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                item { EmptyCard(AppText.Content.UNCONNECTED_CONTENT_PAGE) }
            }
        }
    }
}

@Composable
private fun FeedPage(graph: AppGraph, source: ContentSource, owner: String, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val kind = when (page.kind) {
        "articles" -> ContentFeedKind.ARTICLES
        "history" -> ContentFeedKind.HISTORY
        "messages" -> ContentFeedKind.SYSTEM_MESSAGES
        else -> ContentFeedKind.ANNOUNCEMENTS
    }
    val controller = remember(source, owner, page) { ContentListController(source, ContentFeedRequest(kind, page.id)) }
    val visibility = remember(graph, owner) { HistoryVisibilityStore(graph.platform.storage, owner) }
    val reads = remember(source, owner, page) { NoticeReadController(source, owner) }
    val state by controller.state.collectAsState()
    val history by visibility.state.collectAsState()
    val readState by reads.state.collectAsState()
    val scope = rememberCoroutineScope()
    val lazyState = rememberLazyListState()
    var confirmHide by remember(owner, page) { mutableStateOf(false) }
    var expanded by remember(owner, page) { mutableStateOf<String?>(null) }
    val hidden = kind == ContentFeedKind.HISTORY && history.hidden
    val notices = kind == ContentFeedKind.SYSTEM_MESSAGES || kind == ContentFeedKind.ANNOUNCEMENTS
    val readKind = if (kind == ContentFeedKind.SYSTEM_MESSAGES) NoticeReadKind.SYSTEM else NoticeReadKind.MUTUAL
    LaunchedEffect(controller, hidden) {
        if (!hidden) controller.refresh()
        if (notices) reads.refreshUnread()
    }
    // Pagination is driven by the last visible lazy item, with an explicit fallback button.
    LaunchedEffect(controller, lazyState, hidden) {
        snapshotFlow { lazyState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { last ->
                val current = controller.state.value
                if (last != null && current.items.isNotEmpty() && !hidden && current.error == null &&
                    !current.loading && !current.loadingMore && current.hasMore &&
                    last >= lazyState.layoutInfo.totalItemsCount - 2
                ) controller.loadMore()
            }
    }
    Screen(page.title.ifBlank { when (kind) {
        ContentFeedKind.ARTICLES -> AppText.Content.ARTICLES_TITLE
        ContentFeedKind.HISTORY -> AppText.Content.BROWSING_HISTORY_TITLE
        ContentFeedKind.SYSTEM_MESSAGES -> AppText.Content.SYSTEM_MESSAGES_TITLE
        ContentFeedKind.ANNOUNCEMENTS -> AppText.Content.CAMPUS_ANNOUNCEMENTS_TITLE
    } }, back, actions = {
        if (!hidden && !state.loading) TextButton(AppText.Common.REFRESH, onClick = { scope.launch { controller.refresh() } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), state = lazyState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (kind == ContentFeedKind.ANNOUNCEMENTS) item {
                SectionCard(AppText.Content.SYSTEM_MESSAGES_TITLE, AppText.Content.VIEW_INBOX_NOTIFICATIONS, { open(Route.Page("messages", "0", AppText.Content.SYSTEM_MESSAGES_TITLE)) })
            }
            if (notices && readState.unread != null) item { Text(AppText.Content.unreadMessages((readState.unread))) }
            if (notices && readState.unreadError != null) item {
                SectionCard(AppText.Content.UNREAD_COUNT_LOAD_FAILED, readState.unreadError!!.message, { scope.launch { reads.refreshUnread() } })
            }
            if (kind == ContentFeedKind.HISTORY) {
                item { SectionCard(AppText.Content.LOCAL_HISTORY_VISIBILITY_TITLE, AppText.Content.HISTORY_HIDE_PRESERVES_SERVER_HINT) }
                history.error?.let { error -> item { SectionCard(AppText.App.LOCAL_DATA_NOTICE, error.message) } }
                if (hidden) item { SectionCard(AppText.Content.HISTORY_HIDDEN_LOCALLY, AppText.Content.RESTORE_HISTORY_RELOADS_SERVER_HINT, { visibility.setHidden(false) }) }
                else item { TextButton(AppText.Content.HIDE_HISTORY_LOCALLY_ACTION, onClick = { confirmHide = true }) }
            }
            if (!hidden) {
                if (state.loading) item { LoadingItem() }
                state.error?.let { error -> item { SectionCard(AppText.Common.LOAD_FAILED, error.message + AppText.Common.TAP_TO_RETRY_SUFFIX, {
                    scope.launch { controller.retry() }
                }) } }
                if (!state.loading && state.items.isEmpty() && state.error == null) item { EmptyCard() }
                items(state.items.size, key = { index -> "$index:${state.items[index].id}" }) { index ->
                    val item = state.items[index]
                    if (!notices) {
                        SectionCard(item.title.ifBlank { AppText.Common.UNTITLED }, listOf(item.description.take(180), item.created).filter { it.isNotBlank() }.joinToString("\n"), {
                            if (item.id.isNotBlank()) open(Route.Page("article", item.id, item.title))
                        })
                    } else {
                        val key = reads.key(item, readKind)
                        val marked = item.isRead == true || key in readState.read
                        SectionCard(item.title.ifBlank { AppText.Content.NOTIFICATION_FALLBACK_TITLE }, listOf(if (marked) AppText.Content.READ_STATUS else AppText.Content.OPEN_TO_READ, item.created).filter { it.isNotBlank() }.joinToString(" · "), {
                            expanded = if (expanded == key) null else key
                            if (expanded == key) scope.launch { reads.open(item, readKind) }
                        })
                        if (expanded == key) {
                            if (item.html.isBlank()) EmptyCard(AppText.Content.NOTIFICATION_BODY_MISSING)
                            else HtmlPanel(item.html, Modifier.fillMaxWidth().height(500.dp))
                            if (key in readState.pending) Text(AppText.Content.UPDATING_READ_STATUS)
                            readState.errors[key]?.let { error ->
                                SectionCard(AppText.Content.READ_STATUS_NOT_UPDATED, error.message + AppText.Common.TAP_TO_RETRY_SUFFIX, { scope.launch { reads.open(item, readKind) } })
                            }
                        }
                    }
                }
                if (state.loadingMore) item { LoadingItem() }
                else if (state.hasMore && state.items.isNotEmpty()) item {
                    TextButton(AppText.Common.LOAD_MORE, onClick = { scope.launch { controller.loadMore() } }, modifier = Modifier.fillMaxWidth())
                }
                else if (state.items.isNotEmpty()) item { Text(if (state.total != null) AppText.Content.allItemsShown((state.total)) else AppText.Common.ALL_CONTENT_SHOWN) }
            }
        }
        ConfirmDialog(confirmHide, AppText.Content.HIDE_LOCAL_HISTORY_CONFIRM_TITLE, AppText.Content.HIDE_LOCAL_HISTORY_CONFIRM_DESCRIPTION, { confirmHide = false }) {
            visibility.setHidden(true); confirmHide = false
        }
    }
}

@Composable
private fun ArticlePage(source: ContentSource, owner: String, page: Route.Page, back: () -> Unit) {
    val document = page.kind == "document"
    val controller = remember(source, owner, page) { ArticleController(source, owner, page.id, document) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(controller) { controller.load() }
    Screen(page.title.ifBlank { if (document) page.id else AppText.Content.ARTICLE_DETAIL_TITLE }, back, actions = {
        if (!state.loading && !state.toggling) TextButton(AppText.Common.REFRESH, onClick = { scope.launch { controller.load() } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) item { LoadingItem() }
            state.error?.let { error -> item { SectionCard(AppText.Common.LOAD_FAILED, error.message + AppText.Common.TAP_TO_RETRY_SUFFIX, { scope.launch { controller.load() } }) } }
            state.detail?.let { detail ->
                item { SectionCard(detail.title.ifBlank { page.title.ifBlank { page.id } }, detail.created) }
                item {
                    if (detail.html.isBlank()) EmptyCard(AppText.Content.NO_BODY_CONTENT)
                    else HtmlPanel(detail.html, Modifier.fillMaxWidth().height(580.dp))
                }
                if (!document) {
                    item { Text(AppText.Content.articleCounters((state.hits?.toString() ?: "—"), (state.count?.toString() ?: "—"))) }
                    if (owner.isNotBlank()) {
                        item { Button(enabled = !state.toggling && !state.loading, onClick = { scope.launch { controller.toggleLike() } }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.toggling) AppText.Content.UPDATING_LIKE_STATE else if (state.liked == true) AppText.Content.UNLIKE else AppText.Content.LIKE)
                        } }
                        item { TextButton(AppText.Content.REFRESH_LIKE_STATE, onClick = { scope.launch { controller.refreshLikes() } }) }
                    }
                    state.likeError?.let { error -> item { SectionCard(AppText.Content.LIKE_STATE_NOTICE, error.message + AppText.Content.REFRESH_BEFORE_LIKE_RETRY_SUFFIX) } }
                    state.hitError?.let { error -> item { SectionCard(AppText.Content.READ_COUNT_NOTICE, error.message + AppText.Content.READ_COUNT_ONCE_PER_PROCESS_SUFFIX) } }
                }
            }
        }
    }
}

@Composable
private fun TreePage(source: ContentSource, owner: String, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val columns = page.kind == "columns"
    val controller = remember(source, owner, page) { DiscoveryController(source, if (columns) page.id else null) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var selected by remember(owner, page) { mutableStateOf<ContentTreeNode?>(null) }
    val visible = state.visible
    LaunchedEffect(controller) { controller.refresh() }
    Screen(page.title.ifBlank { if (columns) AppText.Content.COLUMNS_TITLE else AppText.Content.CAMPUS_DISCOVERY_TITLE }, back, actions = {
        if (!state.loading) TextButton(AppText.Common.REFRESH, onClick = { scope.launch { controller.refresh() } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!columns) {
                item { SectionCard(AppText.Navigation.CAMPUS_RUN, when (state.runOpen) { true -> AppText.Run.SEMESTER_RUN_OPEN; false -> AppText.Run.CAMPUS_RUN_NOT_OPEN; null -> AppText.Run.TASKS_AND_ACTIVITY_RECORDS }, { open(Route.Page("runs", title = AppText.Navigation.CAMPUS_RUN)) }) }
                item { SectionCard(AppText.Booking.VENUE_BOOKING_TITLE, onClick = { open(Route.Page("venues", title = AppText.Booking.VENUE_BOOKING_TITLE)) }) }
                item { SectionCard(AppText.Booking.FITNESS_REGISTRATION_TITLE, onClick = { open(Route.Page("fitness", title = AppText.Booking.FITNESS_REGISTRATION_TITLE)) }) }
                item { SectionCard(AppText.CampusNetwork.CAMPUS_NETWORK_TITLE, onClick = { open(Route.Page("campusnet", title = AppText.CampusNetwork.CAMPUS_NETWORK_TITLE)) }) }
                item { SectionCard(AppText.Content.FRESHMAN_GUIDE_DISPLAY_LABEL, onClick = { open(Route.Page("columns", "新生攻略", AppText.Content.FRESHMAN_GUIDE_DISPLAY_LABEL)) }) }
                state.attendanceRadiusMeters?.let { radius -> item { Text(AppText.Content.checkInRadius((radius))) } }
                state.dictionaryError?.let { error -> item { SectionCard(AppText.Content.CONFIGURATION_LOAD_NOTICE, error.message) } }
                item { Text(AppText.Content.SERVICE_DIRECTORY_TITLE) }
            }
            if (state.loading) item { LoadingItem() }
            state.error?.let { error -> item { SectionCard(AppText.Common.LOAD_FAILED, error.message + AppText.Common.TAP_TO_RETRY_SUFFIX, { scope.launch { controller.refresh() } }) } }
            if (!state.loading && visible.isEmpty() && state.error == null) item { EmptyCard() }
            selected?.let { node -> item {
                val labels = state.tree.nodes.associateBy { it.key }
                val crumbs = mutableListOf(node.label)
                var parent = node.parent
                while (parent != null) { val ancestor = labels[parent] ?: break; crumbs += ancestor.label; parent = ancestor.parent }
                Text((listOf(page.title.ifBlank { AppText.Content.DISCOVER_TITLE }) + crumbs.asReversed()).joinToString(" / "))
            } }
            items(visible.size, key = { visible[it].key }) { index ->
                val node = visible[index]
                val branch = state.tree.hasChildren(node.key)
                Column(Modifier.padding(start = (node.depth * 12).coerceAtMost(96).dp)) {
                    SectionCard(node.label, if (branch) { if (node.key in state.expanded) AppText.Content.COLLAPSE_DIRECTORY else AppText.Content.EXPAND_DIRECTORY } else AppText.Common.VIEW, {
                        selected = node
                        when {
                            branch -> controller.toggle(node.key)
                            columns && node.id.isNotBlank() -> open(Route.Page("articles", node.id, node.label))
                            !columns -> contentDestination(node.label)?.let { (kind, id) -> open(Route.Page(kind, id, node.label)) }
                        }
                    })
                    if (columns && branch && node.id.isNotBlank()) TextButton(AppText.Content.VIEW_COLUMN_ARTICLES, onClick = { open(Route.Page("articles", node.id, node.label)) })
                    if (!columns && selected?.key == node.key && !branch && contentDestination(node.label) == null) {
                        Text(AppText.Content.UNLINKED_SERVICE_NO_AUTOMATIC_NAVIGATION_HINT)
                        node.details.take(12).forEach { (name, value) -> Text("$name：${value.take(240)}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingItem() { Box(Modifier.fillMaxWidth().padding(20.dp)) { CircularProgressIndicator() } }
