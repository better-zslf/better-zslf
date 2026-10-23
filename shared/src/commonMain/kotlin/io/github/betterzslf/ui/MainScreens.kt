package io.github.betterzslf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.feature.water.WaterBlePhase
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
fun HomeScreen(graph: AppGraph, open: (Route) -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val water by graph.water.state.collectAsState()
    val waterSummary = when (water.phase) {
        WaterBlePhase.Disconnected -> "未连接"
        WaterBlePhase.Connecting -> "连接中"
        WaterBlePhase.Ready -> "已连接 · 尚未开启"
        WaterBlePhase.Authenticating -> "认证中"
        WaterBlePhase.Active -> "使用中 · 用毕请主动结束"
        WaterBlePhase.Stopping -> "正在结束"
        WaterBlePhase.ManualConfirmationRequired -> "重试前需要手动确认"
        WaterBlePhase.Failed -> "连接已关闭 · 请查看提示"
        WaterBlePhase.Closed -> "已关闭"
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    Screen("首页", actions = { TextButton("消息", onClick = { open(Route.Page("notices", title = "消息")) }) }) { padding ->
        PageList(padding) {
            item { SectionCard("校园生活，从这里开始", "少一点等待，多一点自在。") }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionCard("校园跑", "任务与运动记录", { open(Route.Page("runs", title = "校园跑")) })
                        SectionCard("场馆预约", "查看开放场地", { open(Route.Page("venues", title = "场馆预约")) })
                        SectionCard("校园网", "宽带账号查询", { open(Route.Page("campusnet", title = "校园网")) })
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionCard("社区", "资讯与校园发现", { open(Route.Page("community", title = "社区")) })
                        SectionCard("体测报名", "场次与项目", { open(Route.Page("fitness", title = "体测报名")) })
                    }
                }
            }
            item { SectionCard("宿舍热水器", "蓝牙离线连接 · $waterSummary", { open(Route.Page("water", title = "宿舍热水器")) }) }
            item {
                AsyncContent(refresh, { graph.repositories.notices.unread() }) { data ->
                    val count = (data.text("unreadNumber").toIntOrNull() ?: 0) + (data.text("noticeMutualCount").toIntOrNull() ?: 0)
                    ArrowPreference("消息与通知", summary = if (count == 0) "暂时没有未读消息" else "$count 条未读消息", onClick = { open(Route.Page("notices", title = "消息")) })
                }
            }
            item { Text("校园动态") }
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
            item { TextButton("刷新", onClick = { refresh++ }) }
        }
    }
}

@Composable
fun CommunityScreen(graph: AppGraph, open: (Route) -> Unit) {
    Screen("社区") { padding ->
        PageList(padding) {
            item { Text("发现") }
            item { SectionCard("校园发现", "服务、学习与校园生活", { open(Route.Page("discover", title = "校园发现")) }) }
            item { Text("资讯") }
            item { SectionCard("学校动态", "新闻与校园资讯", { open(Route.Page("columns", "学校动态", "学校动态")) }) }
            item { SectionCard("新生攻略", "从这里认识校园", { open(Route.Page("columns", "新生攻略", "新生攻略")) }) }
            item { SectionCard("浏览历史", onClick = { open(Route.Page("history", title = "浏览历史")) }) }
            item { Text("消息") }
            item { SectionCard("公告与通知", onClick = { open(Route.Page("notices", title = "公告与通知")) }) }
            item { Text("场地与体测") }
            item { SectionCard("场馆预约", onClick = { open(Route.Page("venues", title = "场馆预约")) }) }
            item { SectionCard("我的预约", onClick = { open(Route.Page("myvenues", title = "我的预约")) }) }
            item { SectionCard("体测报名", onClick = { open(Route.Page("fitness", title = "体测报名")) }) }
            item { Text("帮助与反馈") }
            item { SectionCard("意见反馈", "在 GitHub 上反馈问题或建议", { open(Route.Page("feedback", title = "意见反馈")) }) }
            item { SectionCard("使用帮助", onClick = { open(Route.Page("document", "使用帮助", "使用帮助")) }) }
        }
    }
}

@Composable
fun ProfileScreen(graph: AppGraph, open: (Route) -> Unit) {
    val session by graph.sessions.session.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var logout by remember { mutableStateOf(false) }
    Screen("我的", actions = { TextButton("刷新", onClick = { refresh++ }) }) { padding ->
        PageList(padding) {
            item {
                AsyncContent(refresh, { graph.repositories.profile.userInfo() }) { data ->
                    val user = data.field("userInfo")
                    SectionCard(user.text("name").ifBlank { session?.userInfo?.text("name").orEmpty() },
                        listOf(user.text("schoolName"), user.text("studentNum").ifBlank { user.text("account") }, user.text("typeValue")).filter { it.isNotBlank() }.joinToString("\n"))
                }
            }
            item { AsyncContent(refresh, { graph.repositories.profile.statistics() }) { data ->
                SectionCard("我的足迹", "收藏 ${data.text("collect")} · 评论 ${data.text("comment")} · 浏览 ${data.text("history")}")
            } }
            item { Text("账号") }
            item { SectionCard("修改密码", onClick = { open(Route.Page("password", title = "修改密码")) }) }
            item { SectionCard("设置", "主题、跑步偏好与本地数据", { open(Route.Page("settings", title = "设置")) }) }
            item { Text("关于") }
            item { SectionCard("版本更新", "better-zslf ${graph.platform.versionName}", { open(Route.Page("updates", title = "版本更新")) }) }
            item { SectionCard("关于应用", onClick = { open(Route.Page("about", title = "关于应用")) }) }
            item { Text("协议与帮助") }
            listOf("用户协议", "隐私政策", "使用帮助").forEach { title -> item { SectionCard(title, onClick = { open(Route.Page("document", title, title)) }) } }
            item { TextButton("退出登录", onClick = { logout = true }, modifier = Modifier.fillMaxWidth()) }
        }
        ConfirmDialog(logout, "退出登录？", "会清除本机保存的会话，账号和学校数据保持原样。", { logout = false }) {
            graph.logout(); logout = false
        }
    }
}

@Composable
fun ArticleCards(rows: List<JsonElement>, open: (Route) -> Unit) {
    if (rows.isEmpty()) EmptyCard()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { article ->
            SectionCard(article.text("title").ifBlank { "无标题" }, article.text("description").take(120), onClick = {
                val id = article.text("id")
                if (id.isNotBlank()) open(Route.Page("article", id, article.text("title")))
            })
        }
    }
}
