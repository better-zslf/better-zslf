package io.github.betterzslf.feature.booking

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
    if (applyId.isBlank() || userId.isBlank()) add("申请或用户标识缺失，请刷新详情")
    if (status != "1") add("当前申请不在待上传文件状态，请刷新查询")
    if (files.isEmpty()) add("请先选择盖章文件相片")
    if (files.size > 9) add("每次最多选择 9 个附件")
    files.forEachIndexed { index, file ->
        if (file.size !in 1..MAX_PICKED_FILE_BYTES) add("附件 ${index + 1} 为空或超过 8 MiB")
        if (!file.mediaType.startsWith("image/")) add("附件 ${index + 1} 请选择盖章文件的相片")
        if (file.name.any { it in "\"\\/\r\n\u0000" }) add("附件 ${index + 1} 名称含不支持的字符")
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
    val steps = files.mapIndexed { index, file -> BookingWriteStep("传输附件 ${index + 1}/${files.size}") {
        if (validation.isNotEmpty()) BookingStepResult.Failed("附件校验未通过；未发送文件。")
        else when (val result = transport.transfer(UploadFile(file.name, file.readBytes(), file.mediaType), applyId, userId)) {
            is ApiResult.Success -> BookingStepResult.Completed()
            is ApiResult.Failure -> result.attachmentFailure()
        }
    } }.toMutableList()
    steps += BookingWriteStep("通知服务端完成附件传输") {
        if (validation.isNotEmpty()) BookingStepResult.Failed("附件校验未通过；未发送回写。")
        else when (val result = transport.finish(applyId)) {
            is ApiResult.Success -> BookingStepResult.Completed()
            is ApiResult.Failure -> result.attachmentFailure()
        }
    }
    steps += BookingWriteStep("重新查询并核对附件列表") {
        when (val result = transport.detail(applyId)) {
            is ApiResult.Failure -> BookingStepResult.Failed("传输结果待核对，请刷新申请详情；不会自动重传。", uncertain = true)
            is ApiResult.Success -> {
                val newReceipts = attachmentReceipts(result.data) - previousReceipts
                if (newReceipts.size < files.size) BookingStepResult.Failed("已结束传输，但附件列表尚未确认全部新文件。请先查询申请，勿重复上传。", uncertain = true)
                else BookingStepResult.Completed()
            }
        }
    }
    return BookingConfirmation.staticContract(steps)
}
