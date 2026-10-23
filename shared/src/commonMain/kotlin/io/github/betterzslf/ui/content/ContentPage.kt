package io.github.betterzslf.ui.content

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.content.*
import io.github.betterzslf.platform.HtmlPanel
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.ConfirmDialog
import io.github.betterzslf.ui.EmptyCard
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.LocalNavigationBottomInset
import io.github.betterzslf.ui.pageContentPadding
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionTitle
import io.github.betterzslf.ui.UiTone
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/** Only presentation changes here; repositories, owner fences and write controllers remain the source of truth. */
@Composable
fun ContentPage(graph: AppGraph, page: Route.Page, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val session by graph.sessions.session.collectAsState()
    val owner = session?.userId.orEmpty()
    val source = remember(graph) { RepositoryContentSource(graph.repositories) }
    // Dispose page-scoped actions/expansion as well as their remembered controllers on an account change.
    key(owner, page) {
        when (page.kind) {
            "article", "document" -> ArticlePage(source, owner, page, onBack)
            "columns", "discover" -> TreePage(source, owner, page, onOpen, onBack)
            "articles", "history", "notices", "messages" -> FeedPage(graph, source, owner, page, onOpen, onBack)
            else -> Screen(page.title.ifBlank { AppText.Content.CONTENT_TITLE }, onBack) { padding ->
                LazyColumn(Modifier.fillMaxSize().consumeWindowInsets(padding),
                    contentPadding = pageContentPadding(padding, LocalNavigationBottomInset.current, LocalLayoutDirection.current)) {
                    item { EmptyCard(AppText.Content.UNCONNECTED_CONTENT_PAGE) }
                }
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
    val refresh: () -> Unit = {
        if (!hidden && !state.loading) scope.launch {
            controller.refresh()
            if (notices) reads.refreshUnread()
        }
    }
    LaunchedEffect(controller, hidden) {
        if (!hidden) controller.refresh()
        if (notices) reads.refreshUnread()
    }
    // The original pagination boundary and explicit retry/load-more fallback retain already loaded rows.
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
    val title = page.title.ifBlank { when (kind) {
        ContentFeedKind.ARTICLES -> AppText.Content.ARTICLES_TITLE
        ContentFeedKind.HISTORY -> AppText.Content.BROWSING_HISTORY_TITLE
        ContentFeedKind.SYSTEM_MESSAGES -> AppText.Content.SYSTEM_MESSAGES_TITLE
        ContentFeedKind.ANNOUNCEMENTS -> AppText.Content.ANNOUNCEMENTS_AND_NOTIFICATIONS_TITLE
    } }
    Screen(title, back, actions = {
        if (!hidden) TextButton(AppText.Common.REFRESH, onClick = refresh, enabled = !state.loading)
    }) { padding ->
        PullToRefresh(isRefreshing = state.loading && !hidden, onRefresh = refresh,
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = padding, refreshTexts = contentRefreshTexts()) {
            LazyColumn(Modifier.fillMaxSize().overScrollVertical(), state = lazyState, overscrollEffect = null,
                contentPadding = pageContentPadding(padding, LocalNavigationBottomInset.current, LocalLayoutDirection.current), verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                if (notices && readState.unread != null) item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (readState.unread == 0) AppText.Content.NO_UNREAD_MESSAGES else AppText.Content.unreadMessages(readState.unread),
                            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if ((readState.unread ?: 0) > 0) Badge { Text(readState.unread.toString()) }
                    }
                }
                if (notices && readState.unreadError != null) item {
                    ContentError(AppText.Content.UNREAD_COUNT_LOAD_FAILED, readState.unreadError!!.message) {
                        scope.launch { reads.refreshUnread() }
                    }
                }
                if (kind == ContentFeedKind.HISTORY) {
                    item { InfoBanner(AppText.Content.HISTORY_HIDE_PRESERVES_SERVER_HINT) }
                    history.error?.let { error -> item { InfoBanner(error.message, AppText.App.LOCAL_DATA_NOTICE, UiTone.Warning) } }
                    if (hidden) item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoBanner(AppText.Content.RESTORE_HISTORY_RELOADS_SERVER_HINT,
                                AppText.Content.HISTORY_HIDDEN_LOCALLY, UiTone.Neutral)
                            TextButton(AppText.Content.RESTORE_HISTORY_ACTION, onClick = { visibility.setHidden(false) })
                        }
                    } else item {
                        // This store hides the whole account-local history view, not individual server records.
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(AppText.Content.HIDE_HISTORY_LOCALLY_ACTION, enabled = owner.isNotBlank(),
                                onClick = { confirmHide = true })
                        }
                    }
                }
                if (!hidden) {
                    if (state.loading && state.items.isEmpty()) item { LoadingItem() }
                    state.error?.let { error -> item {
                        ContentError(AppText.Common.LOAD_FAILED, error.message) { scope.launch { controller.retry() } }
                    } }
                    if (!state.loading && state.items.isEmpty() && state.error == null) item {
                        EmptyCard(if (notices) AppText.Content.NO_NOTIFICATIONS else AppText.Common.NO_CONTENT)
                    }
                    items(state.items.size, key = { index -> "$index:${state.items[index].id}" }) { index ->
                        val item = state.items[index]
                        if (!notices) {
                            ArticleListCard(item, history = kind == ContentFeedKind.HISTORY) {
                                if (item.id.isNotBlank()) open(Route.Page("article", item.id, item.title))
                            }
                        } else {
                            val noticeKey = reads.key(item, readKind)
                            val marked = item.isRead == true || noticeKey in readState.read
                            NoticeCard(item, marked, expanded == noticeKey, kind == ContentFeedKind.SYSTEM_MESSAGES,
                                pending = noticeKey in readState.pending, error = readState.errors[noticeKey]?.message,
                                onToggle = {
                                    val opening = expanded != noticeKey
                                    expanded = if (opening) noticeKey else null
                                    // A preview, list refresh or unread badge never marks anything read.
                                    if (opening) scope.launch { reads.open(item, readKind) }
                                }, onRetryRead = { scope.launch { reads.open(item, readKind) } })
                        }
                    }
                    if (state.loadingMore) item { LoadingItem() }
                    else if (state.hasMore && state.items.isNotEmpty()) item {
                        TextButton(AppText.Common.LOAD_MORE, onClick = { scope.launch { controller.loadMore() } },
                            modifier = Modifier.fillMaxWidth(), minHeight = 44.dp, cornerRadius = 22.dp)
                    }
                    else if (state.items.isNotEmpty()) item {
                        Text(if (state.total != null) AppText.Content.allItemsShown(state.total) else AppText.Common.ALL_CONTENT_SHOWN,
                            fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
                if (kind == ContentFeedKind.ANNOUNCEMENTS) item {
                    ContentLink(AppText.Content.SYSTEM_MESSAGES_TITLE, AppText.Content.VIEW_INBOX_NOTIFICATIONS) {
                        open(Route.Page("messages", "0", AppText.Content.SYSTEM_MESSAGES_TITLE))
                    }
                }
            }
        }
        ConfirmDialog(confirmHide, AppText.Content.HIDE_LOCAL_HISTORY_CONFIRM_TITLE, AppText.Content.HIDE_LOCAL_HISTORY_CONFIRM_DESCRIPTION,
            { confirmHide = false }) { visibility.setHidden(true); confirmHide = false }
    }
}

@Composable
private fun ArticleListCard(item: ContentItem, history: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = if (history) AppUiTokens.FieldRadius else AppUiTokens.CardRadius,
        insideMargin = PaddingValues(if (history) 14.dp else AppUiTokens.CardPadding),
        onClick = if (item.id.isNotBlank()) onClick else null, showIndication = true) {
        Text(item.title.ifBlank { AppText.Common.UNTITLED }, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (!history) {
            val preview = contentPreview(item.description)
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(preview, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (item.created.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(item.created, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun NoticeCard(item: ContentItem, marked: Boolean, expanded: Boolean, system: Boolean, pending: Boolean,
    error: String?, onToggle: () -> Unit, onRetryRead: () -> Unit) {
    Card(Modifier.fillMaxWidth().animateContentSize(), cornerRadius = AppUiTokens.FieldRadius,
        insideMargin = PaddingValues(14.dp), onClick = onToggle, showIndication = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!marked) Badge()
            Text(item.title.ifBlank { AppText.Content.NOTIFICATION_FALLBACK_TITLE }, fontSize = 15.sp,
                fontWeight = if (marked) FontWeight.Medium else FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null,
                modifier = Modifier.size(12.dp).rotate(if (expanded) 90f else 0f), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (!expanded && !marked) {
            val preview = contentPreview(item.description.ifBlank { item.html })
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(preview, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(listOf(item.created, if (marked) AppText.Content.READ_STATUS
            else if (system) AppText.Content.SYSTEM_MESSAGE_OPEN_MARKS_READ_HINT else AppText.Content.NOTICE_OPEN_MARKS_READ_HINT)
            .filter(String::isNotBlank).joinToString(" · "), fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        if (expanded) {
            Spacer(Modifier.height(AppUiTokens.Gap))
            HorizontalDivider()
            Spacer(Modifier.height(AppUiTokens.Gap))
            if (item.html.isBlank()) Text(AppText.Content.NOTIFICATION_BODY_MISSING, fontSize = 13.sp)
            else HtmlPanel(item.html, Modifier.fillMaxWidth().height(500.dp))
            if (pending) Text(AppText.Content.UPDATING_READ_STATUS, fontSize = 12.sp, color = MiuixTheme.colorScheme.primary)
            error?.let { ContentError(AppText.Content.READ_STATUS_NOT_UPDATED, it, onRetryRead) }
        }
    }
}

@Composable
private fun ArticlePage(source: ContentSource, owner: String, page: Route.Page, back: () -> Unit) {
    val document = page.kind == "document"
    val controller = remember(source, owner, page) { ArticleController(source, owner, page.id, document) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val refresh: () -> Unit = { if (!state.loading && !state.toggling) scope.launch { controller.load() } }
    LaunchedEffect(controller) { controller.load() }
    Screen(page.title.ifBlank { if (document) page.id else AppText.Content.ARTICLE_DETAIL_TITLE }, back, actions = {
        TextButton(AppText.Common.REFRESH, onClick = refresh, enabled = !state.loading && !state.toggling)
    }) { padding ->
        PullToRefresh(state.loading, refresh, Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = padding, refreshTexts = contentRefreshTexts()) {
            LazyColumn(Modifier.fillMaxSize().overScrollVertical(), overscrollEffect = null, contentPadding = pageContentPadding(padding, LocalNavigationBottomInset.current, LocalLayoutDirection.current),
                verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                if (state.loading && state.detail == null) item { LoadingItem() }
                state.error?.let { error -> item { ContentError(AppText.Common.LOAD_FAILED, error.message, refresh) } }
                state.detail?.let { detail ->
                    item {
                        Text(detail.title.ifBlank { page.title.ifBlank { AppText.Common.UNTITLED } },
                            fontSize = if (document) 17.sp else 20.sp, fontWeight = FontWeight.Bold)
                        val metadata = listOf(detail.created, if (!document)
                            AppText.Content.articleCounters(state.hits?.toString() ?: "—", state.count?.toString() ?: "—") else "")
                            .filter(String::isNotBlank).joinToString(" · ")
                        if (metadata.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(metadata, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    item {
                        // Article AND document bodies are the server response; never substitute design sample text.
                        if (detail.html.isBlank()) EmptyCard(AppText.Content.NO_BODY_CONTENT)
                        else Card(Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(0.dp)) {
                            HtmlPanel(detail.html, Modifier.fillMaxWidth().height(580.dp))
                        }
                    }
                    if (!document) {
                        if (owner.isNotBlank()) item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                                Button(enabled = !state.toggling && !state.loading,
                                    onClick = { scope.launch { controller.toggleLike() } }, minHeight = 40.dp, cornerRadius = 20.dp,
                                    insideMargin = PaddingValues(horizontal = 18.dp, vertical = 10.dp)) {
                                    Text(if (state.toggling) AppText.Content.UPDATING_LIKE_STATE
                                        else (if (state.liked == true) AppText.Content.UNLIKE else AppText.Content.LIKE)
                                            + (state.count?.let { " $it" } ?: ""), fontSize = 14.sp)
                                }
                                TextButton(AppText.Content.REFRESH_LIKE_STATE,
                                    enabled = !state.toggling && !state.loading,
                                    onClick = { scope.launch { controller.refreshLikes() } }, modifier = Modifier.weight(1f))
                            }
                        }
                        state.likeError?.let { error -> item {
                            InfoBanner(error.message + AppText.Content.REFRESH_BEFORE_LIKE_RETRY_SUFFIX,
                                AppText.Content.LIKE_STATE_NOTICE, UiTone.Warning)
                        } }
                        state.hitError?.let { error -> item {
                            InfoBanner(error.message + AppText.Content.READ_COUNT_ONCE_PER_PROCESS_SUFFIX,
                                AppText.Content.READ_COUNT_NOTICE, UiTone.Neutral)
                        } }
                    }
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
    val groups = remember(state.tree, state.expanded) { directoryGroups(state.visible) }
    val title = page.title.ifBlank { if (columns) AppText.Content.COLUMNS_TITLE else AppText.Content.SERVICE_DIRECTORY_TITLE }
    val refresh: () -> Unit = { if (!state.loading) scope.launch { controller.refresh() } }
    LaunchedEffect(controller) {
        controller.refresh()
        // Present category contents on entry; later user expansion/collapse remains controller-owned.
        val initial = controller.state.value
        if (initial.error == null) initial.tree.nodes.filter { it.parent == null && initial.tree.hasChildren(it.key) }
            .forEach { if (it.key !in initial.expanded) controller.toggle(it.key) }
    }
    Screen(title, back, actions = { TextButton(AppText.Common.REFRESH, onClick = refresh, enabled = !state.loading) }) { padding ->
        PullToRefresh(state.loading, refresh, Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = padding, refreshTexts = contentRefreshTexts()) {
            LazyColumn(Modifier.fillMaxSize().overScrollVertical(), overscrollEffect = null, contentPadding = pageContentPadding(padding, LocalNavigationBottomInset.current, LocalLayoutDirection.current),
                verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                item {
                    if (columns) Text((listOf(AppText.Navigation.COMMUNITY, title) + directoryCrumbs(selected, state.tree)).distinct().joinToString(" / "),
                        fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    else Text(AppText.Content.SERVICE_DIRECTORY_INTRO, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                if (!columns) item {
                    Card(Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(6.dp)) {
                        ContentLinkRow(AppText.CampusNetwork.CAMPUS_NETWORK_TITLE) { open(Route.Page("campusnet", title = AppText.CampusNetwork.CAMPUS_NETWORK_TITLE)) }
                        HorizontalDivider()
                        ContentLinkRow(AppText.Booking.VENUE_BOOKING_TITLE) { open(Route.Page("venues", title = AppText.Booking.VENUE_BOOKING_TITLE)) }
                        HorizontalDivider()
                        ContentLinkRow(AppText.Booking.FITNESS_REGISTRATION_TITLE) { open(Route.Page("fitness", title = AppText.Booking.FITNESS_REGISTRATION_TITLE)) }
                        HorizontalDivider()
                        ContentLinkRow(AppText.Navigation.CAMPUS_RUN, when (state.runOpen) {
                            true -> AppText.Run.SEMESTER_RUN_OPEN; false -> AppText.Run.CAMPUS_RUN_NOT_OPEN; null -> AppText.Run.TASKS_AND_ACTIVITY_RECORDS
                        }) { open(Route.Page("runs", title = AppText.Navigation.CAMPUS_RUN)) }
                        HorizontalDivider()
                        ContentLinkRow(AppText.Content.FRESHMAN_GUIDE_DISPLAY_LABEL) { open(Route.Page("columns", "新生攻略", AppText.Content.FRESHMAN_GUIDE_DISPLAY_LABEL)) }
                    }
                    Spacer(Modifier.height(AppUiTokens.Gap))
                    SectionTitle(AppText.Content.SERVICE_DIRECTORY_TITLE)
                }
                if (state.loading && state.tree.nodes.isEmpty()) item { LoadingItem() }
                state.error?.let { error -> item { ContentError(AppText.Common.LOAD_FAILED, error.message, refresh) } }
                if (!state.loading && groups.isEmpty() && state.error == null) item { EmptyCard() }
                items(groups.size, key = { groups[it].first().key }) { groupIndex ->
                    val group = groups[groupIndex]
                    Card(Modifier.fillMaxWidth().animateContentSize(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(6.dp)) {
                        group.forEachIndexed { index, node ->
                            val branch = state.tree.hasChildren(node.key)
                            val destination = if (!columns && !branch) contentDestination(node.label) else null
                            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 12.dp))
                            Column(Modifier.padding(start = (node.depth * 12).coerceAtMost(36).dp)) {
                                ContentLinkRow(node.label, if (branch) {
                                    if (node.key in state.expanded) AppText.Content.COLLAPSE_DIRECTORY else AppText.Content.EXPAND_DIRECTORY
                                } else if (!columns && destination == null) AppText.Content.UNLINKED_SERVICE_NO_AUTOMATIC_NAVIGATION_HINT else "",
                                    expanded = branch && node.key in state.expanded,
                                    arrow = branch || columns || destination != null) {
                                    selected = node
                                    when {
                                        branch -> controller.toggle(node.key)
                                        columns && node.id.isNotBlank() -> open(Route.Page("articles", node.id, node.label))
                                        !columns -> destination?.let { (kind, id) -> open(Route.Page(kind, id, node.label)) }
                                    }
                                }
                                if (columns && branch && node.id.isNotBlank()) TextButton(AppText.Content.VIEW_COLUMN_ARTICLES,
                                    onClick = { open(Route.Page("articles", node.id, node.label)) })
                            }
                        }
                    }
                }
                if (!columns) {
                    selected?.let { node -> if (!state.tree.hasChildren(node.key) && contentDestination(node.label) == null) item {
                        InfoBanner(AppText.Content.UNLINKED_SERVICE_NO_AUTOMATIC_NAVIGATION_HINT, node.label, UiTone.Neutral)
                    } }
                    state.attendanceRadiusMeters?.let { radius -> item {
                        Text(AppText.Content.checkInRadius(radius), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    } }
                    state.dictionaryError?.let { error -> item { InfoBanner(error.message, AppText.Content.CONFIGURATION_LOAD_NOTICE, UiTone.Warning) } }
                }
            }
        }
    }
}

@Composable
private fun ContentLink(title: String, subtitle: String = "", onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.FieldRadius, insideMargin = PaddingValues(2.dp)) {
        ContentLinkRow(title, subtitle, onClick = onClick)
    }
}

@Composable
private fun ContentLinkRow(title: String, subtitle: String = "", expanded: Boolean = false, arrow: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (arrow) Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null,
            modifier = Modifier.size(14.dp).rotate(if (expanded) 90f else 0f), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun ContentError(title: String, message: String, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoBanner(message, title, UiTone.Warning)
        TextButton(AppText.Common.RETRY, onClick = retry)
    }
}

private fun contentRefreshTexts() = listOf(AppText.Common.PULL_TO_REFRESH, AppText.Common.RELEASE_TO_REFRESH, AppText.Common.REFRESHING, "")

/** Each native lazy item stays small even for a deeply expanded, server-owned directory. */
private fun directoryGroups(nodes: List<ContentTreeNode>): List<List<ContentTreeNode>> {
    val result = mutableListOf<List<ContentTreeNode>>()
    var group = mutableListOf<ContentTreeNode>()
    nodes.forEach { node ->
        if (group.isNotEmpty() && (node.parent == null || group.size == 12)) { result += group; group = mutableListOf() }
        group += node
    }
    if (group.isNotEmpty()) result += group
    return result
}

private fun directoryCrumbs(selected: ContentTreeNode?, tree: ContentTree): List<String> {
    if (selected == null) return emptyList()
    val byKey = tree.nodes.associateBy { it.key }
    val labels = mutableListOf<String>()
    var current: ContentTreeNode? = selected
    val visited = mutableSetOf<String>()
    while (current != null && visited.add(current.key)) {
        labels += current.label
        current = current.parent?.let(byKey::get)
    }
    return labels.asReversed()
}

/** Bounded plain-text preview only. The original HTML goes unchanged to the existing platform container. */
private fun contentPreview(value: String): String = value.take(8_000)
    .replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>"), " ")
    .replace(Regex("<[^>]*>"), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("\\s+"), " ").trim().take(240)

@Composable
private fun LoadingItem() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
