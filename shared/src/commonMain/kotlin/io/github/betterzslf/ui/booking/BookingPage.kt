package io.github.betterzslf.ui.booking

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.feature.booking.*
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference

/** Dispatcher contract: no App/FeaturePages changes are required inside this module. */
@Composable
fun BookingPage(graph: AppGraph, page: Route.Page, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val session by graph.sessions.session.collectAsState()
    // Replacing the owner destroys local drafts, selections, query results and attachment bytes.
    key(graph, session?.userId, page) {
        when (page.kind) {
            "venues" -> VenueList(graph, page, onOpen, onBack)
            "venue" -> VenueDetail(graph, page, onOpen, onBack)
            "venuetimes" -> VenueTimes(graph, page, onOpen, onBack)
            "venueapply" -> VenueApply(graph, page, onOpen, onBack)
            "myvenues" -> MyVenues(graph, page, onOpen, onBack)
            "venueapplication" -> VenueApplication(graph, page, onBack)
            "fitness" -> FitnessList(graph, page, onOpen, onBack)
            "fitnessitems" -> FitnessItems(graph, page, onOpen, onBack)
            "fitnessenrol" -> FitnessEnrol(graph, page, onOpen, onBack)
            else -> Screen(page.title.ifBlank { "预约与体测" }, onBack) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                    item { SectionCard("页面类型未识别", "请从社区的场馆预约或体测报名入口重新进入。") }
                }
            }
        }
    }
}

@Composable
private fun VenueList(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val query = rememberBookingQuery(graph to "venues") { graph.repositories.venues.list("1") }
    val state by query.state.collectAsState()
    val scope = rememberCoroutineScope()
    Screen(page.title.ifBlank { "场馆预约" }, back, actions = {
        TextButton("刷新", enabled = !state.loading, onClick = { scope.launch { query.refresh() } })
    }) { padding ->
        BookingLazyList(padding, state, { scope.launch { query.refresh() } },
            state.data?.let { bookingRows(it, "siteList") }.orEmpty(), "暂无开放场馆",
            header = {
                item("my-venues") { ArrowPreference("我的预约申请", onClick = { open(Route.Page("myvenues", title = "我的预约")) }) }
                item("venue-contract") { SectionCard("开放场馆", "按已验证的 venueStatus=1 查询。状态和开放信息以服务端原值为准。") }
            }) { item, index ->
            val id = item.bookingId()
            BookingRecordCard(item, item.bookingLabel("场馆 ${index + 1}"), if (id.isBlank()) null else ({
                open(Route.Page("venue", id, item.bookingLabel("场馆详情")))
            }))
            if (id.isBlank()) Text("此记录未返回 id，暂仅展示。")
        }
    }
}

@Composable
private fun VenueDetail(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val siteId = BookingSelection.decode(page.id).siteId
    val query = rememberBookingQuery(graph to siteId) {
        if (siteId.isBlank()) ApiResult.Failure(ApiError.Validation(listOf("siteId"), "缺少场馆标识，请返回列表重新选择"))
        else graph.repositories.venues.info(siteId)
    }
    val state by query.state.collectAsState()
    val scope = rememberCoroutineScope()
    Screen(page.title.ifBlank { "场馆详情" }, back, actions = {
        TextButton("刷新", enabled = !state.loading, onClick = { scope.launch { query.refresh() } })
    }) { padding ->
        PullToRefresh(state.loading, { scope.launch { query.refresh() } }, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ArrowPreference("查看预约时段", enabled = siteId.isNotBlank(), onClick = {
                    open(Route.Page("venuetimes", siteId, "预约时段"))
                }) }
                bookingQueryFeedback(state, { scope.launch { query.refresh() } })
                if (!state.loading && state.error == null && state.data?.let { bookingFields(it) }.isNullOrEmpty()) item {
                    SectionCard("暂无场馆详情", "已保留场馆标识，可刷新详情或查看时段。")
                }
                bookingFieldsItems(state.data)
            }
        }
    }
}

@Composable
private fun VenueTimes(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val selection = remember(page.id) { BookingSelection.decode(page.id) }
    var dateInput by remember { mutableStateOf(selection.dateTime) }
    var requestedDate by remember { mutableStateOf(selection.dateTime) }
    val query = rememberBookingQuery(Triple(graph, selection.siteId, requestedDate)) {
        if (selection.siteId.isBlank()) ApiResult.Failure(ApiError.Validation(listOf("siteId"), "缺少场馆标识"))
        else if (!validBookingDate(requestedDate)) ApiResult.Failure(ApiError.Validation(listOf("dateTime"), "请先填写预约日期并点击查询"))
        else graph.repositories.venues.times(selection.siteId, requestedDate)
    }
    val state by query.state.collectAsState()
    val rows = remember(state.data) { state.data?.let(::bookingTimeRows).orEmpty() }
    var chosen by remember { mutableStateOf(selection.timeIds.toSet()) }
    val occupied = ((state.data as? JsonObject)?.get("applyTimeList") as? JsonArray)?.map { it.bookingId() }?.toSet().orEmpty()
    val selectable = rows.filter { bookingAvailability(it) != SlotAvailability.FULL && it.bookingId() !in occupied }.map { it.bookingId() }.filter(String::isNotBlank).toSet()
    val scope = rememberCoroutineScope()
    Screen(page.title.ifBlank { "预约时段" }, back, actions = {
        TextButton("刷新", enabled = !state.loading, onClick = { scope.launch { query.refresh() } })
    }) { padding ->
        BookingLazyList(padding, state, { scope.launch { query.refresh() } }, rows, "暂无可用时段",
            header = {
                item("slot-date") {
                    TextField(dateInput, { dateInput = it }, label = "预约日期 YYYY-MM-DD", modifier = Modifier.fillMaxWidth(), singleLine = true)
                    TextButton("查询此日期时段", enabled = validBookingDate(dateInput) && !state.loading, onClick = {
                        if (requestedDate == dateInput) scope.launch { query.refresh() }
                        else { requestedDate = dateInput; chosen = emptySet() }
                    })
                }
                item("slot-explanation") {
                    SectionCard("选择时段", "当前查询日期：${requestedDate.ifBlank { "尚未选择" }}。已占用或已使用时段不可选；最终结果以提交时服务端确认为准。")
                }
                val text = state.data?.bookingText("text").orEmpty()
                if (text.isNotBlank()) item("slot-text") { SectionCard("预约说明", text) }
                item("selection-summary") { Text("已选 ${chosen.size} 个时段") }
                if (chosen.any { it !in selectable }) item("stale-selection") {
                    SectionCard("所选时段已变化", "请重新核对。缺失或已满时段不会进入申请预览。")
                    TextButton("清除失效选择", onClick = { chosen = chosen.intersect(selectable) })
                }
                item("apply-entry") {
                    Button(enabled = !state.loading && state.error == null && chosen.isNotEmpty() && chosen.all { it in selectable },
                        onClick = { open(Route.Page("venueapply", selection.copy(timeIds = chosen.toList(), dateTime = requestedDate).encode(), "预约申请")) }) {
                        Text("填写申请资料")
                    }
                }
            }) { item, index ->
            val id = item.bookingId()
            val availability = if (id in occupied) SlotAvailability.FULL else bookingAvailability(item)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BookingRecordCard(item, item.bookingLabel("时段 ${index + 1}"))
                Text(availability.label)
                BookingCheck(id in chosen, if (id.isBlank()) "时段缺少 id，仅展示" else "选定此时段",
                    enabled = id.isNotBlank() && availability != SlotAvailability.FULL && !state.loading) {
                    chosen = if (it) chosen + id else chosen - id
                }
            }
        }
    }
}

@Composable
private fun MyVenues(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val userId = graph.sessions.session.value?.userId.orEmpty()
    val query = rememberBookingPager(graph to ("my-venues" to userId), "applyList") { offset, limit ->
        graph.repositories.venues.applications(userId, io.github.betterzslf.core.net.queryParameters("status" to "", "offset" to offset.toString(), "limit" to limit.toString()))
    }
    val paged by query.state.collectAsState()
    val state = paged.query
    val scope = rememberCoroutineScope()
    Screen(page.title.ifBlank { "我的预约申请" }, back, actions = {
        TextButton("刷新", enabled = !state.loading, onClick = { scope.launch { query.refresh() } })
    }) { padding ->
        BookingLazyList(padding, state, { scope.launch { query.refresh() } }, state.data?.let { bookingRows(it, "applyList") }.orEmpty(),
            "暂无预约申请", header = {
                item { SectionCard("申请状态", "可查看申请详情、审批记录；需要补充文件时，请打开申请上传。取消预约暂未接通。") }
            }, footer = {
                if (paged.moreError != null) item("more-error") { SectionCard("下一页加载失败", paged.moreError.orEmpty()) }
                if (!paged.end && state.data != null) item("server-more") { TextButton("加载下一页申请", enabled = !state.loading, onClick = { scope.launch { query.more() } }) }
            }) { item, index ->
            val id = item.bookingText("applyId").ifBlank { item.bookingId() }
            BookingRecordCard(item, item.bookingLabel("申请 ${index + 1}"), if (id.isBlank()) null else ({
                open(Route.Page("venueapplication", id, "预约申请详情"))
            }))
        }
    }
}

@Composable
private fun VenueApplication(graph: AppGraph, page: Route.Page, back: () -> Unit) {
    val query = rememberBookingQuery(graph to ("application" to page.id)) {
        if (page.id.isBlank()) ApiResult.Failure(ApiError.Validation(listOf("applyId"), "缺少申请标识；未请求详情接口"))
        else graph.repositories.venues.application(page.id)
    }
    val state by query.state.collectAsState()
    val timeline = rememberBookingQuery(graph to ("application-timeline" to page.id)) {
        if (page.id.isBlank()) ApiResult.Failure(ApiError.Validation(listOf("applyId"), "缺少申请标识"))
        else graph.repositories.venues.process(page.id)
    }
    val timelineState by timeline.state.collectAsState()
    val controller = remember { BookingConfirmation(BookingAction.VENUE_CANCEL) }
    val confirmation by controller.state.collectAsState()
    var dialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Screen(page.title.ifBlank { "预约申请详情" }, back, actions = {
        TextButton("刷新", enabled = !state.loading, onClick = { scope.launch { query.refresh() } })
    }) { padding ->
        PullToRefresh(state.loading, { scope.launch { query.refresh() } }, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bookingQueryFeedback(state, { scope.launch { query.refresh() } })
                if (state.data != null) item("cancel-explanation") {
                    SectionCard("取消预约暂未接通", "当前仅查看申请与审批进度；不会调用审批查询接口来取消预约。")
                    TextButton("预览取消状态", enabled = page.id.isNotBlank(), onClick = {
                        controller.preview(BookingPreview("取消预约", listOf("申请标识：${page.id}", "当前无已验证的取消接口；确认不会改变申请状态。")))
                    })
                }
                bookingFieldsItems(state.data)
                item("venue-attachment-form") { VenueAttachmentSection(graph, page.id, state) { scope.launch { query.refresh() } } }
                item("timeline-title") { SmallTitle("审批进度") }
                bookingQueryFeedback(timelineState, { scope.launch { timeline.refresh() } }, "timeline")
                itemsIndexed((timelineState.data as? JsonArray)?.toList().orEmpty(), key = { index, _ -> "timeline:$index" }) { index, node ->
                    SectionCard(node.bookingText("nodeName").ifBlank { "审批节点 ${index + 1}" },
                        listOf(node.bookingText("approver"), node.bookingText("opinion"), node.bookingText("reviewTime")).filter(String::isNotBlank).joinToString("\n"))
                }
                // Only server-relative userfiles paths are ever opened, using the repository's validator.
                val attachments = (state.data as? JsonObject)?.get("fileList") as? JsonArray
                itemsIndexed(attachments?.toList().orEmpty(), key = { index, _ -> "attachment:$index" }) { index, file ->
                    val path = file.bookingText("path").ifBlank { file.bookingText("filepath") }
                    val url = graph.repositories.fileUrl(path)
                    if (url != null) ArrowPreference(file.bookingText("filename").ifBlank { "附件 ${index + 1}" },
                        summary = "打开服务端附件", onClick = { graph.platform.openUrl(url) })
                }
                bookingConfirmationItems(confirmation, controller::edit)
                if (confirmation.phase == BookingPhase.PREVIEW) item("confirm-cancel") {
                    TextButton("核对完毕，打开最终确认", onClick = { dialog = true })
                }
                if (!state.loading && state.error == null && state.data?.let { bookingFields(it) }.isNullOrEmpty()) item { SectionCard("暂无申请详情") }
            }
        }
        BookingFinalDialog(controller, dialog) { dialog = false }
    }
}

internal data class FitnessQueries(val counts: BookingQuery, val mine: BookingQuery)

@Composable
internal fun rememberFitnessQueries(graph: AppGraph): FitnessQueries {
    val owner = graph.sessions.session.value?.userId
    val counts = rememberBookingQuery(Triple(graph, owner, "fitness-counts")) { graph.repositories.fitness.counts() }
    val mine = rememberBookingQuery(Triple(graph, owner, "fitness-mine")) { graph.repositories.fitness.myInfo() }
    return remember(counts, mine) { FitnessQueries(counts, mine) }
}

@Composable
private fun FitnessList(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val userId = graph.sessions.session.value?.userId.orEmpty()
    val query = rememberBookingPager(graph to ("fitness-tests" to userId), "physicalTestList") { offset, limit ->
        graph.repositories.fitness.tests(io.github.betterzslf.core.net.queryParameters("userId" to userId, "offset" to offset.toString(), "limit" to limit.toString()))
    }
    val paged by query.state.collectAsState()
    val state = paged.query
    val aux = rememberFitnessQueries(graph)
    val counts by aux.counts.state.collectAsState()
    val mine by aux.mine.state.collectAsState()
    val scope = rememberCoroutineScope()
    val refresh: () -> Unit = { scope.launch { query.refresh() }; scope.launch { aux.counts.refresh() }; scope.launch { aux.mine.refresh() } }
    Screen(page.title.ifBlank { "体测报名" }, back, actions = { TextButton("刷新", enabled = !state.loading, onClick = refresh) }) { padding ->
        BookingLazyList(padding, state, refresh, state.data?.let { bookingRows(it, "physicalTestList") }.orEmpty(), "暂无体测场次",
            header = {
                item("all-items") { ArrowPreference("查询全部体测项目", summary = "完整惰性列表与本地筛选；未选场次时仅查询", onClick = {
                    open(Route.Page("fitnessitems", title = "全部体测项目"))
                }) }
                item("fitness-evidence") { SectionCard("场次查询", "如服务端场次接口异常，项目、按日期人数与当前报名信息仍可分别查询。人数原始分类含义未确认，不换算成剩余名额。") }
            }, footer = {
                if (paged.moreError != null) item("more-error") { SectionCard("下一页加载失败", paged.moreError.orEmpty()) }
                if (!paged.end && state.data != null) item("server-more") { TextButton("加载下一页场次", enabled = !state.loading, onClick = { scope.launch { query.more() } }) }
                item("mine-title") { SmallTitle("当前报名信息（服务端原值）") }
                bookingQueryFeedback(mine, { scope.launch { aux.mine.refresh() } }, "mine")
                bookingFieldsItems(mine.data, keyPrefix = "mine-field")
                item("counts-title") { SmallTitle("按日期人数（服务端原值）") }
                bookingQueryFeedback(counts, { scope.launch { aux.counts.refresh() } }, "counts")
                bookingFieldsItems(counts.data, keyPrefix = "counts-field")
            }) { item, index ->
            val id = item.bookingId()
            BookingRecordCard(item, item.bookingLabel("体测场次 ${index + 1}"), if (id.isBlank()) null else ({
                open(Route.Page("fitnessitems", BookingSelection(physicalId = id).encode(), item.bookingLabel("体测项目")))
            }))
            if (id.isBlank()) Text("此场次缺少 id，暂仅展示，不构造报名标识。")
        }
    }
}

@Composable
private fun FitnessItems(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val selection = remember(page.id) { BookingSelection.decode(page.id, physical = true) }
    val query = rememberBookingQuery(graph to ("fitness-items" to selection.physicalId)) {
        graph.repositories.fitness.items(selection.physicalId.ifBlank { null })
    }
    val state by query.state.collectAsState()
    val rows = remember(state.data) { state.data?.let { bookingRows(it, "getRelatedItemsList") }.orEmpty() }
    var chosen by remember { mutableStateOf(selection.itemIds.toSet()) }
    val returnedIds = rows.map { it.bookingId() }.filter(String::isNotBlank).toSet()
    val scope = rememberCoroutineScope()
    Screen(page.title.ifBlank { "体测项目" }, back, actions = { TextButton("刷新", enabled = !state.loading, onClick = { scope.launch { query.refresh() } }) }) { padding ->
        BookingLazyList(padding, state, { scope.launch { query.refresh() } }, rows, "暂无体测项目",
            header = {
                item("fitness-selection-help") { SectionCard(if (selection.physicalId.isBlank()) "全局项目查询" else "本地选择项目",
                    if (selection.physicalId.isBlank()) "请先从体测场次进入，当前列表不生成报名。所有返回记录均可通过搜索和加载更多访问。"
                    else "场次标识：${selection.physicalId}\n所选项目 ${chosen.size} 个；选择不会提交报名。") }
                if (chosen.any { it !in returnedIds }) item("stale-items") {
                    SectionCard("部分项目未在最新结果中返回", "请重新选择后预览。")
                    TextButton("清除失效选择", onClick = { chosen = chosen.intersect(returnedIds) })
                }
                if (selection.physicalId.isNotBlank()) item("fitness-preview-entry") {
                    Button(enabled = !state.loading && state.error == null && chosen.all { it in returnedIds }, onClick = {
                        open(Route.Page("fitnessenrol", selection.copy(itemIds = chosen.toList()).encode(), "体测报名核对"))
                    }) { Text("预览报名选择（尚未提交）") }
                }
            }) { item, index ->
            val id = item.bookingId()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BookingRecordCard(item, item.bookingLabel("项目 ${index + 1}"))
                if (selection.physicalId.isNotBlank()) BookingCheck(id in chosen, if (id.isBlank()) "项目缺少 id，仅展示" else "选择此项目",
                    enabled = id.isNotBlank() && !state.loading) { chosen = if (it) chosen + id else chosen - id }
            }
        }
    }
}
