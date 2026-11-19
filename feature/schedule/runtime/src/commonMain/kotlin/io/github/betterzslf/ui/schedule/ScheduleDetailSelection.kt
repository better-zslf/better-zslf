package io.github.betterzslf.ui.schedule

import kotlinx.serialization.json.*

/** Navigation contains only local selection identifiers, never credentials or whole course payloads. */
public data class ScheduleDetailSelection(val termId: String, val courseIds: List<String>, val week: Int) {
    fun encode(): String = buildJsonObject {
        put("term", termId)
        put("courses", JsonArray(courseIds.map(::JsonPrimitive)))
        put("week", week)
    }.toString()
    companion object {
        fun decode(value: String): ScheduleDetailSelection? {
            return runCatching {
                val objectValue = Json.parseToJsonElement(value) as? JsonObject ?: return null
                val term = (objectValue["term"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
                val week = (objectValue["week"] as? JsonPrimitive)?.intOrNull ?: return null
                val ids = (objectValue["courses"] as? JsonArray)?.mapNotNull {
                    (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
                }?.distinct()?.takeIf { it.isNotEmpty() } ?: return null
                ScheduleDetailSelection(term, ids, week)
            }.getOrNull()
        }
    }
}
