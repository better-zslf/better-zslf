package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiConfig
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Six explicitly additional read-transport counterparts to the root's write binding scenarios. */
class RunReadTransportBindingTest {
    @Test fun delayedBoundHttpAndEnvelope401CannotClearSameValueReplacementEpochs() = runTest {
        for (envelope in listOf(false, true)) for (mutation in 0..2) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val fixture = RunDataFixture(MockEngine { request ->
                assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
                entered.complete(Unit); release.await()
                if (envelope) respond("""{"code":401,"message":"fixture expired"}""") else respond("", HttpStatusCode.Unauthorized)
            })
            try {
                val transport = assertNotNull(fixture.readTransport.boundToCurrentSession())
                val pending = async { transport.detail("fixture-errand", "owner", "fixture-schedule") }
                entered.await()
                val original = assertNotNull(fixture.sessions.session.value)
                when (mutation) {
                    0 -> fixture.sessions.save(original.copy())
                    1 -> { fixture.sessions.clear(); fixture.sessions.save(original) }
                    else -> fixture.sessions.save(original.copy(mapToken = "changed-fixture-map", mapWebType = "2"))
                }
                val current = fixture.sessions.session.value; val epoch = fixture.sessions.revision.value
                val persisted = fixture.storage.get(SessionStore.STORAGE_KEY)
                release.complete(Unit)
                assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(pending.await()).error)
                assertSame(current, fixture.sessions.session.value); assertEquals(epoch, fixture.sessions.revision.value)
                assertEquals(persisted, fixture.storage.get(SessionStore.STORAGE_KEY))
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test fun accountChangeWhileClientPipelineIsSuspendedNeverReplacesTheCapturedHeader() = runTest {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val gate = createClientPlugin("FixtureReadBindingGate") { onRequest { _, _ -> entered.complete(Unit); release.await() } }
        var requests = 0
        val engine = MockEngine { request ->
            requests++; assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            respond("""{"code":200,"data":{}}""")
        }
        val http = HttpClient(engine) { install(gate) }
        val sessions = SessionStore(MemoryKeyValueStore()).apply { save(runFixtureSession()) }
        val api = ApiClient(http, sessions, ApiConfig(appBaseUrl = "https://app.fixture.invalid/app", runBaseUrl = "https://run.fixture.invalid"))
        try {
            val transport = assertNotNull(RunReadTransport(api).boundToCurrentSession())
            val pending = async { transport.detail("fixture-errand", "owner", "fixture-schedule") }
            entered.await(); sessions.save(runFixtureSession("other-fixture-token", "other-fixture-owner")); release.complete(Unit)
            // Raw transport still reports its original successful response. Public typed publication is separate.
            assertIs<ApiResult.Success<*>>(pending.await()); assertEquals(1, requests)
            assertEquals("other-fixture-token", sessions.session.value?.token)
        } finally { release.complete(Unit); api.close(); http.close() }
    }

    @Test fun lateHttp401DoesNotClearADifferentOwnerEvenIfTheTokenIsTheSame() = runTest {
        lateinit var fixture: RunDataFixture
        fixture = RunDataFixture(MockEngine { request ->
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            fixture.sessions.save(runFixtureSession(owner = "other-fixture-owner")); respond("", HttpStatusCode.Unauthorized)
        })
        try {
            val transport = assertNotNull(fixture.readTransport.boundToCurrentSession())
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(transport.detail("fixture-errand", "owner", "fixture-schedule")).error)
            assertEquals("other-fixture-owner", fixture.sessions.session.value?.userId)
            assertNotNull(fixture.storage.get(SessionStore.STORAGE_KEY))
        } finally { fixture.close() }
    }

    @Test fun lateEnvelope401DoesNotClearTheSameOwnerWithANewerToken() = runTest {
        lateinit var fixture: RunDataFixture
        fixture = RunDataFixture(MockEngine { request ->
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            fixture.sessions.save(runFixtureSession("replacement-fixture-token")); respond("""{"code":401,"message":"fixture expired"}""")
        })
        try {
            val transport = assertNotNull(fixture.readTransport.boundToCurrentSession())
            assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(transport.detail("fixture-errand", "owner", "fixture-schedule")).error)
            assertEquals("replacement-fixture-token", fixture.sessions.session.value?.token)
            assertNotNull(fixture.storage.get(SessionStore.STORAGE_KEY))
        } finally { fixture.close() }
    }

    @Test fun matchingBound401StillInvalidatesOnlyItsOwnSession() = runTest {
        for (envelope in listOf(false, true)) {
            val fixture = RunDataFixture(MockEngine { if (envelope) respond("""{"code":401}""") else respond("", HttpStatusCode.Unauthorized) })
            try {
                val transport = assertNotNull(fixture.readTransport.boundToCurrentSession()); val epoch = fixture.sessions.revision.value
                assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(transport.detail("fixture-errand", "owner", "fixture-schedule")).error)
                assertNull(fixture.sessions.session.value); assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
                assertEquals(epoch + 1, fixture.sessions.revision.value)
            } finally { fixture.close() }
        }
    }

    @Test fun omittingABindingPreservesLegacyCurrentSessionBehavior() = runTest {
        var requests = 0
        val fixture = RunDataFixture(MockEngine { request ->
            requests++; assertEquals("replacement-fixture-token", request.headers[HttpHeaders.Authorization])
            respond("""{"code":200,"data":{}}""")
        })
        try {
            assertNotNull(fixture.api.captureSessionBinding()); fixture.sessions.save(runFixtureSession("replacement-fixture-token"))
            assertIs<ApiResult.Success<*>>(fixture.readTransport.detail("fixture-errand", "owner", "fixture-schedule"))
            assertEquals(1, requests)
        } finally { fixture.close() }
    }
}
