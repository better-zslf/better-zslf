package io.github.betterzslf.feature.runui

import io.github.betterzslf.feature.run.GeoPoint

/** One immutable captured owner/binding; no method silently recaptures a later session. */
interface RunReadSource {
    val boundOwnerId: String
    fun isCurrentSession(): Boolean
    suspend fun tasks(ownerId: String, offset: Int = 0, limit: Int = 20, isFinish: String = "0"): RunReadOutcome<RunRows<RunTaskItem>>
    suspend fun detail(errandId: String, ownerId: String, errandScheduleId: String): RunReadOutcome<RunReadValue<RunTaskDetail>>
    suspend fun records(ownerId: String): RunReadOutcome<RunRows<RunRecordItem>>
    suspend fun records(ownerId: String, errandScheduleId: String, offset: Int = 0, limit: Int = 10): RunReadOutcome<RunRows<RunRecordItem>>
    suspend fun record(trid: String): RunReadOutcome<RunReadValue<RunRecordItem>>
    suspend fun track(trid: String): RunReadOutcome<RunRows<GeoPoint>>
}

fun interface RunTaskPageSource {
    suspend fun load(ownerId: String, offset: Int, limit: Int, isFinish: String): RunReadOutcome<RunRows<RunTaskItem>>
}

fun interface RunRecordPageSource {
    suspend fun load(ownerId: String, errandScheduleId: String, offset: Int, limit: Int): RunReadOutcome<RunRows<RunRecordItem>>
}
