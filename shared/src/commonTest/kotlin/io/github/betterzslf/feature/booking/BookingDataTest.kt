package io.github.betterzslf.feature.booking

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class BookingDataTest {
    private fun row(id: Int, status: String = "1") = buildJsonObject { put("id", "$id"); put("name", "项目$id"); put("status", status) }

    @Test fun routeRoundTripContainsIdentifiersAndDateOnly() {
        val value = BookingSelection("site", listOf("t1", "t2"), "test", listOf("i1"), "2030-02-15")
        assertEquals(value, BookingSelection.decode(value.encode()))
        assertEquals("plain", BookingSelection.decode("plain").siteId)
        assertEquals("plain", BookingSelection.decode("plain", physical = true).physicalId)
        assertEquals(BookingSelection(), BookingSelection.decode("{broken"))
        assertEquals(BookingSelection(), BookingSelection.decode("{\"bookingVersion\":9}"))
    }

    @Test fun all1489ProjectsRemainReachableWithLocalPaging() {
        val rows = (1..1489).map { row(it) }
        var window = BookingWindow()
        assertEquals(30, window.show(rows).size)
        while (window.show(rows).size < rows.size) window = window.more(rows.size)
        assertEquals(1489, window.show(rows).size)
        assertEquals("1489", window.show(rows).last().bookingId())
        assertEquals(1, window.search("项目1489").filtered(rows).size)
        assertEquals(30, window.search("new").visible)
    }

    @Test fun filtersAreLocalAndRawStatesAreNotReinterpreted() {
        val rows = listOf(row(1, "0"), row(2, "1"), row(3, "1"))
        assertEquals(listOf("2", "3"), BookingWindow(status = "1").filtered(rows).map { it.bookingId() })
        assertEquals(SlotAvailability.UNKNOWN, bookingAvailability(buildJsonObject { put("status", 1) }))
        assertEquals(SlotAvailability.FULL, bookingAvailability(buildJsonObject { put("isUse", 1) }))
        assertEquals(SlotAvailability.AVAILABLE, bookingAvailability(buildJsonObject { put("isUse", 0) }))
        assertEquals(SlotAvailability.FULL, bookingAvailability(buildJsonObject { put("status", "已约满") }))
    }

    @Test fun timeRowsDoNotOfferAlreadyAppliedRowsAsExtraSlots() {
        val data = buildJsonObject {
            put("placeTimeList", JsonArray(listOf(row(1))))
            put("applyTimeList", JsonArray(listOf(row(2))))
        }
        assertEquals(listOf("1"), bookingTimeRows(data).map { it.bookingId() })
    }

    @Test fun flattenedDetailsExcludeCredentialFieldsAtEveryLevel() {
        val data = Json.parseToJsonElement("""{"name":"fixture","accessToken":"not-for-ui","nested":{"old_password":"hidden","value":"shown"},"items":[{"id":"x"}]}""")
        val fields = bookingFields(data)
        assertEquals(setOf("fixture", "shown", "x"), fields.map { it.value }.toSet())
        assertTrue(fields.any { it.path == "items[1].id" })
    }

    @Test fun calendarValidationRejectsInvalidLeapDaysAndKeepsWeekdayStable() {
        assertTrue(validBookingDate("2032-02-29"))
        assertFalse(validBookingDate("2031-02-29"))
        assertFalse(validBookingDate("2030-04-31"))
        assertFalse(validBookingDate("2030-4-01"))
        assertEquals(4, bookingWeekday("1970-01-01"))
        assertEquals(-28_800_000L, bookingSchoolMidnight("1970-01-01"))
    }

    @Test fun scheduleUsesExactSexPeriodCapacityFields() {
        val test = Json.parseToJsonElement("""{"testStart":"2030-01-01","testEnd":"2030-12-31","showDay":"0,1,2,3,4,5,6","mLimitBoy":2,"aLimitBoy":4,"mLimitGirls":5,"aLimitGirls":6}""")
        val counts = Json.parseToJsonElement("""{"2030-01-10":{"mlbCount":2,"albCount":0,"mlgCount":0,"algCount":0}}""")
        assertTrue(fitnessScheduleValidation("2030-01-10", "男", "上午", test, counts).isNotEmpty())
        assertTrue(fitnessScheduleValidation("2030-01-10", "男", "下午", test, counts).isEmpty())
        assertTrue(fitnessScheduleValidation("2031-01-01", "男", "下午", test, counts).isNotEmpty())
    }

    @Test fun cancellationMatchesFloorHoursGreaterThanFiveBoundary() {
        val test = Json.parseToJsonElement("""{"userStatus":1,"userPeriod":0,"userPhysicalTime":"2030-01-10 00:00:00"}""")
        val scheduled = bookingSchoolMidnight("2030-01-10")!! + 8 * 3_600_000L
        assertNull(fitnessCancellationTiming(test, scheduled - 6 * 3_600_000L))
        assertNotNull(fitnessCancellationTiming(test, scheduled - 5 * 3_600_000L - 59 * 60_000L))
    }

    @Test fun refreshCoalescesConcurrentTapsAndRetainsPreviousDataOnFailure() = runTest {
        var calls = 0
        val hold = CompletableDeferred<Unit>()
        val query = BookingQuery {
            calls++
            when (calls) {
                1 -> { hold.await(); ApiResult.Success(JsonPrimitive("first")) }
                else -> ApiResult.Failure(ApiError.Network())
            }
        }
        val first = launch { query.refresh() }
        testScheduler.runCurrent()
        query.refresh()
        assertEquals(1, calls)
        hold.complete(Unit); first.join()
        query.refresh()
        assertEquals(JsonPrimitive("first"), query.state.value.data)
        assertIs<ApiError.Network>(query.state.value.error)
        assertFalse(query.state.value.loading)
    }

    @Test fun remotePagingUsesRealOffsetAndDeduplicatesAnIgnoredCursor() = runTest {
        val offsets = mutableListOf<Int>()
        val query = BookingPagedQuery("applyList") { offset, limit ->
            offsets += offset; assertEquals(10, limit)
            ApiResult.Success(buildJsonObject { put("applyList", JsonArray((0..9).map { row(it) })) })
        }
        query.refresh(); query.more(); query.more()
        assertEquals(listOf(0, 10), offsets)
        assertTrue(query.state.value.end)
        assertEquals(10, bookingRows(query.state.value.query.data!!, "applyList").size)
    }

    @Test fun failedNextPageRetainsRowsAndRequiresExplicitRetry() = runTest {
        var calls = 0
        val query = BookingPagedQuery("physicalTestList") { _, _ ->
            if (++calls == 1) ApiResult.Success(buildJsonObject { put("physicalTestList", JsonArray((1..10).map { row(it) })) })
            else ApiResult.Failure(ApiError.Timeout())
        }
        query.refresh(); query.more()
        assertEquals(10, bookingRows(query.state.value.query.data!!, "physicalTestList").size)
        assertNotNull(query.state.value.moreError)
        assertEquals(2, calls)
    }
}
