package io.github.betterzslf.core.net

import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
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

/** This fixture owns only core networking/session objects; no repository or application dependency. */
internal class CoreApiTestFixture(engine: MockEngine, authenticated: Boolean = true) {
    val storage = MemoryKeyValueStore()
    val sessions = SessionStore(storage).apply { if (authenticated) save(fixtureSession()) }
    private val http = HttpClient(engine)
    val api = ApiClient(http, sessions, ApiConfig(
        appBaseUrl = "https://app.fixture.invalid/app",
        runBaseUrl = "https://run.fixture.invalid",
        filesBaseUrl = "https://files.fixture.invalid/userfiles/",
    ))
    fun close() { api.close(); http.close() }
}
