package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiConfig
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun runFixtureSession(token: String = "fixture-token", owner: String = "owner") = Session(
    token = token, userInfo = buildJsonObject { put("id", owner) },
    deptInfo = JsonObject(emptyMap()), mapToken = "fixture-map-token", mapWebType = "1",
)

/** Completely independent offline fixture: no shared AppRepositories, device or live configuration. */
internal class RunDataFixture(engine: MockEngine, authenticated: Boolean = true, owner: String = "owner") {
    val storage = MemoryKeyValueStore()
    val sessions = SessionStore(storage).apply { if (authenticated) save(runFixtureSession(owner = owner)) }
    private val http = HttpClient(engine)
    val api = ApiClient(http, sessions, ApiConfig(appBaseUrl = "https://app.fixture.invalid/app", runBaseUrl = "https://run.fixture.invalid"))
    val readTransport = RunReadTransport(api)
    fun close() { api.close(); http.close() }
}
