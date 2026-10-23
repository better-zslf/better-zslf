package io.github.betterzslf.feature.booking

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.*

/** Route payloads contain identifiers/selections only, never a session or form/attachment bytes. */
data class BookingSelection(
    val siteId: String = "",
    val timeIds: List<String> = emptyList(),
    val physicalId: String = "",
    val itemIds: List<String> = emptyList(),
    val dateTime: String = "",
) {
    fun encode(): String = buildJsonObject {
        put("bookingVersion", 1)
        put("siteId", siteId)
        put("timeIds", JsonArray(timeIds.distinct().map(::JsonPrimitive)))
        put("physicalId", physicalId)
        put("itemIds", JsonArray(itemIds.distinct().map(::JsonPrimitive)))
        put("dateTime", dateTime)
    }.toString()

    companion object {
        /** A plain id is still supported for dispatcher/community links. Malformed JSON is not an id. */
        fun decode(value: String, physical: Boolean = false): BookingSelection {
            if (!value.trimStart().startsWith("{")) {
                return if (physical) BookingSelection(physicalId = value) else BookingSelection(siteId = value)
            }
            val obj = runCatching { Json.parseToJsonElement(value) as? JsonObject }.getOrNull()
                ?: return BookingSelection()
            if (obj.bookingText("bookingVersion") != "1") return BookingSelection()
            fun ids(name: String) = (obj[name] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            }?.distinct().orEmpty()
            return BookingSelection(obj.bookingText("siteId"), ids("timeIds"), obj.bookingText("physicalId"), ids("itemIds"), obj.bookingText("dateTime"))
        }
    }
}

fun JsonElement.bookingText(name: String): String =
    ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()

fun JsonElement.bookingId(): String = bookingText("id")

fun JsonElement.bookingLabel(fallback: String): String =
    listOf("venueName", "siteName", "physicalName", "testName", "itemName", "name", "title", "date")
        .firstNotNullOfOrNull { bookingText(it).takeIf(String::isNotBlank) } ?: fallback

/** Containers are read contracts, not permission to infer any request fields. */
fun bookingRows(data: JsonElement, container: String): List<JsonElement> = when (data) {
    is JsonArray -> data.toList()
    is JsonObject -> (data[container] as? JsonArray)?.toList().orEmpty()
    else -> emptyList()
}

/** Undocumented time responses are displayed as data; no guessed container is sent back to the API. */
fun bookingTimeRows(data: JsonElement): List<JsonElement> = when (data) {
    is JsonArray -> data.toList()
    is JsonObject -> (data["placeTimeList"] as? JsonArray)?.toList().orEmpty()
    else -> emptyList()
}

data class BookingField(val path: String, val value: String)

private fun isCredentialField(key: String): Boolean {
    val normalized = key.lowercase().replace("_", "").replace("-", "")
    return listOf("password", "passwd", "token", "authorization", "cookie", "secret").any { it in normalized }
}

/** Flatten for LazyColumn: a long server list is never emitted as one giant Column/Text. */
fun bookingFields(data: JsonElement, prefix: String = "", depth: Int = 0): List<BookingField> {
    if (depth > 10) return listOf(BookingField(prefix, "嵌套内容过深，暂不展开"))
    return when (data) {
        JsonNull -> emptyList()
        is JsonPrimitive -> data.contentOrNull?.takeIf(String::isNotBlank)?.let {
            val key = prefix.substringAfterLast('.').lowercase()
            val display = if (key in setOf("idcard", "studentid", "studentnum", "account", "phone", "uphone")) bookingMasked(it) else it
            listOf(BookingField(prefix.ifBlank { "内容" }, display))
        }.orEmpty()
        is JsonArray -> data.flatMapIndexed { index, item -> bookingFields(item, "$prefix[${index + 1}]", depth + 1) }
        is JsonObject -> data.entries.filterNot { isCredentialField(it.key) }.flatMap { (key, value) ->
            bookingFields(value, if (prefix.isEmpty()) key else "$prefix.$key", depth + 1)
        }
    }
}

/** Raw numeric states stay raw: docs/13 does not define a 0/1 availability mapping. */
enum class SlotAvailability(val label: String) { AVAILABLE("服务端标注可预约"), FULL("服务端标注已满/关闭"), UNKNOWN("可用性未确认") }

fun bookingAvailability(row: JsonElement): SlotAvailability {
    // Shipped reservationTime callsite explicitly disables isUse===1.
    if (row.bookingText("isUse") == "1") return SlotAvailability.FULL
    if (row.bookingText("isUse") == "0") return SlotAvailability.AVAILABLE
    val text = listOf("status", "statusName", "state", "text").map { row.bookingText(it).trim().lowercase() }
    return when {
        text.any { it in setOf("已满", "已约满", "约满", "关闭", "已关闭", "不可预约", "full", "closed", "unavailable") } -> SlotAvailability.FULL
        text.any { it in setOf("可预约", "空闲", "available") } -> SlotAvailability.AVAILABLE
        else -> SlotAvailability.UNKNOWN
    }
}

fun bookingRawStatus(row: JsonElement): String = listOf("status", "venueStatus", "state")
    .firstNotNullOfOrNull { row.bookingText(it).takeIf(String::isNotBlank) }.orEmpty()

/** Local paging is explicit: docs/13 proves full result sets, not a remote paging protocol. */
data class BookingWindow(val query: String = "", val status: String = "", val visible: Int = PAGE_SIZE) {
    fun filtered(rows: List<JsonElement>): List<JsonElement> {
        val needle = query.trim()
        return rows.filter { row ->
            (status.isEmpty() || bookingRawStatus(row) == status) &&
                (needle.isEmpty() || bookingFields(row).any { it.value.contains(needle, ignoreCase = true) })
        }
    }
    fun show(rows: List<JsonElement>): List<JsonElement> = filtered(rows).take(visible)
    fun search(value: String) = copy(query = value, visible = PAGE_SIZE)
    fun filter(value: String) = copy(status = value, visible = PAGE_SIZE)
    fun more(total: Int) = copy(visible = (visible + PAGE_SIZE).coerceAtMost(total.coerceAtLeast(PAGE_SIZE)))
    companion object { const val PAGE_SIZE = 30 }
}

data class BookingQueryState(
    val data: JsonElement? = null,
    val loading: Boolean = false,
    val error: ApiError? = null,
    val message: String = "",
)

/** Coalesces refresh taps, retains last successful data on failure and never retries by itself. */
class BookingQuery(private val loader: suspend () -> ApiResult<JsonElement>) {
    private val mutableState = MutableStateFlow(BookingQueryState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    suspend fun refresh() {
        if (!mutex.tryLock()) return
        val previous = mutableState.value
        mutableState.value = previous.copy(loading = true, error = null)
        try {
            when (val result = loader()) {
                is ApiResult.Success -> mutableState.value = BookingQueryState(result.data, message = result.message)
                is ApiResult.Failure -> mutableState.value = previous.copy(loading = false, error = result.error)
            }
        } catch (cancelled: CancellationException) {
            mutableState.value = previous.copy(loading = false)
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = previous.copy(loading = false, error = ApiError.Network())
        } finally { mutex.unlock() }
    }
}
