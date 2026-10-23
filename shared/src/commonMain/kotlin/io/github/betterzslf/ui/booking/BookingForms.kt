package io.github.betterzslf.ui.booking

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
        if (selection.siteId.isBlank()) ApiResult.Failure(ApiError.Validation(listOf("siteId"), "缺少场馆标识"))
        else graph.repositories.venues.info(selection.siteId)
    }
    val times = rememberBookingQuery(graph to ("form-times" to selection)) {
        if (!validBookingDate(selection.dateTime)) ApiResult.Failure(ApiError.Validation(listOf("dateTime"), "请返回时段页选择有效日期"))
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
    Screen("预约申请", { if (!frozen || confirmation?.phase != BookingPhase.RUNNING) back() }, actions = {
        TextButton("刷新信息", enabled = !frozen && !infoState.loading && !timeState.loading, onClick = refresh)
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("venue-summary") { SectionCard(venueName.ifBlank { "预约场馆" }, "日期：${selection.dateTime}\n场馆标识：${selection.siteId}") }
            bookingQueryFeedback(infoState, { scope.launch { info.refresh() } }, "form-info")
            bookingQueryFeedback(timeState, { scope.launch { times.refresh() } }, "form-time")
            itemsIndexed(selectedRows, key = { index, _ -> "selected-time:$index" }) { index, row -> BookingRecordCard(row, "所选时段 ${index + 1}") }
            item("form-help") { SectionCard("申请资料", "提交后需等待审核；要求补充盖章文件时，可从“我的预约”打开详情再上传。此处不提前上传附件。") }
            item("group") { TextField(group, { changed(); group = it }, label = "申请团体", enabled = !frozen, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("content") { TextField(content, { changed(); content = it }, label = "活动内容", enabled = !frozen, modifier = Modifier.fillMaxWidth(), maxLines = 6) }
            item("contact") { TextField(contact, { changed(); contact = it }, label = "联系人", enabled = !frozen, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("phone") { TextField(phone, { changed(); phone = it }, label = "联系电话", enabled = !frozen, modifier = Modifier.fillMaxWidth(), singleLine = true) }
            item("acknowledge") { BookingCheck(acknowledged, "我已核对申请资料与所选时段", enabled = !frozen) { acknowledged = it; controller?.edit(); controller = null } }
            if (validation.isNotEmpty()) item("validation") { SectionCard("请先核对", validation.joinToString("\n")) }
            item("preview-button") {
                Button(enabled = !frozen && !infoState.loading && !timeState.loading, onClick = {
                    val form = VenueApplicationForm(selection.siteId, selection.timeIds, venueName, venueStatus,
                        group.trim(), content.trim(), contact.trim(), phone.trim(), userId, userName)
                    val problems = form.validation() + buildList {
                        if (infoState.error != null || timeState.error != null) add("请先成功刷新场馆与时段信息")
                        if (selection.timeIds.any { it !in validIds }) add("所选时段已满或已变化，请返回重新选择")
                        if (!acknowledged) add("请先勾选核对申请资料")
                    }
                    validation = problems
                    if (problems.isEmpty()) {
                        val next = venueConfirmation(transport, form)
                        next.preview(BookingPreview("预约申请", listOf("场馆：$venueName", "日期：${selection.dateTime}",
                            "申请团体：${form.groupName}", "活动内容：${form.content}", "联系人：${form.contactName}", "联系电话：${bookingMasked(form.phone)}",
                            "时段：${selectedRows.joinToString("；") { it.bookingText("startNode") + "–" + it.bookingText("endNode") }}",
                            "提交步骤：创建申请 → 关联场馆 → 关联时段 → 关联申请人。任何一步失败后停止，不自动重提。")))
                        controller = next
                    }
                }) { Text("生成完整预览") }
            }
            if (confirmation != null) {
                bookingConfirmationItems(confirmation, { controller?.edit(); controller = null })
                if (confirmation.phase == BookingPhase.PREVIEW) item("final-button") { Button(onClick = { dialog = true }) { Text("核对完毕，打开最终确认") } }
                val applyId = confirmation.references["applyId"].orEmpty()
                if (applyId.isNotBlank()) item("created-detail") { ArrowPreference("查看已创建的申请", summary = "先查询结果，避免重复申请", onClick = { open(Route.Page("venueapplication", applyId, "预约申请详情")) }) }
            }
            item("my-entry") { ArrowPreference("我的预约申请", onClick = { open(Route.Page("myvenues", title = "我的预约")) }) }
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
            FilePickResult.Cancelled -> pickerMessage = "已取消选择"
            is FilePickResult.Failed -> pickerMessage = "选择文件失败，请重试"
            is FilePickResult.Selected -> {
                if (files.size >= 9) pickerMessage = "最多选择 9 个附件"
                else { files = files + result.file; pickerMessage = "已添加本地附件；尚未上传"; controller = null; acknowledged = false }
            }
        }
    }
    // This bounded section contains at most 9 files; the enclosing details page remains lazy.
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SmallTitle("补充盖章文件")
        Text("请上传已填写盖章的相关文件相片。每个文件不超过 8 MiB，最多 9 个。选择文件不会上传。")
        TextButton("选择相片文件", enabled = !frozen && files.size < 9, onClick = picker::launch)
        if (pickerMessage.isNotBlank()) Text(pickerMessage)
        files.forEachIndexed { index, file ->
            SectionCard("附件 ${index + 1}：${file.name}", "${file.mediaType} · ${file.size} bytes · 仅在内存中")
            if (!frozen) TextButton("移除附件 ${index + 1}", onClick = { files = files.filterIndexed { i, _ -> i != index }; controller = null; acknowledged = false })
        }
        BookingCheck(acknowledged, "我已核对这些文件只用于当前预约申请", !frozen) { acknowledged = it; controller = null }
        if (validation.isNotEmpty()) SectionCard("请先核对", validation.joinToString("\n"))
        Button(enabled = !frozen, onClick = {
            validation = attachmentValidation(applyId, userId, files, status) + if (!acknowledged) listOf("请勾选附件核对") else emptyList()
            if (validation.isEmpty()) {
                val next = attachmentConfirmation(RepositoryAttachmentTransport(graph.repositories.venues), applyId, userId,
                    files.toList(), status, detail.data?.let(::attachmentReceipts).orEmpty())
                next.preview(BookingPreview("上传预约附件", listOf("申请标识：$applyId", "文件：${files.joinToString("；") { it.name + "（" + it.size + " bytes）" }}",
                    "按文件顺序上传 → 回写完成 → 重新查询附件列表。若失败或结果待核对，不会自动重传。")))
                controller = next
            }
        }) { Text("预览附件上传") }
        if (state != null) {
            if (state.message.isNotBlank()) SectionCard("操作状态", state.message)
            state.preview?.lines?.forEach { SectionCard("核对项", it) }
            state.steps.forEach { SectionCard(it.label, bookingStepLabel(it.phase)) }
            if (state.phase == BookingPhase.PREVIEW) Button(onClick = { dialog = true }) { Text("核对完毕，打开最终确认") }
            if (state.phase in setOf(BookingPhase.COMPLETED, BookingPhase.FAILED, BookingPhase.UNCERTAIN)) TextButton("查询服务端附件状态", onClick = refreshDetail)
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
    Screen("体测报名核对", { if (state?.phase != BookingPhase.RUNNING) back() }, actions = { TextButton("刷新", enabled = !frozen, onClick = refresh) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("fitness-test") { SectionCard(test?.bookingLabel("体测场次") ?: "体测场次", "场次标识：${selection.physicalId}") }
            bookingQueryFeedback(testState, { scope.launch { tests.refresh() } }, "enrol-test")
            bookingFieldsItems(test, keyPrefix = "enrol-test-field")
            item("items-heading") { SmallTitle("场次项目（仅供核对）") }
            item { Text("报名按场次、日期和时段提交；勾选项目仅用于本地核对，不额外上传项目字段。") }
            bookingQueryFeedback(itemState, { scope.launch { items.refresh() } }, "enrol-items")
            itemsIndexed(rows.filter { selection.itemIds.isEmpty() || it.bookingId() in selection.itemIds }, key = { index, _ -> "enrol-item:$index" }) { index, row -> BookingRecordCard(row, row.bookingLabel("项目 ${index + 1}")) }
            item("enrol-form-title") { SmallTitle("报名资料") }
            item("card") { TextField(card, { changed(); card = it }, label = "身份证号（只在最终确认后发送）", visualTransformation = PasswordVisualTransformation(), enabled = !frozen, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item("sex") { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(if (sex == "男") "✓ 男" else "男", enabled = !frozen, onClick = { changed(); sex = "男" })
                TextButton(if (sex == "女") "✓ 女" else "女", enabled = !frozen, onClick = { changed(); sex = "女" })
            } }
            item("date") { TextField(date, { changed(); date = it }, label = "预约日期 YYYY-MM-DD", enabled = !frozen, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item("period") { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("上午", "下午").forEach { value -> TextButton(if (period == value) "✓ $value" else value, enabled = !frozen, onClick = { changed(); period = value }) }
            } }
            item("enrol-ack") { BookingCheck(acknowledged, "我已核对场次、日期、身份资料与当前报名信息", !frozen) { acknowledged = it; controller = null } }
            if (validation.isNotEmpty()) item("enrol-validation") { SectionCard("请先核对", validation.joinToString("\n")) }
            item("preview-enrol") { Button(enabled = !frozen && !testState.loading, onClick = {
                val form = FitnessEnrolmentForm(selection.physicalId, rId, userId, session?.userInfo?.bookingText("name").orEmpty(),
                    session?.userInfo?.bookingText("account").orEmpty(), card.trim(), sex, date.trim(), period)
                validation = form.validation() + buildList {
                    if (test == null || testState.error != null) add("当前场次未在查询结果中确认，请刷新后重试")
                    if (test != null) {
                        addAll(fitnessScheduleValidation(date.trim(), sex, period, test, counts.data))
                        addAll(fitnessEnrolmentTiming(date.trim(), test, kotlin.time.Clock.System.now().toEpochMilliseconds()))
                    }
                    if (counts.loading || counts.error != null || counts.data == null) add("请先成功查询当前场次人数")
                    fitnessCancellationLimit(mine.data)?.let { add(it) }
                    if (!acknowledged) add("请勾选报名资料核对")
                }
                if (validation.isEmpty()) {
                    val next = fitnessEnrolmentConfirmation(transport, form)
                    next.preview(BookingPreview("体测报名", listOf("场次：${test?.bookingLabel("体测")}", "日期：${form.date} ${form.period}", "性别：${form.sex}",
                        "姓名：${bookingMasked(form.userName)}", "学号：${bookingMasked(form.studentId)}", "身份证号：${bookingMasked(form.identityCard)}",
                        "将提交报名资料到学校服务；项目选择只用于本地核对。")))
                    controller = next
                }
            }) { Text("生成报名预览") } }
            if (rId.isNotBlank()) {
                item("cancel-title") { SmallTitle("取消当前体测报名") }
                item("cancel-reason") { TextField(reason, { changed(); reason = it }, label = "取消原因", enabled = !frozen, modifier = Modifier.fillMaxWidth(), maxLines = 4) }
                item("cancel-preview") { TextButton("生成取消预览", enabled = !frozen, onClick = {
                    val form = FitnessCancellation(rId, reason.trim())
                    validation = form.validation() + buildList {
                        if (test == null || testState.error != null) add("请先刷新确认当前场次")
                        else fitnessCancellationTiming(test, kotlin.time.Clock.System.now().toEpochMilliseconds())?.let { add(it) }
                        fitnessCancellationLimit(mine.data)?.let { add(it) }
                    }
                    if (validation.isEmpty()) {
                        val next = fitnessCancellationConfirmation(transport, form)
                        next.preview(BookingPreview("取消体测报名", listOf("场次：${test?.bookingLabel("体测")}", "报名标识：$rId", "取消原因：${form.reason}", "确认后将取消该次报名，请谨慎核对。")))
                        controller = next
                    }
                }) }
            }
            if (state != null) {
                bookingConfirmationItems(state, { controller = null })
                if (state.phase == BookingPhase.PREVIEW) item("fitness-final") { Button(onClick = { dialog = true }) { Text("核对完毕，打开最终确认") } }
                if (state.phase in setOf(BookingPhase.COMPLETED, BookingPhase.FAILED, BookingPhase.UNCERTAIN)) item("query-after-write") { TextButton("查询最新报名状态", onClick = refresh) }
            }
            item("mine-title") { SmallTitle("取消次数信息（服务端原值）") }
            bookingQueryFeedback(mine, { scope.launch { auxMine.refresh() } }, "fitness-mine")
            bookingFieldsItems(mine.data, keyPrefix = "fitness-mine-field")
            item("count-title") { SmallTitle("当前场次按日期人数") }
            bookingQueryFeedback(counts, { scope.launch { auxCounts.refresh() } }, "fitness-count")
            bookingFieldsItems(counts.data, keyPrefix = "fitness-count-field")
            item("back-items") { ArrowPreference("重新核对体测项目", onClick = { open(Route.Page("fitnessitems", BookingSelection(physicalId = selection.physicalId).encode(), "体测项目")) }) }
        }
        controller?.let { BookingFinalDialog(it, dialog) { dialog = false } }
    }
}

internal fun bookingStepLabel(phase: BookingStepPhase): String = when (phase) {
    BookingStepPhase.PENDING -> "待执行"; BookingStepPhase.RUNNING -> "执行中"; BookingStepPhase.COMPLETED -> "已完成"
    BookingStepPhase.FAILED -> "失败，已停止后续步骤"; BookingStepPhase.UNCERTAIN -> "结果待核对，未自动重试"
}
