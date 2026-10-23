package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.queryParameters
import io.github.betterzslf.core.net.UploadFile
import kotlinx.serialization.json.JsonObject

class VenueRepository(private val api: ApiClient) {
    suspend fun list(venueStatus: String = "1") = api.execute(ApiEndpoint.SITE_LIST, queryParameters("venueStatus" to venueStatus))

    /** Live docs name siteId; the original declaration names sId. Keep both aliases equal. */
    suspend fun info(siteId: String) = api.execute(ApiEndpoint.SITE_INFO, queryParameters("siteId" to siteId, "sId" to siteId))

    /** Compatibility overload; callers must also supply the confirmed dateTime query field. */
    suspend fun times(siteId: String, parameters: JsonObject = JsonObject(emptyMap())) =
        api.execute(ApiEndpoint.SITE_TIMES, JsonObject(queryParameters("venueId" to siteId, "applyId" to "") + parameters + queryParameters("siteId" to siteId)))

    /** Shipped form sends venueId/dateTime/applyId; retain the previously observed siteId alias. */
    suspend fun times(venueId: String, dateTime: String, applyId: String = "", siteId: String = venueId) =
        api.execute(ApiEndpoint.SITE_TIMES, queryParameters("venueId" to venueId, "dateTime" to dateTime, "applyId" to applyId, "siteId" to siteId))

    /** Transport completion is not proof of linkage; reload application().fileList afterwards. */
    suspend fun uploadAttachment(file: UploadFile, applyId: String, userId: String) = api.uploadVenueAttachment(file, applyId, userId)

    /** Form field spellings (including uerName) are server-defined and preserved verbatim. */
    suspend fun apply(parameters: JsonObject) = api.execute(ApiEndpoint.SITE_ADD_APPLY, parameters)

    suspend fun relate(venueId: String, applyId: String) = api.execute(ApiEndpoint.SITE_ADD_RELATED,
        queryParameters("venueId" to venueId, "applyId" to applyId))

    /** Validate both IDs locally: the server throws HTTP 500 for empty parameters. */
    suspend fun selectTime(venueTimeId: String, applyId: String) = api.execute(ApiEndpoint.SITE_ADD_TIME,
        queryParameters("venueTimeId" to venueTimeId, "applyId" to applyId))

    suspend fun define(userId: String, applyId: String, teacherId: String? = null, teacherName: String? = null) =
        api.execute(ApiEndpoint.SITE_ADD_DEFINITION, queryParameters(
            "userId" to userId, "applyId" to applyId, "teacherId" to teacherId, "teacherName" to teacherName,
        ))

    suspend fun applications(userId: String, parameters: JsonObject = JsonObject(emptyMap())) =
        api.execute(ApiEndpoint.SITE_APPLIES, JsonObject(parameters + queryParameters("userId" to userId)))

    suspend fun application(applyId: String) = api.execute(ApiEndpoint.SITE_APPLY_DETAIL, queryParameters("applyId" to applyId))

    /** Shipped intProcess reads the approval timeline; it is not a cancellation action. */
    suspend fun process(id: String) = api.execute(ApiEndpoint.SITE_PROCESS, queryParameters("id" to id))
    suspend fun afterUpload(applyId: String) = api.execute(ApiEndpoint.SITE_AFTER_UPLOAD, queryParameters("applyId" to applyId))
}
