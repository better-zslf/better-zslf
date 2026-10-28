package io.github.betterzslf.ui.booking

import io.github.betterzslf.core.text.AppText
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
import io.github.betterzslf.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.MiuixTheme
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
            else -> Screen(page.title.ifBlank { AppText.Booking.BOOKING_AND_FITNESS_TITLE }, onBack) { padding ->
                PageList(padding) {
                    item { SectionCard(AppText.Common.UNKNOWN_PAGE_TYPE, AppText.Booking.REOPEN_FROM_COMMUNITY_HINT) }
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
    Screen(page.title.ifBlank { AppText.Booking.VENUE_BOOKING_TITLE }, back, actions = {
        TextButton(AppText.Common.REFRESH, enabled = !state.loading, onClick = { scope.launch { query.refresh() } })
    }) { padding ->
        BookingLazyList(padding, state, { scope.launch { query.refresh() } },
            state.data?.let { bookingRows(it, "siteList") }.orEmpty(), AppText.Booking.NO_OPEN_VENUES,
            header = {
                item("venue-contract") { InfoBanner(AppText.Booking.OPEN_VENUE_STATUS_QUERY_HINT) }
                item("my-venues") { Card(cornerRadius = AppUiTokens.CardRadius) { ArrowPreference(AppText.Booking.MY_BOOKING_APPLICATIONS_TITLE, onClick = { open(Route.Page("myvenues", title = AppText.Booking.MY_BOOKINGS_TITLE)) }) } }
                item("venue-heading") { SectionTitle(AppText.Booking.SELECT_VENUE_TITLE) }
            }) { item, index ->
            val id = item.bookingId()
            BookingRecordCard(item, item.bookingLabel(AppText.Booking.venueOrdinal((index + 1))), if (id.isBlank()) null else ({
                open(Route.Page("venue", id, item.bookingLabel(AppText.Booking.VENUE_DETAIL_TITLE)))
            }))
            if (id.isBlank()) Text(AppText.Booking.RECORD_ID_MISSING_READ_ONLY)
        }
    }
}

@Composable
private fun VenueDetail(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val siteId = BookingSelection.decode(page.id).siteId
    val query = rememberBookingQuery(graph to siteId) {
        if (siteId.isBlank()) ApiResult.Failure(ApiError.Validation(listOf("siteId"), AppText.Booking.VENUE_ID_MISSING_RESELECT_HINT))
        else graph.repositories.venues.info(siteId)
    }
    val state by query.state.collectAsState()
    val scope = rememberCoroutineScope()
    Screen(page.title.ifBlank { AppText.Booking.VENUE_DETAIL_TITLE }, back, actions = {
        TextButton(AppText.Common.REFRESH, enabled = !state.loading, onClick = { scope.launch { query.refresh() } })
    }) { padding ->
        PullToRefresh(state.loading, { scope.launch { query.refresh() } }, Modifier.fillMaxSize().consumeWindowInsets(padding), contentPadding = padding, refreshTexts = appRefreshTexts()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bookingContentPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("venue-detail-help") { InfoBanner(AppText.Booking.VENUE_DETAIL_INTRO_HINT) }
                bookingQueryFeedback(state, { scope.launch { query.refresh() } })
                if (!state.loading && state.error == null && state.data?.let { bookingFields(it) }.isNullOrEmpty()) item {
                    SectionCard(AppText.Booking.NO_VENUE_DETAILS, AppText.Booking.VENUE_ID_RETAINED_REFRESH_HINT)
                }
                bookingFieldsItems(state.data)
                item("view-times") { BookingPrimaryButton(AppText.Booking.VIEW_BOOKING_SLOTS, enabled = siteId.isNotBlank()) {
                    open(Route.Page("venuetimes", siteId, AppText.Booking.BOOKING_SLOTS_TITLE))
                } }
            }
        }
    }
}

@Composable
private fun VenueTimes(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val selection = remember(page.id) { BookingSelection.decode(page.id) }
    var dateInput by remember { mutableStateOf(selection.dateTime) }
    var requestedDate by remember { mutableStateOf(selection.dateTime) }
    val hasRequestedDate = requestedDate.isNotBlank()
    val query = rememberBookingQuery(Triple(graph, selection.siteId, requestedDate), enabled = hasRequestedDate) {
        if (selection.siteId.isBlank()) ApiResult.Failure(ApiError.Validation(listOf("siteId"), AppText.Booking.VENUE_ID_MISSING))
        else if (!validBookingDate(requestedDate)) ApiResult.Failure(ApiError.Validation(listOf("dateTime"), AppText.Booking.ENTER_DATE_BEFORE_SLOT_QUERY))
        else graph.repositories.venues.times(selection.siteId, requestedDate)
    }
    val state by query.state.collectAsState()
    val rows = remember(state.data) { state.data?.let(::bookingTimeRows).orEmpty() }
    var chosen by remember { mutableStateOf(selection.timeIds.toSet()) }
    val occupied = ((state.data as? JsonObject)?.get("applyTimeList") as? JsonArray)?.map { it.bookingId() }?.toSet().orEmpty()
    val selectable = rows.filter { bookingAvailability(it) != SlotAvailability.FULL && it.bookingId() !in occupied }.map { it.bookingId() }.filter(String::isNotBlank).toSet()
    val scope = rememberCoroutineScope()
    Screen(page.title.ifBlank { AppText.Booking.BOOKING_SLOTS_TITLE }, back, actions = {
        TextButton(AppText.Common.REFRESH, enabled = hasRequestedDate && !state.loading,
            onClick = { scope.launch { query.refreshIfReady(hasRequestedDate) } })
    }) { padding ->
        BookingLazyList(padding, state, { scope.launch { query.refreshIfReady(hasRequestedDate) } }, rows,
            if (hasRequestedDate) AppText.Booking.NO_AVAILABLE_SLOTS else AppText.Booking.ENTER_DATE_BEFORE_SLOT_QUERY,
            header = {
                item("slot-explanation") { InfoBanner(AppText.Booking.SLOT_SELECTION_QUERY_HINT,
                    tone = if (hasRequestedDate) UiTone.Info else UiTone.Neutral) }
                item("slot-date") {
                    BookingPanel(AppText.Booking.SELECT_DATE_TITLE) {
                        BookingDateChoices(requestedDate, enabled = !state.loading, from = bookingSchoolDate(1)) { date ->
                            dateInput = date
                            if (requestedDate == date) scope.launch { query.refresh() }
                            else { requestedDate = date; chosen = emptySet() }
                        }
                        TextField(dateInput, { dateInput = it }, label = AppText.Booking.BOOKING_DATE_FIELD,
                            cornerRadius = AppUiTokens.FieldRadius, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        TextButton(AppText.Booking.QUERY_DATE_SLOTS, enabled = validBookingDate(dateInput) && !state.loading, onClick = {
                            if (requestedDate == dateInput) scope.launch { query.refresh() }
                            else { requestedDate = dateInput; chosen = emptySet() }
                        })
                    }
                }
                item("slot-heading") { SectionTitle(requestedDate.let(::bookingDateLabel).ifBlank { AppText.Booking.BOOKING_SLOTS_TITLE }) }
                val text = state.data?.bookingText("text").orEmpty()
                if (text.isNotBlank()) item("slot-text") { SectionCard(AppText.Booking.BOOKING_INSTRUCTIONS_TITLE, text) }
                item("selection-summary") { Text(AppText.Booking.chosenSlots((chosen.size))) }
                if (chosen.any { it !in selectable }) item("stale-selection") {
                    SectionCard(AppText.Booking.SELECTED_SLOTS_CHANGED, AppText.Booking.RECHECK_CHANGED_OR_FULL_SLOTS_HINT)
                    TextButton(AppText.Booking.CLEAR_INVALID_SELECTION, onClick = { chosen = chosen.intersect(selectable) })
                }
            }, footer = {
                item("apply-entry") {
                    BookingPrimaryButton(AppText.Booking.FILL_APPLICATION_DETAILS,
                        enabled = !state.loading && state.error == null && chosen.isNotEmpty() && chosen.all { it in selectable }) {
                        open(Route.Page("venueapply", selection.copy(timeIds = chosen.toList(), dateTime = requestedDate).encode(), AppText.Booking.BOOKING_APPLICATION_TITLE))
                    }
                }
            }, filterable = false, grid = true) { item, index ->
            val id = item.bookingId()
            val availability = if (id in occupied) SlotAvailability.FULL else bookingAvailability(item)
            BookingPanel {
                val start = item.bookingValue("startNode", "startTime")
                val end = item.bookingValue("endNode", "endTime")
                Text(listOf(start, end).filter(String::isNotBlank).joinToString("–").ifBlank { item.bookingLabel(AppText.Booking.slotOrdinal(index + 1)) },
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                BookingBadge(availability.label,
                    if (availability == SlotAvailability.AVAILABLE) UiTone.Positive else UiTone.Neutral)
                BookingCheck(id in chosen, if (id.isBlank()) AppText.Booking.SLOT_ID_MISSING_READ_ONLY else if (id in chosen) AppText.Booking.SLOT_SELECTED_LABEL else AppText.Booking.SELECT_THIS_SLOT,
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
    Screen(page.title.ifBlank { AppText.Booking.MY_BOOKING_APPLICATIONS_TITLE }, back, actions = {
        TextButton(AppText.Common.REFRESH, enabled = !state.loading, onClick = { scope.launch { query.refresh() } })
    }) { padding ->
        BookingLazyList(padding, state, { scope.launch { query.refresh() } }, state.data?.let { bookingRows(it, "applyList") }.orEmpty(),
            AppText.Booking.NO_BOOKING_APPLICATIONS, header = {
                item { InfoBanner(AppText.Booking.APPLICATION_STATUS_QUERY_HINT) }
            }, footer = {
                if (paged.moreError != null) item("more-error") { SectionCard(AppText.Common.NEXT_PAGE_LOAD_FAILED, paged.moreError.orEmpty()) }
                if (!paged.end && state.data != null) item("server-more") { TextButton(AppText.Booking.LOAD_NEXT_APPLICATIONS_PAGE, enabled = !state.loading, onClick = { scope.launch { query.more() } }) }
            }) { item, index ->
            val id = item.bookingText("applyId").ifBlank { item.bookingId() }
            BookingRecordCard(item, item.bookingValue("venueName", "place").ifBlank { item.bookingLabel(AppText.Booking.applicationOrdinal(index + 1)) }, if (id.isBlank()) null else ({
                open(Route.Page("venueapplication", id, AppText.Booking.BOOKING_APPLICATION_DETAIL_TITLE))
            }))
        }
    }
}

@Composable
private fun VenueApplication(graph: AppGraph, page: Route.Page, back: () -> Unit) {
    val query = rememberBookingQuery(graph to ("application" to page.id)) {
        if (page.id.isBlank()) ApiResult.Failure(ApiError.Validation(listOf("applyId"), AppText.Booking.APPLICATION_ID_MISSING_REQUEST_SKIPPED))
        else graph.repositories.venues.application(page.id)
    }
    val state by query.state.collectAsState()
    val timeline = rememberBookingQuery(graph to ("application-timeline" to page.id)) {
        if (page.id.isBlank()) ApiResult.Failure(ApiError.Validation(listOf("applyId"), AppText.Booking.APPLICATION_ID_MISSING))
        else graph.repositories.venues.process(page.id)
    }
    val timelineState by timeline.state.collectAsState()
    val controller = remember { BookingConfirmation(BookingAction.VENUE_CANCEL) }
    val confirmation by controller.state.collectAsState()
    var dialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Screen(page.title.ifBlank { AppText.Booking.BOOKING_APPLICATION_DETAIL_TITLE }, back, actions = {
        TextButton(AppText.Common.REFRESH, enabled = !state.loading, onClick = { scope.launch { query.refresh() } })
    }) { padding ->
        PullToRefresh(state.loading, { scope.launch { query.refresh() } }, Modifier.fillMaxSize().consumeWindowInsets(padding), contentPadding = padding, refreshTexts = appRefreshTexts()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = bookingContentPadding(padding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bookingQueryFeedback(state, { scope.launch { query.refresh() } })
                bookingFieldsItems(state.data)
                item("timeline-title") { SectionTitle(AppText.Booking.APPROVAL_PROGRESS_TITLE) }
                bookingQueryFeedback(timelineState, { scope.launch { timeline.refresh() } }, "timeline")
                itemsIndexed((timelineState.data as? JsonArray)?.toList().orEmpty(), key = { index, _ -> "timeline:$index" }) { index, node ->
                    SectionCard(node.bookingText("nodeName").ifBlank { AppText.Booking.approvalNodeOrdinal((index + 1)) },
                        listOf(node.bookingText("approver"), node.bookingText("opinion"), node.bookingText("reviewTime")).filter(String::isNotBlank).joinToString("\n"))
                }
                item("venue-attachment-form") { VenueAttachmentSection(graph, page.id, state) { scope.launch { query.refresh() } } }
                // Only server-relative userfiles paths are ever opened, using the repository's validator.
                val attachments = (state.data as? JsonObject)?.get("fileList") as? JsonArray
                itemsIndexed(attachments?.toList().orEmpty(), key = { index, _ -> "attachment:$index" }) { index, file ->
                    val path = file.bookingText("path").ifBlank { file.bookingText("filepath") }
                    val url = graph.repositories.fileUrl(path)
                    if (url != null) ArrowPreference(file.bookingText("filename").ifBlank { AppText.Booking.attachmentOrdinal((index + 1)) },
                        summary = AppText.Booking.OPEN_SERVER_ATTACHMENT, onClick = { graph.platform.openUrl(url) })
                }
                if (state.data != null) item("cancel-explanation") {
                    InfoBanner(AppText.Booking.APPLICATION_DETAILS_AND_UPLOAD_HINT,
                        title = AppText.Booking.BOOKING_CANCELLATION_UNCONNECTED, tone = UiTone.Neutral)
                    TextButton(AppText.Booking.PREVIEW_CANCELLATION_STATUS, enabled = page.id.isNotBlank(), onClick = {
                        controller.preview(BookingPreview(AppText.Booking.CANCEL_BOOKING_TITLE, listOf(AppText.Booking.applicationId(page.id), AppText.Booking.CANCELLATION_PREVIEW_PRESERVES_STATUS_HINT)))
                    })
                }
                bookingConfirmationItems(confirmation, controller::edit)
                if (confirmation.phase == BookingPhase.PREVIEW) item("confirm-cancel") {
                    TextButton(AppText.Common.OPEN_FINAL_CONFIRMATION_AFTER_REVIEW, onClick = { dialog = true })
                }
                if (!state.loading && state.error == null && state.data?.let { bookingFields(it) }.isNullOrEmpty()) item { SectionCard(AppText.Booking.NO_APPLICATION_DETAILS) }
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
    Screen(page.title.ifBlank { AppText.Booking.FITNESS_REGISTRATION_TITLE }, back, actions = { TextButton(AppText.Common.REFRESH, enabled = !state.loading, onClick = refresh) }) { padding ->
        BookingLazyList(padding, state, refresh, state.data?.let { bookingRows(it, "physicalTestList") }.orEmpty(), AppText.Booking.NO_FITNESS_SESSIONS,
            header = {
                item("all-items") { ArrowPreference(AppText.Booking.QUERY_ALL_FITNESS_ITEMS, summary = AppText.Booking.ALL_ITEMS_READ_ONLY_WITHOUT_SESSION_HINT, onClick = {
                    open(Route.Page("fitnessitems", title = AppText.Booking.ALL_FITNESS_ITEMS_TITLE))
                }) }
                item("fitness-evidence") { InfoBanner(AppText.Booking.FITNESS_SESSION_SELECTION_HINT) }
                item("fitness-heading") { SectionTitle(AppText.Booking.AVAILABLE_FITNESS_SESSIONS_TITLE) }
            }, footer = {
                if (paged.moreError != null) item("more-error") { SectionCard(AppText.Common.NEXT_PAGE_LOAD_FAILED, paged.moreError.orEmpty()) }
                if (!paged.end && state.data != null) item("server-more") { TextButton(AppText.Booking.LOAD_NEXT_FITNESS_SESSIONS_PAGE, enabled = !state.loading, onClick = { scope.launch { query.more() } }) }
                item("mine-title") { SectionTitle(AppText.Booking.MY_REGISTRATION_TITLE) }
                bookingQueryFeedback(mine, { scope.launch { aux.mine.refresh() } }, "mine")
                if (mine.data == null && !mine.loading) item("mine-unread") { InfoBanner(AppText.Booking.REGISTRATION_NOT_READ_HINT, tone = UiTone.Neutral) }
                bookingFieldsItems(mine.data, keyPrefix = "mine-field")
                item("counts-title") { SectionTitle(AppText.Booking.TODAY_COUNTS_TITLE) }
                bookingQueryFeedback(counts, { scope.launch { aux.counts.refresh() } }, "counts")
                item("counts-summary") { BookingCounts(counts.data) }
                counts.data?.let { data -> item("counts-source") { BookingRawDetails(data) } }
            }) { item, index ->
            val id = item.bookingId()
            BookingRecordCard(item, item.bookingLabel(AppText.Booking.fitnessSessionOrdinal((index + 1))), if (id.isBlank()) null else ({
                open(Route.Page("fitnessitems", BookingSelection(physicalId = id).encode(), item.bookingLabel(AppText.Booking.FITNESS_ITEMS_TITLE)))
            }))
            if (id.isBlank()) Text(AppText.Booking.FITNESS_SESSION_ID_MISSING_READ_ONLY)
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
    Screen(page.title.ifBlank { AppText.Booking.FITNESS_ITEMS_TITLE }, back, actions = { TextButton(AppText.Common.REFRESH, enabled = !state.loading, onClick = { scope.launch { query.refresh() } }) }) { padding ->
        BookingLazyList(padding, state, { scope.launch { query.refresh() } }, rows, AppText.Booking.NO_FITNESS_ITEMS,
            header = {
                item("fitness-selection-help") { InfoBanner(if (selection.physicalId.isBlank()) AppText.Booking.SELECT_SESSION_BEFORE_REGISTRATION_HINT
                    else AppText.Booking.REGISTRATION_ITEMS_LOCAL_REVIEW_HINT) }
                item("fitness-item-heading") { SectionTitle(if (selection.physicalId.isBlank()) AppText.Booking.ALL_FITNESS_ITEMS_TITLE else AppText.Booking.ITEMS_REVIEW_SELECT_TITLE) }
                if (chosen.any { it !in returnedIds }) item("stale-items") {
                    SectionCard(AppText.Booking.SELECTED_ITEMS_MISSING_FROM_LATEST_RESULTS, AppText.Booking.RESELECT_BEFORE_PREVIEW_HINT)
                    TextButton(AppText.Booking.CLEAR_INVALID_SELECTION, onClick = { chosen = chosen.intersect(returnedIds) })
                }
            }, footer = {
                if (selection.physicalId.isNotBlank()) item("fitness-preview-entry") {
                    Text(AppText.Booking.localReviewItemCount(chosen.size), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    BookingPrimaryButton(AppText.Booking.GENERATE_REGISTRATION_PREVIEW, enabled = !state.loading && state.error == null && chosen.all { it in returnedIds }) {
                        open(Route.Page("fitnessenrol", selection.copy(itemIds = chosen.toList()).encode(), AppText.Booking.REVIEW_FITNESS_REGISTRATION_TITLE))
                    }
                }
            }, filterable = false) { item, index ->
            val id = item.bookingId()
            BookingPanel(item.bookingLabel(AppText.Booking.itemOrdinal(index + 1))) {
                item.bookingValue("description", "content", "remark").takeIf(String::isNotBlank)?.let { Text(it, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                if (selection.physicalId.isNotBlank()) BookingCheck(id in chosen, if (id.isBlank()) AppText.Booking.FITNESS_ITEM_ID_MISSING_READ_ONLY else AppText.Booking.ITEM_REVIEW_ACKNOWLEDGE_ACTION,
                    enabled = id.isNotBlank() && !state.loading) { chosen = if (it) chosen + id else chosen - id }
            }
        }
    }
}
