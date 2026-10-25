package io.github.betterzslf.ui.booking

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.feature.booking.*
import io.github.betterzslf.platform.FilePickResult
import io.github.betterzslf.platform.PickedFile
import io.github.betterzslf.platform.rememberFilePicker
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun VenueApply(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val selection = remember(page.id) { BookingSelection.decode(page.id) }
    val session by graph.sessions.session.collectAsState()
    val userId = session?.userId.orEmpty()
    val userName = session?.userInfo?.bookingText("name").orEmpty()
    val info = rememberBookingQuery(graph to ("form-info" to selection.siteId)) {
        if (selection.siteId.isBlank()) ApiResult.Failure(ApiError.Validation(listOf("siteId"), AppText.Booking.VENUE_ID_MISSING))
        else graph.repositories.venues.info(selection.siteId)
    }
    val times = rememberBookingQuery(graph to ("form-times" to selection)) {
        if (!validBookingDate(selection.dateTime)) ApiResult.Failure(ApiError.Validation(listOf("dateTime"), AppText.Booking.SELECT_VALID_DATE_FROM_SLOTS_HINT))
        else graph.repositories.venues.times(selection.siteId, selection.dateTime)
    }
    val infoState by info.state.collectAsState()
    val timeState by times.state.collectAsState()
    val venue = (infoState.data as? JsonObject)?.let { (it["siteInfo"] as? JsonObject) ?: (it["site"] as? JsonObject) ?: it }
    val venueName = venue?.bookingText("name").orEmpty().ifBlank { venue?.bookingText("venueName").orEmpty() }
    val venueStatus = venue?.get("venueStatus") ?: venue?.get("status") ?: JsonNull
    val timeRows = remember(timeState.data) { timeState.data?.let(::bookingTimeRows).orEmpty() }
    val occupied = ((timeState.data as? JsonObject)?.get("applyTimeList") as? JsonArray)?.map { it.bookingId() }?.toSet().orEmpty()
    val validIds = timeRows.filter { bookingAvailability(it) != SlotAvailability.FULL && it.bookingId() !in occupied }.map { it.bookingId() }.toSet()
    val selectedRows = timeRows.filter { it.bookingId() in selection.timeIds }
    var group by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var contact by rememberSaveable { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var controller by remember { mutableStateOf<BookingConfirmation?>(null) }
    var validation by remember { mutableStateOf<List<String>>(emptyList()) }
    var dialog by remember { mutableStateOf(false) }
    val transport = remember(graph) { RepositoryBookingTransport(graph.repositories.venues, graph.repositories.fitness) }
    val scope = rememberCoroutineScope()
    val confirmation = controller?.state?.collectAsState()?.value
    val frozen = confirmation?.phase in setOf(BookingPhase.RUNNING, BookingPhase.FAILED, BookingPhase.UNCERTAIN, BookingPhase.COMPLETED)
    fun changed() { controller?.edit(); if (!frozen) controller = null; acknowledged = false; validation = emptyList() }
    val refresh: () -> Unit = { scope.launch { info.refresh() }; scope.launch { times.refresh() }; changed() }
    Screen(AppText.Booking.BOOKING_APPLICATION_TITLE, { if (!frozen || confirmation?.phase != BookingPhase.RUNNING) back() }, actions = {
        TextButton(AppText.Common.REFRESH_INFORMATION, enabled = !frozen && !infoState.loading && !timeState.loading, onClick = refresh)
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("venue-summary") { SectionCard(venueName.ifBlank { AppText.Booking.APPLICATION_VENUE_TITLE }, AppText.Booking.venueSelection((selection.dateTime), (selection.siteId))) }
            bookingQueryFeedback(infoState, { scope.launch { info.refresh() } }, "form-info")
            bookingQueryFeedback(timeState, { scope.launch { times.refresh() } }, "form-time")
            itemsIndexed(selectedRows, key = { index, _ -> "selected-time:$index" }) { index, row -> BookingRecordCard(row, AppText.Booking.selectedSlotOrdinal((index + 1))) }
            item("form-help") { SectionCard(AppText.Booking.APPLICATION_DETAILS_TITLE, AppText.Booking.APPROVAL_AND_STAMPED_DOCUMENT_UPLOAD_HINT) }
            item("group") { TextField(group, { changed(); group = it }, label = AppText.Booking.APPLICANT_GROUP_FIELD, enabled = !frozen, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("content") { TextField(content, { changed(); content = it }, label = AppText.Booking.ACTIVITY_DESCRIPTION_FIELD, enabled = !frozen, modifier = Modifier.fillMaxWidth(), maxLines = 6) }
            item("contact") { TextField(contact, { changed(); contact = it }, label = AppText.Booking.CONTACT_PERSON_FIELD, enabled = !frozen, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("phone") { TextField(phone, { changed(); phone = it }, label = AppText.Booking.CONTACT_PHONE_FIELD, enabled = !frozen, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("acknowledge") { BookingCheck(acknowledged, AppText.Booking.APPLICATION_AND_SLOTS_ACKNOWLEDGEMENT, enabled = !frozen) { acknowledged = it; controller?.edit(); controller = null } }
            if (validation.isNotEmpty()) item("validation") { SectionCard(AppText.Common.REVIEW_REQUIRED, validation.joinToString("\n")) }
            item("preview-button") {
                Button(enabled = !frozen && !infoState.loading && !timeState.loading, onClick = {
                    val form = VenueApplicationForm(selection.siteId, selection.timeIds, venueName, venueStatus,
                        group.trim(), content.trim(), contact.trim(), phone.trim(), userId, userName)
                    val problems = form.validation() + buildList {
                        if (infoState.error != null || timeState.error != null) add(AppText.Booking.REFRESH_VENUE_AND_SLOTS_FIRST)
                        if (selection.timeIds.any { it !in validIds }) add(AppText.Booking.SELECTED_SLOT_FULL_OR_CHANGED)
                        if (!acknowledged) add(AppText.Booking.ACKNOWLEDGE_APPLICATION_DETAILS_FIRST)
                    }
                    validation = problems
                    if (problems.isEmpty()) {
                        val next = venueConfirmation(transport, form)
                        next.preview(BookingPreview(AppText.Booking.BOOKING_APPLICATION_TITLE, listOf(AppText.Booking.venueLine((venueName)), AppText.Booking.dateLine((selection.dateTime)),
                            AppText.Booking.groupLine((form.groupName)), AppText.Booking.activityLine((form.content)), AppText.Booking.contactLine((form.contactName)), AppText.Booking.contactPhoneLine((bookingMasked(form.phone))),
                            AppText.Booking.timeSlotsLine((selectedRows.joinToString("；") { it.bookingText("startNode") + "–" + it.bookingText("endNode") })),
                            AppText.Booking.APPLICATION_SUBMISSION_STEPS_HINT)))
                        controller = next
                    }
                }) { Text(AppText.Booking.GENERATE_FULL_PREVIEW) }
            }
            if (confirmation != null) {
                bookingConfirmationItems(confirmation, { controller?.edit(); controller = null })
                if (confirmation.phase == BookingPhase.PREVIEW) item("final-button") { Button(onClick = { dialog = true }) { Text(AppText.Common.OPEN_FINAL_CONFIRMATION_AFTER_REVIEW) } }
                val applyId = confirmation.references["applyId"].orEmpty()
                if (applyId.isNotBlank()) item("created-detail") { ArrowPreference(AppText.Booking.VIEW_CREATED_APPLICATION, summary = AppText.Booking.QUERY_RESULT_BEFORE_REAPPLYING, onClick = { open(Route.Page("venueapplication", applyId, AppText.Booking.BOOKING_APPLICATION_DETAIL_TITLE)) }) }
            }
            item("my-entry") { ArrowPreference(AppText.Booking.MY_BOOKING_APPLICATIONS_TITLE, onClick = { open(Route.Page("myvenues", title = AppText.Booking.MY_BOOKINGS_TITLE)) }) }
        }
        controller?.let { BookingFinalDialog(it, dialog) { dialog = false } }
    }
}

@Composable
internal fun VenueAttachmentSection(graph: AppGraph, applyId: String, detail: BookingQueryState, refreshDetail: () -> Unit) {
    val application = (detail.data as? JsonObject)?.get("sportsVenueApply") ?: JsonNull
    val status = application.bookingText("status")
    if (status != "1") return
    val userId = graph.sessions.session.value?.userId.orEmpty()
    var files by remember { mutableStateOf<List<PickedFile>>(emptyList()) }
    var pickerMessage by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<List<String>>(emptyList()) }
    var controller by remember { mutableStateOf<BookingConfirmation?>(null) }
    var dialog by remember { mutableStateOf(false) }
    val state = controller?.state?.collectAsState()?.value
    val frozen = state?.phase in setOf(BookingPhase.RUNNING, BookingPhase.FAILED, BookingPhase.UNCERTAIN, BookingPhase.COMPLETED)
    val picker = rememberFilePicker { result ->
        if (!frozen) when (result) {
            FilePickResult.Cancelled -> pickerMessage = AppText.Common.FILE_SELECTION_CANCELLED
            is FilePickResult.Failed -> pickerMessage = AppText.Common.FILE_SELECTION_FAILED_RETRY
            is FilePickResult.Selected -> {
                if (files.size >= 9) pickerMessage = AppText.Booking.ATTACHMENT_COUNT_LIMIT_HINT
                else { files = files + result.file; pickerMessage = AppText.Booking.LOCAL_ATTACHMENT_ADDED_NOT_UPLOADED; controller = null; acknowledged = false }
            }
        }
    }
    // This bounded section contains at most 9 files; the enclosing details page remains lazy.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallTitle(AppText.Booking.STAMPED_DOCUMENTS_TITLE)
        Text(AppText.Booking.STAMPED_DOCUMENT_UPLOAD_LIMITS_HINT)
        TextButton(AppText.Booking.SELECT_PHOTO_FILES, enabled = !frozen && files.size < 9, onClick = picker::launch)
        if (pickerMessage.isNotBlank()) Text(pickerMessage)
        files.forEachIndexed { index, file ->
            SectionCard(AppText.Booking.attachmentName((index + 1), (file.name)), AppText.Booking.attachmentMemoryInfo((file.mediaType), (file.size)))
            if (!frozen) TextButton(AppText.Booking.removeAttachment((index + 1)), onClick = { files = files.filterIndexed { i, _ -> i != index }; controller = null; acknowledged = false })
        }
        BookingCheck(acknowledged, AppText.Booking.ATTACHMENT_PURPOSE_ACKNOWLEDGEMENT, !frozen) { acknowledged = it; controller = null }
        if (validation.isNotEmpty()) SectionCard(AppText.Common.REVIEW_REQUIRED, validation.joinToString("\n"))
        Button(enabled = !frozen, onClick = {
            validation = attachmentValidation(applyId, userId, files, status) + if (!acknowledged) listOf(AppText.Booking.ACKNOWLEDGE_ATTACHMENTS_FIRST) else emptyList()
            if (validation.isEmpty()) {
                val next = attachmentConfirmation(RepositoryAttachmentTransport(graph.repositories.venues), applyId, userId,
                    files.toList(), status, detail.data?.let(::attachmentReceipts).orEmpty())
                next.preview(BookingPreview(AppText.Booking.UPLOAD_BOOKING_ATTACHMENTS_TITLE, listOf(AppText.Booking.applicationIdReference((applyId)), AppText.Booking.filesLine((files.joinToString("；") { it.name + "（" + it.size + " bytes）" })),
                    AppText.Booking.ATTACHMENT_UPLOAD_SEQUENCE_HINT)))
                controller = next
            }
        }) { Text(AppText.Booking.PREVIEW_ATTACHMENT_UPLOAD) }
        if (state != null) {
            if (state.message.isNotBlank()) SectionCard(AppText.Common.OPERATION_STATUS_TITLE, state.message)
            state.preview?.lines?.forEach { SectionCard(AppText.Common.REVIEW_CHECKLIST_TITLE, it) }
            state.steps.forEach { SectionCard(it.label, bookingStepLabel(it.phase)) }
            if (state.phase == BookingPhase.PREVIEW) Button(onClick = { dialog = true }) { Text(AppText.Common.OPEN_FINAL_CONFIRMATION_AFTER_REVIEW) }
            if (state.phase in setOf(BookingPhase.COMPLETED, BookingPhase.FAILED, BookingPhase.UNCERTAIN)) TextButton(AppText.Booking.QUERY_SERVER_ATTACHMENT_STATUS, onClick = refreshDetail)
        }
        controller?.let { BookingFinalDialog(it, dialog) { dialog = false } }
    }
}

@Composable
internal fun FitnessEnrol(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) {
    val selection = remember(page.id) { BookingSelection.decode(page.id, physical = true) }
    val session by graph.sessions.session.collectAsState()
    val userId = session?.userId.orEmpty()
    val tests = rememberBookingQuery(graph to ("enrol-test" to selection.physicalId)) {
        graph.repositories.fitness.findBookingTest(userId, selection.physicalId)
    }
    val items = rememberBookingQuery(graph to ("enrol-items" to selection.physicalId)) { graph.repositories.fitness.items(selection.physicalId.ifBlank { null }) }
    val testState by tests.state.collectAsState()
    val itemState by items.state.collectAsState()
    val test = testState.data?.takeIf { it.bookingId() == selection.physicalId }
    val rId = test?.bookingText("rId").orEmpty()
    val auxCounts = rememberBookingQuery(graph to ("enrol-count" to selection.physicalId)) { graph.repositories.fitness.counts(selection.physicalId.ifBlank { null }) }
    val auxMine = rememberBookingQuery(graph to ("enrol-mine" to rId)) { graph.repositories.fitness.myInfo(rId.ifBlank { null }) }
    val counts by auxCounts.state.collectAsState()
    val mine by auxMine.state.collectAsState()
    val rows = itemState.data?.let { bookingRows(it, "getRelatedItemsList") }.orEmpty()
    var card by remember { mutableStateOf("") }
    var sex by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var acknowledged by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<List<String>>(emptyList()) }
    var controller by remember { mutableStateOf<BookingConfirmation?>(null) }
    var dialog by remember { mutableStateOf(false) }
    val state = controller?.state?.collectAsState()?.value
    val frozen = state?.phase in setOf(BookingPhase.RUNNING, BookingPhase.FAILED, BookingPhase.UNCERTAIN, BookingPhase.COMPLETED)
    val scope = rememberCoroutineScope()
    val transport = remember(graph) { RepositoryBookingTransport(graph.repositories.venues, graph.repositories.fitness) }
    fun changed() { if (!frozen) { controller = null; acknowledged = false; validation = emptyList() } }
    val refresh: () -> Unit = { scope.launch { tests.refresh() }; scope.launch { items.refresh() }; scope.launch { auxCounts.refresh() }; scope.launch { auxMine.refresh() } }
    Screen(AppText.Booking.REVIEW_FITNESS_REGISTRATION_TITLE, { if (state?.phase != BookingPhase.RUNNING) back() }, actions = { TextButton(AppText.Common.REFRESH, enabled = !frozen, onClick = refresh) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("fitness-test") { SectionCard(test?.bookingLabel(AppText.Booking.FITNESS_SESSION_TITLE) ?: AppText.Booking.FITNESS_SESSION_TITLE, AppText.Booking.fitnessSessionId((selection.physicalId))) }
            bookingQueryFeedback(testState, { scope.launch { tests.refresh() } }, "enrol-test")
            bookingFieldsItems(test, keyPrefix = "enrol-test-field")
            item("items-heading") { SmallTitle(AppText.Booking.SESSION_ITEMS_REVIEW_ONLY_TITLE) }
            item { Text(AppText.Booking.REGISTRATION_ITEMS_LOCAL_REVIEW_HINT) }
            bookingQueryFeedback(itemState, { scope.launch { items.refresh() } }, "enrol-items")
            itemsIndexed(rows.filter { selection.itemIds.isEmpty() || it.bookingId() in selection.itemIds }, key = { index, _ -> "enrol-item:$index" }) { index, row -> BookingRecordCard(row, row.bookingLabel(AppText.Booking.itemOrdinal((index + 1)))) }
            item("enrol-form-title") { SmallTitle(AppText.Booking.REGISTRATION_DETAILS_TITLE) }
            item("card") { TextField(card, { changed(); card = it }, label = AppText.Booking.IDENTITY_CARD_FINAL_CONFIRMATION_FIELD, visualTransformation = PasswordVisualTransformation(), enabled = !frozen, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item("sex") { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(if (sex == "男") AppText.Booking.SEX_MALE_SELECTED_LABEL else AppText.Booking.SEX_MALE_DISPLAY_LABEL, enabled = !frozen, onClick = { changed(); sex = "男" })
                TextButton(if (sex == "女") AppText.Booking.SEX_FEMALE_SELECTED_LABEL else AppText.Booking.SEX_FEMALE_DISPLAY_LABEL, enabled = !frozen, onClick = { changed(); sex = "女" })
            } }
            item("date") { TextField(date, { changed(); date = it }, label = AppText.Booking.BOOKING_DATE_FIELD, enabled = !frozen, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item("period") { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("上午" to AppText.Booking.MORNING_DISPLAY_LABEL, "下午" to AppText.Booking.AFTERNOON_DISPLAY_LABEL).forEach { (value, label) -> TextButton(if (period == value) AppText.Booking.selectedPeriod(label) else label, enabled = !frozen, onClick = { changed(); period = value }) }
            } }
            item("enrol-ack") { BookingCheck(acknowledged, AppText.Booking.REGISTRATION_DETAILS_ACKNOWLEDGEMENT, !frozen) { acknowledged = it; controller = null } }
            if (validation.isNotEmpty()) item("enrol-validation") { SectionCard(AppText.Common.REVIEW_REQUIRED, validation.joinToString("\n")) }
            item("preview-enrol") { Button(enabled = !frozen && !testState.loading, onClick = {
                val form = FitnessEnrolmentForm(selection.physicalId, rId, userId, session?.userInfo?.bookingText("name").orEmpty(),
                    session?.userInfo?.bookingText("account").orEmpty(), card.trim(), sex, date.trim(), period)
                validation = form.validation() + buildList {
                    if (test == null || testState.error != null) add(AppText.Booking.CURRENT_FITNESS_SESSION_UNCONFIRMED)
                    if (test != null) {
                        addAll(fitnessScheduleValidation(date.trim(), sex, period, test, counts.data))
                        addAll(fitnessEnrolmentTiming(date.trim(), test, kotlin.time.Clock.System.now().toEpochMilliseconds()))
                    }
                    if (counts.loading || counts.error != null || counts.data == null) add(AppText.Booking.QUERY_SESSION_COUNTS_FIRST)
                    fitnessCancellationLimit(mine.data)?.let { add(it) }
                    if (!acknowledged) add(AppText.Booking.ACKNOWLEDGE_REGISTRATION_DETAILS_FIRST)
                }
                if (validation.isEmpty()) {
                    val next = fitnessEnrolmentConfirmation(transport, form)
                    next.preview(BookingPreview(AppText.Booking.FITNESS_REGISTRATION_TITLE, listOf(AppText.Booking.fitnessSessionLine((test?.bookingLabel(AppText.Booking.FITNESS_TEST_FALLBACK_LABEL))), AppText.Booking.fitnessDateLine((form.date), (form.period)), AppText.Booking.genderLine((form.sex)),
                        AppText.Booking.nameLine((bookingMasked(form.userName))), AppText.Booking.studentNumberLine((bookingMasked(form.studentId))), AppText.Booking.identityCardLine((bookingMasked(form.identityCard))),
                        AppText.Booking.REGISTRATION_SUBMISSION_SCOPE_HINT)))
                    controller = next
                }
            }) { Text(AppText.Booking.GENERATE_REGISTRATION_PREVIEW) } }
            if (rId.isNotBlank()) {
                item("cancel-title") { SmallTitle(AppText.Booking.CANCEL_CURRENT_FITNESS_REGISTRATION) }
                item("cancel-reason") { TextField(reason, { changed(); reason = it }, label = AppText.Booking.CANCELLATION_REASON_FIELD, enabled = !frozen, modifier = Modifier.fillMaxWidth(), maxLines = 4) }
                item("cancel-preview") { TextButton(AppText.Booking.GENERATE_CANCELLATION_PREVIEW, enabled = !frozen, onClick = {
                    val form = FitnessCancellation(rId, reason.trim())
                    validation = form.validation() + buildList {
                        if (test == null || testState.error != null) add(AppText.Booking.REFRESH_CURRENT_SESSION_FIRST)
                        else fitnessCancellationTiming(test, kotlin.time.Clock.System.now().toEpochMilliseconds())?.let { add(it) }
                        fitnessCancellationLimit(mine.data)?.let { add(it) }
                    }
                    if (validation.isEmpty()) {
                        val next = fitnessCancellationConfirmation(transport, form)
                        next.preview(BookingPreview(AppText.Booking.CANCEL_FITNESS_REGISTRATION_TITLE, listOf(AppText.Booking.fitnessSessionLine((test?.bookingLabel(AppText.Booking.FITNESS_TEST_FALLBACK_LABEL))), AppText.Booking.enrolmentId((rId)), AppText.Booking.cancellationReason((form.reason)), AppText.Booking.FITNESS_CANCELLATION_CONFIRM_HINT)))
                        controller = next
                    }
                }) }
            }
            if (state != null) {
                bookingConfirmationItems(state, { controller = null })
                if (state.phase == BookingPhase.PREVIEW) item("fitness-final") { Button(onClick = { dialog = true }) { Text(AppText.Common.OPEN_FINAL_CONFIRMATION_AFTER_REVIEW) } }
                if (state.phase in setOf(BookingPhase.COMPLETED, BookingPhase.FAILED, BookingPhase.UNCERTAIN)) item("query-after-write") { TextButton(AppText.Booking.QUERY_LATEST_REGISTRATION_STATUS, onClick = refresh) }
            }
            item("mine-title") { SmallTitle(AppText.Booking.RAW_CANCELLATION_COUNTS_TITLE) }
            bookingQueryFeedback(mine, { scope.launch { auxMine.refresh() } }, "fitness-mine")
            bookingFieldsItems(mine.data, keyPrefix = "fitness-mine-field")
            item("count-title") { SmallTitle(AppText.Booking.CURRENT_SESSION_DAILY_COUNTS_TITLE) }
            bookingQueryFeedback(counts, { scope.launch { auxCounts.refresh() } }, "fitness-count")
            bookingFieldsItems(counts.data, keyPrefix = "fitness-count-field")
            item("back-items") { ArrowPreference(AppText.Booking.REVIEW_FITNESS_ITEMS_AGAIN, onClick = { open(Route.Page("fitnessitems", BookingSelection(physicalId = selection.physicalId).encode(), AppText.Booking.FITNESS_ITEMS_TITLE)) }) }
        }
        controller?.let { BookingFinalDialog(it, dialog) { dialog = false } }
    }
}

internal fun bookingStepLabel(phase: BookingStepPhase): String = when (phase) {
    BookingStepPhase.PENDING -> AppText.Booking.STEP_PENDING; BookingStepPhase.RUNNING -> AppText.Booking.STEP_RUNNING; BookingStepPhase.COMPLETED -> AppText.Common.COMPLETED
    BookingStepPhase.FAILED -> AppText.Booking.STEP_FAILED_REMAINING_STOPPED; BookingStepPhase.UNCERTAIN -> AppText.Booking.STEP_RESULT_UNCERTAIN_NO_AUTO_RETRY
}
