package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiConfig
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.ApiLoginOutcome
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.github.betterzslf.feature.campusnet.CampusOutcome
import io.github.betterzslf.feature.campusnet.RepositoryCampusNetworkSource
import io.github.betterzslf.feature.runui.ApiRunReadSource
import io.github.betterzslf.feature.runui.RunReadOutcome
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Opt-in integration test. No secrets/production responses are written to files or assertions.
 * Disabled by default. Supply ZSLF_LIVE_TEST=1, ZSLF_USERNAME, and ZSLF_PASSWORD externally.
 * Login is the sole credential-bearing call; every subsequent call is read-only.
 */
class LiveReadOnlyTest {
    @Test
    fun readOnlyProductionContract() = runBlocking {
        val enabled = System.getenv("ZSLF_LIVE_TEST") == "1"
        val username = System.getenv("ZSLF_USERNAME")
        val password = System.getenv("ZSLF_PASSWORD")
        if (!enabled || username.isNullOrBlank() || password.isNullOrBlank()) {
            println("LIVE_READ_ONLY_SKIPPED: no live requests; opt-in flag and credential environment required")
            return@runBlocking
        }
        val sessions = SessionStore(MemoryKeyValueStore())
        val http = HttpClient(CIO)
        val api = ApiClient(http, sessions, ApiConfig(platform = "android", appVersion = "host-live-readonly"))
        val repos = AppRepositories(api, sessions)
        try {
            // Login is the sole credential-bearing call; it now drives the typed core LOGIN
            // protocol directly (AuthRepository no longer owns a login path). Test-only seam.
            val attempt = api.captureLoginAttempt()
            assertTrue(api.claimLoginAttempt(attempt))
            when (val login = api.executeLogin(attempt, username, password)) {
                is ApiLoginOutcome.Accepted -> println("${ApiEndpoint.LOGIN.path} code=200 count=1")
                is ApiLoginOutcome.Rejected -> {
                    println("${ApiEndpoint.LOGIN.path} code=${login.issue} count=0")
                    assertTrue(false, "${ApiEndpoint.LOGIN.path} code=${login.issue}")
                    return@runBlocking
                }
                is ApiLoginOutcome.Unknown -> {
                    println("${ApiEndpoint.LOGIN.path} code=${login.issue} count=0")
                    assertTrue(false, "${ApiEndpoint.LOGIN.path} code=${login.issue}")
                    return@runBlocking
                }
            }
            val userId = sessions.session.value?.userId
            assertTrue(!userId.isNullOrBlank(), "${ApiEndpoint.LOGIN.path} code=MISSING_USER_ID")

            report(ApiEndpoint.USER_INFO, repos.profile.userInfo())
            report(ApiEndpoint.USER_PHOTO, repos.profile.photo())
            report(ApiEndpoint.USER_STATISTICS, repos.profile.statistics())

            when (val tasks = assertNotNull(ApiRunReadSource.capture(api)).tasks(userId, offset = 0, limit = 5, isFinish = "0")) {
                is RunReadOutcome.Success -> println("${ApiEndpoint.RUN_TASKS.path} code=200 count=${tasks.data.rawSize}")
                is RunReadOutcome.Failure -> {
                    println("${ApiEndpoint.RUN_TASKS.path} code=${tasks.issue} count=0")
                    assertTrue(false, "${ApiEndpoint.RUN_TASKS.path} code=${tasks.issue}")
                }
            }

            // Typed factory keeps the raw campus wire methods private to their data module.
            when (val accounts = assertNotNull(RepositoryCampusNetworkSource.capture(repos.campusNetwork)).accounts()) {
                is CampusOutcome.Success -> println("${ApiEndpoint.CAMPUS_ACCOUNTS.path} code=200 count=${accounts.data.size}")
                is CampusOutcome.Failure -> {
                    println("${ApiEndpoint.CAMPUS_ACCOUNTS.path} code=${accounts.issue} count=0")
                    assertTrue(false, "${ApiEndpoint.CAMPUS_ACCOUNTS.path} code=${accounts.issue}")
                }
            }
            report(ApiEndpoint.CHECK_VERSION, repos.updates.check())
        } finally {
            sessions.clear()
            api.close()
            http.close()
        }
    }

    private fun report(
        endpoint: ApiEndpoint,
        result: ApiResult<JsonElement>,
        acceptedBackendCodes: Set<Int> = emptySet(),
    ): JsonElement? = when (result) {
        is ApiResult.Success -> {
            println("${endpoint.path} code=200 count=${count(result.data)}")
            result.data
        }
        is ApiResult.Failure -> {
            val code = errorCode(result.error)
            println("${endpoint.path} code=$code count=0")
            val numeric = when (val error = result.error) {
                is ApiError.Business -> error.code
                is ApiError.Http -> error.status
                else -> null
            }
            assertTrue(numeric in acceptedBackendCodes, "${endpoint.path} code=$code")
            null
        }
    }

    private fun count(data: JsonElement): Int = when (data) {
        JsonNull -> 0
        is JsonArray -> data.size
        is JsonObject -> data.values.filterIsInstance<JsonArray>().sumOf { it.size }
        is JsonPrimitive -> if (data.contentOrNull.isNullOrEmpty()) 0 else 1
    }

    private fun errorCode(error: ApiError): String = when (error) {
        is ApiError.Business -> "BUSINESS_${error.code}"
        is ApiError.Http -> "HTTP_${error.status}"
        is ApiError.Authentication -> "HTTP_401"
        is ApiError.Network -> "NETWORK"
        is ApiError.Timeout -> "TIMEOUT"
        is ApiError.Decode -> "DECODE"
        is ApiError.Validation -> "VALIDATION"
        is ApiError.Storage -> "STORAGE"
    }

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull
}
