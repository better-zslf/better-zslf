package io.github.betterzslf.ui.booking

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.booking.*
import io.github.betterzslf.ui.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

@Composable
internal fun rememberBookingPager(key: Any?, container: String, load: suspend (Int, Int) -> ApiResult<JsonElement>): BookingPagedQuery {
    val loader by rememberUpdatedState(load)
    val pager = remember(key, container) { BookingPagedQuery(container) { offset, limit -> loader(offset, limit) } }
    LaunchedEffect(pager) { pager.refresh() }
    return pager
}

@Composable
internal fun rememberBookingQuery(key: Any?, enabled: Boolean = true, load: suspend () -> ApiResult<JsonElement>): BookingQuery {
    val loader by rememberUpdatedState(load)
    val query = remember(key) { BookingQuery { loader() } }
    LaunchedEffect(query, enabled) { query.refreshIfReady(enabled) }
    return query
}

internal fun LazyListScope.bookingQueryFeedback(state: BookingQueryState, refresh: () -> Unit, prefix: String = "query") {
    when (state.feedback) {
        BookingQueryFeedback.LOADING -> item("$prefix:loading") {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
        }
        BookingQueryFeedback.ERROR -> {
            val error = state.error ?: return
            item("$prefix:error") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBanner(error.message + AppText.Booking.REQUERY_WITHOUT_WRITE_RETRY_NEWLINE_SUFFIX,
                        title = if (state.data == null) AppText.Common.LOAD_FAILED else AppText.Common.REFRESH_FAILED_PREVIOUS_RESULTS_RETAINED,
                        tone = UiTone.Warning)
                    TextButton(AppText.Common.REFRESH_INFORMATION, onClick = refresh)
                }
            }
        }
        BookingQueryFeedback.NONE -> Unit
    }
}

/** Keep the body behind Screen's blur bar; only scroll content reserves native/app-bar/navigation insets. */
@Composable
internal fun bookingContentPadding(padding: PaddingValues, form: Boolean = false): PaddingValues {
    val direction = LocalLayoutDirection.current
    val base = pageContentPadding(padding, LocalNavigationBottomInset.current, direction,
        horizontal = if (form) AppUiTokens.FormPadding else AppUiTokens.PagePadding)
    if (!form) return base
    return PaddingValues(
        start = base.calculateStartPadding(direction),
        end = base.calculateEndPadding(direction),
        top = padding.calculateTopPadding() + AppUiTokens.FormPadding,
        bottom = base.calculateBottomPadding(),
    )
}

/** Server paging and the bounded local window are unchanged; search/filter never send new query parameters. */
@Composable
internal fun BookingLazyList(
    padding: PaddingValues,
    state: BookingQueryState,
    refresh: () -> Unit,
    rows: List<JsonElement>,
    empty: String,
    header: LazyListScope.() -> Unit = {},
    footer: LazyListScope.() -> Unit = {},
    filterable: Boolean = true,
    grid: Boolean = false,
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
    val fontScale = LocalDensity.current.fontScale
    PullToRefresh(state.loading, refresh, modifier = Modifier.fillMaxSize().consumeWindowInsets(padding), contentPadding = padding, refreshTexts = appRefreshTexts()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = if (grid && maxWidth >= 360.dp && fontScale <= 1.2f) 2 else 1
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = bookingContentPadding(padding),
                verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                header()
                bookingQueryFeedback(state, refresh)
                if (rows.isNotEmpty() && filterable) {
                    item("search") {
                        TextField(window.query, { window = window.search(it) }, modifier = Modifier.fillMaxWidth(),
                            label = AppText.Booking.SEARCH_RETURNED_CONTENT_FIELD, cornerRadius = AppUiTokens.FieldRadius, singleLine = true)
                    }
                    if (statuses.isNotEmpty()) item("status-filter") {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BookingChoice(AppText.Common.ALL, window.status.isBlank(), onClick = { window = window.filter("") })
                            statuses.forEach { raw -> BookingChoice(bookingStatusText(raw), window.status == raw,
                                onClick = { window = window.filter(raw) }) }
                        }
                    }
                    item("result-count") { Text(AppText.Booking.loadedMatchingCounts(filtered.size, rows.size), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                }
                if (!state.loading && state.error == null && rows.isEmpty()) item("empty") { EmptyCard(empty) }
                if (rows.isNotEmpty() && filtered.isEmpty()) item("no-match") { EmptyCard(AppText.Common.NO_MATCHING_RESULTS) }
                itemsIndexed(shown.chunked(columns), key = { index, group -> "record:$columns:$index:${group.first().bookingId()}" },
                    contentType = { _, _ -> "booking-record" }) { groupIndex, group ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        group.forEachIndexed { column, item -> Box(Modifier.weight(1f)) { row(item, groupIndex * columns + column) } }
                        if (group.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
                if (hasMore) item("load-more") { TextButton(AppText.Booking.loadMoreCounts(shown.size, filtered.size), onClick = loadMore) }
                footer()
            }
        }
    }
}

@Composable
internal fun BookingPanel(title: String = "", content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth().animateContentSize(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title.isNotBlank()) Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
internal fun BookingPrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(text, onClick, modifier = Modifier.fillMaxWidth(), enabled = enabled,
        minHeight = AppUiTokens.ButtonHeight, cornerRadius = AppUiTokens.ButtonRadius,
        colors = ButtonDefaults.textButtonColorsPrimary())
}

@Composable
internal fun BookingChoice(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(text, onClick, enabled = enabled, minHeight = 48.dp, cornerRadius = AppUiTokens.FieldRadius,
        modifier = Modifier.semantics { this.selected = selected },
        colors = if (selected) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
}

/** No status-code translation or approval-color inference without an observed contract. */
internal fun bookingStatusText(raw: String): String = if (raw.isBlank()) AppText.Booking.STATUS_UNCONFIRMED_LABEL else AppText.Booking.unverifiedStatus(raw)

@Composable
internal fun BookingBadge(text: String, tone: UiTone = UiTone.Neutral) {
    val color = when (tone) {
        UiTone.Info -> MiuixTheme.colorScheme.primary
        UiTone.Positive -> AppUiTokens.Positive
        UiTone.Warning -> AppUiTokens.Warning
        UiTone.Negative -> AppUiTokens.Negative
        UiTone.Neutral -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Text(text, fontSize = 12.sp, color = color, modifier = Modifier
        .background(color.copy(alpha = 0.10f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp))
}

internal fun JsonElement.bookingValue(vararg names: String): String = names.firstNotNullOfOrNull { bookingText(it).takeIf(String::isNotBlank) }.orEmpty()
internal fun bookingObject(data: JsonElement): JsonElement = (data as? JsonObject)?.let {
    it["sportsVenueApply"] ?: it["siteInfo"] ?: it["site"] ?: it
} ?: data

internal fun bookingSummary(data: JsonElement): List<Pair<String, String>> {
    val item = bookingObject(data)
    val application = (data as? JsonObject)?.containsKey("sportsVenueApply") == true || item.bookingText("applicants") == "团体" || item.bookingText("uName").isNotBlank()
    fun value(vararg keys: String) = item.bookingValue(*keys)
    return buildList {
        val date = value("dateTime", "date", "userPhysicalTime", "physicalTime")
        if (date.isNotBlank()) add(AppText.Booking.DATE_LABEL to bookingDateLabel(date.take(10)).ifBlank { date })
        val start = value("startNode", "startTime", "testStart")
        val end = value("endNode", "endTime", "testEnd")
        if (start.isNotBlank() || end.isNotBlank()) add(AppText.Booking.TIME_LABEL to listOf(start, end).filter(String::isNotBlank).joinToString(" – "))
        value("time").takeIf(String::isNotBlank)?.let { add(AppText.Booking.TIME_SLOT_LABEL to it) }
        value("address", "location", "siteAddress", "place").takeIf(String::isNotBlank)?.let { add((if (application) AppText.Booking.APPLICATION_VENUE_TITLE else AppText.Booking.LOCATION_LABEL) to it) }
        value("openTime", "openingTime", "businessHours").takeIf(String::isNotBlank)?.let { add(AppText.Booking.OPENING_TIME_LABEL to it) }
        value("groupName", "group").ifBlank { if (application) value("name") else "" }.takeIf(String::isNotBlank)?.let { add(AppText.Booking.APPLICANT_GROUP_LABEL to it) }
        value("content", "description", "remark", "text").takeIf(String::isNotBlank)?.let { add(AppText.Booking.DESCRIPTION_LABEL to it) }
        value("contactName", "linkman", "uName").takeIf(String::isNotBlank)?.let { add(AppText.Booking.CONTACT_NAME_LABEL to it) }
        value("phone", "uphone", "uPhone").takeIf(String::isNotBlank)?.let { add(AppText.Booking.CONTACT_PHONE_FIELD to bookingMasked(it)) }
        value("userPeriod").takeIf(String::isNotBlank)?.let { add(AppText.Booking.REGISTRATION_PERIOD_LABEL to when (it) { "0" -> AppText.Booking.MORNING_DISPLAY_LABEL; "1" -> AppText.Booking.AFTERNOON_DISPLAY_LABEL; else -> AppText.Booking.unverifiedStatus(it) }) }
        value("mcount").takeIf(String::isNotBlank)?.let { add(AppText.Booking.CANCELLATION_LIMIT_LABEL to it) }
        value("pcount").takeIf(String::isNotBlank)?.let { add(AppText.Booking.CANCELLATIONS_USED_LABEL to it) }
    }
}

@Composable
internal fun BookingRecordCard(item: JsonElement, title: String, onClick: (() -> Unit)? = null) {
    val record = bookingObject(item)
    val summary = remember(item) { bookingSummary(item) }
    val raw = bookingRawStatus(record)
    Card(Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(AppUiTokens.CardPadding),
        onClick = onClick, pressFeedbackType = PressFeedbackType.Sink, showIndication = onClick != null) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (raw.isNotBlank()) BookingBadge(bookingStatusText(raw), UiTone.Neutral)
            summary.forEach { (label, value) -> BookingDetailLine(label, value) }
            if (onClick != null) Text(AppText.Booking.RECORD_DETAILS_ACTION, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary)
            else if (summary.isEmpty()) Text(AppText.Booking.RECORD_INFORMATION_SOURCE_HINT, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, fontSize = 13.sp)
        }
    }
}

@Composable
internal fun BookingDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, fontSize = 15.sp)
    }
}

/** Raw fields remain available on demand, not as the page's main card hierarchy. Core redaction is reused. */
@Composable
internal fun BookingRawDetails(data: JsonElement, prefix: String = "") {
    var expanded by remember(data) { mutableStateOf(false) }
    var start by remember(data) { mutableIntStateOf(0) }
    val fields = remember(data, prefix) { bookingFields(data, prefix) }
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(if (expanded) AppText.Booking.COLLAPSE_RETURNED_INFORMATION_ACTION else AppText.Booking.VIEW_RETURNED_INFORMATION_ACTION, onClick = { expanded = !expanded })
        if (expanded) {
            fields.drop(start).take(12).forEach { BookingDetailLine(it.path, it.value) }
            if (fields.size > 12) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(AppText.Booking.PREVIOUS_RETURNED_FIELDS_ACTION, enabled = start > 0, onClick = { start = (start - 12).coerceAtLeast(0) })
                TextButton(AppText.Booking.NEXT_RETURNED_FIELDS_ACTION, enabled = start + 12 < fields.size, onClick = { start += 12 })
            }
        }
    }
}

internal fun LazyListScope.bookingFieldsItems(data: JsonElement?, prefix: String = "", keyPrefix: String = "field") {
    if (data == null) return
    item("$keyPrefix:summary") {
        val record = bookingObject(data)
        val title = if ((data as? JsonObject)?.containsKey("sportsVenueApply") == true) record.bookingValue("place", "venueName").ifBlank { AppText.Booking.APPLICATION_SUMMARY_TITLE } else record.bookingLabel(AppText.Common.DETAILS_FALLBACK_LABEL)
        BookingRecordCard(data, title)
    }
    item("$keyPrefix:raw") { BookingRawDetails(data, prefix) }
}

/** School dates share the existing UTC+08:00 business calendar, never the sample dates from the mockup. */
internal fun bookingSchoolDate(offset: Int = 0, from: String = ""): String {
    val millis = bookingSchoolMidnight(from) ?: kotlin.time.Clock.System.now().toEpochMilliseconds()
    val days = (millis + 28_800_000L) / 86_400_000L + offset
    val z = days + 719468
    val era = z / 146097
    val doe = (z - era * 146097).toInt()
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    var year = yoe + era.toInt() * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = mp + if (mp < 10) 3 else -9
    if (month <= 2) year++
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
}

internal fun bookingDateLabel(date: String): String = bookingWeekday(date)?.let {
    AppText.Booking.displayCalendarDate(date.substring(5, 7).toInt(), date.takeLast(2).toInt(), it)
}.orEmpty()

@Composable
internal fun BookingDateChoices(value: String, enabled: Boolean = true, from: String = "", onChoose: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(7) { offset ->
            val date = bookingSchoolDate(offset, from)
            val chosen = value == date
            Card(Modifier.width(100.dp).selectable(chosen, enabled = enabled, role = Role.RadioButton, onClick = { onChoose(date) }),
                cornerRadius = AppUiTokens.FieldRadius, insideMargin = PaddingValues(12.dp),
                colors = CardDefaults.defaultColors(color = if (chosen) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f) else MiuixTheme.colorScheme.surface)) {
                Text(AppText.Booking.displayWeekday(bookingWeekday(date) ?: -1),
                    color = if (chosen) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface)
                Text(AppText.Booking.displayMonthDay(date.substring(5, 7).toInt(), date.takeLast(2).toInt()), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
internal fun BookingCounts(data: JsonElement?, date: String = bookingSchoolDate()) {
    // Protocol-only dictionary suffix, matching FitnessSchedule; displayWeekday must not build this key.
    val countsKeyWeekday = bookingWeekday(date)?.let { listOf("日", "一", "二", "三", "四", "五", "六")[it] }.orEmpty()
    val day = (data as? JsonObject)?.let { it[date] ?: it["$date($countsKeyWeekday)"] }
    val values = listOf("mlbCount" to AppText.Booking.MORNING_BOYS_COUNT_LABEL, "mlgCount" to AppText.Booking.MORNING_GIRLS_COUNT_LABEL, "albCount" to AppText.Booking.AFTERNOON_BOYS_COUNT_LABEL, "algCount" to AppText.Booking.AFTERNOON_GIRLS_COUNT_LABEL)
    BookingPanel(AppText.Booking.returnedPeopleTitle(bookingDateLabel(date).ifBlank { date })) {
        if (day == null) Text(AppText.Booking.DAILY_COUNTS_NOT_READ_HINT, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.chunked(2).forEach { pair -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (key, label) -> Column(Modifier.weight(1f)) {
                    Text(day.bookingText(key).ifBlank { "—" }, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(label, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                } }
            } }
        }
    }
}

@Composable
internal fun BookingCheck(checked: Boolean, label: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ConfirmationCheckRow(checked = checked, label = label, enabled = enabled, onCheckedChange = onChange)
}

/** Read-only refresh may revoke a PREVIEW, never a running/uncertain receipt. Uses the existing domain edit API. */
internal fun BookingConfirmation.invalidatePreviewForRefresh(): Boolean {
    if (state.value.phase != BookingPhase.PREVIEW) return false
    edit()
    return state.value.phase == BookingPhase.EDITING
}

/** This remains a bounded final acknowledgement; full review and receipts stay on the owning page. */
@Composable
internal fun BookingFinalDialog(controller: BookingConfirmation, requested: Boolean, close: () -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var finalAcknowledged by remember(controller, state.previewId) { mutableStateOf(false) }
    val show = requested && state.phase == BookingPhase.PREVIEW
    OverlayDialog(show = show, title = AppText.Booking.finalCheckTitle(state.preview?.title.orEmpty()),
        summary = if (controller.permitsSubmission) AppText.Booking.FINAL_SUBMISSION_REVIEW_HINT else AppText.Booking.UNCONNECTED_OPERATION_PREVIEW_ONLY_HINT,
        onDismissRequest = close) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BookingCheck(finalAcknowledged, if (controller.permitsSubmission) AppText.Booking.FINAL_SUBMISSION_ACKNOWLEDGEMENT else AppText.Booking.PREVIEW_ONLY_ACKNOWLEDGEMENT) { finalAcknowledged = it }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(AppText.Common.BACK_TO_EDIT, onClick = { controller.edit(); close() }, modifier = Modifier.weight(1f))
                TextButton(if (controller.permitsSubmission) AppText.Common.CONFIRM_SUBMISSION else AppText.Booking.CONFIRM_VIEW_STATUS, enabled = finalAcknowledged,
                    colors = ButtonDefaults.textButtonColorsPrimary(), onClick = { close(); scope.launch { controller.confirm(state.previewId, finalAcknowledged) } }, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun BookingReceipt(state: BookingConfirmationState) {
    if (state.message.isNotBlank()) InfoBanner(state.message, title = when (state.phase) {
        BookingPhase.COMPLETED -> AppText.Booking.RECEIPT_RECEIVED_TITLE
        BookingPhase.UNCERTAIN -> AppText.Booking.RECEIPT_UNCERTAIN_TITLE
        BookingPhase.FAILED -> AppText.Booking.SUBMISSION_STOPPED_TITLE
        BookingPhase.BLOCKED -> AppText.Booking.SUBMISSION_NOT_SENT_TITLE
        else -> AppText.Booking.OPERATION_PROGRESS_TITLE
    }, tone = if (state.phase == BookingPhase.COMPLETED) UiTone.Positive else UiTone.Warning)
    state.preview?.let { preview -> BookingPanel(AppText.Booking.finalPreviewTitle(preview.title)) {
        preview.lines.forEach { Text(it, fontSize = 14.sp) }
    } }
    if (state.steps.isNotEmpty()) BookingPanel(AppText.Booking.SUBMISSION_PROGRESS_TITLE) {
        state.steps.forEach { step ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(step.label, modifier = Modifier.weight(1f), fontSize = 14.sp)
                BookingBadge(bookingStepLabel(step.phase), if (step.phase == BookingStepPhase.COMPLETED) UiTone.Positive else UiTone.Neutral)
            }
        }
    }
}

internal fun LazyListScope.bookingConfirmationItems(state: BookingConfirmationState, edit: () -> Unit) {
    item("confirmation-receipt") { Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) { BookingReceipt(state) } }
    if (state.phase == BookingPhase.BLOCKED) item("return-edit") { TextButton(AppText.Booking.BACK_TO_LOCAL_EDIT, onClick = edit) }
}
