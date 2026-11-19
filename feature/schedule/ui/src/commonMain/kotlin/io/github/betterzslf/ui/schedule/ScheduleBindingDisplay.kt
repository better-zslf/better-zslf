package io.github.betterzslf.ui.schedule

import io.github.betterzslf.core.text.AppText

/** Finite, locally-authored copy only; this is not a channel for arbitrary service messages. */
enum class ScheduleBindingNotice { NOT_VERIFIED, INPUT_CHANGED, VERIFY_FAILED, SAVE_FAILED }
enum class ScheduleBindingMessage {
    STORAGE_FAILURE, VERIFY_FAILED, CREDENTIALS_REQUIRED, CACHE_RETAINED, NO_CACHE,
    PERIODS_INVALID, UNBIND_FAILED, NATIVE_UPDATE_FAILED, PARTIAL_PARSE_RETAINED, READ_FAILED,
}

data class ScheduleBindingDisplay(
    val updatingPassword: Boolean = false,
    val hasSlot: Boolean = false,
    val storageFailure: Boolean = false,
    val testing: Boolean = false,
    val saving: Boolean = false,
    val notice: ScheduleBindingNotice = ScheduleBindingNotice.NOT_VERIFIED,
    /** Already masked by the product. Never an input value or a binding/query identifier. */
    val verifiedLabel: String = "",
    val runtimeMessage: ScheduleBindingMessage? = null,
) {
    override fun toString(): String = "ScheduleBindingDisplay(redacted)"
}

internal fun ScheduleBindingNotice.text(): String = when (this) {
    ScheduleBindingNotice.NOT_VERIFIED -> AppText.Schedule.NOT_VERIFIED
    ScheduleBindingNotice.INPUT_CHANGED -> AppText.Schedule.INPUT_CHANGED
    ScheduleBindingNotice.VERIFY_FAILED -> AppText.Schedule.VERIFY_FAILED
    ScheduleBindingNotice.SAVE_FAILED -> AppText.Common.LOCAL_DATA_SAVE_FAILED
}

internal fun ScheduleBindingMessage.text(): String = when (this) {
    ScheduleBindingMessage.STORAGE_FAILURE -> AppText.Schedule.STORAGE_FAILURE
    ScheduleBindingMessage.VERIFY_FAILED -> AppText.Schedule.VERIFY_FAILED
    ScheduleBindingMessage.CREDENTIALS_REQUIRED -> AppText.Schedule.CREDENTIALS_REQUIRED
    ScheduleBindingMessage.CACHE_RETAINED -> AppText.Schedule.CACHE_RETAINED_HINT
    ScheduleBindingMessage.NO_CACHE -> AppText.Schedule.NO_CACHE
    ScheduleBindingMessage.PERIODS_INVALID -> AppText.Schedule.PERIODS_INVALID
    ScheduleBindingMessage.UNBIND_FAILED -> AppText.Schedule.UNBIND_FAILED
    ScheduleBindingMessage.NATIVE_UPDATE_FAILED -> AppText.Schedule.NATIVE_UPDATE_FAILED
    ScheduleBindingMessage.PARTIAL_PARSE_RETAINED -> AppText.Schedule.PARTIAL_PARSE_RETAINED
    ScheduleBindingMessage.READ_FAILED -> AppText.Common.READ_FAILED
}
