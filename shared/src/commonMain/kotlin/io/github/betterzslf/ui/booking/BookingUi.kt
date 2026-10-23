package io.github.betterzslf.ui.booking

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.feature.booking.*
import io.github.betterzslf.ui.SectionCard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun rememberBookingPager(key: Any?, container: String, load: suspend (Int, Int) -> ApiResult<JsonElement>): BookingPagedQuery {
    val loader by rememberUpdatedState(load)
    val pager = remember(key, container) { BookingPagedQuery(container) { offset, limit -> loader(offset, limit) } }
    LaunchedEffect(pager) { pager.refresh() }
    return pager
}

@Composable
internal fun rememberBookingQuery(key: Any?, load: suspend () -> ApiResult<JsonElement>): BookingQuery {
    val loader by rememberUpdatedState(load)
    val query = remember(key) { BookingQuery { loader() } }
    LaunchedEffect(query) { query.refresh() }
    return query
}

internal fun LazyListScope.bookingQueryFeedback(state: BookingQueryState, refresh: () -> Unit, prefix: String = "query") {
    if (state.loading) item("$prefix:loading") {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
    }
    if (state.error != null) item("$prefix:error") {
        SectionCard(if (state.data == null) "加载失败" else "刷新失败，保留上次结果",
            state.error.message + "\n点按重新查询，不会重试任何写操作。", refresh)
    }
    if (state.message.isNotBlank()) item("$prefix:message") { SectionCard("服务端提示", state.message) }
}

/** Every returned record is its own lazy item. Full-result APIs are paged locally, never with guessed query parameters. */
@Composable
internal fun BookingLazyList(
    padding: PaddingValues,
    state: BookingQueryState,
    refresh: () -> Unit,
    rows: List<JsonElement>,
    empty: String,
    header: LazyListScope.() -> Unit = {},
    footer: LazyListScope.() -> Unit = {},
    row: @Composable (JsonElement, Int) -> Unit,
) {
    var window by remember { mutableStateOf(BookingWindow()) }
    val filtered = remember(rows, window.query, window.status) { window.filtered(rows) }
    val shown = filtered.take(window.visible)
    val statuses = remember(rows) { rows.map(::bookingRawStatus).filter(String::isNotBlank).distinct().sorted() }
    val listState = rememberLazyListState()
    val hasMore = shown.size < filtered.size
    val loadMore = { window = window.more(filtered.size) }
    LaunchedEffect(listState, hasMore, shown.size) {
        if (hasMore) snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 3 && layout.totalItemsCount > 0
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) loadMore() }
    }
    PullToRefresh(state.loading, refresh,
        modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            header()
            bookingQueryFeedback(state, refresh)
            if (rows.isNotEmpty()) {
                item("search") {
                    TextField(window.query, { window = window.search(it) }, modifier = Modifier.fillMaxWidth(),
                        label = "搜索已返回的内容", singleLine = true)
                }
                if (statuses.isNotEmpty()) item("status-filter") {
                    // Raw codes are kept verbatim: no undocumented numeric-to-business-state mapping.
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("状态筛选：${window.status.ifBlank { "全部" }}（服务端原值）")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton("全部", onClick = { window = window.filter("") })
                            TextButton("下一状态", onClick = {
                                val next = (statuses.indexOf(window.status) + 1) % statuses.size
                                window = window.filter(statuses[next])
                            })
                        }
                    }
                }
                item("result-count") { Text("本地筛选 ${filtered.size} / ${rows.size} 条 · 已展开 ${shown.size} 条") }
            }
            if (!state.loading && state.error == null && rows.isEmpty()) item("empty") {
                SectionCard(empty, "服务端尚未返回可展示的记录，可稍后刷新。")
            }
            if (rows.isNotEmpty() && filtered.isEmpty()) item("no-match") {
                SectionCard("没有匹配结果", "清除搜索或切回全部状态后重试。")
            }
            itemsIndexed(shown, key = { index, item -> "record:$index:${item.bookingId()}" },
                contentType = { _, _ -> "booking-record" }) { index, item -> row(item, index) }
            if (hasMore) item("load-more") { TextButton("加载更多（${shown.size}/${filtered.size}）", onClick = loadMore) }
            if (rows.isNotEmpty() && !hasMore && filtered.isNotEmpty()) item("end") { Text("已展示全部匹配记录") }
            footer()
        }
    }
}

@Composable
internal fun BookingRecordCard(item: JsonElement, title: String, onClick: (() -> Unit)? = null) {
    val fields = remember(item) { bookingFields(item) }
    var expanded by remember(item) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionCard(title, fields.take(if (expanded) 24 else 5).joinToString("\n") { "${it.path}：${it.value.take(500)}" }, onClick)
        if (fields.size > 5) TextButton(if (expanded) "收起字段" else "展开返回字段", onClick = { expanded = !expanded })
        if (expanded && fields.size > 24) Text("更多字段请打开详情查看。")
    }
}

internal fun LazyListScope.bookingFieldsItems(data: JsonElement?, prefix: String = "", keyPrefix: String = "field") {
    if (data == null) return
    itemsIndexed(bookingFields(data, prefix), key = { index, field -> "$keyPrefix:$index:${field.path}" },
        contentType = { _, _ -> "booking-field" }) { _, field ->
        SectionCard(field.path, field.value)
    }
}

@Composable
internal fun BookingCheck(checked: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Checkbox(if (checked) ToggleableState.On else ToggleableState.Off, onClick = { onChange(!checked) }, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}

/** Kept inside the Screen/Scaffold content, never made a navigation destination. */
@Composable
internal fun BookingFinalDialog(controller: BookingConfirmation, requested: Boolean, close: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var finalAcknowledged by remember(state.previewId) { mutableStateOf(false) }
    val show = requested && state.phase == BookingPhase.PREVIEW
    OverlayDialog(show = show, title = "最终核对：${state.preview?.title.orEmpty()}",
        summary = if (controller.permitsSubmission) "确认后将向学校服务提交当前预览中的信息。请核对场地、日期、身份资料与附件；失败后保留步骤，不会自动重提。"
            else "此操作暂未接通。确认后只展示当前状态，不会改变预约或报名。",
        onDismissRequest = close) {
        // The full preview is a lazy list below; this bounded dialog is only the final explicit acknowledgement.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BookingCheck(finalAcknowledged, if (controller.permitsSubmission) "我已核对完整预览，确认提交以上信息" else "我已核对预览，了解此操作暂不提交") { finalAcknowledged = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("返回编辑", onClick = { controller.edit(); close() }, modifier = Modifier.weight(1f))
                TextButton(if (controller.permitsSubmission) "确认提交" else "确认查看状态", enabled = finalAcknowledged,
                    onClick = { close(); scope.launch { controller.confirm(state.previewId, finalAcknowledged) } }, modifier = Modifier.weight(1f))
            }
        }
    }
}

internal fun LazyListScope.bookingConfirmationItems(state: BookingConfirmationState, edit: () -> Unit) {
    if (state.message.isNotBlank()) item("confirmation-message") {
        SectionCard(if (state.phase == BookingPhase.BLOCKED) "提交已暂停 · 未发送" else "操作状态", state.message)
    }
    val preview = state.preview
    if (preview != null) {
        item("preview-title") { SmallTitle("最终预览 · ${preview.title}") }
        itemsIndexed(preview.lines, key = { index, _ -> "preview:$index" }) { _, line -> SectionCard("核对项", line) }
    }
    itemsIndexed(state.steps, key = { index, _ -> "step:$index" }) { _, step -> SectionCard(step.label, bookingStepLabel(step.phase)) }
    if (state.phase == BookingPhase.BLOCKED) item("return-edit") { TextButton("返回本地编辑", onClick = edit) }
}
