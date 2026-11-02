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
