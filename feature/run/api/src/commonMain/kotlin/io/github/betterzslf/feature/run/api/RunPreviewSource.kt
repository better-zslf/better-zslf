package io.github.betterzslf.feature.run.api

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** Request ids only. Not a serialized account or a production run id. */
@Serializable
data class RunPreviewSelection(val taskId: String, val scheduleId: String) {
    init {
        require(listOf(taskId, scheduleId).all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) })
    }
}

/** Owner/revision fence, opaque to consumers. Equality of user-visible ids is not sufficient authority. */
interface RunPreviewLease {
    fun isCurrent(): Boolean
}

sealed interface RunPreviewRead {
    data class Ready(val context: RunPreviewContext, val lease: RunPreviewLease) : RunPreviewRead
    data class Failed(val message: String) : RunPreviewRead
}

/** Read capability only: deliberately no punch/upload/store/location methods. */
interface RunPreviewSource {
    val revision: StateFlow<Long>
    suspend fun read(selection: RunPreviewSelection): RunPreviewRead
}
