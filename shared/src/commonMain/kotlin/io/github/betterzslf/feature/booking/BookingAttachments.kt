package io.github.betterzslf.feature.booking

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.net.UploadFile
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.VenueRepository
import io.github.betterzslf.platform.MAX_PICKED_FILE_BYTES
import io.github.betterzslf.platform.PickedFile
import kotlinx.serialization.json.*

interface BookingAttachmentTransport {
    suspend fun transfer(file: UploadFile, applyId: String, userId: String): ApiResult<JsonElement>
    suspend fun finish(applyId: String): ApiResult<JsonElement>
    suspend fun detail(applyId: String): ApiResult<JsonElement>
}

class RepositoryAttachmentTransport(private val venues: VenueRepository) : BookingAttachmentTransport {
    override suspend fun transfer(file: UploadFile, applyId: String, userId: String) = venues.uploadAttachment(file, applyId, userId)
    override suspend fun finish(applyId: String) = venues.afterUpload(applyId)
    override suspend fun detail(applyId: String) = venues.application(applyId)
}

fun attachmentValidation(applyId: String, userId: String, files: List<PickedFile>, status: String): List<String> = buildList {
    if (applyId.isBlank() || userId.isBlank()) add(AppText.Booking.APPLICATION_OR_USER_ID_MISSING_REFRESH_DETAILS)
    if (status != "1") add(AppText.Booking.APPLICATION_NOT_AWAITING_FILES_REFRESH_STATUS)
    if (files.isEmpty()) add(AppText.Booking.SELECT_STAMPED_DOCUMENT_PHOTOS_FIRST)
    if (files.size > 9) add(AppText.Booking.ATTACHMENT_SELECTION_LIMIT_PER_OPERATION)
    files.forEachIndexed { index, file ->
        if (file.size !in 1..MAX_PICKED_FILE_BYTES) add(AppText.Booking.attachmentEmptyOrOversized((index + 1)))
        if (!file.mediaType.startsWith("image/")) add(AppText.Booking.attachmentPhotoRequired((index + 1)))
        if (file.name.any { it in "\"\\/\r\n\u0000" }) add(AppText.Booking.attachmentNameUnsupported((index + 1)))
    }
}

/** Path/id are comparison receipts only; they are not upload credentials or invented response fields. */
fun attachmentReceipts(data: JsonElement): Set<String> = ((data as? JsonObject)?.get("fileList") as? JsonArray)
    ?.mapNotNull { file -> file.bookingText("id").ifBlank { file.bookingText("path") }.takeIf(String::isNotBlank) }?.toSet().orEmpty()

private fun ApiResult.Failure.attachmentFailure() = BookingStepResult.Failed(error.message,
    uncertain = error is ApiError.Network || error is ApiError.Timeout || error is ApiError.Http || error is ApiError.Decode)

/** Original client uploads each file, then afterUploadFile. We add a read-back before declaring completion. */
fun attachmentConfirmation(
    transport: BookingAttachmentTransport,
    applyId: String,
    userId: String,
    files: List<PickedFile>,
    status: String,
    previousReceipts: Set<String>,
): BookingConfirmation {
    val validation = attachmentValidation(applyId, userId, files, status)
    val steps = files.mapIndexed { index, file -> BookingWriteStep(AppText.Booking.attachmentTransferStep((index + 1), (files.size))) {
        if (validation.isNotEmpty()) BookingStepResult.Failed(AppText.Booking.ATTACHMENT_VALIDATION_FAILED_FILES_NOT_SENT)
        else when (val result = transport.transfer(UploadFile(file.name, file.readBytes(), file.mediaType), applyId, userId)) {
            is ApiResult.Success -> BookingStepResult.Completed()
            is ApiResult.Failure -> result.attachmentFailure()
        }
    } }.toMutableList()
    steps += BookingWriteStep(AppText.Booking.NOTIFY_ATTACHMENT_TRANSFER_COMPLETION_STEP) {
        if (validation.isNotEmpty()) BookingStepResult.Failed(AppText.Booking.ATTACHMENT_VALIDATION_FAILED_WRITEBACK_NOT_SENT)
        else when (val result = transport.finish(applyId)) {
            is ApiResult.Success -> BookingStepResult.Completed()
            is ApiResult.Failure -> result.attachmentFailure()
        }
    }
    steps += BookingWriteStep(AppText.Booking.REQUERY_AND_REVIEW_ATTACHMENT_LIST_STEP) {
        when (val result = transport.detail(applyId)) {
            is ApiResult.Failure -> BookingStepResult.Failed(AppText.Booking.TRANSFER_RESULT_UNCONFIRMED_REFRESH_WITHOUT_AUTO_REUPLOAD, uncertain = true)
            is ApiResult.Success -> {
                val newReceipts = attachmentReceipts(result.data) - previousReceipts
                if (newReceipts.size < files.size) BookingStepResult.Failed(AppText.Booking.TRANSFER_ENDED_NEW_ATTACHMENTS_UNCONFIRMED_QUERY_FIRST, uncertain = true)
                else BookingStepResult.Completed()
            }
        }
    }
    return BookingConfirmation.staticContract(steps)
}
