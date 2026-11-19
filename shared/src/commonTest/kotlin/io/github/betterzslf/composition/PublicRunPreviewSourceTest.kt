package io.github.betterzslf.composition

import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiConfig
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.storage.MemoryKeyValueStore
import io.github.betterzslf.core.storage.SessionStore
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.api.RunPreviewRead
import io.github.betterzslf.feature.run.api.RunPreviewSelection
import io.github.betterzslf.platform.RunMapCoordinateSystem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Every HTTP exchange is a MockEngine fixture; no transport reaches a real endpoint. */
class PublicRunPreviewSourceTest {
    private val selection = RunPreviewSelection("fixture-errand", "fixture-schedule")

    private fun detailResponse(identityFields: String = """
        "errandId":"fixture-errand","errandScheduleId":"fixture-schedule",
    """): String = """{"code":200,"data":{"errandDetail":{
        $identityFields
        "eName":"Fixture route","geoCoorList":[[113.4,22.5]],
        "walkPaths":[[113.4,22.5],[113.4001,22.5]]
    }}}"""

    private class Fixture(engine: MockEngine, authenticated: Boolean = true) {
        val storage = MemoryKeyValueStore()
        val sessions = SessionStore(storage).apply { if (authenticated) save(fixtureSession()) }
        private val http = HttpClient(engine)
        val api = ApiClient(http, sessions, ApiConfig(
            appBaseUrl = "https://app.fixture.invalid/app",
            runBaseUrl = "https://run.fixture.invalid",
        ))
        val ownerJob = Job()
        val source = PublicRunPreviewSource(api, sessions, CoroutineScope(ownerJob))

        fun close() { ownerJob.cancel(); api.close(); http.close() }
    }

    @Test fun readUsesTheCapturedOwnerAndReturnsOnlyTheTypedProjectionAndRedactedLease() = runTest {
        var requests = 0
        val fixture = Fixture(MockEngine { request ->
            requests++
            assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
            assertEquals("fixture-user-id", request.url.parameters["userId"])
            assertEquals(selection.taskId, request.url.parameters["errandId"])
            assertEquals(selection.scheduleId, request.url.parameters["errandScheduleId"])
            respond(detailResponse())
        })
        try {
            assertSame(fixture.sessions.revision, fixture.source.revision)
            val epoch = fixture.source.revision.value
            val ready = assertIs<RunPreviewRead.Ready>(fixture.source.read(selection))
            assertEquals(epoch, fixture.source.revision.value)
            assertEquals("Fixture route", ready.context.title)
            assertEquals(listOf(GeoPoint(113.4, 22.5)), ready.context.checkpoints.map { it.point })
            assertEquals(1, ready.context.checkpointSourceCount)
            assertEquals(listOf(listOf(GeoPoint(113.4, 22.5), GeoPoint(113.4001, 22.5))), ready.context.walkPaths)
            assertEquals(RunMapCoordinateSystem.GCJ02, ready.context.coordinateSystem)
            assertTrue(ready.lease.isCurrent())
            assertEquals("RunPreviewLease(redacted)", ready.lease.toString())
            for (text in listOf(ready.context.toString(), ready.lease.toString(), ready.toString())) {
                for (privateValue in listOf("fixture-token", "fixture-user-id", "fixture-map-token", selection.taskId, selection.scheduleId)) {
                    assertFalse(text.contains(privateValue))
                }
            }
            assertEquals(1, requests)
        } finally { fixture.close() }
    }

    @Test fun signedOutAndMissingOwnerFailLocallyWithoutSendingAnyRequest() = runTest {
        var requests = 0
        val fixture = Fixture(MockEngine { requests++; error("No request expected") }, authenticated = false)
        try {
            assertEquals(AppText.Run.LOGIN_TO_VIEW_CURRENT_ACCOUNT_RUN_DATA_HINT,
                assertIs<RunPreviewRead.Failed>(fixture.source.read(selection)).message)
            fixture.sessions.save(fixtureSession().copy(userInfo = JsonObject(emptyMap())))
            assertIs<RunPreviewRead.Failed>(fixture.source.read(selection))
            assertEquals(0, requests)
        } finally { fixture.close() }
    }

    @Test fun missingIdentityFieldsMayInheritTheRequestedTaskAndSchedule() = runTest {
        for (identity in listOf("", """"errandId":"fixture-errand",""", """"scheduleId":"fixture-schedule",""")) {
            val fixture = Fixture(MockEngine { respond(detailResponse(identity)) })
            try {
                val ready = assertIs<RunPreviewRead.Ready>(fixture.source.read(selection))
                assertTrue(ready.lease.isCurrent())
                assertEquals("Fixture route", ready.context.title)
                assertEquals(1, ready.context.checkpoints.size)
            } finally { fixture.close() }
        }
    }

    @Test fun explicitTaskOrScheduleContradictionsNeverBecomeReadyGeometry() = runTest {
        val identities = listOf(
            """"errandId":"another-fixture-task","errandScheduleId":"fixture-schedule",""",
            """"errandId":"fixture-errand","errandScheduleId":"another-fixture-schedule",""",
            """"errandId":"fixture-errand","scheduleId":"another-fixture-schedule",""",
        )
        for (identity in identities) {
            val fixture = Fixture(MockEngine { respond(detailResponse(identity)) })
            try {
                assertEquals(AppText.Run.TASK_DETAIL_IDENTITY_MISMATCH_START_DISABLED,
                    assertIs<RunPreviewRead.Failed>(fixture.source.read(selection)).message)
                assertNotNull(fixture.sessions.session.value)
            } finally { fixture.close() }
        }
    }

    @Test fun malformedOrConflictingExplicitAliasesCannotMasqueradeAsMissingIdentity() = runTest {
        val identities = listOf(
            """"errandId":null,"errandScheduleId":"fixture-schedule",""",
            """"errandId":"fixture-errand","errandScheduleId":null,""",
            """"errandId":"fixture-errand","errandScheduleId":"fixture-schedule","scheduleId":"another-fixture-schedule",""",
        )
        for (identity in identities) {
            val fixture = Fixture(MockEngine { respond(detailResponse(identity)) })
            try {
                assertIs<RunPreviewRead.Failed>(fixture.source.read(selection))
            } finally { fixture.close() }
        }
    }

    @Test fun nullableDecoderResultHasAnExplicitFailureInsteadOfAReadyOrThrownException() = runTest {
        for (data in listOf("null", "{}", "[]", """{"errandDetail":null}""")) {
            val fixture = Fixture(MockEngine { respond("""{"code":200,"data":$data}""") })
            try {
                assertEquals(AppText.Run.ERRAND_DETAIL_OBJECT_UNRECOGNIZED,
                    assertIs<RunPreviewRead.Failed>(fixture.source.read(selection)).message)
            } finally { fixture.close() }
        }
    }

    @Test fun savingTheSameCredentialSnapshotInvalidatesAnOldLeaseSynchronously() = runTest {
        val fixture = Fixture(MockEngine { respond(detailResponse()) })
        try {
            val ready = assertIs<RunPreviewRead.Ready>(fixture.source.read(selection))
            val epoch = fixture.source.revision.value
            fixture.sessions.save(assertNotNull(fixture.sessions.session.value).copy())
            // No yield, collector or clock tick between mutation and this admission check.
            assertEquals(epoch + 1, fixture.source.revision.value)
            assertFalse(ready.lease.isCurrent())
            val replacement = assertIs<RunPreviewRead.Ready>(fixture.source.read(selection))
            assertTrue(replacement.lease.isCurrent())
            assertFalse(ready.lease.isCurrent())
        } finally { fixture.close() }
    }

    @Test fun clearThenRestoreSameOwnerAndTokenCannotReviveTheOldLease() = runTest {
        val fixture = Fixture(MockEngine { respond(detailResponse()) })
        try {
            val ready = assertIs<RunPreviewRead.Ready>(fixture.source.read(selection))
            val snapshot = assertNotNull(fixture.sessions.session.value)
            val epoch = fixture.source.revision.value
            fixture.sessions.clear()
            fixture.sessions.save(snapshot)
            assertEquals(epoch + 2, fixture.source.revision.value)
            assertEquals(snapshot, fixture.sessions.session.value)
            assertFalse(ready.lease.isCurrent())
            assertTrue(assertIs<RunPreviewRead.Ready>(fixture.source.read(selection)).lease.isCurrent())
            assertFalse(ready.lease.isCurrent())
        } finally { fixture.close() }
    }

    @Test fun mapConfigurationMutationAndRestorationBothFenceExistingDrafts() = runTest {
        val fixture = Fixture(MockEngine { respond(detailResponse()) })
        try {
            val original = assertNotNull(fixture.sessions.session.value)
            val first = assertIs<RunPreviewRead.Ready>(fixture.source.read(selection))
            fixture.sessions.save(original.copy(mapToken = "replacement-fixture-map", mapWebType = "2"))
            assertFalse(first.lease.isCurrent())
            val second = assertIs<RunPreviewRead.Ready>(fixture.source.read(selection))
            assertTrue(second.lease.isCurrent())
            fixture.sessions.save(original)
            assertFalse(first.lease.isCurrent())
            assertFalse(second.lease.isCurrent())
            assertEquals(original.token, fixture.sessions.session.value?.token)
            assertEquals(original.userId, fixture.sessions.session.value?.userId)
        } finally { fixture.close() }
    }

    @Test fun ownerScopeClosureInvalidatesLeasesAndPreventsAnyNewRequest() = runTest {
        var requests = 0
        val fixture = Fixture(MockEngine { requests++; respond(detailResponse()) })
        try {
            val ready = assertIs<RunPreviewRead.Ready>(fixture.source.read(selection))
            val epoch = fixture.source.revision.value
            fixture.ownerJob.cancel()
            assertFalse(ready.lease.isCurrent())
            assertIs<RunPreviewRead.Failed>(fixture.source.read(selection))
            assertEquals(epoch, fixture.source.revision.value)
            assertEquals(1, requests)
            assertNotNull(fixture.sessions.session.value)
        } finally { fixture.close() }
    }

    @Test fun inFlightSuccessAfterSameSnapshotAbaOrMapMutationIsNotPublished() = runTest {
        for (mutation in 0..2) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = Fixture(MockEngine { request ->
                assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
                entered.complete(Unit)
                release.await()
                respond(detailResponse())
            })
            try {
                val pending = async { fixture.source.read(selection) }
                entered.await()
                val snapshot = assertNotNull(fixture.sessions.session.value)
                when (mutation) {
                    0 -> fixture.sessions.save(snapshot.copy())
                    1 -> { fixture.sessions.clear(); fixture.sessions.save(snapshot) }
                    else -> fixture.sessions.save(snapshot.copy(mapWebType = "2"))
                }
                release.complete(Unit)
                assertEquals(AppText.Auth.REQUEST_ACCOUNT_OWNERSHIP_MISMATCH,
                    assertIs<RunPreviewRead.Failed>(pending.await()).message)
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test fun inFlightSuccessAfterOwnerScopeClosureDoesNotReturnAReadyProjection() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = Fixture(MockEngine {
            entered.complete(Unit)
            release.await()
            respond(detailResponse())
        })
        try {
            val pending = async { fixture.source.read(selection) }
            entered.await()
            fixture.ownerJob.cancel()
            release.complete(Unit)
            assertIs<RunPreviewRead.Failed>(pending.await())
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test fun delayedHttpOrEnvelope401DoesNotClearADifferentOwnerOrNewCredential() = runTest {
        for (envelope in listOf(false, true)) for (changeOwner in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = Fixture(MockEngine { request ->
                assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
                entered.complete(Unit)
                release.await()
                if (envelope) respond("""{"code":401,"message":"fixture expired"}""")
                else respond("", HttpStatusCode.Unauthorized)
            })
            try {
                val pending = async { fixture.source.read(selection) }
                entered.await()
                val replacement = if (changeOwner) fixtureSession().copy(
                    userInfo = buildJsonObject { put("id", "other-fixture-owner") })
                else fixtureSession("replacement-fixture-token")
                fixture.sessions.save(replacement)
                val epoch = fixture.source.revision.value
                val stored = fixture.storage.get(SessionStore.STORAGE_KEY)
                release.complete(Unit)
                assertIs<RunPreviewRead.Failed>(pending.await())
                assertEquals(replacement, fixture.sessions.session.value)
                assertEquals(epoch, fixture.source.revision.value)
                assertEquals(stored, fixture.storage.get(SessionStore.STORAGE_KEY))
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test fun delayedHttpAndEnvelope401AfterSameSnapshotAbaOrMapChangePreservesNewEpoch() = runTest {
        for (envelope in listOf(false, true)) for (mutation in 0..2) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = Fixture(MockEngine { request ->
                assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
                entered.complete(Unit)
                release.await()
                if (envelope) respond("""{"code":401,"message":"fixture expired"}""")
                else respond("", HttpStatusCode.Unauthorized)
            })
            try {
                val pending = async { fixture.source.read(selection) }
                entered.await()
                val original = assertNotNull(fixture.sessions.session.value)
                when (mutation) {
                    0 -> fixture.sessions.save(original.copy())
                    1 -> { fixture.sessions.clear(); fixture.sessions.save(original) }
                    else -> fixture.sessions.save(original.copy(mapToken = "changed-fixture-map", mapWebType = "2"))
                }
                val replacement = fixture.sessions.session.value
                val epoch = fixture.source.revision.value
                val persisted = fixture.storage.get(SessionStore.STORAGE_KEY)
                release.complete(Unit)
                assertIs<RunPreviewRead.Failed>(pending.await())
                assertSame(replacement, fixture.sessions.session.value)
                assertEquals(epoch, fixture.source.revision.value)
                assertEquals(persisted, fixture.storage.get(SessionStore.STORAGE_KEY))
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test fun matchingBound401StillClearsItsOwnSessionAndAdvancesTheEpoch() = runTest {
        for (envelope in listOf(false, true)) {
            val fixture = Fixture(MockEngine {
                if (envelope) respond("""{"code":401}""") else respond("", HttpStatusCode.Unauthorized)
            })
            try {
                val epoch = fixture.source.revision.value
                assertIs<RunPreviewRead.Failed>(fixture.source.read(selection))
                assertNull(fixture.sessions.session.value)
                assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
                assertEquals(epoch + 1, fixture.source.revision.value)
            } finally { fixture.close() }
        }
    }

    @Test fun failureMessagesStayFiniteRatherThanEchoingBackendPayloads() = runTest {
        val privatePayload = "fixture-server-token-secret-owner-id"
        for ((status, body) in listOf(
            HttpStatusCode.OK to """{"code":400,"message":"$privatePayload"}""",
            HttpStatusCode.InternalServerError to privatePayload,
        )) {
            var requests = 0
            val fixture = Fixture(MockEngine { requests++; respond(body, status) })
            try {
                val epoch = fixture.source.revision.value
                val failure = assertIs<RunPreviewRead.Failed>(fixture.source.read(selection))
                assertEquals(AppText.Run.READ_FAILURE_MANUAL_REFRESH_NO_WRITE_RETRY_HINT, failure.message)
                assertFalse(failure.message.contains(privatePayload))
                assertFalse(failure.toString().contains(privatePayload))
                assertEquals(1, requests)
                assertEquals(epoch, fixture.source.revision.value)
                assertNotNull(fixture.sessions.session.value)
            } finally { fixture.close() }
        }
    }
}
