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

    CMS_COLUMNS("/app/appCmsColumn/getCmsByModule", required = listOf("name")),
    CMS_ARTICLES("/app/appCmsArticle/getCmsArticleByColumnId", required = listOf("cmsCloumnId", "offset", "limit")),
    ARTICLE_DETAIL("/app/appCmsArticle/articleDetailsById", required = listOf("articleId")),
    ARTICLE_HITS("/app/appCmsArticle/increaseHits", required = listOf("articleId"), acceptsEmptyResponse = true),
    ARTICLE_LIKE_COUNT("/app/appCmsArticleLike/getCountLikeByAtricleId", required = listOf("articleId")),
    ARTICLE_MY_LIKE("/app/appCmsArticleLike/getCountByUid", required = listOf("articleId")),
    ARTICLE_TOGGLE_LIKE("/app/appCmsArticleLike/setArticleLike", required = listOf("articleId")),
    ARTICLE_HISTORY("/app/appCmsArticle/articleHistory"),
    DOCUMENT("/app/appCmsArticle/articleDetailsByName", authentication = Authentication.OPTIONAL, required = listOf("articleName")),

    UNREAD("/app/appNotice/unreadNumber"),
    ANNOUNCEMENTS("/app/announcement/announcementList", authentication = Authentication.OPTIONAL),
    NOTICES("/app/appNotice/noticeListByType", required = listOf("typeId", "offset", "limit")),
    NOTICE_READ("/app/appNotice/updateNoticeIsRead", required = listOf("noticeId")),
    NOTICE_MUTUAL_READ("/app/appNotice/updateNoticeMutuaById", required = listOf("noticeId")),
    DISCOVER("/app/appFind/getAppFindById"),
    DICTIONARY("/app/appFind/getDictTips", required = listOf("code", "num")),

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

    SITE_LIST("/run/site/getSiteList", required = listOf("venueStatus")),
    SITE_INFO("/run/site/getSiteInfo", required = listOf("siteId")),
    SITE_TIMES("/run/site/getSiteTimeList", required = listOf("venueId", "dateTime")),
    SITE_UPLOAD("/run/site/addApplyFile", location = ParameterLocation.MULTIPART, acceptsEmptyResponse = true),
    SITE_ADD_APPLY("/run/site/addApply", required = listOf("userId")),
    SITE_ADD_RELATED("/run/site/addSiteApplyRelated", required = listOf("venueId", "applyId")),
    SITE_ADD_TIME("/run/site/addApplySelectTime", required = listOf("venueTimeId", "applyId")),
    SITE_ADD_DEFINITION("/run/site/addApplyDenfition", required = listOf("userId", "applyId")),
    SITE_APPLIES("/run/site/getSiteApplyList", required = listOf("userId")),
    SITE_APPLY_DETAIL("/run/site/getApplyDetail", required = listOf("applyId")),
    SITE_PROCESS("/run/site/intProcess", required = listOf("id")),
    SITE_AFTER_UPLOAD("/run/site/afterUploadFile", required = listOf("applyId")),

    PHYSICAL_TESTS("/run/physical/getPhysicalTestList"),
    PHYSICAL_ITEMS("/run/physical/getPhysicalRelatedItemsList"),
    PHYSICAL_COUNTS("/run/physical/getPhysicalUserCount"),
    PHYSICAL_MY_INFO("/run/physical/getPhysicalMaInfo"),
    PHYSICAL_ADD("/run/physical/addPhysicalUser", requiresParameters = true),
    PHYSICAL_CANCEL("/run/physical/cancelPhysical", requiresParameters = true),

    CAMPUS_CHECK_PHONE("/app/campusNetwork/checkPhone", required = listOf("phone")),
    CAMPUS_ACCOUNTS("/app/campusNetwork/getOperatorAccountByIDCard"),
    CAMPUS_UPDATE_PASSWORD("/app/campusNetwork/updateOperatorPasswordById", required = listOf("operatorAccountId", "newOperatorPassword")),
    CHECK_VERSION("/app/checkVersion", method = HttpMethod.Get, authentication = Authentication.NONE),
    UPLOAD("/app/appCommomUpload/uploadIdCardImg", location = ParameterLocation.MULTIPART),
}
