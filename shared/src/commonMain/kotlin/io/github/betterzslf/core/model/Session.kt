package io.github.betterzslf.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Only the login response is persisted; username/password are never stored. */
@Serializable
data class Session(
    val token: String,
    val userInfo: JsonObject,
    val deptInfo: JsonObject,
    val mapToken: String? = null,
    val mapWebType: String? = null,
) {
    val userId: String?
        get() = (userInfo["id"] as? JsonPrimitive)?.contentOrNull
            ?: (deptInfo["id"] as? JsonPrimitive)?.contentOrNull

    // Prevent accidental credential/PII disclosure in debug prints or assertion messages.
    override fun toString(): String = "Session(authenticated=${token.isNotBlank()})"
}
