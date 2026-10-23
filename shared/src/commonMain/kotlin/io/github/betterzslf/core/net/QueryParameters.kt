package io.github.betterzslf.core.net

import io.ktor.http.ParametersBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Null values are omitted; arrays become repeated key[]=value, objects remain JSON. */
internal fun ParametersBuilder.appendQuery(query: JsonObject) {
    for ((name, value) in query) {
        if (value is JsonArray) {
            val arrayName = if (name.endsWith("[]")) name else "$name[]"
            value.filter { it != JsonNull }.forEach { append(arrayName, it.queryValue()) }
        } else if (value != JsonNull) {
            append(name, value.queryValue())
        }
    }
}

private fun JsonElement.queryValue(): String = when (this) {
    is JsonPrimitive -> content
    else -> toString()
}

fun queryParameters(vararg values: Pair<String, String?>): JsonObject = buildJsonObject {
    values.forEach { (key, value) -> if (value != null) put(key, value) }
}

internal fun JsonElement?.isMissingParameter(): Boolean = when (this) {
    null, JsonNull -> true
    is JsonPrimitive -> content.isBlank()
    is JsonArray -> isEmpty()
    is JsonObject -> isEmpty()
}
