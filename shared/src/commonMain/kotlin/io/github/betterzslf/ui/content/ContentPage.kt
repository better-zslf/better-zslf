package io.github.betterzslf.ui.content

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
        else -> Screen(page.title.ifBlank { "内容" }, onBack) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                item { EmptyCard("暂未连接此内容页面") }
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
        ContentFeedKind.ARTICLES -> "文章"
        ContentFeedKind.HISTORY -> "浏览历史"
        ContentFeedKind.SYSTEM_MESSAGES -> "系统消息"
        ContentFeedKind.ANNOUNCEMENTS -> "校园公告"
    } }, back, actions = {
        if (!hidden && !state.loading) TextButton("刷新", onClick = { scope.launch { controller.refresh() } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), state = lazyState,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (kind == ContentFeedKind.ANNOUNCEMENTS) item {
                SectionCard("系统消息", "查看站内通知", { open(Route.Page("messages", "0", "系统消息")) })
            }
            if (notices && readState.unread != null) item { Text("未读消息 ${readState.unread}") }
            if (notices && readState.unreadError != null) item {
                SectionCard("未读计数读取失败", readState.unreadError!!.message, { scope.launch { reads.refreshUnread() } })
            }
            if (kind == ContentFeedKind.HISTORY) {
                item { SectionCard("本机历史显示", "服务器尚无已验证的清空接口。本机隐藏不删除学校服务器上的浏览记录。") }
                history.error?.let { error -> item { SectionCard("本机数据提示", error.message) } }
                if (hidden) item { SectionCard("浏览历史已在本机隐藏", "恢复显示会重新读取服务器历史。", { visibility.setHidden(false) }) }
                else item { TextButton("在本机隐藏历史…", onClick = { confirmHide = true }) }
            }
            if (!hidden) {
                if (state.loading) item { LoadingItem() }
                state.error?.let { error -> item { SectionCard("加载失败", error.message + " · 点按重试", {
                    scope.launch { controller.retry() }
                }) } }
                if (!state.loading && state.items.isEmpty() && state.error == null) item { EmptyCard() }
                items(state.items.size, key = { index -> "$index:${state.items[index].id}" }) { index ->
                    val item = state.items[index]
                    if (!notices) {
                        SectionCard(item.title.ifBlank { "无标题" }, listOf(item.description.take(180), item.created).filter { it.isNotBlank() }.joinToString("\n"), {
                            if (item.id.isNotBlank()) open(Route.Page("article", item.id, item.title))
                        })
                    } else {
                        val key = reads.key(item, readKind)
                        val marked = item.isRead == true || key in readState.read
                        SectionCard(item.title.ifBlank { "通知" }, listOf(if (marked) "已读" else "点开阅读", item.created).filter { it.isNotBlank() }.joinToString(" · "), {
                            expanded = if (expanded == key) null else key
                            if (expanded == key) scope.launch { reads.open(item, readKind) }
                        })
                        if (expanded == key) {
                            if (item.html.isBlank()) EmptyCard("此通知没有正文")
                            else HtmlPanel(item.html, Modifier.fillMaxWidth().height(500.dp))
                            if (key in readState.pending) Text("正在更新已读状态…")
                            readState.errors[key]?.let { error ->
                                SectionCard("已读状态未更新", error.message + " · 点按重试", { scope.launch { reads.open(item, readKind) } })
                            }
                        }
                    }
                }
                if (state.loadingMore) item { LoadingItem() }
                else if (state.hasMore && state.items.isNotEmpty()) item {
                    TextButton("加载更多", onClick = { scope.launch { controller.loadMore() } }, modifier = Modifier.fillMaxWidth())
                }
                else if (state.items.isNotEmpty()) item { Text(if (state.total != null) "共 ${state.total} 条，已全部显示" else "已显示全部内容") }
            }
        }
        ConfirmDialog(confirmHide, "在本机隐藏浏览历史？", "仅影响当前账号在本机的显示，服务器上的历史记录保持原样，可随时恢复。", { confirmHide = false }) {
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
    Screen(page.title.ifBlank { if (document) page.id else "文章详情" }, back, actions = {
        if (!state.loading && !state.toggling) TextButton("刷新", onClick = { scope.launch { controller.load() } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) item { LoadingItem() }
            state.error?.let { error -> item { SectionCard("加载失败", error.message + " · 点按重试", { scope.launch { controller.load() } }) } }
            state.detail?.let { detail ->
                item { SectionCard(detail.title.ifBlank { page.title.ifBlank { page.id } }, detail.created) }
                item {
                    if (detail.html.isBlank()) EmptyCard("暂无正文")
                    else HtmlPanel(detail.html, Modifier.fillMaxWidth().height(580.dp))
                }
                if (!document) {
                    item { Text("阅读 ${state.hits?.toString() ?: "—"} · 赞 ${state.count?.toString() ?: "—"}") }
                    if (owner.isNotBlank()) {
                        item { Button(enabled = !state.toggling && !state.loading, onClick = { scope.launch { controller.toggleLike() } }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.toggling) "正在更新…" else if (state.liked == true) "取消点赞" else "点赞")
                        } }
                        item { TextButton("刷新点赞状态", onClick = { scope.launch { controller.refreshLikes() } }) }
                    }
                    state.likeError?.let { error -> item { SectionCard("点赞状态提示", error.message + "；请先刷新确认，避免重复切换。") } }
                    state.hitError?.let { error -> item { SectionCard("阅读计数提示", error.message + "；同一进程内不会重复提交阅读计数。") } }
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
    Screen(page.title.ifBlank { if (columns) "栏目" else "校园发现" }, back, actions = {
        if (!state.loading) TextButton("刷新", onClick = { scope.launch { controller.refresh() } })
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!columns) {
                item { SectionCard("校园跑", when (state.runOpen) { true -> "本学期校园跑已开启"; false -> "校园跑尚未开启"; null -> "任务与运动记录" }, { open(Route.Page("runs", title = "校园跑")) }) }
                item { SectionCard("场馆预约", onClick = { open(Route.Page("venues", title = "场馆预约")) }) }
                item { SectionCard("体测报名", onClick = { open(Route.Page("fitness", title = "体测报名")) }) }
                item { SectionCard("校园网", onClick = { open(Route.Page("campusnet", title = "校园网")) }) }
                item { SectionCard("新生攻略", onClick = { open(Route.Page("columns", "新生攻略", "新生攻略")) }) }
                state.attendanceRadiusMeters?.let { radius -> item { Text("签到半径配置：$radius 米（不改变校园跑的轨迹阈值）") } }
                state.dictionaryError?.let { error -> item { SectionCard("配置读取提示", error.message) } }
                item { Text("服务目录") }
            }
            if (state.loading) item { LoadingItem() }
            state.error?.let { error -> item { SectionCard("加载失败", error.message + " · 点按重试", { scope.launch { controller.refresh() } }) } }
            if (!state.loading && visible.isEmpty() && state.error == null) item { EmptyCard() }
            selected?.let { node -> item {
                val labels = state.tree.nodes.associateBy { it.key }
                val crumbs = mutableListOf(node.label)
                var parent = node.parent
                while (parent != null) { val ancestor = labels[parent] ?: break; crumbs += ancestor.label; parent = ancestor.parent }
                Text((listOf(page.title.ifBlank { "发现" }) + crumbs.asReversed()).joinToString(" / "))
            } }
            items(visible.size, key = { visible[it].key }) { index ->
                val node = visible[index]
                val branch = state.tree.hasChildren(node.key)
                Column(Modifier.padding(start = (node.depth * 12).coerceAtMost(96).dp)) {
                    SectionCard(node.label, if (branch) { if (node.key in state.expanded) "收起目录" else "展开目录" } else "查看", {
                        selected = node
                        when {
                            branch -> controller.toggle(node.key)
                            columns && node.id.isNotBlank() -> open(Route.Page("articles", node.id, node.label))
                            !columns -> contentDestination(node.label)?.let { (kind, id) -> open(Route.Page(kind, id, node.label)) }
                        }
                    })
                    if (columns && branch && node.id.isNotBlank()) TextButton("查看本栏目文章", onClick = { open(Route.Page("articles", node.id, node.label)) })
                    if (!columns && selected?.key == node.key && !branch && contentDestination(node.label) == null) {
                        Text("此服务尚未绑定应用内入口；不会根据未知字段自动跳转。")
                        node.details.take(12).forEach { (name, value) -> Text("$name：${value.take(240)}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingItem() { Box(Modifier.fillMaxWidth().padding(20.dp)) { CircularProgressIndicator() } }
