package io.github.betterzslf.feature.booking

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.platform.MAX_PICKED_FILE_BYTES
import io.github.betterzslf.platform.PickedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

enum class BookingAction(val title: String, val evidenceGap: String) {
    VENUE_APPLY(AppText.Booking.BOOKING_APPLICATION_TITLE, AppText.Booking.APPLICATION_CONTRACT_UNVERIFIED_LOCAL_DRAFT_ONLY_HINT),
    VENUE_CANCEL(AppText.Booking.CANCEL_BOOKING_TITLE, AppText.Booking.CANCELLATION_UNCONNECTED_QUERY_DOES_NOT_CHANGE_STATE_HINT),
    FITNESS_ENROL(AppText.Booking.FITNESS_REGISTRATION_TITLE, AppText.Booking.FITNESS_REGISTRATION_CONTRACT_UNVERIFIED_PREVIEW_ONLY_HINT),
    FITNESS_CANCEL(AppText.Booking.CANCEL_FITNESS_REGISTRATION_TITLE, AppText.Booking.FITNESS_CANCELLATION_CONTRACT_UNVERIFIED_QUERY_ONLY_HINT),
}

/** These are local draft fields, deliberately NOT serialized into an API request. */
class VenueDraft(
    val selection: BookingSelection,
    val note: String,
    val attachment: PickedFile?,
    val acknowledged: Boolean,
) {
    fun validation(ownerId: String, selectableTimeIds: Set<String>): List<String> = buildList {
        if (ownerId.isBlank()) add(AppText.Auth.LOGIN_REQUIRED)
        if (selection.siteId.isBlank()) add(AppText.Booking.VENUE_ID_MISSING_RETURN_TO_VENUE_LIST)
        if (selection.timeIds.isEmpty()) add(AppText.Booking.SELECT_AT_LEAST_ONE_TIME_SLOT)
        if (selection.timeIds.any { it !in selectableTimeIds }) add(AppText.Booking.SELECTED_SLOTS_CHANGED_OR_MISSING_REQUERY)
        if (note.isBlank()) add(AppText.Booking.LOCAL_APPLICATION_DESCRIPTION_REQUIRED)
        if (note.length > 2000) add(AppText.Booking.LOCAL_APPLICATION_DESCRIPTION_LENGTH_LIMIT)
        attachment?.let {
            if (it.size == 0) add(AppText.Booking.ATTACHMENT_EMPTY_RESELECT)
            if (it.size > MAX_PICKED_FILE_BYTES) add(AppText.Booking.ATTACHMENT_SIZE_LIMIT_EXCEEDED)
            if (it.name.any { char -> char in "\"\\/\r\n\u0000" }) add(AppText.Booking.ATTACHMENT_NAME_UNSUPPORTED_CHARACTERS_RENAME)
        }
        if (!acknowledged) add(AppText.Booking.ACKNOWLEDGE_VENUE_SLOTS_AND_LOCAL_ATTACHMENTS)
    }
    override fun toString(): String = "VenueDraft(times=${selection.timeIds.size}, attachment=${attachment != null})"
}

fun fitnessValidation(ownerId: String, selection: BookingSelection, returnedIds: Set<String>, acknowledged: Boolean): List<String> = buildList {
    if (ownerId.isBlank()) add(AppText.Auth.LOGIN_REQUIRED)
    if (selection.physicalId.isBlank()) add(AppText.Booking.FITNESS_SESSION_UNSELECTED_GLOBAL_ITEMS_QUERY_ONLY)
    if (selection.itemIds.isEmpty()) add(AppText.Booking.SELECT_AT_LEAST_ONE_FITNESS_ITEM)
    if (selection.itemIds.any { it !in returnedIds }) add(AppText.Booking.SELECTED_ITEMS_CHANGED_OR_MISSING_RESELECT)
    if (!acknowledged) add(AppText.Booking.ACKNOWLEDGE_SESSION_ITEMS_AND_CURRENT_REGISTRATION)
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
                        message = AppText.Booking.OPERATION_INTERRUPTED_RESULT_UNCONFIRMED_COMPLETED_STEPS_RETAINED)
                    throw cancelled
                } catch (_: Exception) {
                    BookingStepResult.Failed(AppText.Booking.OPERATION_RESULT_UNCONFIRMED_REFRESH_WITHOUT_AUTO_RESUBMIT, uncertain = true)
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
            mutableState.value = mutableState.value.copy(phase = BookingPhase.COMPLETED, message = AppText.Booking.OPERATION_FINISHED_REFRESH_STATUS)
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
