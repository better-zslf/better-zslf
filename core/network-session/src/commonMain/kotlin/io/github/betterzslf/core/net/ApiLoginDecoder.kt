package io.github.betterzslf.core.net

import io.github.betterzslf.core.model.Session
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The single owner of the LOGIN response decode, moved here from the former shared
 * AuthRepository.login. The transport contract — POST with query parameters, anonymous, no
 * Authorization header, the default 8s request timeout and no redirects — comes from the shared
 * pipeline and the LOGIN endpoint declaration; only the payload rules below live in this owner.
 * No other endpoint is decoded here and nothing is persisted here.
 */
internal object ApiLoginDecoder {
    /**
     * Original rule set, unchanged: a usable session needs a non-blank accessToken plus userInfo
     * and deptInfo objects; mapToken/mapWebType are optional passthrough. A null return is a
     * malformed login response (ApiLoginIssue.MALFORMED), never a stored or half-stored session.
     */
    internal fun decodeSession(data: JsonElement?): Session? {
        val objectData = data as? JsonObject
        val token = (objectData?.get("accessToken") as? JsonPrimitive)?.contentOrNull
        val userInfo = objectData?.get("userInfo") as? JsonObject
        val deptInfo = objectData?.get("deptInfo") as? JsonObject
        if (token.isNullOrBlank() || userInfo == null || deptInfo == null) return null
        return Session(
            token = token,
            userInfo = userInfo,
            deptInfo = deptInfo,
            mapToken = (objectData["mapToken"] as? JsonPrimitive)?.contentOrNull,
            mapWebType = (objectData["mapWebType"] as? JsonPrimitive)?.contentOrNull,
        )
    }
}
