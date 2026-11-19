package io.github.betterzslf.core.net

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.github.betterzslf.data.RunRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

/** Repository-entry portions of the original binding tests, across the real core-module boundary. */
class RepositorySessionBindingIntegrationTest {
    @Test
    fun signedOutOrMissingOwnerCannotCreateABinding() {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { requests++; error("No request expected") }, authenticated = false)
        try {
            assertNull(fixture.api.captureSessionBinding())
            assertNull(fixture.repositories.run.boundToCurrentSession())
            fixture.sessions.save(fixtureSession().copy(userInfo = JsonObject(emptyMap())))
            assertNull(fixture.api.captureSessionBinding())
            fixture.sessions.save(fixtureSession().copy(userInfo = buildJsonObject { put("id", " ") }))
            assertNull(fixture.api.captureSessionBinding())
            assertEquals(0, requests)
        } finally { fixture.close() }
    }

    @Test
    fun sameSnapshotSaveClearSaveAndMapChangeInvalidateBindingsBeforeAnyRequest() = runTest {
        for (mutation in 0..2) {
            var requests = 0
            val fixture = ApiTestFixture(MockEngine { requests++; error("Retired epoch must stay local") })
            try {
                val original = assertNotNull(fixture.sessions.session.value)
                val binding = assertNotNull(fixture.api.captureSessionBinding())
                val repository = assertNotNull(fixture.repositories.run.boundToCurrentSession())
                val auth = assertNotNull(fixture.repositories.auth.boundToCurrentSession())
                when (mutation) {
                    0 -> fixture.sessions.save(original.copy())
                    1 -> { fixture.sessions.clear(); fixture.sessions.save(original) }
                    else -> fixture.sessions.save(original.copy(mapToken = "changed-fixture-map", mapWebType = "2"))
                }
                val current = fixture.sessions.session.value
                val epoch = fixture.sessions.revision.value
                val persisted = fixture.storage.get(SessionStore.STORAGE_KEY)
                assertFalse(fixture.api.isBindingCurrent(binding))
                assertNull(repository.boundToCurrentSession())
                assertFalse(auth.isBoundToCurrentSession)
                assertNull(auth.boundToCurrentSession())
                assertFalse(fixture.api.clearCurrentBinding(binding))
                val result = fixture.api.execute(ApiEndpoint.RUN_DETAIL,
                    queryParameters("errandId" to "fixture-errand", "userId" to "fixture-user-id", "errandScheduleId" to "fixture-schedule"),
                    expectedSession = binding)
                assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(result).error)
                assertEquals(0, requests)
                assertSame(current, fixture.sessions.session.value)
                assertEquals(epoch, fixture.sessions.revision.value)
                assertEquals(persisted, fixture.storage.get(SessionStore.STORAGE_KEY))
                assertTrue(fixture.api.isBindingCurrent(assertNotNull(fixture.api.captureSessionBinding())))
            } finally { fixture.close() }
        }
    }

    @Test
    fun delayedBoundHttpAndEnvelope401CannotClearSameValueReplacementEpochs() = runTest {
        for (envelope in listOf(false, true)) for (mutation in 0..2) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = ApiTestFixture(MockEngine { request ->
                assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
                entered.complete(Unit)
                release.await()
                if (envelope) respond("""{"code":401,"message":"fixture expired"}""")
                else respond("", HttpStatusCode.Unauthorized)
            })
            try {
                val repository = assertNotNull(fixture.repositories.run.boundToCurrentSession())
                val pending = async { repository.punch(queryParameters("longitude1" to "110", "latitude1" to "20", "longitude2" to "110", "latitude2" to "20")) }
                entered.await()
                val original = assertNotNull(fixture.sessions.session.value)
                when (mutation) {
                    0 -> fixture.sessions.save(original.copy())
                    1 -> { fixture.sessions.clear(); fixture.sessions.save(original) }
                    else -> fixture.sessions.save(original.copy(mapToken = "changed-fixture-map", mapWebType = "2"))
                }
                val current = fixture.sessions.session.value
                val epoch = fixture.sessions.revision.value
                val persisted = fixture.storage.get(SessionStore.STORAGE_KEY)
                release.complete(Unit)
                assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(pending.await()).error)
                assertSame(current, fixture.sessions.session.value)
                assertEquals(epoch, fixture.sessions.revision.value)
                assertEquals(persisted, fixture.storage.get(SessionStore.STORAGE_KEY))
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test
    fun accountChangeWhileClientPipelineIsSuspendedNeverReplacesTheCapturedHeader() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val gate = createClientPlugin("FixtureBindingGate") {
            onRequest { _, _ -> entered.complete(Unit); release.await() }
        }
        var requests = 0
        val engine = MockEngine { request ->
            requests++
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            respond("{\"code\":200,\"data\":{}}")
        }
        val http = HttpClient(engine) { install(gate) }
        val sessions = SessionStore(MemoryKeyValueStore()).apply { save(fixtureSession()) }
        val api = ApiClient(http, sessions, ApiConfig(appBaseUrl = "https://app.fixture.invalid/app", runBaseUrl = "https://run.fixture.invalid"))
        try {
            val repository = assertNotNull(RunRepository(api).boundToCurrentSession())
            val pending = async { repository.punch(queryParameters("longitude1" to "110", "latitude1" to "20", "longitude2" to "110", "latitude2" to "20")) }
            entered.await()
            sessions.save(fixtureSession("other-fixture-token").copy(userInfo = buildJsonObject { put("id", "other-fixture-owner") }))
            release.complete(Unit)
            assertIs<ApiResult.Success<*>>(pending.await())
            assertEquals(1, requests)
            assertEquals("other-fixture-token", sessions.session.value?.token)
        } finally { release.complete(Unit); api.close(); http.close() }
    }

    @Test
    fun lateHttp401DoesNotClearADifferentOwnerEvenIfTheTokenIsTheSame() = runTest {
        lateinit var fixture: ApiTestFixture
        fixture = ApiTestFixture(MockEngine { request ->
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            fixture.sessions.save(fixtureSession().copy(userInfo = buildJsonObject { put("id", "other-fixture-owner") }))
            respond("", HttpStatusCode.Unauthorized)
        })
        try {
            val repository = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(repository.punch(queryParameters("longitude1" to "110", "latitude1" to "20", "longitude2" to "110", "latitude2" to "20"))).error)
            assertEquals("other-fixture-owner", fixture.sessions.session.value?.userId)
            assertNotNull(fixture.storage.get(SessionStore.STORAGE_KEY))
        } finally { fixture.close() }
    }

    @Test
    fun lateEnvelope401DoesNotClearTheSameOwnerWithANewerToken() = runTest {
        lateinit var fixture: ApiTestFixture
        fixture = ApiTestFixture(MockEngine { request ->
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            fixture.sessions.save(fixtureSession("replacement-fixture-token"))
            respond("{\"code\":401,\"message\":\"fixture expired\"}")
        })
        try {
            val repository = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(repository.punch(queryParameters("longitude1" to "110", "latitude1" to "20", "longitude2" to "110", "latitude2" to "20"))).error)
            assertEquals("replacement-fixture-token", fixture.sessions.session.value?.token)
            assertNotNull(fixture.storage.get(SessionStore.STORAGE_KEY))
        } finally { fixture.close() }
    }

    @Test
    fun matchingBound401StillInvalidatesOnlyItsOwnSession() = runTest {
        for (envelope in listOf(false, true)) {
            val fixture = ApiTestFixture(MockEngine {
                if (envelope) respond("{\"code\":401}") else respond("", HttpStatusCode.Unauthorized)
            })
            try {
                val repository = assertNotNull(fixture.repositories.run.boundToCurrentSession())
                val epoch = fixture.sessions.revision.value
                assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(repository.punch(queryParameters("longitude1" to "110", "latitude1" to "20", "longitude2" to "110", "latitude2" to "20"))).error)
                assertNull(fixture.sessions.session.value)
                assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
                assertEquals(epoch + 1, fixture.sessions.revision.value)
            } finally { fixture.close() }
        }
    }

    @Test
    fun bindingAndRepositoryDiagnosticsContainNoOwnerOrCredentials() {
        val fixture = ApiTestFixture(MockEngine { error("No request expected") })
        try {
            val binding = assertNotNull(fixture.api.captureSessionBinding())
            val repository = assertNotNull(fixture.repositories.run.boundToCurrentSession())
            assertEquals("ApiSessionBinding(redacted)", binding.toString())
            assertEquals("RunRepository(bound=true)", repository.toString())
            for (value in listOf(binding.toString(), repository.toString())) {
                assertFalse(value.contains("fixture-token"))
                assertFalse(value.contains("fixture-user-id"))
                assertFalse(value.contains("fixture-map-token"))
            }
        } finally { fixture.close() }
    }

    @Test
    fun omittingABindingPreservesLegacyCurrentSessionBehavior() = runTest {
        var requests = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            requests++
            assertEquals("replacement-fixture-token", request.headers[HttpHeaders.Authorization])
            respond("{\"code\":200,\"data\":{}}")
        })
        try {
            assertNotNull(fixture.api.captureSessionBinding())
            fixture.sessions.save(fixtureSession("replacement-fixture-token"))
            assertIs<ApiResult.Success<*>>(fixture.repositories.run.punch(queryParameters("longitude1" to "110", "latitude1" to "20", "longitude2" to "110", "latitude2" to "20")))
            assertEquals(1, requests)
        } finally { fixture.close() }
    }
}
