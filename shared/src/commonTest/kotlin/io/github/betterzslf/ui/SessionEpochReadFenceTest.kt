@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.betterzslf.ui

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.*

class SessionEpochReadFenceTest {
    @Test fun currentReadKeepsTheExactResult() = runTest {
        val fence = SessionEpochReadFence(7, { 7 })
        val expected = ApiResult.Success("fixture")
        assertSame(expected, fence.read { expected })
        assertTrue(fence.isCurrent())
    }

    @Test fun changedEpochRejectsAdmissionBeforeDispatchEvenForAnEqualSessionValue() = runTest {
        var epoch = 7L
        var calls = 0
        val fence = SessionEpochReadFence(epoch, { epoch })
        epoch++ // SessionStore.save(sameValue) advances revision without a session-flow emission.
        val result = fence.read { calls++; ApiResult.Success("should not dispatch") }
        assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(result).error)
        assertEquals(0, calls)
    }

    @Test fun successfulLateResponseCannotPassTheCapturedEpochFence() = runTest {
        var epoch = 7L
        val gate = CompletableDeferred<ApiResult<String>>()
        val fence = SessionEpochReadFence(epoch, { epoch })
        val reading = async { fence.read { gate.await() } }
        runCurrent()
        epoch++
        gate.complete(ApiResult.Success("old user's result"))
        assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(reading.await()).error)
    }

    @Test fun bindingIsCheckedAfterAwaitNotOnlyBeforeTheRead() = runTest {
        var current = true
        val gate = CompletableDeferred<ApiResult<String>>()
        val fence = SessionEpochReadFence(7, { 7 }, { current })
        val reading = async { fence.read { gate.await() } }
        runCurrent()
        current = false
        gate.complete(ApiResult.Success("old bound result"))
        assertIs<ApiError.Authentication>(assertIs<ApiResult.Failure>(reading.await()).error)
        assertFalse(fence.isCurrent())
    }

    @Test fun closeRetiresBothAdmissionAndAnAlreadyPendingPublication() = runTest {
        val gate = CompletableDeferred<ApiResult<String>>()
        val fence = SessionEpochReadFence(7, { 7 })
        val reading = async { fence.read { gate.await() } }
        runCurrent()
        fence.close(); fence.close()
        gate.complete(ApiResult.Success("old route result"))
        assertIs<ApiResult.Failure>(reading.await())
        var calls = 0
        assertIs<ApiResult.Failure>(fence.read { calls++; ApiResult.Success("unreachable") })
        assertEquals(0, calls)
    }

    @Test fun throwingCapabilityFailsClosedWithoutDispatch() = runTest {
        val fence = SessionEpochReadFence(7, { 7 }, { error("fixture capability failure") })
        var calls = 0
        assertFalse(fence.isCurrent())
        assertIs<ApiResult.Failure>(fence.read { calls++; ApiResult.Success("unreachable") })
        assertEquals(0, calls)
    }

    @Test fun diagnosisCapabilityRejectsStaleResultByCancellationBeforeSharedPublication() = runTest {
        var epoch = 7L
        val gate = CompletableDeferred<String>()
        var sharedPublication: String? = null
        val fence = SessionEpochReadFence(epoch, { epoch })
        val reading = async {
            try { sharedPublication = fence.readCurrent { gate.await() } }
            catch (_: CancellationException) { }
        }
        runCurrent()
        epoch++
        gate.complete("old diagnosis")
        reading.await()
        assertNull(sharedPublication)
    }

    @Test fun diagnosisCapabilityDoesNotStartOnARetiredPage() = runTest {
        val fence = SessionEpochReadFence(7, { 7 })
        fence.close()
        var calls = 0
        assertFailsWith<CancellationException> { fence.readCurrent { calls++; "unreachable" } }
        assertEquals(0, calls)
    }

    @Test fun cancellationIsRecheckedEvenWhenTheReadIgnoredCancellation() = runTest {
        val gate = CompletableDeferred<ApiResult<String>>()
        val fence = SessionEpochReadFence(7, { 7 })
        var published = false
        val reading = async {
            fence.read { withContext(NonCancellable) { gate.await() } }
            published = true
        }
        runCurrent()
        reading.cancel()
        gate.complete(ApiResult.Success("late non-cancellable response"))
        reading.join()
        assertFalse(published)
        assertTrue(reading.isCancelled)
    }

    @Test fun sessionAbaCannotReviveTheOldEpochOrRetireANewFence() = runTest {
        var epoch = 7L
        val old = SessionEpochReadFence(epoch, { epoch })
        epoch++ // clear
        epoch++ // same account/token saved again
        val fresh = SessionEpochReadFence(epoch, { epoch })
        old.close()
        assertFalse(old.isCurrent())
        assertTrue(fresh.isCurrent())
        assertIs<ApiResult.Success<String>>(fresh.read { ApiResult.Success("fresh") })
    }
}
