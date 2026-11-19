package io.github.betterzslf.feature.campusnet

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** Wire decoding belongs only in data. Unknown schema is distinct from a known empty account list. */
internal fun parseCampusAccounts(data: JsonElement): CampusOutcome<List<CampusAccountRecord>> {
    val rows = (data as? JsonObject)?.get("operatorAccount") as? JsonArray
        ?: return CampusOutcome.Failure(CampusIssue.MALFORMED)
    if (rows.any { it !is JsonObject }) return CampusOutcome.Failure(CampusIssue.MALFORMED)
    fun JsonObject.value(key: String): String = (get(key) as? JsonPrimitive)?.let {
        if (it.isString || it.longOrNull != null) it.contentOrNull.orEmpty() else ""
    }.orEmpty()
    return CampusOutcome.Success(rows.map { row ->
        row as JsonObject
        CampusAccountRecord(row.value("id"), row.value("operatorAccount"), row.value("typeStatus").take(32))
    })
}

/** Both numeric and string 0/1 are accepted by the existing implementation. */
internal fun parseCampusOnlineStatus(data: JsonElement): CampusOnlineStatus {
    val row = data as? JsonObject ?: return CampusOnlineStatus.Unreadable("")
    val raw = (row["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    return when (raw) {
        "1" -> CampusOnlineStatus.Online
        "0" -> CampusOnlineStatus.Offline
        else -> CampusOnlineStatus.Unreadable(raw.take(32))
    }
}

internal fun parseCampusRedirectTarget(data: JsonElement): CampusRedirectTarget =
    CampusRedirectTarget(((data as? JsonObject)?.get("addr") as? JsonPrimitive)?.contentOrNull.orEmpty())

internal fun parseCampusDialResult(data: JsonElement): CampusDialResult {
    val row = data as? JsonObject ?: return CampusDialResult.Rejected(CampusDialRejection.Other, "")
    val status = (row["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
    if (status == "1") return CampusDialResult.Accepted
    val message = (row["msg"] as? JsonPrimitive)?.contentOrNull
        ?: (row["message"] as? JsonPrimitive)?.contentOrNull
        ?: ""
    return CampusDialResult.Rejected(classifyCampusDialRejection(message), message.take(120))
}

/** Preserve the dorm route's deliberately more permissive lookup; do not reuse the strict parser. */
internal fun parseCampusProvisionedPhone(data: JsonElement): String? {
    val rows = (data as? JsonObject)?.get("operatorAccount") as? JsonArray
    return rows?.firstNotNullOfOrNull { row ->
        ((row as? JsonObject)?.get("operatorAccount") as? JsonPrimitive)
            ?.let { if (it.isString || it.content.isNotEmpty()) it.content else null }
            ?.let(::campusProvisionedPhonePrefix)
    }
}
