package io.github.betterzslf.ui.auth

import io.github.betterzslf.composition.ContributedOfflineEntry
import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.auth.ApiCampusLoginSource
import io.github.betterzslf.feature.auth.CampusLoginController
import io.github.betterzslf.feature.auth.CampusLoginIssue
import io.github.betterzslf.feature.auth.CampusLoginPhase
import io.github.betterzslf.feature.auth.CampusLoginState
import io.github.betterzslf.feature.auth.noticeText
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.nav.core.NavKey
import kotlin.test.*

/**
 * Intent-only binding tests for the campus login root. The controller, the captured
 * ApiCampusLoginSource and the ApiClient behind it are all real; only the composition shell is
 * absent. Every case drives CampusLoginBinderCore and asserts what the root is allowed to do —
 * no visual claim is made or implied anywhere in this file.
 */
private const val LOGIN_SUCCESS =
    """{"code":200,"data":{"accessToken":"fixture-login-token","userInfo":{"id":"fixture-id"},"deptInfo":{"schoolName":"fixture-school"},"mapToken":"fixture-map","mapWebType":"1"},"message":"ok"}"""
private const val LOGIN_REFUSED = """{"code":400,"message":"fixture-refusal"}"""

class CampusLoginBindingTest {
    private class RecordingEffects : CampusLoginBinderSideEffects {
        val log = mutableListOf<String>()
        override fun back() { log += "back" }
        override fun recoverPassword() { log += "recoverPassword" }
        override fun openDocument(id: String) { log += "document:$id" }
        override fun openExtraEntry(target: NavKey) { log += "extra:$target" }
        override fun clearStorageNotice() { log += "clearStorageNotice" }
        override fun clearPassword() { log += "clearPassword" }
    }

    private data class FixtureKey(val id: String) : NavKey

    /** The real controller/core pair the composition mounts, with the same preflight closure shape. */
    private fun newCore(
        fixture: ApiTestFixture,
        effects: CampusLoginBinderSideEffects,
        mountEpoch: Long,
        scope: CoroutineScope,
    ): Pair<CampusLoginController, CampusLoginBinderCore> {
        val controller = CampusLoginController(
            sourceFactory = { ApiCampusLoginSource.capture(fixture.api) },
            isCurrent = { fixture.sessions.revision.value == mountEpoch },
        )
        return controller to CampusLoginBinderCore(controller, scope, effects)
    }

    @Test
    fun duplicateSubmitsBeginExactlyOneNetworkAttempt() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; respond(LOGIN_SUCCESS) }, authenticated = false)
        try {
            val effects = RecordingEffects()
            val (_, core) = newCore(fixture, effects, fixture.sessions.revision.value, this)
            val first = assertNotNull(core.beginSubmit("fixture-account", "fixture-password"))
            // The busy lock is taken synchronously inside begin, so a second tap never begins.
            assertNull(core.beginSubmit("fixture-account", "fixture-password"))
            first.join()
            assertEquals(CampusLoginPhase.ACCEPTED, core.state.phase)
            assertEquals(1, calls)
            assertEquals(listOf("clearStorageNotice", "clearPassword"), effects.log)
        } finally { fixture.close() }
    }

    @Test
    fun leavingThePageDropsTheInFlightReceiptWithoutAnyRootAction() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val fixture = ApiTestFixture(MockEngine {
            calls++; started.complete(Unit); release.await(); respond(LOGIN_REFUSED)
        }, authenticated = false)
        try {
            val effects = RecordingEffects()
            val (controller, core) = newCore(fixture, effects, fixture.sessions.revision.value, this)
            val job = assertNotNull(core.beginSubmit("fixture", "fixture"))
            started.await()
            // The dispose the composition runs on back: the mount is retired before the receipt.
            controller.close()
            release.complete(Unit)
            job.join()
            assertEquals(CampusLoginPhase.CLOSED, core.state.phase)
            assertTrue(effects.log.isEmpty(), "a retired receipt must clear no notice and no input")
            assertEquals(1, calls)
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test
    fun stageABAOldReceiptNeverBecomesTheNewMountsState() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var gate = 0
        val fixture = ApiTestFixture(MockEngine {
            if (gate++ == 0) { started.complete(Unit); release.await(); respond(LOGIN_REFUSED) }
            else respond(LOGIN_SUCCESS)
        }, authenticated = false)
        try {
            // Old mount begins, then the user leaves mid-flight; its attempt is still on the wire.
            val oldEffects = RecordingEffects()
            val (oldController, oldCore) = newCore(fixture, oldEffects, fixture.sessions.revision.value, this)
            val oldJob = assertNotNull(oldCore.beginSubmit("fixture", "fixture"))
            started.await()
            oldController.close()
            // A new mount on the same graph completes its own accepted chain first.
            val newEffects = RecordingEffects()
            val (_, newCore) = newCore(fixture, newEffects, fixture.sessions.revision.value, this)
            val newJob = assertNotNull(newCore.beginSubmit("fixture", "fixture"))
            newJob.join()
            assertEquals(CampusLoginPhase.ACCEPTED, newCore.state.phase)
            assertEquals(listOf("clearStorageNotice", "clearPassword"), newEffects.log)
            // The old mount's late rejected receipt lands last and writes nothing anywhere.
            release.complete(Unit)
            oldJob.join()
            assertEquals(CampusLoginPhase.ACCEPTED, newCore.state.phase)
            assertTrue(oldEffects.log.isEmpty())
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test
    fun editingRetiresTheInFlightAttemptAndDropsItsLateReceipt() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = ApiTestFixture(MockEngine {
            started.complete(Unit); release.await(); respond(LOGIN_REFUSED)
        }, authenticated = false)
        try {
            val effects = RecordingEffects()
            val (_, core) = newCore(fixture, effects, fixture.sessions.revision.value, this)
            val job = assertNotNull(core.beginSubmit("fixture", "fixture"))
            started.await()
            core.onEdit()
            assertEquals(CampusLoginPhase.EDITING, core.state.phase)
            assertEquals(1L, core.state.inputRevision)
            release.complete(Unit)
            job.join()
            // The late rejection was retired with the edit; no banner, no root action.
            assertEquals(CampusLoginPhase.EDITING, core.state.phase)
            assertNull(core.state.noticeText())
            assertTrue(effects.log.isEmpty())
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test
    fun ownAcceptedSaveAdvancesTheEpochYetConsumesItsOwnContinuationOnce() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { request ->
            calls++
            // The student number is trimmed as typed; the password goes through exactly as typed.
            assertEquals("fixture-account", request.url.parameters["username"])
            assertEquals("fixture-password !@#", request.url.parameters["password"])
            respond(LOGIN_SUCCESS)
        }, authenticated = false)
        try {
            val effects = RecordingEffects()
            val mountEpoch = fixture.sessions.revision.value
            val (_, core) = newCore(fixture, effects, mountEpoch, this)
            val job = assertNotNull(core.beginSubmit("  fixture-account  ", "fixture-password !@#"))
            job.join()
            // The login's own save advanced the epoch past the mount gate…
            assertTrue(fixture.sessions.revision.value != mountEpoch)
            // …yet the own-accepted receipt survived, consumed exactly once, same call frame.
            assertEquals(CampusLoginPhase.ACCEPTED, core.state.phase)
            assertEquals(1, calls)
            assertNotNull(fixture.sessions.session.value)
            assertEquals(listOf("clearStorageNotice", "clearPassword"), effects.log)
        } finally { fixture.close() }
    }

    @Test
    fun foreignEpochTurnsAFailureReceiptIntoSessionChangedWithoutRootActions() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = ApiTestFixture(MockEngine {
            started.complete(Unit); release.await(); respond(LOGIN_REFUSED)
        }, authenticated = false)
        try {
            val effects = RecordingEffects()
            val mountEpoch = fixture.sessions.revision.value
            val (_, core) = newCore(fixture, effects, mountEpoch, this)
            val job = assertNotNull(core.beginSubmit("fixture", "fixture"))
            started.await()
            // A foreign session lands while our attempt is still on the wire.
            fixture.sessions.save(fixtureSession("replacement-token"))
            release.complete(Unit)
            job.join()
            assertEquals(CampusLoginPhase.SESSION_CHANGED, core.state.phase)
            assertEquals(CampusLoginIssue.SESSION_CHANGED, core.state.issue)
            assertEquals(AppText.Auth.OPERATION_SESSION_CHANGED_RECONFIRM_BEFORE_CONTINUING, core.state.noticeText())
            assertEquals("replacement-token", fixture.sessions.session.value?.token)
            assertTrue(effects.log.isEmpty())
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test
    fun anEditedMountsLateFinallyNeverDisturbsItsNewerTicket() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var gate = 0
        val fixture = ApiTestFixture(MockEngine {
            if (gate++ == 0) { started.complete(Unit); release.await(); respond(LOGIN_REFUSED) }
            else respond(LOGIN_SUCCESS)
        }, authenticated = false)
        try {
            val effects = RecordingEffects()
            val (_, core) = newCore(fixture, effects, fixture.sessions.revision.value, this)
            val oldJob = assertNotNull(core.beginSubmit("fixture", "fixture"))
            started.await()
            // The edit retires the first attempt and reopens the gate for a second one.
            core.onEdit()
            val newJob = assertNotNull(core.beginSubmit("fixture", "fixture"))
            newJob.join()
            assertEquals(CampusLoginPhase.ACCEPTED, core.state.phase)
            assertEquals(listOf("clearStorageNotice", "clearPassword"), effects.log)
            // The first attempt's late receipt must not un-write the second one's state.
            release.complete(Unit)
            oldJob.join()
            assertEquals(CampusLoginPhase.ACCEPTED, core.state.phase)
            assertEquals(2, effects.log.size)
        } finally { release.complete(Unit); fixture.close() }
    }

    @Test
    fun extraEntryKeysResolveOnlyWithinTheirOwnRenderGeneration() {
        val effects = RecordingEffects()
        val oldEntry = ContributedOfflineEntry("Fixture label old", FixtureKey("old"))
        val newEntry = ContributedOfflineEntry("Fixture label new", FixtureKey("new"))
        val oldBinding = assertNotNull(nextCampusLoginExtraBinding(oldEntry, 1))
        val newBinding = assertNotNull(nextCampusLoginExtraBinding(newEntry, 2))
        val core = CampusLoginBinderCore(
            CampusLoginController(sourceFactory = { null }, isCurrent = { true }),
            CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            effects,
        )
        // An old generation's key never resolves against the newest binding.
        core.onExtraEntry(oldBinding.key, newBinding)
        core.onExtraEntry("login-extra-stale", newBinding)
        assertTrue(effects.log.isEmpty())
        core.onExtraEntry(newBinding.key, newBinding)
        assertEquals(listOf("extra:FixtureKey(id=new)"), effects.log)
    }

    @Test
    fun chooserAndEntryIntentsRouteWithoutEverTouchingTheController() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; respond("{}") }, authenticated = false)
        try {
            val effects = RecordingEffects()
            val (_, core) = newCore(fixture, effects, fixture.sessions.revision.value, this)
            core.onEvent(AuthLoginUiEvent.RECOVER_PASSWORD, "fixture", "fixture")
            core.onEvent(AuthLoginUiEvent.USER_AGREEMENT, "fixture", "fixture")
            core.onEvent(AuthLoginUiEvent.PRIVACY_POLICY, "fixture", "fixture")
            core.onEvent(AuthLoginUiEvent.BACK, "fixture", "fixture")
            // Chooser-only intents never route into the campus binder.
            core.onEvent(AuthLoginUiEvent.CHOOSE_CAMPUS, "fixture", "fixture")
            core.onEvent(AuthLoginUiEvent.ENTER_APP, "fixture", "fixture")
            assertEquals(
                listOf("recoverPassword",
                    "document:${AppText.Document.USER_AGREEMENT}",
                    "document:${AppText.Document.PRIVACY_POLICY}",
                    "back"),
                effects.log,
            )
            assertEquals(0, calls)
            assertEquals(CampusLoginState.initial().inputRevision, core.state.inputRevision)
            // Blank drafts never become a submit through any of these routes either.
            assertNull(core.beginSubmit("", ""))
            assertEquals(0, calls)
        } finally { fixture.close() }
    }

    @Test
    fun bannerNoticeMirrorsTheLegacyMessageFirstSplitWithWhitelistedCopy() {
        val rejected = CampusLoginState(
            phase = CampusLoginPhase.REJECTED, issue = CampusLoginIssue.BUSINESS,
            inputRevision = 1L, busy = false, submitAllowed = true,
        )
        val loginBanner = assertNotNull(campusLoginBannerNotice(rejected, "fixture-unused-storage-copy"))
        assertEquals(AppText.Auth.LOGIN_NOTICE_TITLE, loginBanner.title)
        assertEquals(AppText.Common.OPERATION_UNSUCCESSFUL_TRY_AGAIN_LATER, loginBanner.message)
        val editing = CampusLoginState.initial()
        assertNull(campusLoginBannerNotice(editing, null))
        val storageBanner = assertNotNull(
            campusLoginBannerNotice(editing, AppText.App.SESSION_CLEANUP_FAILED_OLD_SESSION_MAY_REAPPEAR_HINT))
        assertEquals(AppText.App.LOCAL_DATA_NOTICE, storageBanner.title)
        assertEquals(AppText.App.SESSION_CLEANUP_FAILED_OLD_SESSION_MAY_REAPPEAR_HINT, storageBanner.message)
        val unrecognized = assertNotNull(campusLoginBannerNotice(editing, "fixture-unrecognized-notice"))
        assertEquals(AppText.App.LOCAL_DATA_NOTICE, unrecognized.title)
        assertEquals(AppText.App.SESSION_READ_FAILED_TITLE, unrecognized.message)
    }
}
