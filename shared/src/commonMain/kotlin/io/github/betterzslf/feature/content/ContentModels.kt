package io.github.betterzslf.feature.content

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.serialization.json.*

internal fun JsonElement.text(name: String): String = ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonElement.number(name: String): Int? = ((this as? JsonObject)?.get(name) as? JsonPrimitive)?.intOrNull
internal fun JsonElement.flag(name: String): Boolean? = when (((this as? JsonObject)?.get(name) as? JsonPrimitive)?.contentOrNull) {
    "true", "1" -> true
    "false", "0" -> false
    else -> null
}

/** Only observed content fields are decoded; unknown metadata is never turned into navigation. */
data class ContentItem(
    val id: String,
    val title: String,
    val description: String = "",
    val html: String = "",
    val created: String = "",
    val isRead: Boolean? = null,
) {
    companion object {
        fun article(raw: JsonObject) = ContentItem(raw.text("id"), raw.text("title"), raw.text("description"), raw.text("content"), raw.text("creatTime"))
        fun notice(raw: JsonObject) = ContentItem(raw.text("id"), raw.text("title"), html = raw.text("content"), created = raw.text("createtime"), isRead = raw.flag("isRead"))
    }
}

enum class ContentFeedKind { ARTICLES, HISTORY, SYSTEM_MESSAGES, ANNOUNCEMENTS }
data class ContentFeedRequest(val kind: ContentFeedKind, val id: String = "")
data class ContentListState(
    val items: List<ContentItem> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: ApiError? = null,
    val rawOffset: Int = 0,
    val total: Int? = null,
)

internal fun parseContentList(data: JsonElement, field: String, notices: Boolean): ApiResult<List<ContentItem>> {
    val rows = (data as? JsonObject)?.get(field) as? JsonArray
        ?: return ApiResult.Failure(ApiError.Decode(AppText.Content.missingListField((field))))
    if (rows.any { it !is JsonObject }) return ApiResult.Failure(ApiError.Decode(AppText.Content.LIST_CONTAINS_INVALID_ITEMS))
    return ApiResult.Success(rows.map { if (notices) ContentItem.notice(it as JsonObject) else ContentItem.article(it as JsonObject) })
}

/** Traverses container structure, not invented children/url fields. Paths are stable within a response. */
data class ContentTreeNode(val key: String, val label: String, val id: String, val depth: Int, val parent: String?, val details: List<Pair<String, String>>)
data class ContentTree(val nodes: List<ContentTreeNode>) {
    fun visible(expanded: Set<String>): List<ContentTreeNode> {
        val visibility = mutableMapOf<String, Boolean>()
        return nodes.filter { node ->
            val visible = node.parent == null || (visibility[node.parent] == true && node.parent in expanded)
            visibility[node.key] = visible
            visible
        }
    }
    fun hasChildren(key: String): Boolean = nodes.any { it.parent == key }
}

fun parseContentTree(data: JsonElement, columns: Boolean = false): ApiResult<ContentTree> {
    val obj = data as? JsonObject ?: return ApiResult.Failure(ApiError.Decode())
    val roots: JsonArray = if (columns) {
        when (val single = obj["cmsColumn"]) {
            is JsonObject -> JsonArray(listOf(single))
            else -> obj["cmsColumnList"] as? JsonArray ?: return ApiResult.Failure(ApiError.Decode(AppText.Content.INVALID_COLUMN_STRUCTURE))
        }
    } else obj["findList"] as? JsonArray ?: return ApiResult.Failure(ApiError.Decode(AppText.Content.INVALID_DISCOVERY_MENU_STRUCTURE))
    data class Pending(val value: JsonObject, val key: String, val parent: String?, val depth: Int)
    val stack = ArrayDeque<Pending>()
    roots.withIndex().reversed().forEach { (index, value) ->
        if (value !is JsonObject) return ApiResult.Failure(ApiError.Decode())
        stack.addLast(Pending(value, "root/$index", null, 0))
    }
    val nodes = mutableListOf<ContentTreeNode>()
    while (stack.isNotEmpty()) {
        val current = stack.removeLast()
        if (current.depth > 32 || nodes.size >= 20_000) return ApiResult.Failure(ApiError.Decode(AppText.Content.MENU_DEPTH_OR_COUNT_DISPLAY_LIMIT_EXCEEDED))
        val scalars = current.value.entries.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
        val label = current.value.text("name").ifBlank { current.value.text("title") }.ifBlank { AppText.Content.UNNAMED_ITEM }
        nodes += ContentTreeNode(current.key, label, current.value.text("id"), current.depth, current.parent, scalars)
        // Generic structural recursion accommodates server-owned array names without inventing them.
        val children = mutableListOf<Pair<String, JsonObject>>()
        fun collect(value: JsonElement, path: String, nesting: Int = 0) {
            if (nesting > 32) return
            when (value) {
                is JsonArray -> value.forEachIndexed { index, child ->
                    if (child is JsonObject) children += "$path/$index" to child
                    else if (child is JsonArray) collect(child, "$path/$index", nesting + 1)
                }
                is JsonObject -> value.forEach { (key, child) -> collect(child, "$path/$key", nesting + 1) }
                else -> Unit
            }
        }
        current.value.forEach { (key, value) -> if (value is JsonArray || value is JsonObject) collect(value, "${current.key}/$key") }
        children.asReversed().forEach { (key, value) -> stack.addLast(Pending(value, key, current.key, current.depth + 1)) }
    }
    return ApiResult.Success(ContentTree(nodes))
}

/** Exact, product-owned routes; arbitrary server labels/URLs never execute a native action. */
fun contentDestination(label: String): Pair<String, String>? = when (label) {
    "校园跑", "校园运动" -> "runs" to ""
    "校园体测", "体测报名" -> "fitness" to ""
    "场馆预约" -> "venues" to ""
    "校园网", "宽带更正" -> "campusnet" to ""
    "学校动态", "新生攻略" -> "columns" to label
    "用户协议", "隐私政策", "使用帮助" -> "document" to label
    else -> null
}
