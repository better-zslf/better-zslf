package io.github.betterzslf.ui.run

import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.runui.RunReadSource
import io.github.betterzslf.feature.runui.RunReadOutcome
import io.github.betterzslf.feature.runui.RunReadIssue
import io.github.betterzslf.feature.runui.RunTaskPageSource
import io.github.betterzslf.feature.runui.RunRecordPageSource
import io.github.betterzslf.ui.SessionEpochReadFence
import io.github.betterzslf.feature.runui.RunRecordItem
import io.github.betterzslf.feature.runui.RunRecordListState
import io.github.betterzslf.feature.runui.RunTaskItem
import io.github.betterzslf.feature.runui.RunTaskListState
import io.github.betterzslf.feature.runui.RunTaskSelection

/** Root-only identity registry. A transport fingerprint or a server id never becomes a UI key. */
internal class RunListBindings<T>(
    private val prefix: String,
    private val identity: (T) -> String,
    private val isLive: () -> Boolean,
    private val currentRows: () -> List<T>,
) {
    internal class Render<T> internal constructor(internal val input: List<T>, internal val rows: Map<String, T>) {
        override fun toString(): String = "RunListRender(redacted)"
    }

    private val keys = mutableMapOf<String, String>()
    private var next = 0L
    private var mounted = true
    private var active: Render<T>? = null

    /** Each replacement list owns a fresh render token, even if rows have equal display values. */
    fun render(rows: List<T>): Render<T> {
        active?.takeIf { it.input === rows }?.let { return it }
        val projected = linkedMapOf<String, T>()
        rows.forEach { row ->
            val key = keys.getOrPut(identity(row)) { "$prefix:${++next}" }
            projected[key] = row
        }
        return Render(rows, projected.toMap()).also { active = it }
    }

    fun isCurrent(render: Render<T>): Boolean = mounted && active === render && live() &&
        currentRows() === render.input && live()

    fun lookup(render: Render<T>, localKey: String): T? {
        if (!isCurrent(render)) return null
        val row = render.rows[localKey] ?: return null
        return row.takeIf { isCurrent(render) }
    }

    fun retire() { mounted = false; active = null; keys.clear() }
    private fun live(): Boolean = runCatching(isLive).getOrDefault(false)
    override fun toString(): String = "RunListBindings(redacted)"
}

internal fun boundRunTaskPages(source: RunReadSource?, fence: SessionEpochReadFence, owner: String): RunTaskPageSource =
    RunTaskPageSource { requestedOwner, offset, limit, filter ->
        if (owner.isBlank() || source == null || source.boundOwnerId != requestedOwner || owner != requestedOwner)
            RunReadOutcome.Failure(RunReadIssue.AUTHENTICATION)
        else fence.readCurrent { source.tasks(requestedOwner, offset, limit, filter) }
    }

internal fun boundRunRecordPages(source: RunReadSource?, fence: SessionEpochReadFence, owner: String): RunRecordPageSource =
    RunRecordPageSource { requestedOwner, schedule, offset, limit ->
        if (source == null || source.boundOwnerId != requestedOwner || owner != requestedOwner)
            RunReadOutcome.Failure(RunReadIssue.AUTHENTICATION)
        else fence.readCurrent { source.records(requestedOwner, schedule, offset, limit) }
    }

internal fun taskListDestination(task: RunTaskItem, action: RunTaskRowAction): Route.Page? {
    val selection = RunTaskSelection.from(task) ?: return null
    return when (action) {
        RunTaskRowAction.DETAIL, RunTaskRowAction.RECORD_REAL -> Route.Page("rundetail", selection.encode(), task.title)
        RunTaskRowAction.RECORDS -> Route.Page("runrecords", selection.encode(), AppText.Run.SCHEDULE_RUN_RECORDS_TITLE)
    }
}

internal fun recordListDestination(record: RunRecordItem): Route.Page? =
    record.trid?.takeIf { it.isNotBlank() }?.let { Route.Page("runrecord", it, record.title) }

internal fun taskListDisplay(
    state: RunTaskListState,
    render: RunListBindings.Render<RunTaskItem>,
    signedIn: Boolean,
    metricUnits: Boolean,
    contributions: List<RunListContributionDisplay>,
): RunTaskListDisplay = RunTaskListDisplay(
    rows = render.rows.map { (key, task) -> RunTaskRowDisplay(key, task.title, task.minimumDistanceMeters,
        task.endText, task.isFinish, RunTaskSelection.from(task) != null) },
    filter = state.filter,
    signedIn = signedIn,
    metricUnits = metricUnits,
    loading = state.loading,
    end = state.end,
    error = state.error,
    moreError = state.moreError,
    warnings = state.warnings,
    contributions = contributions,
)

internal fun recordListDisplay(
    state: RunRecordListState,
    render: RunListBindings.Render<RunRecordItem>,
    minimum: Double?,
    metricUnits: Boolean,
    planError: String?,
): RunRecordListDisplay = RunRecordListDisplay(
    rows = render.rows.map { (key, record) -> RunRecordRowDisplay(key, record.title, record.distanceMeters,
        record.elapsedMillis, record.status, !record.trid.isNullOrBlank()) },
    hasSelection = true,
    minimumDistanceMeters = minimum,
    metricUnits = metricUnits,
    loading = state.loading,
    end = state.end,
    error = state.error,
    moreError = state.moreError,
    warnings = state.warnings,
    planError = planError,
)
