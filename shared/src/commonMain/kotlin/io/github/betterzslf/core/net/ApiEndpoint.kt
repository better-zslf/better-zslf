package io.github.betterzslf.core.net

import io.ktor.http.HttpMethod

enum class ParameterLocation { QUERY, JSON, MULTIPART }
enum class Authentication { REQUIRED, OPTIONAL, NONE }

/**
 * The v1 base scope has 55 paths (docs/18), plus verified site/addApplyFile (56 total).
 * Paths are canonical server paths, including /app or /run exactly once.
 * Auth flags follow docs/13, not the inaccurate static requires_auth values.
 */
enum class ApiEndpoint(
    val path: String,
    val method: HttpMethod = HttpMethod.Post,
    val location: ParameterLocation = ParameterLocation.QUERY,
    val authentication: Authentication = Authentication.REQUIRED,
    val required: List<String> = emptyList(),
    val timeoutMillis: Long? = null,
    val acceptsEmptyResponse: Boolean = false,
    val requiresParameters: Boolean = false,
) {
    LOGIN("/app/login", authentication = Authentication.NONE, required = listOf("username", "password")),
    CHECK_PHONE("/app/appUser/checkPhone", required = listOf("phone", "smsCode")),
    RESET_PASSWORD("/app/appUser/resetNewPassWord", required = listOf("userId", "newPassWord")),
    MODIFY_PASSWORD("/app/appUser/modifyPassword", required = listOf("oldPassword", "newPassWord")),
    USER_INFO("/app/appUser/getUserInfo", method = HttpMethod.Get),
    USER_PHOTO("/app/appUser/getUserPhotoURL"),
    USER_STATISTICS("/app/appUser/getUserArctileStatis"),

    DOCUMENT("/app/appCmsArticle/articleDetailsByName", authentication = Authentication.OPTIONAL, required = listOf("articleName")),

    RUN_TASKS("/run/errand/getErrandList", required = listOf("userId", "offset", "limit", "isFinish")),
    RUN_DETAIL("/run/errand/getErrandDetail", required = listOf("errandId", "userId", "errandScheduleId")),
    RUN_RECORDS("/run/errand/getUserErrandTrackRecord", required = listOf("userId")),
    RUN_TRACK("/run/errand/getTrackByTrid", required = listOf("trid")),
    RUN_RECORD("/run/errand/getTrackRecordByTrid", required = listOf("trid")),
    RUN_PUNCH("/run/errand/punch", required = listOf("longitude1", "latitude1", "longitude2", "latitude2")),
    RUN_ADD_TRACK("/run/errand/addErrandTrackByData", location = ParameterLocation.JSON, required = listOf("trid"), timeoutMillis = 30_000),
    RUN_SYNC("/run/errand/syncSportData", location = ParameterLocation.JSON, required = listOf("trid"), timeoutMillis = 30_000),
    RUN_REPORT("/run/errand/reportLocation", location = ParameterLocation.JSON, required = listOf("trid"), timeoutMillis = 30_000),
    RUN_QUICK_SAVE("/run/errand/quickSave", location = ParameterLocation.JSON, required = listOf("trid"), timeoutMillis = 15_000),

    CAMPUS_CHECK_PHONE("/app/campusNetwork/checkPhone", required = listOf("phone")),
    CAMPUS_ACCOUNTS("/app/campusNetwork/getOperatorAccountByIDCard"),
    CAMPUS_UPDATE_PASSWORD("/app/campusNetwork/updateOperatorPasswordById", required = listOf("operatorAccountId", "newOperatorPassword")),
    CHECK_VERSION("/app/checkVersion", method = HttpMethod.Get, authentication = Authentication.NONE),
    UPLOAD("/app/appCommomUpload/uploadIdCardImg", location = ParameterLocation.MULTIPART),
}
