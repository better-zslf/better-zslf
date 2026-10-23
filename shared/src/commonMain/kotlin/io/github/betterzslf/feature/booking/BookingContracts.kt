package io.github.betterzslf.feature.booking

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.FitnessRepository
import io.github.betterzslf.data.VenueRepository
import kotlinx.serialization.json.*

/** Exact form observed in app-service.js, SHA 887642f9…712f; the evidence record is kept in Pro CI docs. */
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
        if (siteId.isBlank()) add(AppText.Booking.VENUE_ID_MISSING)
        if (timeIds.isEmpty() || timeIds.any(String::isBlank)) add(AppText.Booking.RESELECT_VALID_TIME_SLOT)
        if (venueName.isBlank() || venueStatus is JsonNull) add(AppText.Booking.VENUE_NAME_OR_STATUS_MISSING_REFRESH_DETAILS)
        if (userId.isBlank() || userName.isBlank()) add(AppText.Booking.SESSION_USER_ID_OR_NAME_MISSING_RELOGIN_REQUIRED)
        if (groupName.isBlank()) add(AppText.Booking.APPLICANT_GROUP_REQUIRED)
        if (content.isBlank()) add(AppText.Booking.ACTIVITY_DESCRIPTION_REQUIRED)
        if (contactName.isBlank()) add(AppText.Booking.CONTACT_PERSON_REQUIRED)
        if (phone.isBlank() || phone.length !in 6..30 || phone.any { !it.isDigit() && it !in "+- ()" }) add(AppText.Booking.REVIEW_CONTACT_PHONE)
        if (listOf(groupName, content, contactName, phone).any { it.length > 2000 || it.any(Char::isISOControl) }) add(AppText.Booking.FORM_CONTAINS_EXCESS_LENGTH_OR_CONTROL_CHARACTERS)
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
        if (physicalId.isBlank()) add(AppText.Booking.FITNESS_SESSION_ID_REQUIRED)
        if (userId.isBlank() || userName.isBlank() || studentId.isBlank()) add(AppText.Booking.REGISTRATION_IDENTITY_FIELDS_MISSING_RELOGIN_REQUIRED)
        if (!identityCard.matches(Regex("(?:[0-9]{15}|[0-9]{17}[0-9Xx])"))) add(AppText.Booking.REVIEW_IDENTITY_CARD_FORMAT)
        if (sex !in listOf("男", "女")) add(AppText.Booking.SEX_SELECTION_REQUIRED)
        if (!validBookingDate(date)) add(AppText.Booking.VALID_BOOKING_DATE_REQUIRED)
        if (period !in listOf("上午", "下午")) add(AppText.Booking.MORNING_OR_AFTERNOON_SELECTION_REQUIRED)
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
        if (physicalUserId.isBlank()) add(AppText.Booking.REGISTRATION_ID_MISSING_CANCELLATION_NOT_PREPARED)
        if (reason.isBlank() || reason.length > 1000 || reason.any(Char::isISOControl)) add(AppText.Booking.CANCELLATION_REASON_LENGTH_AND_CONTENT_REQUIRED)
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
fun bookingMasked(value: String): String = when { value.isBlank() -> AppText.Common.NOT_FILLED_IN; value.length <= 4 -> "••••"; else -> "••••${value.takeLast(4)}" }

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
        BookingWriteStep(AppText.Booking.CREATE_APPLICATION_STEP) {
            if (form.validation().isNotEmpty()) BookingStepResult.Failed(AppText.Booking.APPLICATION_FORM_INVALID_NOT_SENT)
            else when (val result = transport.createVenue(form.parameters())) {
                is ApiResult.Failure -> result.step()
                is ApiResult.Success -> {
                    val id = ((result.data as? JsonObject)?.get("sportsVenueApply") as? JsonObject)?.bookingId().orEmpty()
                    if (id.isBlank()) BookingStepResult.Failed(AppText.Booking.CREATED_APPLICATION_ID_MISSING_FOLLOWING_STEPS_STOPPED, uncertain = true)
                    else BookingStepResult.Completed(mapOf("applyId" to id))
                }
            }
        },
        BookingWriteStep(AppText.Booking.LINK_VENUE_STEP) { refs -> transport.relateVenue(form.siteId, refs.getValue("applyId")).step() },
        BookingWriteStep(AppText.Booking.LINK_SELECTED_TIME_SLOTS_STEP) { refs -> transport.selectVenueTime(form.timeIds.distinct().joinToString(","), refs.getValue("applyId")).step() },
        BookingWriteStep(AppText.Booking.LINK_APPLICANT_AND_TEACHER_STEP) { refs -> transport.defineVenue(form.userId, refs.getValue("applyId"), form.teacherId, form.teacherName).step() },
    ))

fun fitnessEnrolmentConfirmation(transport: BookingWriteTransport, form: FitnessEnrolmentForm): BookingConfirmation =
    BookingConfirmation.staticContract(listOf(BookingWriteStep(AppText.Booking.SUBMIT_FITNESS_REGISTRATION_STEP) {
        if (form.validation().isNotEmpty()) BookingStepResult.Failed(AppText.Booking.FITNESS_REGISTRATION_FORM_INVALID_NOT_SENT)
        else transport.enrolFitness(form.parameters()).step()
    }))

fun fitnessCancellationConfirmation(transport: BookingWriteTransport, form: FitnessCancellation): BookingConfirmation =
    BookingConfirmation.staticContract(listOf(BookingWriteStep(AppText.Booking.CANCEL_FITNESS_REGISTRATION_TITLE) {
        if (form.validation().isNotEmpty()) BookingStepResult.Failed(AppText.Booking.CANCELLATION_PARAMETERS_INVALID_NOT_SENT)
        else transport.cancelFitness(form.parameters()).step()
    }))
