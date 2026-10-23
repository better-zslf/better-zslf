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
            else -> Screen(page.title.ifBlank { AppText.Booking.BOOKING_AND_FITNESS_TITLE }, onBack) { padding ->
                LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
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
                item("my-venues") { ArrowPreference(AppText.Booking.MY_BOOKING_APPLICATIONS_TITLE, onClick = { open(Route.Page("myvenues", title = AppText.Booking.MY_BOOKINGS_TITLE)) }) }
                item("venue-contract") { SectionCard(AppText.Booking.OPEN_VENUES_TITLE, AppText.Booking.OPEN_VENUE_STATUS_QUERY_HINT) }
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
        PullToRefresh(state.loading, { scope.launch { query.refresh() } }, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { ArrowPreference(AppText.Booking.VIEW_BOOKING_SLOTS, enabled = siteId.isNotBlank(), onClick = {
                    open(Route.Page("venuetimes", siteId, AppText.Booking.BOOKING_SLOTS_TITLE))
                }) }
                bookingQueryFeedback(state, { scope.launch { query.refresh() } })
                if (!state.loading && state.error == null && state.data?.let { bookingFields(it) }.isNullOrEmpty()) item {
                    SectionCard(AppText.Booking.NO_VENUE_DETAILS, AppText.Booking.VENUE_ID_RETAINED_REFRESH_HINT)
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
        TextButton(AppText.Common.REFRESH, enabled = !state.loading, onClick = { scope.launch { query.refresh() } })
    }) { padding ->
        BookingLazyList(padding, state, { scope.launch { query.refresh() } }, rows, AppText.Booking.NO_AVAILABLE_SLOTS,
            header = {
                item("slot-date") {
                    TextField(dateInput, { dateInput = it }, label = AppText.Booking.BOOKING_DATE_FIELD, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    TextButton(AppText.Booking.QUERY_DATE_SLOTS, enabled = validBookingDate(dateInput) && !state.loading, onClick = {
                        if (requestedDate == dateInput) scope.launch { query.refresh() }
                        else { requestedDate = dateInput; chosen = emptySet() }
                    })
                }
                item("slot-explanation") {
                    SectionCard(AppText.Booking.SELECT_SLOT_TITLE, AppText.Booking.queryDateNotice((requestedDate.ifBlank { AppText.Common.NOT_SELECTED })))
                }
                val text = state.data?.bookingText("text").orEmpty()
                if (text.isNotBlank()) item("slot-text") { SectionCard(AppText.Booking.BOOKING_INSTRUCTIONS_TITLE, text) }
                item("selection-summary") { Text(AppText.Booking.chosenSlots((chosen.size))) }
                if (chosen.any { it !in selectable }) item("stale-selection") {
                    SectionCard(AppText.Booking.SELECTED_SLOTS_CHANGED, AppText.Booking.RECHECK_CHANGED_OR_FULL_SLOTS_HINT)
                    TextButton(AppText.Booking.CLEAR_INVALID_SELECTION, onClick = { chosen = chosen.intersect(selectable) })
                }
                item("apply-entry") {
                    Button(enabled = !state.loading && state.error == null && chosen.isNotEmpty() && chosen.all { it in selectable },
                        onClick = { open(Route.Page("venueapply", selection.copy(timeIds = chosen.toList(), dateTime = requestedDate).encode(), AppText.Booking.BOOKING_APPLICATION_TITLE)) }) {
                        Text(AppText.Booking.FILL_APPLICATION_DETAILS)
                    }
                }
            }) { item, index ->
            val id = item.bookingId()
            val availability = if (id in occupied) SlotAvailability.FULL else bookingAvailability(item)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BookingRecordCard(item, item.bookingLabel(AppText.Booking.slotOrdinal((index + 1))))
                Text(availability.label)
                BookingCheck(id in chosen, if (id.isBlank()) AppText.Booking.SLOT_ID_MISSING_READ_ONLY else AppText.Booking.SELECT_THIS_SLOT,
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
                item { SectionCard(AppText.Booking.APPLICATION_STATUS_TITLE, AppText.Booking.APPLICATION_DETAILS_AND_UPLOAD_HINT) }
            }, footer = {
                if (paged.moreError != null) item("more-error") { SectionCard(AppText.Common.NEXT_PAGE_LOAD_FAILED, paged.moreError.orEmpty()) }
                if (!paged.end && state.data != null) item("server-more") { TextButton(AppText.Booking.LOAD_NEXT_APPLICATIONS_PAGE, enabled = !state.loading, onClick = { scope.launch { query.more() } }) }
            }) { item, index ->
            val id = item.bookingText("applyId").ifBlank { item.bookingId() }
            BookingRecordCard(item, item.bookingLabel(AppText.Booking.applicationOrdinal((index + 1))), if (id.isBlank()) null else ({
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
        PullToRefresh(state.loading, { scope.launch { query.refresh() } }, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bookingQueryFeedback(state, { scope.launch { query.refresh() } })
                if (state.data != null) item("cancel-explanation") {
                    SectionCard(AppText.Booking.BOOKING_CANCELLATION_UNCONNECTED, AppText.Booking.CANCELLATION_DOES_NOT_USE_APPROVAL_QUERY_HINT)
                    TextButton(AppText.Booking.PREVIEW_CANCELLATION_STATUS, enabled = page.id.isNotBlank(), onClick = {
                        controller.preview(BookingPreview(AppText.Booking.CANCEL_BOOKING_TITLE, listOf(AppText.Booking.applicationId((page.id)), AppText.Booking.CANCELLATION_PREVIEW_PRESERVES_STATUS_HINT)))
                    })
                }
                bookingFieldsItems(state.data)
                item("venue-attachment-form") { VenueAttachmentSection(graph, page.id, state) { scope.launch { query.refresh() } } }
                item("timeline-title") { SmallTitle(AppText.Booking.APPROVAL_PROGRESS_TITLE) }
                bookingQueryFeedback(timelineState, { scope.launch { timeline.refresh() } }, "timeline")
                itemsIndexed((timelineState.data as? JsonArray)?.toList().orEmpty(), key = { index, _ -> "timeline:$index" }) { index, node ->
                    SectionCard(node.bookingText("nodeName").ifBlank { AppText.Booking.approvalNodeOrdinal((index + 1)) },
                        listOf(node.bookingText("approver"), node.bookingText("opinion"), node.bookingText("reviewTime")).filter(String::isNotBlank).joinToString("\n"))
                }
                // Only server-relative userfiles paths are ever opened, using the repository's validator.
                val attachments = (state.data as? JsonObject)?.get("fileList") as? JsonArray
                itemsIndexed(attachments?.toList().orEmpty(), key = { index, _ -> "attachment:$index" }) { index, file ->
                    val path = file.bookingText("path").ifBlank { file.bookingText("filepath") }
                    val url = graph.repositories.fileUrl(path)
                    if (url != null) ArrowPreference(file.bookingText("filename").ifBlank { AppText.Booking.attachmentOrdinal((index + 1)) },
                        summary = AppText.Booking.OPEN_SERVER_ATTACHMENT, onClick = { graph.platform.openUrl(url) })
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
                item("fitness-evidence") { SectionCard(AppText.Booking.FITNESS_SESSION_QUERY_TITLE, AppText.Booking.INDEPENDENT_FITNESS_QUERIES_AND_RAW_COUNTS_HINT) }
            }, footer = {
                if (paged.moreError != null) item("more-error") { SectionCard(AppText.Common.NEXT_PAGE_LOAD_FAILED, paged.moreError.orEmpty()) }
                if (!paged.end && state.data != null) item("server-more") { TextButton(AppText.Booking.LOAD_NEXT_FITNESS_SESSIONS_PAGE, enabled = !state.loading, onClick = { scope.launch { query.more() } }) }
                item("mine-title") { SmallTitle(AppText.Booking.CURRENT_REGISTRATION_RAW_TITLE) }
                bookingQueryFeedback(mine, { scope.launch { aux.mine.refresh() } }, "mine")
                bookingFieldsItems(mine.data, keyPrefix = "mine-field")
                item("counts-title") { SmallTitle(AppText.Booking.DAILY_COUNTS_RAW_TITLE) }
                bookingQueryFeedback(counts, { scope.launch { aux.counts.refresh() } }, "counts")
                bookingFieldsItems(counts.data, keyPrefix = "counts-field")
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
                item("fitness-selection-help") { SectionCard(if (selection.physicalId.isBlank()) AppText.Booking.GLOBAL_FITNESS_ITEMS_QUERY_TITLE else AppText.Booking.SELECT_ITEMS_LOCALLY_TITLE,
                    if (selection.physicalId.isBlank()) AppText.Booking.SELECT_SESSION_BEFORE_REGISTRATION_HINT
                    else AppText.Booking.fitnessSelection((selection.physicalId), (chosen.size))) }
                if (chosen.any { it !in returnedIds }) item("stale-items") {
                    SectionCard(AppText.Booking.SELECTED_ITEMS_MISSING_FROM_LATEST_RESULTS, AppText.Booking.RESELECT_BEFORE_PREVIEW_HINT)
                    TextButton(AppText.Booking.CLEAR_INVALID_SELECTION, onClick = { chosen = chosen.intersect(returnedIds) })
                }
                if (selection.physicalId.isNotBlank()) item("fitness-preview-entry") {
                    Button(enabled = !state.loading && state.error == null && chosen.all { it in returnedIds }, onClick = {
                        open(Route.Page("fitnessenrol", selection.copy(itemIds = chosen.toList()).encode(), AppText.Booking.REVIEW_FITNESS_REGISTRATION_TITLE))
                    }) { Text(AppText.Booking.PREVIEW_REGISTRATION_SELECTION) }
                }
            }) { item, index ->
            val id = item.bookingId()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BookingRecordCard(item, item.bookingLabel(AppText.Booking.itemOrdinal((index + 1))))
                if (selection.physicalId.isNotBlank()) BookingCheck(id in chosen, if (id.isBlank()) AppText.Booking.FITNESS_ITEM_ID_MISSING_READ_ONLY else AppText.Booking.SELECT_THIS_FITNESS_ITEM,
                    enabled = id.isNotBlank() && !state.loading) { chosen = if (it) chosen + id else chosen - id }
            }
        }
    }
}
