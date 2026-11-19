package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiConfig
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.github.betterzslf.data.CampusNetworkRepository
import io.github.betterzslf.data.CampusPortalRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun campusFixtureSession(token: String = "fixture-token") = Session(
    token = token, userInfo = buildJsonObject { put("id", "fixture-user-id") },
    deptInfo = JsonObject(emptyMap()), mapToken = "fixture-map-token", mapWebType = "1",
)

/** This independent data fixture has no shared AppGraph/AppRepositories or live configuration. */
internal class CampusDataFixture(engine: MockEngine, authenticated: Boolean = true) {
    val sessions = SessionStore(MemoryKeyValueStore()).apply { if (authenticated) save(campusFixtureSession()) }
    private val http = HttpClient(engine)
    val api = ApiClient(http, sessions, ApiConfig(
        appBaseUrl = "https://app.fixture.invalid/app", runBaseUrl = "https://run.fixture.invalid",
        filesBaseUrl = "https://files.fixture.invalid/userfiles/",
    ))
    val campusNetwork = CampusNetworkRepository(api)
    val campusPortal = CampusPortalRepository(api)
    fun close() { api.close(); http.close() }
}
