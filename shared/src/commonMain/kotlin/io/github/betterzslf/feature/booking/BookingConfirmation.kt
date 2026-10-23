package io.github.betterzslf.feature.booking

import io.github.betterzslf.platform.MAX_PICKED_FILE_BYTES
import io.github.betterzslf.platform.PickedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

enum class BookingAction(val title: String, val evidenceGap: String) {
    VENUE_APPLY("预约申请", "docs/13 仅验证了空参失败；申请必填字段、成功申请标识、时段关联顺序及预约附件上传契约尚未确认。当前仅保留本地草稿，不发送申请或附件。"),
    VENUE_CANCEL("取消预约", "取消预约暂未接通。现有申请查询与审批进度不用于取消，当前申请状态保持不变。"),
    FITNESS_ENROL("体测报名", "docs/13 未确认 addPhysicalUser 的真实必填字段和成功报名标识；api-spec 只记录透传参数。当前仅预览选择，不发送报名。"),
    FITNESS_CANCEL("取消体测报名", "docs/13 未确认 cancelPhysical 的真实请求字段与报名标识。当前仅展示查询结果，不发送取消请求。"),
}

/** These are local draft fields, deliberately NOT serialized into an API request. */
class VenueDraft(
    val selection: BookingSelection,
    val note: String,
    val attachment: PickedFile?,
    val acknowledged: Boolean,
) {
    fun validation(ownerId: String, selectableTimeIds: Set<String>): List<String> = buildList {
        if (ownerId.isBlank()) add("请先登录")
        if (selection.siteId.isBlank()) add("缺少场馆标识，请返回场馆列表重新选择")
        if (selection.timeIds.isEmpty()) add("请选择至少一个时段")
        if (selection.timeIds.any { it !in selectableTimeIds }) add("所选时段已变化或未返回，请重新查询时段")
        if (note.isBlank()) add("请填写本地申请说明")
        if (note.length > 2000) add("本地申请说明请控制在 2000 字以内")
        attachment?.let {
            if (it.size == 0) add("附件为空，请重新选择")
            if (it.size > MAX_PICKED_FILE_BYTES) add("附件超过 8 MiB")
            if (it.name.any { char -> char in "\"\\/\r\n\u0000" }) add("附件名称含有不支持的字符，请重命名后选择")
        }
        if (!acknowledged) add("请勾选已核对场馆、时段和本地附件")
    }
    override fun toString(): String = "VenueDraft(times=${selection.timeIds.size}, attachment=${attachment != null})"
}

fun fitnessValidation(ownerId: String, selection: BookingSelection, returnedIds: Set<String>, acknowledged: Boolean): List<String> = buildList {
    if (ownerId.isBlank()) add("请先登录")
    if (selection.physicalId.isBlank()) add("尚未选择体测场次；全局项目列表仅供查询")
    if (selection.itemIds.isEmpty()) add("请选择至少一个体测项目")
    if (selection.itemIds.any { it !in returnedIds }) add("所选项目已变化或未返回，请重新选择")
    if (!acknowledged) add("请勾选已核对场次、项目及当前报名信息")
}

data class BookingPreview(val title: String, val lines: List<String>, val validation: List<String> = emptyList()) {
    // A preview may contain user-entered data: never expose it in automatic debug output.
    override fun toString(): String = "BookingPreview(valid=${validation.isEmpty()}, fields=${lines.size})"
}

enum class BookingPhase { EDITING, PREVIEW, RUNNING, BLOCKED, FAILED, UNCERTAIN, COMPLETED }
enum class BookingStepPhase { PENDING, RUNNING, COMPLETED, FAILED, UNCERTAIN }
data class BookingStepState(val label: String, val phase: BookingStepPhase = BookingStepPhase.PENDING)
data class BookingConfirmationState(
    val phase: BookingPhase = BookingPhase.EDITING,
    val previewId: Long = 0,
    val preview: BookingPreview? = null,
    val steps: List<BookingStepState> = emptyList(),
    val message: String = "",
    val references: Map<String, String> = emptyMap(),
) {
    override fun toString(): String = "BookingConfirmationState(phase=$phase, steps=${steps.size})"
}

internal sealed interface BookingStepResult {
    data class Completed(val references: Map<String, String> = emptyMap()) : BookingStepResult
    data class Failed(val message: String, val uncertain: Boolean = false) : BookingStepResult
}
internal class BookingWriteStep(val label: String, val run: suspend (Map<String, String>) -> BookingStepResult)

/**
 * Unknown operations use the blocked constructor. Artifact-backed plans inject exact steps.
 * No operation runs until a valid immutable preview is explicitly confirmed; failures never auto-retry.
 */
class BookingConfirmation private constructor(
    private val gap: String?,
    private val writes: List<BookingWriteStep>,
) {
    val permitsSubmission: Boolean get() = gap == null
    constructor(action: BookingAction) : this(action.evidenceGap, emptyList())

    private val mutableState = MutableStateFlow(BookingConfirmationState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var nextId = 0L

    fun preview(value: BookingPreview): Boolean {
        if (mutableState.value.phase in setOf(BookingPhase.RUNNING, BookingPhase.FAILED, BookingPhase.UNCERTAIN, BookingPhase.COMPLETED)) return false
        if (value.validation.isNotEmpty()) {
            mutableState.value = BookingConfirmationState(message = value.validation.joinToString("\n"))
            return false
        }
        mutableState.value = BookingConfirmationState(BookingPhase.PREVIEW, ++nextId, value,
            writes.map { BookingStepState(it.label) })
        return true
    }

    /** A stale preview or an unacknowledged final dialog is never a submission. */
    suspend fun confirm(previewId: Long, acknowledged: Boolean) {
        if (!mutex.tryLock()) return
        try {
            val current = mutableState.value
            if (current.phase != BookingPhase.PREVIEW || current.previewId != previewId || !acknowledged) return
            if (gap != null) {
                mutableState.value = current.copy(phase = BookingPhase.BLOCKED, message = gap)
                return
            }
            mutableState.value = current.copy(phase = BookingPhase.RUNNING)
            for ((index, write) in writes.withIndex()) {
                updateStep(index, BookingStepPhase.RUNNING)
                val result = try {
                    write.run(mutableState.value.references.toMap())
                } catch (cancelled: CancellationException) {
                    updateStep(index, BookingStepPhase.UNCERTAIN)
                    mutableState.value = mutableState.value.copy(phase = BookingPhase.UNCERTAIN,
                        message = "操作已中断，服务端结果待核对；已完成步骤保留，不会自动重提。")
                    throw cancelled
                } catch (_: Exception) {
                    BookingStepResult.Failed("操作结果待核对，请先刷新查询；不会自动重提。", uncertain = true)
                }
                when (result) {
                    is BookingStepResult.Completed -> {
                        updateStep(index, BookingStepPhase.COMPLETED)
                        mutableState.value = mutableState.value.copy(references = mutableState.value.references + result.references)
                    }
                    is BookingStepResult.Failed -> {
                        updateStep(index, if (result.uncertain) BookingStepPhase.UNCERTAIN else BookingStepPhase.FAILED)
                        mutableState.value = mutableState.value.copy(
                            phase = if (result.uncertain) BookingPhase.UNCERTAIN else BookingPhase.FAILED,
                            message = result.message,
                        )
                        return
                    }
                }
            }
            mutableState.value = mutableState.value.copy(phase = BookingPhase.COMPLETED, message = "操作完成，请刷新查询状态。")
        } finally { mutex.unlock() }
    }

    fun edit() {
        if (mutableState.value.phase in setOf(BookingPhase.EDITING, BookingPhase.PREVIEW, BookingPhase.BLOCKED)) {
            mutableState.value = BookingConfirmationState()
        }
    }

    private fun updateStep(index: Int, phase: BookingStepPhase) {
        mutableState.value = mutableState.value.copy(steps = mutableState.value.steps.mapIndexed { i, step ->
            if (i == index) step.copy(phase = phase) else step
        })
    }

    internal companion object {
        /** Only exact artifact-backed plans use this path; successful server execution remains unverified. */
        fun staticContract(steps: List<BookingWriteStep>) = BookingConfirmation(null, steps)
        fun fixture(steps: List<BookingWriteStep>) = BookingConfirmation(null, steps)
    }
}
