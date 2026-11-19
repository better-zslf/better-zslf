package io.github.betterzslf.feature.accountrecovery

import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiConfig
import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun recoveryFixtureSession(token: String = "fixture-token", owner: String = "fixture-owner") = Session(
    token = token,
    userInfo = buildJsonObject { put("id", owner) },
    deptInfo = JsonObject(emptyMap()),
    mapToken = "fixture-map-token",
    mapWebType = "1",
)

/** Data-only fixture: no AuthRepository, AppRepositories, Graph, UI or presentation dependency. */
internal class RecoveryApiFixture(
    engine: MockEngine,
    authenticated: Boolean = true,
    val storage: KeyValueStore = MemoryKeyValueStore(),
) {
    val sessions = SessionStore(storage).apply { if (authenticated) save(recoveryFixtureSession()) }
    private val http = HttpClient(engine)
    val api = ApiClient(http, sessions, ApiConfig(
        appBaseUrl = "https://app.fixture.invalid/app",
        runBaseUrl = "https://run.fixture.invalid",
        filesBaseUrl = "https://files.fixture.invalid/userfiles/",
    ))
    fun source(): ApiAccountRecoverySource = requireNotNull(ApiAccountRecoverySource.capture(api, sessions))
    fun close() { api.close(); http.close() }
}
