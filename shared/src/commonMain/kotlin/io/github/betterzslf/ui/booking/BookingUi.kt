package io.github.betterzslf.ui.booking

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.feature.booking.*
import io.github.betterzslf.ui.ConfirmationCheckRow
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
        SectionCard(if (state.data == null) AppText.Common.LOAD_FAILED else AppText.Common.REFRESH_FAILED_PREVIOUS_RESULTS_RETAINED,
            state.error.message + AppText.Booking.REQUERY_WITHOUT_WRITE_RETRY_NEWLINE_SUFFIX, refresh)
    }
    if (state.message.isNotBlank()) item("$prefix:message") { SectionCard(AppText.Common.SERVER_NOTICE_TITLE, state.message) }
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
                        label = AppText.Booking.SEARCH_RETURNED_CONTENT_FIELD, singleLine = true)
                }
                if (statuses.isNotEmpty()) item("status-filter") {
                    // Raw codes are kept verbatim: no undocumented numeric-to-business-state mapping.
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(AppText.Booking.statusFilter((window.status.ifBlank { AppText.Common.ALL })))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(AppText.Common.ALL, onClick = { window = window.filter("") })
                            TextButton(AppText.Booking.NEXT_STATUS_FILTER, onClick = {
                                val next = (statuses.indexOf(window.status) + 1) % statuses.size
                                window = window.filter(statuses[next])
                            })
                        }
                    }
                }
                item("result-count") { Text(AppText.Booking.localFilterCounts((filtered.size), (rows.size), (shown.size))) }
            }
            if (!state.loading && state.error == null && rows.isEmpty()) item("empty") {
                SectionCard(empty, AppText.Booking.NO_SERVER_RECORDS_REFRESH_LATER_HINT)
            }
            if (rows.isNotEmpty() && filtered.isEmpty()) item("no-match") {
                SectionCard(AppText.Common.NO_MATCHING_RESULTS, AppText.Booking.CLEAR_SEARCH_OR_RESET_STATUS_HINT)
            }
            itemsIndexed(shown, key = { index, item -> "record:$index:${item.bookingId()}" },
                contentType = { _, _ -> "booking-record" }) { index, item -> row(item, index) }
            if (hasMore) item("load-more") { TextButton(AppText.Booking.loadMoreCounts((shown.size), (filtered.size)), onClick = loadMore) }
            if (rows.isNotEmpty() && !hasMore && filtered.isNotEmpty()) item("end") { Text(AppText.Booking.ALL_MATCHING_RECORDS_SHOWN) }
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
        if (fields.size > 5) TextButton(if (expanded) AppText.Booking.COLLAPSE_FIELDS else AppText.Booking.EXPAND_RETURNED_FIELDS, onClick = { expanded = !expanded })
        if (expanded && fields.size > 24) Text(AppText.Booking.OPEN_DETAILS_FOR_MORE_FIELDS_HINT)
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
    ConfirmationCheckRow(checked = checked, label = label, enabled = enabled, onCheckedChange = onChange)
}

/** Kept inside the Screen/Scaffold content, never made a navigation destination. */
@Composable
internal fun BookingFinalDialog(controller: BookingConfirmation, requested: Boolean, close: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var finalAcknowledged by remember(state.previewId) { mutableStateOf(false) }
    val show = requested && state.phase == BookingPhase.PREVIEW
    OverlayDialog(show = show, title = AppText.Booking.finalCheckTitle((state.preview?.title.orEmpty())),
        summary = if (controller.permitsSubmission) AppText.Booking.FINAL_SUBMISSION_REVIEW_HINT
            else AppText.Booking.UNCONNECTED_OPERATION_PREVIEW_ONLY_HINT,
        onDismissRequest = close) {
        // The full preview is a lazy list below; this bounded dialog is only the final explicit acknowledgement.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BookingCheck(finalAcknowledged, if (controller.permitsSubmission) AppText.Booking.FINAL_SUBMISSION_ACKNOWLEDGEMENT else AppText.Booking.PREVIEW_ONLY_ACKNOWLEDGEMENT) { finalAcknowledged = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(AppText.Common.BACK_TO_EDIT, onClick = { controller.edit(); close() }, modifier = Modifier.weight(1f))
                TextButton(if (controller.permitsSubmission) AppText.Common.CONFIRM_SUBMISSION else AppText.Booking.CONFIRM_VIEW_STATUS, enabled = finalAcknowledged,
                    onClick = { close(); scope.launch { controller.confirm(state.previewId, finalAcknowledged) } }, modifier = Modifier.weight(1f))
            }
        }
    }
}

internal fun LazyListScope.bookingConfirmationItems(state: BookingConfirmationState, edit: () -> Unit) {
    if (state.message.isNotBlank()) item("confirmation-message") {
        SectionCard(if (state.phase == BookingPhase.BLOCKED) AppText.Booking.SUBMISSION_PAUSED_NOT_SENT else AppText.Common.OPERATION_STATUS_TITLE, state.message)
    }
    val preview = state.preview
    if (preview != null) {
        item("preview-title") { SmallTitle(AppText.Booking.finalPreviewTitle((preview.title))) }
        itemsIndexed(preview.lines, key = { index, _ -> "preview:$index" }) { _, line -> SectionCard(AppText.Common.REVIEW_CHECKLIST_TITLE, line) }
    }
    itemsIndexed(state.steps, key = { index, _ -> "step:$index" }) { _, step -> SectionCard(step.label, bookingStepLabel(step.phase)) }
    if (state.phase == BookingPhase.BLOCKED) item("return-edit") { TextButton(AppText.Booking.BACK_TO_LOCAL_EDIT, onClick = edit) }
}
