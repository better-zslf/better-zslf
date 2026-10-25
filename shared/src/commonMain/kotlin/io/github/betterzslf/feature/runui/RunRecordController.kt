package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun interface RunRecordPageSource {
    suspend fun load(ownerId: String, errandScheduleId: String, offset: Int, limit: Int): ApiResult<JsonElement>
}

data class RunRecordListState(
    val items: List<RunRecordItem> = emptyList(),
    val loading: Boolean = false,
    val end: Boolean = false,
    val error: String? = null,
    val moreError: String? = null,
    val warnings: List<String> = emptyList(),
)

/**
 * One immutable owner/schedule binding, with at most one page fetched per explicit action.
 * The source owns repository/session binding; this controller never invents an "all" schedule.
 * Offsets count raw transport rows, not displayed or unique records. A failed/cancelled refresh
 * retains the previous data and offset, but the next explicit action must retry offset zero.
 * Only the decoder's real server trid remains a query id; anonymous keys are private list keys.
 */
class RunRecordController(
    private val source: RunRecordPageSource,
    private val ownerId: String,
    private val errandScheduleId: String,
    private val pageSize: Int = 10,
) {
    init { require(pageSize > 0) { AppText.Common.PAGE_SIZE_MUST_BE_POSITIVE } }

    private data class PageState(
        val offset: Int = 0,
        val refreshPending: Boolean = true,
        val end: Boolean = false,
        val rows: Map<String, RunRecordItem> = emptyMap(),
        val warnings: List<String> = emptyList(),
        val problem: String? = null,
        val refreshProblem: Boolean = false,
    )

    private data class Request(val ticket: Long, val before: PageState) {
        val offset: Int get() = if (before.refreshPending) 0 else before.offset
    }

    private val mutable = MutableStateFlow(RunRecordListState())
    val state: StateFlow<RunRecordListState> = mutable.asStateFlow()
    private val control = Mutex()
    private var generation = 0L
    private var page = PageState()

    suspend fun refresh() { begin(refresh = true)?.let { execute(it) } }
    suspend fun more() { begin(refresh = false)?.let { execute(it) } }

    private suspend fun begin(refresh: Boolean): Request? = control.withLock {
        currentCoroutineContext().ensureActive()
        // Coalesce all taps while a request is active, without queuing a second action.
        if (mutable.value.loading) return@withLock null
        if (refresh) page = page.copy(refreshPending = true)
        if (ownerId.isBlank() || errandScheduleId.isBlank()) {
            mutable.value = mutable.value.copy(
                loading = false,
                end = false,
                error = if (ownerId.isBlank()) AppText.Auth.USER_ID_MISSING_LOG_IN_AND_REFRESH else AppText.Run.REAL_SCHEDULE_ID_MISSING_SELECT_AND_REFRESH,
                moreError = null,
            )
            return@withLock null
        }
        if (!page.refreshPending && page.end) return@withLock null
        mutable.value = mutable.value.copy(loading = true, end = false)
        Request(++generation, page)
    }

    private suspend fun execute(request: Request) {
        try {
            val updated = try {
                currentCoroutineContext().ensureActive()
                val result = source.load(ownerId, errandScheduleId, request.offset, pageSize)
                // A source may catch cancellation itself; such a response must not replace data.
                currentCoroutineContext().ensureActive()
                when (result) {
                    is ApiResult.Failure -> failed(request.before, result.error.message)
                    is ApiResult.Success -> consume(request.before, request.offset, RunDataDecoder.records(result.data))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never publish exception messages, URLs, raw responses or credentials.
                failed(request.before, AppText.Run.RUN_RECORD_LOAD_FAILED_RETRY)
            }
            currentCoroutineContext().ensureActive()
            control.withLock {
                currentCoroutineContext().ensureActive()
                if (request.ticket == generation) {
                    page = updated
                    publish()
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                control.withLock {
                    if (request.ticket == generation) mutable.value = mutable.value.copy(loading = false)
                }
            }
            throw cancelled
        }
    }

    private fun failed(before: PageState, problem: String, warnings: List<String> = emptyList()): PageState =
        before.copy(
            problem = problem.ifBlank { AppText.Run.RUN_RECORD_LOAD_FAILED_RETRY },
            refreshProblem = before.refreshPending,
            warnings = (before.warnings + warnings).distinct(),
        )

    private fun consume(before: PageState, offset: Int, rows: RunRows<RunRecordItem>): PageState {
        if (!rows.recognized) return failed(before, AppText.Run.UNRECOGNIZED_RECORD_RESPONSE_RETRY, rows.warnings)
        val base = if (before.refreshPending) PageState(refreshPending = false) else before
        val keyed = rows.items.map { item -> identity(item) to item }
        val noProgress = rows.rawSize > 0 && keyed.none { (key, _) -> key !in base.rows }
        val warnings = (base.warnings + rows.warnings).toMutableList()
        if (noProgress) warnings += AppText.Run.recordPaginationRepeated((offset))
        if (rows.rawSize > pageSize) warnings += AppText.Run.recordPaginationOversized((offset))
        val next = offset.toLong() + rows.rawSize.toLong()
        if (next > Int.MAX_VALUE) warnings += AppText.Run.PAGINATION_OFFSET_INTEGER_RANGE_EXCEEDED_STOPPED
        val merged = linkedMapOf<String, RunRecordItem>()
        merged.putAll(base.rows)
        // Latest duplicate contents win, but existing positions and real trid are preserved.
        keyed.forEach { (key, item) -> merged[key] = item }
        return PageState(
            offset = next.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            refreshPending = false,
            end = rows.rawSize < pageSize || noProgress || next > Int.MAX_VALUE,
            rows = merged.toMap(),
            warnings = warnings.distinct(),
        )
    }

    private fun publish() {
        mutable.value = RunRecordListState(
            items = page.rows.values.toList(),
            loading = false,
            end = !page.refreshPending && page.end,
            error = page.problem?.takeIf { page.refreshProblem },
            moreError = page.problem?.takeUnless { page.refreshProblem },
            warnings = page.warnings,
        )
    }

    private fun identity(item: RunRecordItem): String = item.trid?.let { "trid:$it" } ?: rawKey(item.raw)

    private sealed interface KeyPart {
        data class Value(val element: JsonElement) : KeyPart
        data class Field(val name: String) : KeyPart
    }

    /**
     * Retain only two fixed-size hashes, never an unbounded raw JSON string or truncated prefix.
     * All scalar characters participate; object field order is canonical and array order matters.
     * Iterative traversal also handles deeply nested raw extras without recursive hashing.
     * This local fingerprint is deliberately never assigned to RunRecordItem.trid or sent to a source.
     */
    private fun rawKey(raw: JsonObject): String {
        var first = -3750763034362895579L
        var second = -7046029254386353131L
        fun feed(character: Char) {
            first = (first xor character.code.toLong()) * 1099511628211L
            second = (second xor character.code.toLong()) * 6364136223846793005L + 1442695040888963407L
        }
        fun token(text: String) {
            text.length.toString().forEach(::feed)
            feed(':')
            text.forEach(::feed)
        }
        val pending = ArrayDeque<KeyPart>()
        pending.addLast(KeyPart.Value(raw))
        while (pending.isNotEmpty()) {
            when (val part = pending.removeLast()) {
                is KeyPart.Field -> { token("key"); token(part.name) }
                is KeyPart.Value -> when (val value = part.element) {
                    is JsonObject -> {
                        token("object"); token(value.size.toString())
                        value.keys.sortedDescending().forEach { key ->
                            pending.addLast(KeyPart.Value(value.getValue(key)))
                            pending.addLast(KeyPart.Field(key))
                        }
                    }
                    is JsonArray -> {
                        token("array"); token(value.size.toString())
                        for (index in value.lastIndex downTo 0) pending.addLast(KeyPart.Value(value[index]))
                    }
                    is JsonPrimitive -> { token(if (value.isString) "string" else "literal"); token(value.content) }
                }
            }
        }
        return "raw:${first.toULong().toString(16).padStart(16, '0')}:${second.toULong().toString(16).padStart(16, '0')}"
    }
}
