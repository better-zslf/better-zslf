package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

fun interface RunTaskPageSource {
    suspend fun load(ownerId: String, offset: Int, limit: Int, isFinish: String): ApiResult<JsonElement>
}

enum class RunTaskFilter { ALL, UNFINISHED, FINISHED }

data class RunTaskListState(
    val items: List<RunTaskItem> = emptyList(),
    val filter: RunTaskFilter = RunTaskFilter.ALL,
    val loading: Boolean = false,
    val end: Boolean = false,
    val error: String? = null,
    val moreError: String? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * Explicit, bounded pagination: each action fetches at most ONE page for each selected status.
 * ALL is the union of two independent streams (isFinish=0 and isFinish=1), never a made-up -1.
 * Refresh failures retain visible rows and a pending offset-zero retry, not an automatic loop.
 * Paging preserves decoded task identifiers; it never allocates a client run/session id.
 */
class RunTaskController(
    private val source: RunTaskPageSource,
    private val ownerId: String,
    private val pageSize: Int = 20,
) {
    init { require(pageSize > 0) { AppText.Common.PAGE_SIZE_MUST_BE_POSITIVE } }

    private data class Branch(
        val offset: Int = 0,
        val end: Boolean = false,
        val loaded: Boolean = false,
        val refreshPending: Boolean = true,
        val items: List<RunTaskItem> = emptyList(),
        val seen: Set<String> = emptySet(),
        val pages: Set<String> = emptySet(),
        val warnings: List<String> = emptyList(),
        val problem: String? = null,
        val refreshProblem: Boolean = false,
    )
    private data class Request(val ticket: Long, val branches: Map<String, Branch>, val flags: List<String>)
    private enum class Action { SELECT, REFRESH, MORE }

    private val mutable = MutableStateFlow(RunTaskListState())
    val state: StateFlow<RunTaskListState> = mutable.asStateFlow()
    private val control = Mutex()
    private var initialized = false
    private var generation = 0L
    private var branches = emptyMap<String, Branch>()

    suspend fun select(filter: RunTaskFilter) { begin(Action.SELECT, filter)?.let { execute(it) } }
    suspend fun refresh() { begin(Action.REFRESH)?.let { execute(it) } }
    suspend fun more() { begin(Action.MORE)?.let { execute(it) } }

    private suspend fun begin(action: Action, selected: RunTaskFilter? = null): Request? = control.withLock {
        val previous = mutable.value
        val filter = selected ?: previous.filter
        val changed = filter != previous.filter
        // Same action/load taps coalesce; a DIFFERENT filter may supersede an in-flight response.
        if (action == Action.SELECT && initialized && !changed) return@withLock null
        if (previous.loading && !changed) return@withLock null
        val flags = flags(filter)
        if (changed || !initialized) {
            branches = flags.associateWith { Branch() }
            mutable.value = RunTaskListState(filter = filter)
        } else if (action == Action.REFRESH) {
            branches = branches.mapValues { (_, branch) -> branch.copy(refreshPending = true) }
        }
        initialized = true
        if (ownerId.isBlank()) {
            mutable.value = mutable.value.copy(loading = false, error = AppText.Auth.USER_ID_MISSING_LOG_IN_AND_REFRESH)
            return@withLock null
        }
        val ready = flags.filter { branches.getValue(it).let { branch -> branch.refreshPending || !branch.end } }
        if (ready.isEmpty()) return@withLock null
        val ticket = ++generation
        mutable.value = mutable.value.copy(loading = true, end = false)
        Request(ticket, branches.toMap(), ready)
    }

    private suspend fun execute(request: Request) {
        try {
            val updated = request.branches.toMutableMap()
            for (flag in request.flags) {
                if (!current(request.ticket)) return
                val branch = updated.getValue(flag)
                val offset = if (branch.refreshPending) 0 else branch.offset
                val result = try { source.load(ownerId, offset, pageSize, flag) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { ApiResult.Failure(ApiError.Network(AppText.Run.RUN_TASK_LOAD_FAILED_RETRY)) }
                updated[flag] = when (result) {
                    is ApiResult.Failure -> failed(branch, result.error.message)
                    is ApiResult.Success -> consume(branch, flag, offset, RunDataDecoder.tasks(result.data))
                }
            }
            control.withLock {
                if (request.ticket == generation) {
                    branches = updated.toMap()
                    publish()
                }
            }
        } catch (cancelled: CancellationException) {
            // The caller owns cancellation; no retries are launched and pending offsets remain usable.
            withContext(NonCancellable) {
                control.withLock {
                    if (request.ticket == generation) mutable.value = mutable.value.copy(loading = false)
                }
            }
            throw cancelled
        }
    }

    private fun failed(branch: Branch, problem: String, warnings: List<String> = emptyList()): Branch =
        branch.copy(problem = problem, refreshProblem = branch.refreshPending, warnings = (branch.warnings + warnings).distinct())

    private fun consume(branch: Branch, flag: String, offset: Int, rows: RunRows<RunTaskItem>): Branch {
        if (!rows.recognized) return failed(branch, AppText.Run.UNRECOGNIZED_TASK_RESPONSE_REFRESH_AND_RETRY, rows.warnings)
        val base = if (branch.refreshPending) Branch(refreshPending = false) else branch
        val warnings = (base.warnings + rows.warnings).toMutableList()
        val keys = rows.items.map { it.identityKey }
        val signature = JsonArray(keys.map(::JsonPrimitive)).toString()
        val noProgress = rows.rawSize > 0 && (rows.items.isEmpty() ||
            (base.loaded && (signature in base.pages || keys.none { it !in base.seen })))
        if (noProgress) warnings += AppText.Run.taskPaginationRepeated((flag), (offset))
        if (rows.rawSize > pageSize) warnings += AppText.Run.taskPaginationOversized((flag), (offset))
        val next = offset.toLong() + rows.rawSize.toLong()
        if (next > Int.MAX_VALUE) warnings += AppText.Run.taskPaginationOverflow((flag))
        return Branch(
            offset = next.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            end = rows.rawSize < pageSize || noProgress || next > Int.MAX_VALUE,
            loaded = true,
            refreshPending = false,
            items = merge(base.items, rows.items),
            seen = base.seen + keys,
            pages = base.pages + signature,
            warnings = warnings.distinct(),
        )
    }

    private suspend fun current(ticket: Long): Boolean = control.withLock { ticket == generation }

    private fun publish() {
        val active = flags(mutable.value.filter).map { flag -> flag to branches.getValue(flag) }
        fun problems(refresh: Boolean): String? = active.mapNotNull { (flag, branch) ->
            branch.problem?.takeIf { branch.refreshProblem == refresh }?.let { "isFinish=$flag：$it" }
        }.distinct().takeIf { it.isNotEmpty() }?.joinToString("；")
        mutable.value = mutable.value.copy(
            // A later branch may carry a newer raw finish field, but its position stays stable.
            items = merge(emptyList(), active.flatMap { it.second.items }),
            loading = false,
            end = active.all { (_, branch) -> branch.loaded && !branch.refreshPending && branch.end },
            error = problems(true),
            moreError = problems(false),
            warnings = active.flatMap { (flag, branch) -> branch.warnings.map { "isFinish=$flag：$it" } }.distinct(),
        )
    }

    private fun merge(before: List<RunTaskItem>, after: List<RunTaskItem>): List<RunTaskItem> {
        val result = linkedMapOf<String, RunTaskItem>()
        (before + after).forEach { result[it.identityKey] = it }
        return result.values.toList()
    }

    private fun flags(filter: RunTaskFilter): List<String> = when (filter) {
        RunTaskFilter.ALL -> listOf("0", "1")
        RunTaskFilter.UNFINISHED -> listOf("0")
        RunTaskFilter.FINISHED -> listOf("1")
    }
}
