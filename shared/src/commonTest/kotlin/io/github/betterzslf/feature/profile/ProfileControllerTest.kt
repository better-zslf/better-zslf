package io.github.betterzslf.feature.profile

import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.net.fixtureSession
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileControllerTest {
    private fun session(id: String = "fixture-A", token: String = "fixture-token-A") = fixtureSession(token).copy(userInfo = buildJsonObject { put("id", id) })
    private class FakeProfiles(private val sessions: MutableStateFlow<Session?>) : ProfileSource {
        var fieldsCalls = 0; var statsCalls = 0; var photoCalls = 0
        var fieldsGate: CompletableDeferred<Unit>? = null
        var metadata: ProfilePhotoMetadata = ProfilePhotoMetadata.Empty
        var onPhoto: (() -> Unit)? = null
        var fail = false
        override fun boundToCurrentSession(): BoundProfileSource? {
            val captured = sessions.value ?: return null
            return object : BoundProfileSource {
                override val isBoundToCurrentSession: Boolean get() = sessions.value?.userId == captured.userId && sessions.value?.token == captured.token
                override suspend fun readFields(): ApiResult<ProfileFields> {
                    fieldsCalls++; fieldsGate?.await()
                    return if (fail) ApiResult.Failure(ApiError.Decode()) else ApiResult.Success(ProfileFields(captured.userId, "Fixture school", "Fixture student", "Fixture account", "Fixture role", null))
                }
                override suspend fun readStatistics(): ApiResult<ProfileStatistics> {
                    statsCalls++
                    return if (fail) ApiResult.Failure(ApiError.Network()) else ApiResult.Success(ProfileStatistics(1, 2, 3))
                }
                override suspend fun readPhotoMetadata(): ApiResult<ProfilePhotoMetadata> {
                    photoCalls++; onPhoto?.invoke()
                    return if (fail) ApiResult.Failure(ApiError.Decode()) else ApiResult.Success(metadata)
                }
            }
        }
    }
    private class FakeAvatars : AvatarSource {
        var calls = 0
        override suspend fun load(resource: ProfilePhotoMetadata.Resource): AvatarLoadResult {
            calls++
            return AvatarLoadResult.Image(AvatarBytes(profilePngHeader(), 64, 64, AvatarFormat.PNG))
        }
    }
    private val resource = ProfilePhotoMetadata.Resource(AvatarImagePolicy.filesBaseUrl + "image/fixture.png")

    @Test
    fun constructionDoesNoHttpAndRefreshIsSingleGenerationNotThreeConcurrentRefreshes() = runTest {
        val sessions = MutableStateFlow<Session?>(session())
        val source = FakeProfiles(sessions); val avatars = FakeAvatars()
        val gate = CompletableDeferred<Unit>(); source.fieldsGate = gate
        val controller = ProfileController(source, sessions, avatars, this)
        assertEquals(0, source.fieldsCalls); assertEquals(0, avatars.calls)
        controller.refresh(); controller.refresh(); runCurrent()
        assertTrue(controller.state.value.loading); assertEquals(1, source.fieldsCalls); assertEquals(0, source.statsCalls)
        gate.complete(Unit); runCurrent()
        assertEquals("fixture-A", controller.state.value.fields?.name)
        assertEquals(1, source.statsCalls); assertEquals(1, source.photoCalls); assertEquals(0, avatars.calls)
        assertEquals(ProfileAvatarState.Empty, controller.state.value.avatar); assertFalse(controller.state.value.loading)
        assertFalse(controller.state.value.toString().contains("fixture-A"))
        controller.close()
    }

    @Test
    fun ownerTokenChangesAndLogoutSynchronouslyGateOldResultsAndClearState() = runTest {
        for (change in 0..2) {
            val sessions = MutableStateFlow<Session?>(session())
            val source = FakeProfiles(sessions); val avatars = FakeAvatars(); source.metadata = resource
            val controller = ProfileController(source, sessions, avatars, this)
            controller.refresh(); runCurrent()
            val old = assertIs<ProfileAvatarState.BytesReady>(controller.state.value.avatar).image
            sessions.value = when (change) { 0 -> session("fixture-B", "fixture-token-B"); 1 -> session(token = "new-fixture-token-A"); else -> null }
            assertNull(controller.visibleState().fields)
            runCurrent()
            assertNull(controller.state.value.fields); assertNull(controller.state.value.statistics)
            assertTrue(old.encoded.all { it == 0.toByte() })
            if (change != 2) {
                source.metadata = ProfilePhotoMetadata.Empty
                controller.refresh(); runCurrent()
                assertEquals(sessions.value?.userId, controller.state.value.fields?.name)
            } else {
                controller.refresh(); runCurrent(); assertFalse(controller.state.value.hasSession)
            }
            controller.close()
        }
    }

    @Test
    fun cancelWhileReadingPreventsLateFieldsAndNeverAutoRetries() = runTest {
        val sessions = MutableStateFlow<Session?>(session())
        val source = FakeProfiles(sessions); val avatars = FakeAvatars()
        val gate = CompletableDeferred<Unit>(); source.fieldsGate = gate
        val controller = ProfileController(source, sessions, avatars, this)
        controller.refresh(); runCurrent(); controller.cancel(); runCurrent()
        gate.complete(Unit); runCurrent()
        assertTrue(controller.state.value.cancelled); assertNull(controller.state.value.fields)
        assertEquals(1, source.fieldsCalls); assertEquals(0, source.statsCalls); assertEquals(0, source.photoCalls); assertEquals(0, avatars.calls)
        source.fieldsGate = null; controller.refresh(); runCurrent()
        assertEquals(2, source.fieldsCalls); assertNotNull(controller.state.value.fields)
        controller.close()
    }

    @Test
    fun lateAvatarThatIgnoresCancellationIsDiscardedAndItsBytesAreCleared() = runTest {
        val sessions = MutableStateFlow<Session?>(session())
        val source = FakeProfiles(sessions); source.metadata = resource
        var continuation: Continuation<AvatarLoadResult>? = null
        val avatars = object : AvatarSource {
            override suspend fun load(resource: ProfilePhotoMetadata.Resource): AvatarLoadResult = suspendCoroutine { continuation = it }
        }
        val controller = ProfileController(source, sessions, avatars, this)
        controller.refresh(); runCurrent(); assertNotNull(continuation)
        sessions.value = session("fixture-B", "fixture-token-B"); runCurrent()
        source.metadata = ProfilePhotoMetadata.Empty
        controller.refresh(); runCurrent(); assertEquals("fixture-B", controller.state.value.fields?.name)
        val late = AvatarBytes(profilePngHeader(), 64, 64, AvatarFormat.PNG)
        continuation!!.resume(AvatarLoadResult.Image(late)); runCurrent()
        assertTrue(late.encoded.all { it == 0.toByte() })
        assertEquals("fixture-B", controller.state.value.fields?.name); assertEquals(ProfileAvatarState.Empty, controller.state.value.avatar)
        controller.close()
    }

    @Test
    fun metadataOwnerChangeStopsBeforeAnyPublicAvatarGet() = runTest {
        val sessions = MutableStateFlow<Session?>(session())
        val source = FakeProfiles(sessions); source.metadata = resource
        source.onPhoto = { sessions.value = session("fixture-B", "fixture-token-B") }
        val avatars = FakeAvatars(); val controller = ProfileController(source, sessions, avatars, this)
        controller.refresh(); runCurrent()
        assertEquals(0, avatars.calls); assertNull(controller.visibleState().fields)
        assertEquals(ProfileAvatarState.Unrequested, controller.state.value.avatar)
        controller.close()
    }

    @Test
    fun refreshDecodeFailureAndDisposeOnlyClearTheMatchingGeneration() = runTest {
        val sessions = MutableStateFlow<Session?>(session())
        val source = FakeProfiles(sessions); source.metadata = resource
        val avatars = FakeAvatars(); val controller = ProfileController(source, sessions, avatars, this)
        controller.refresh(); runCurrent()
        val first = assertIs<ProfileAvatarState.BytesReady>(controller.state.value.avatar).image
        controller.refresh(); runCurrent()
        val second = assertIs<ProfileAvatarState.BytesReady>(controller.state.value.avatar).image
        assertTrue(first.encoded.all { it == 0.toByte() }); assertEquals(2, avatars.calls)
        controller.avatarDecodeFailed(first); assertSame(second, assertIs<ProfileAvatarState.BytesReady>(controller.state.value.avatar).image)
        controller.avatarDecodeFailed(second)
        assertEquals(ProfileAvatarState.Failed(AvatarIssue.DECODE), controller.state.value.avatar)
        assertTrue(second.encoded.all { it == 0.toByte() }); runCurrent(); assertEquals(2, avatars.calls)
        controller.refresh(); runCurrent(); val third = assertIs<ProfileAvatarState.BytesReady>(controller.state.value.avatar).image
        controller.close(); controller.close()
        assertTrue(third.encoded.all { it == 0.toByte() }); assertNull(controller.state.value.fields)
        controller.refresh(); runCurrent(); assertEquals(3, avatars.calls)
    }

    @Test
    fun fixedReadErrorsDoNotInventEmptyFieldsOrLeakBackendDetails() = runTest {
        val sessions = MutableStateFlow<Session?>(session())
        val source = FakeProfiles(sessions); source.fail = true
        val avatars = FakeAvatars(); val controller = ProfileController(source, sessions, avatars, this)
        controller.refresh(); runCurrent()
        assertNull(controller.state.value.fields); assertNull(controller.state.value.statistics)
        assertEquals(ProfileReadIssue.MALFORMED, controller.state.value.fieldsIssue)
        assertEquals(ProfileReadIssue.FAILED, controller.state.value.statisticsIssue)
        assertEquals(ProfileReadIssue.MALFORMED, controller.state.value.photoIssue)
        assertEquals(0, avatars.calls)
        controller.close()
    }
}
