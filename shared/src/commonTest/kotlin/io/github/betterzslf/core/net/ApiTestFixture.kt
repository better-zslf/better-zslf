package io.github.betterzslf.core.net

import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.github.betterzslf.data.AppRepositories
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun fixtureSession(token: String = "fixture-token") = Session(
    token = token,
    userInfo = buildJsonObject { put("id", "fixture-user-id") },
    deptInfo = JsonObject(emptyMap()),
    mapToken = "fixture-map-token",
    mapWebType = "1",
)

internal class ApiTestFixture(engine: MockEngine, authenticated: Boolean = true) {
    val storage = MemoryKeyValueStore()
    val sessions = SessionStore(storage).apply { if (authenticated) save(fixtureSession()) }
    private val http = HttpClient(engine)
    val api = ApiClient(http, sessions, ApiConfig(
        appBaseUrl = "https://app.fixture.invalid/app",
        runBaseUrl = "https://run.fixture.invalid",
        filesBaseUrl = "https://files.fixture.invalid/userfiles/",
    ))
    val repositories = AppRepositories(api, sessions)
    fun close() { api.close(); http.close() }
}
