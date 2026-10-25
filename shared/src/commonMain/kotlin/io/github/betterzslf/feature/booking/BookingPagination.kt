package io.github.betterzslf.feature.booking

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.FitnessRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

data class BookingPagedState(
    val query: BookingQueryState = BookingQueryState(),
    val offset: Int = 0,
    val end: Boolean = false,
    val moreError: String? = null,
)

/** Offset/limit=10 are observed in the original my-applications and fitness-list callsites. */
class BookingPagedQuery(private val container: String, private val loader: suspend (Int, Int) -> ApiResult<JsonElement>) {
    private val mutableState = MutableStateFlow(BookingPagedState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    suspend fun refresh() = load(reset = true)
    suspend fun more() = load(reset = false)

    private suspend fun load(reset: Boolean) {
        if (!mutex.tryLock()) return
        val old = mutableState.value
        try {
            if (!reset && (old.end || old.query.data == null)) return
            mutableState.value = old.copy(query = old.query.copy(loading = true, error = null), moreError = null)
            val offset = if (reset) 0 else old.offset
            when (val result = loader(offset, 10)) {
                is ApiResult.Failure -> mutableState.value = old.copy(query = old.query.copy(loading = false,
                    error = if (reset) result.error else null), moreError = if (reset) null else result.error.message)
                is ApiResult.Success -> {
                    val next = bookingRows(result.data, container)
                    val previous = if (reset) emptyList() else old.query.data?.let { bookingRows(it, container) }.orEmpty()
                    val existing = previous.map { it.bookingId() }.filter(String::isNotBlank).toSet()
                    val additions = next.filter { it.bookingId().isBlank() || it.bookingId() !in existing }
                    // A backend that ignores offset must not create an endless duplicate list.
                    val stopped = next.size < 10 || !reset && next.isNotEmpty() && additions.isEmpty()
                    mutableState.value = BookingPagedState(
                        BookingQueryState(JsonObject(mapOf(container to JsonArray(previous + additions))), message = result.message),
                        offset + next.size, stopped,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = old.copy(query = old.query.copy(loading = false))
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = old.copy(query = old.query.copy(loading = false, error = ApiError.Network()))
        } finally { mutex.unlock() }
    }
}

/** Resolve a route id across real pages; never silently limit eligibility to the first 100 records. */
suspend fun FitnessRepository.findBookingTest(userId: String, physicalId: String): ApiResult<JsonElement> {
    if (userId.isBlank() || physicalId.isBlank()) return ApiResult.Failure(ApiError.Validation(listOf("physicalId"), AppText.Booking.FITNESS_SESSION_OR_USER_ID_MISSING))
    var offset = 0
    val seen = mutableSetOf<String>()
    while (true) {
        when (val result = tests(queryParameters("userId" to userId, "offset" to offset.toString(), "limit" to "10"))) {
            is ApiResult.Failure -> return result
            is ApiResult.Success -> {
                val rows = bookingRows(result.data, "physicalTestList")
                rows.firstOrNull { it.bookingId() == physicalId }?.let { return ApiResult.Success(it, result.message) }
                val newIds = rows.map { it.bookingId() }.filter(String::isNotBlank).filter { seen.add(it) }
                if (rows.size < 10 || newIds.isEmpty()) return ApiResult.Failure(ApiError.Validation(listOf("physicalId"), AppText.Booking.CURRENT_SESSION_MISSING_FROM_LATEST_LIST_RESELECT))
                offset += rows.size
            }
        }
    }
}
