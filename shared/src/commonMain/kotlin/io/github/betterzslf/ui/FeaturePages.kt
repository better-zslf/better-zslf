package io.github.betterzslf.ui

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
import io.github.betterzslf.ui.water.WaterHeaterScreen
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
            TextButton("取消", onClick = dismiss, modifier = Modifier.weight(1f))
            TextButton("确认", onClick = confirm, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun FeaturePage(graph: AppGraph, page: Route.Page, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    when (page.kind) {
        "settings" -> SettingsScreen(graph, onOpen, onBack)
        "password" -> PasswordScreen(graph, onBack)
        "columns", "articles", "article", "document", "history", "notices", "messages", "discover" -> ContentPage(graph, page, onOpen, onBack)
        "venues", "venue", "venuetimes", "venueapply", "myvenues", "venueapplication", "fitness", "fitnessitems", "fitnessenrol" -> BookingPage(graph, page, onOpen, onBack)
        "runs" -> RunTasksScreen(graph, onOpen, onBack)
        "community" -> CommunityScreen(graph, onOpen)
        "water" -> WaterHeaterScreen(graph.water, onBack)
        "campusnet" -> CampusNetworkScreen(graph, onBack)
        "feedback" -> FeedbackScreen(graph, onBack)
        "updates", "about" -> AboutScreen(graph, page.kind == "updates", onBack)
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
        "rundetail" -> ({ repo.run.detail(page.id) })
        "runrecords" -> ({ repo.run.records(userId) })
        "runrecord" -> ({ repo.run.record(page.id) })
        else -> ({ ApiResult.Success(JsonNull) })
    }
    Screen(page.title, back, actions = { TextButton("刷新", onClick = { refresh++ }) }) { padding ->
        PageList(padding) {
            if (page.kind == "notices") item { ArrowPreference("站内通知", onClick = { open(Route.Page("messages", "1", "站内通知")) }) }
            if (page.kind == "fitness") item { ArrowPreference("体测项目", onClick = { open(Route.Page("fitnessitems", title = "体测项目")) }) }
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
                                rows.forEach { item -> HtmlCard(item.text("title").ifBlank { "校园公告" }, item.text("content")) }
                            }
                            "messages" -> {
                                val rows = data.rows("noticeList")
                                if (rows.isEmpty()) EmptyCard("暂无通知")
                                rows.forEach { item -> HtmlCard(item.text("title").ifBlank { "通知" }, item.text("content")) }
                            }
                            "discover" -> DiscoverNodes(data.rows("findList"), graph)
                            "venues" -> {
                                val rows = data.rows("siteList")
                                if (rows.isEmpty()) EmptyCard("暂无开放场馆")
                                rows.forEach { item -> SectionCard(labelOf(item, "venueName", "siteName", "name"), detailOf(item, "address", "venueAddress", "description"), {
                                    open(Route.Page("venue", item.text("id"), labelOf(item, "venueName", "siteName", "name")))
                                }) }
                            }
                            "venue" -> {
                                val item = data.field("siteInfo").takeIf { it is JsonObject } ?: data.field("site").takeIf { it is JsonObject } ?: data
                                SectionCard(labelOf(item, "venueName", "siteName", "name"), detailOf(item, "address", "venueAddress", "description", "notice"))
                                ArrowPreference("查看预约时段", onClick = { open(Route.Page("venuetimes", page.id, "预约时段")) })
                            }
                            "runrecord" -> {
                                val record = data.field("recordMap")
                                if (record !is JsonObject) EmptyCard("暂无跑步记录") else SectionCard("运动记录", detailOf(record, "runLength", "runTime", "startTime", "endTime", "result", "status"))
                            }
                            "venuetimes" -> {
                                val explanation = data.text("text")
                                if (explanation.isNotBlank()) SectionCard("预约说明", explanation)
                                val rows = firstRows(data)
                                if (rows.isEmpty() && explanation.isBlank()) EmptyCard("暂无可用时段")
                                rows.forEach { item -> SectionCard(labelOf(item, "name", "timeName"), detailOf(item, "date", "startTime", "endTime", "status")) }
                            }
                            "rundetail" -> {
                                val item = data.field("errandDetail")
                                SectionCard(labelOf(item, "name", "errandName", "title"), detailOf(item, "runLength", "minDistance", "startTime", "endTime", "description"))
                                val checkpoints = item.rows("geoCoorList")
                                SectionCard("路线", "${checkpoints.size} 个打卡点" + if (item.field("walkPaths") is JsonArray) " · 有推荐路线" else " · 服务端未返回推荐路线")
                                checkpoints.forEachIndexed { index, point -> SectionCard("${index + 1}. ${point.text("name")}", "${point.text("lon")}, ${point.text("lat")}") }
                            }
                            else -> {
                                val rows = firstRows(data)
                                if (rows.isEmpty()) EmptyCard()
                                if (rows.size > 50) SectionCard("共 ${rows.size} 条", "当前先展示前 50 条，完整筛选分页待补齐。")
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
private fun labelOf(item: JsonElement, vararg names: String) = names.firstNotNullOfOrNull { item.text(it).takeIf(String::isNotBlank) } ?: "详情"
private fun detailOf(item: JsonElement, vararg names: String) = names.map { item.text(it) }.filter(String::isNotBlank).joinToString(" · ")

@Composable
private fun HtmlCard(title: String, html: String) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard(title, if (expanded) "点按收起" else "点按查看内容", { expanded = !expanded })
    if (expanded) HtmlPanel(html, Modifier.fillMaxWidth().height(480.dp))
}

@Composable
private fun DiscoverNodes(nodes: List<JsonElement>, graph: AppGraph, depth: Int = 0) {
    if (nodes.isEmpty() && depth == 0) EmptyCard()
    nodes.take(100).forEach { node ->
        var expanded by remember { mutableStateOf(false) }
        val children = node.rows("children").ifEmpty { node.rows("list") }
        val url = node.text("url").ifBlank { node.text("link") }
        ArrowPreference(labelOf(node, "name", "title"), summary = if (children.isNotEmpty()) "展开分类" else "校园服务", onClick = {
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
    Screen(page.title.ifBlank { "详情" }, back, actions = {
        if (page.kind == "article") TextButton("赞", enabled = !liking, onClick = {
            liking = true
            scope.launch {
                try {
                    when (val result = graph.repositories.content.toggleLike(page.id)) {
                        is ApiResult.Success -> { actionMessage = "操作成功"; revision++ }
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
                if (body.isBlank()) EmptyCard("暂无正文") else HtmlPanel(body, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun FeedbackScreen(graph: AppGraph, back: () -> Unit) {
    val body = "### App 版本\n${graph.platform.versionName}\n\n### 系统\n${graph.platform.description}\n\n### 问题描述\n\n\n请勿填写学号、密码、token 等敏感信息。"
    Screen("意见反馈", back) { padding -> PageList(padding) {
        item { SectionCard("一起让它更好", "反馈在 GitHub 公开展示。仅预填应用与系统版本，不附带账号或设备标识。") }
        item { Button(onClick = { graph.platform.openUrl("https://github.com/better-zslf/better-zslf/issues/new?title=${"[功能异常] ".encodeURLParameter()}&body=${body.encodeURLParameter()}") }) { Text("反馈问题") } }
        item { TextButton("查看现有问题", onClick = { graph.platform.openUrl("https://github.com/better-zslf/better-zslf/issues") }) }
    } }
}

@Composable
private fun AboutScreen(graph: AppGraph, updates: Boolean, back: () -> Unit) {
    Screen(if (updates) "版本更新" else "关于应用", back) { padding -> PageList(padding) {
        item { SectionCard("better-zslf", "${graph.platform.versionName}\n电子科技大学中山学院第三方校园客户端。与学校及官方客户端无隶属关系。") }
        item { SectionCard("你的数据，只在需要时使用", "无广告、无统计和崩溃上报。校园功能直连学校服务；热水器协议完全离线。") }
        if (updates) item { AsyncContent("upstream-version", { graph.repositories.updates.check() }) { data ->
            SectionCard("学校服务公布的官方版本", data.text("version") + "\n" + data.text("contents") + "\n此版本号属于官方应用，不用于覆盖安装 better-zslf。")
        } }
        item { ArrowPreference("better-zslf 发布与更新", summary = "GitHub Releases", onClick = { graph.platform.openUrl("https://github.com/better-zslf/better-zslf/releases/latest") }) }
        item { ArrowPreference("项目源码", onClick = { graph.platform.openUrl("https://github.com/better-zslf/better-zslf") }) }
    } }
}

@Composable
fun RunTasksScreen(graph: AppGraph, open: (Route) -> Unit, back: (() -> Unit)? = null) {
    var group by remember { mutableStateOf("0") }
    var refresh by remember { mutableIntStateOf(0) }
    val userId = graph.sessions.session.value?.userId.orEmpty()
    Screen("校园跑", back, actions = { TextButton("记录", onClick = { open(Route.Page("runrecords", title = "我的跑步记录")) }) }) { padding ->
        PageList(padding) {
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("0" to "未完成", "1" to "已完成").forEach { (value, title) -> TextButton(title, onClick = { group = value }) } } }
            item { AsyncContent(group to refresh, { graph.repositories.run.tasks(userId, 0, 20, group) }) { data ->
                val rows = data.rows("errandList")
                if (rows.isEmpty()) EmptyCard("暂无跑步任务")
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { rows.forEach { item ->
                    val id = item.text("trid").ifBlank { item.text("id") }
                    SectionCard(labelOf(item, "errandName", "name", "title"), detailOf(item, "runLength", "startTime", "endTime"), {
                        if (id.isNotBlank()) open(Route.Page("rundetail", id, "任务详情"))
                    })
                } }
            } }
            item { TextButton("刷新", onClick = { refresh++ }) }
        }
    }
}

@Composable
private fun WaterScreen(graph: AppGraph, back: () -> Unit) {
    Screen("宿舍热水器", back) { padding -> PageList(padding) {
        item { SectionCard("蓝牙直连，离线使用", "未连接设备。协议移植与模拟器验证进行中，当前构建不向真实设备发送开启指令。") }
        item { SectionCard("连接前", "多次认证失败可能锁定水控器，需等待约 1 小时。认证失败后立即断开，不自动重试。") }
    } }
}

@Composable
private fun CampusNetworkScreen(graph: AppGraph, back: () -> Unit) {
    Screen("校园网", back) { padding -> PageList(padding) {
        item { SectionCard("宽带账号", "仅查询当前登录账号已办理的校园宽带，不修改密码或联网状态。") }
        item { AsyncContent("campus-accounts", { graph.repositories.campusNetwork.accounts() }) { data ->
            val rows = data.rows("operatorAccount")
            if (rows.isEmpty()) EmptyCard("暂无已办理的宽带账号")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { rows.forEach { item ->
                SectionCard("宽带账号", item.text("operatorAccount"))
            } }
        } }
    } }
}
