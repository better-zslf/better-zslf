package io.github.betterzslf.feature.auth

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiConfig
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine

/** Data-only fixture: no AuthRepository, AppRepositories, graph, UI or presentation dependency. */
internal class CampusLoginApiFixture(engine: MockEngine) {
    val storage = MemoryKeyValueStore()
    val sessions = SessionStore(storage)
    private val http = HttpClient(engine)
    val api = ApiClient(http, sessions, ApiConfig(
        appBaseUrl = "https://app.fixture.invalid/app",
        runBaseUrl = "https://run.fixture.invalid",
        filesBaseUrl = "https://files.fixture.invalid/userfiles/",
    ))
    fun source(): CampusLoginSource = ApiCampusLoginSource.capture(api)
    fun close() { api.close(); http.close() }
}

internal const val CAMPUS_LOGIN_SUCCESS_BODY =
    """{"code":200,"data":{"accessToken":"campus-fixture-token","userInfo":{"id":"campus-fixture-owner"},"deptInfo":{"schoolName":"fixture-school"},"mapToken":"campus-fixture-map","mapWebType":"1"},"message":"ok"}"""
