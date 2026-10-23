package io.github.betterzslf.feature.booking

import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.FitnessRepository
import io.github.betterzslf.data.VenueRepository
import kotlinx.serialization.json.*

/** Exact form observed in app-service.js, SHA 887642f9…712f; see BOOKING_EVIDENCE.md. */
class VenueApplicationForm(
    val siteId: String,
    val timeIds: List<String>,
    val venueName: String,
    val venueStatus: JsonElement,
    val groupName: String,
    val content: String,
    val contactName: String,
    val phone: String,
    val userId: String,
    val userName: String,
    val teacherId: String = "",
    val teacherName: String = "",
) {
    fun validation(): List<String> = buildList {
        if (siteId.isBlank()) add("缺少场馆标识")
        if (timeIds.isEmpty() || timeIds.any(String::isBlank)) add("请重新选择有效时段")
        if (venueName.isBlank() || venueStatus is JsonNull) add("场馆名称或 venueStatus 未返回，请刷新场馆详情")
        if (userId.isBlank() || userName.isBlank()) add("当前会话缺少用户标识或姓名，请重新登录后查询")
        if (groupName.isBlank()) add("请填写申请团体")
        if (content.isBlank()) add("请填写活动内容")
        if (contactName.isBlank()) add("请填写联系人")
        if (phone.isBlank() || phone.length !in 6..30 || phone.any { !it.isDigit() && it !in "+- ()" }) add("请核对联系电话")
        if (listOf(groupName, content, contactName, phone).any { it.length > 2000 || it.any(Char::isISOControl) }) add("表单含过长内容或控制字符")
    }
    fun parameters(): JsonObject = buildJsonObject {
        put("id", "")
        put("name", groupName)
        put("userId", userId)
        put("uerName", userName) // The misspelling belongs to the shipped client's contract.
        put("teacherId", teacherId)
        put("teacher", teacherName)
        put("applicants", "团体")
        put("objectId", "e872dbd2faddcb4c70d547e7ad010b45") // Application category, not a user identifier.
        put("place", venueName)
        put("time", "")
        put("deviceName", "")
        put("remark", "")
        put("venueStatus", venueStatus)
        put("status", 0)
        put("content", content)
        put("uName", contactName)
        put("uPhone", phone)
    }
    override fun toString(): String = "VenueApplicationForm(times=${timeIds.size})"
}

class FitnessEnrolmentForm(
    val physicalId: String,
    val physicalUserId: String,
    val userId: String,
    val userName: String,
    val studentId: String,
    val identityCard: String,
    val sex: String,
    val date: String,
    val period: String,
) {
    fun validation(): List<String> = buildList {
        if (physicalId.isBlank()) add("缺少体测场次标识")
        if (userId.isBlank() || userName.isBlank() || studentId.isBlank()) add("当前会话缺少报名所需身份字段，请重新登录")
        if (!identityCard.matches(Regex("(?:[0-9]{15}|[0-9]{17}[0-9Xx])"))) add("请核对身份证号格式")
        if (sex !in listOf("男", "女")) add("请选择性别")
        if (!validBookingDate(date)) add("请选择有效日期（YYYY-MM-DD）")
        if (period !in listOf("上午", "下午")) add("请选择上午或下午")
    }
    fun parameters(): JsonObject = queryParameters(
        "physicalId" to physicalId, "userId" to userId, "idcard" to identityCard,
        "name" to userName, "studentID" to studentId, "status" to "1", "sex" to sex,
        "physicalTime" to "$date 00:00", "period" to period, "physicalUserId" to physicalUserId,
    )
    override fun toString(): String = "FitnessEnrolmentForm(redacted)"
}

class FitnessCancellation(val physicalUserId: String, val reason: String) {
    fun validation(): List<String> = buildList {
        if (physicalUserId.isBlank()) add("服务端未返回 rId，暂不构造取消请求")
        if (reason.isBlank() || reason.length > 1000 || reason.any(Char::isISOControl)) add("请填写 1–1000 字取消原因")
    }
    fun parameters() = queryParameters("id" to physicalUserId, "cancelText" to reason, "status" to "4")
    override fun toString(): String = "FitnessCancellation(redacted)"
}

fun validBookingDate(value: String): Boolean {
    if (!value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) return false
    val year = value.take(4).toIntOrNull() ?: return false
    val month = value.substring(5, 7).toIntOrNull() ?: return false
    val day = value.takeLast(2).toIntOrNull() ?: return false
    if (year !in 1900..9999 || month !in 1..12) return false
    val max = when (month) { 4, 6, 9, 11 -> 30; 2 -> if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28; else -> 31 }
    return day in 1..max
}

/** Never put full account/card/contact details into a route, log, exception, or automatic debug output. */
fun bookingMasked(value: String): String = when { value.isBlank() -> "未填写"; value.length <= 4 -> "••••"; else -> "••••${value.takeLast(4)}" }

interface BookingWriteTransport {
    suspend fun createVenue(parameters: JsonObject): ApiResult<JsonElement>
    suspend fun relateVenue(siteId: String, applyId: String): ApiResult<JsonElement>
    suspend fun selectVenueTime(csvTimeIds: String, applyId: String): ApiResult<JsonElement>
    suspend fun defineVenue(userId: String, applyId: String, teacherId: String, teacherName: String): ApiResult<JsonElement>
    suspend fun enrolFitness(parameters: JsonObject): ApiResult<JsonElement>
    suspend fun cancelFitness(parameters: JsonObject): ApiResult<JsonElement>
}

class RepositoryBookingTransport(private val venues: VenueRepository, private val fitness: FitnessRepository) : BookingWriteTransport {
    override suspend fun createVenue(parameters: JsonObject) = venues.apply(parameters)
    override suspend fun relateVenue(siteId: String, applyId: String) = venues.relate(siteId, applyId)
    override suspend fun selectVenueTime(csvTimeIds: String, applyId: String) = venues.selectTime(csvTimeIds, applyId)
    override suspend fun defineVenue(userId: String, applyId: String, teacherId: String, teacherName: String) = venues.define(userId, applyId, teacherId, teacherName)
    override suspend fun enrolFitness(parameters: JsonObject) = fitness.enrol(parameters)
    override suspend fun cancelFitness(parameters: JsonObject) = fitness.cancel(parameters)
}

private fun ApiResult<JsonElement>.step(): BookingStepResult = when (this) {
    is ApiResult.Success -> BookingStepResult.Completed()
    is ApiResult.Failure -> BookingStepResult.Failed(error.message,
        uncertain = error is ApiError.Network || error is ApiError.Timeout || error is ApiError.Http || error is ApiError.Decode)
}

/** Calls are deliberately sequential instead of the original Promise.all: a failure keeps prior receipts and stops. */
fun venueConfirmation(transport: BookingWriteTransport, form: VenueApplicationForm): BookingConfirmation =
    BookingConfirmation.staticContract(listOf(
        BookingWriteStep("创建申请") {
            if (form.validation().isNotEmpty()) BookingStepResult.Failed("表单校验未通过；未发送申请。")
            else when (val result = transport.createVenue(form.parameters())) {
                is ApiResult.Failure -> result.step()
                is ApiResult.Success -> {
                    val id = ((result.data as? JsonObject)?.get("sportsVenueApply") as? JsonObject)?.bookingId().orEmpty()
                    if (id.isBlank()) BookingStepResult.Failed("服务端未返回 sportsVenueApply.id；结果待核对，后续步骤已停止。", uncertain = true)
                    else BookingStepResult.Completed(mapOf("applyId" to id))
                }
            }
        },
        BookingWriteStep("关联场馆") { refs -> transport.relateVenue(form.siteId, refs.getValue("applyId")).step() },
        BookingWriteStep("关联所选时段") { refs -> transport.selectVenueTime(form.timeIds.distinct().joinToString(","), refs.getValue("applyId")).step() },
        BookingWriteStep("关联申请人与教师") { refs -> transport.defineVenue(form.userId, refs.getValue("applyId"), form.teacherId, form.teacherName).step() },
    ))

fun fitnessEnrolmentConfirmation(transport: BookingWriteTransport, form: FitnessEnrolmentForm): BookingConfirmation =
    BookingConfirmation.staticContract(listOf(BookingWriteStep("提交体测报名") {
        if (form.validation().isNotEmpty()) BookingStepResult.Failed("报名表单校验未通过；未发送报名。")
        else transport.enrolFitness(form.parameters()).step()
    }))

fun fitnessCancellationConfirmation(transport: BookingWriteTransport, form: FitnessCancellation): BookingConfirmation =
    BookingConfirmation.staticContract(listOf(BookingWriteStep("取消体测报名") {
        if (form.validation().isNotEmpty()) BookingStepResult.Failed("取消参数校验未通过；未发送取消。")
        else transport.cancelFitness(form.parameters()).step()
    }))
