package io.github.betterzslf.feature.booking

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class BookingQueryPresentationTest {
    @Test fun successfulReadsRetainServerDataWithoutAPersistentSuccessBanner() = runTest {
        val data = JsonPrimitive("venue-fixture")
        val query = BookingQuery { ApiResult.Success(data, "query accepted") }
        query.refresh()
        assertEquals(data, query.state.value.data)
        assertEquals("query accepted", query.state.value.message)
        assertEquals(BookingQueryFeedback.NONE, query.state.value.feedback)
    }

    @Test fun independentFitnessReadsDoNotCreateTwoSuccessBanners() = runTest {
        val sessions = BookingQuery { ApiResult.Success(JsonPrimitive("sessions"), "query accepted") }
        val registrations = BookingQuery { ApiResult.Success(JsonPrimitive("registrations"), "query accepted") }
        sessions.refresh(); registrations.refresh()
        assertEquals(listOf(BookingQueryFeedback.NONE, BookingQueryFeedback.NONE),
            listOf(sessions.state.value.feedback, registrations.state.value.feedback))
    }

    @Test fun loadingStillProducesProgressInsteadOfASuccessNotice() = runTest {
        val hold = CompletableDeferred<Unit>()
        val query = BookingQuery { hold.await(); ApiResult.Success(JsonPrimitive("ready"), "query accepted") }
        val refresh = launch { query.refresh() }
        testScheduler.runCurrent()
        assertEquals(BookingQueryFeedback.LOADING, query.state.value.feedback)
        hold.complete(Unit); refresh.join()
        assertEquals(BookingQueryFeedback.NONE, query.state.value.feedback)
    }

    @Test fun failedRefreshShowsFailureAndRetainsPreviousRowsNotThePreviousSuccessNotice() = runTest {
        var calls = 0
        val query = BookingQuery {
            if (++calls == 1) ApiResult.Success(JsonPrimitive("previous"), "query accepted")
            else ApiResult.Failure(ApiError.Network())
        }
        query.refresh(); query.refresh()
        assertEquals(JsonPrimitive("previous"), query.state.value.data)
        assertIs<ApiError.Network>(query.state.value.error)
        assertEquals(BookingQueryFeedback.ERROR, query.state.value.feedback)
        assertFalse(query.state.value.loading)
    }

    @Test fun anUnrequestedDateStaysIdleAndNeverInvokesTheLoader() = runTest {
        var calls = 0
        val query = BookingQuery { calls++; ApiResult.Failure(ApiError.Network()) }
        query.refreshIfReady(false)
        query.refreshIfReady(false)
        assertEquals(0, calls)
        assertEquals(BookingQueryState(), query.state.value)
        assertEquals(BookingQueryFeedback.NONE, query.state.value.feedback)
        assertNull(query.state.value.error)
    }

    @Test fun explicitDateRequestStartsOneReadAndAClosedGateDoesNotDiscardResults() = runTest {
        var calls = 0
        val query = BookingQuery { calls++; ApiResult.Success(JsonPrimitive("slots"), "query accepted") }
        query.refreshIfReady(false)
        assertEquals(0, calls)
        query.refreshIfReady(true)
        assertEquals(1, calls)
        val successful = query.state.value
        query.refreshIfReady(false)
        assertEquals(1, calls)
        assertEquals(successful, query.state.value)
    }

    @Test fun onlyAnAttemptedDateQueryCanProduceTheReadFailureState() = runTest {
        var calls = 0
        val query = BookingQuery { calls++; ApiResult.Failure(ApiError.Timeout()) }
        query.refreshIfReady(false)
        assertEquals(BookingQueryFeedback.NONE, query.state.value.feedback)
        query.refreshIfReady(true)
        assertEquals(1, calls)
        assertEquals(BookingQueryFeedback.ERROR, query.state.value.feedback)
        assertIs<ApiError.Timeout>(query.state.value.error)
    }

    @Test fun gatedRefreshKeepsTheExistingConcurrentTapCoalescing() = runTest {
        var calls = 0
        val hold = CompletableDeferred<Unit>()
        val query = BookingQuery { calls++; hold.await(); ApiResult.Success(JsonPrimitive("slots")) }
        val refresh = launch { query.refreshIfReady(true) }
        testScheduler.runCurrent()
        query.refreshIfReady(true)
        assertEquals(1, calls)
        hold.complete(Unit); refresh.join()
        assertEquals(BookingQueryFeedback.NONE, query.state.value.feedback)
    }
}
