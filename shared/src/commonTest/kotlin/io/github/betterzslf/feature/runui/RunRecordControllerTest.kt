package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Offline protocol fixtures only; no repository, HTTP client, real account or schedule lookup. */
class RunRecordControllerTest {
    private data class Request(val ownerId: String, val scheduleId: String, val offset: Int, val limit: Int)

    private fun row(id: String, title: String = "fixture-$id"): JsonObject = buildJsonObject {
        put("trid", id)
        put("eName", title)
    }

    private fun response(vararg rows: JsonElement): JsonObject = buildJsonObject {
        put("code", 200)
        put("data", buildJsonObject { put("userErrandTrackRecord", JsonArray(rows.toList())) })
    }

    private fun page(vararg ids: String): JsonObject = response(*ids.map { row(it) }.toTypedArray())
    private fun ids(controller: RunRecordController): List<String?> = controller.state.value.items.map { it.trid }
    private fun hasWarning(controller: RunRecordController, text: String) =
        assertTrue(controller.state.value.warnings.any { text in it }, controller.state.value.warnings.joinToString("\n"))

    @Test fun defaultPagingPassesExactOwnerScheduleOffsetAndLimitWithoutPageNumberConversion() = runTest {
        val calls = mutableListOf<Request>()
        val controller = RunRecordController(RunRecordPageSource { owner, schedule, offset, limit ->
            calls += Request(owner, schedule, offset, limit)
            ApiResult.Success(if (offset == 0) page(*(0 until 10).map { "r-$it" }.toTypedArray()) else page("last"))
        }, "fixture-owner", "fixture-real-schedule")
        assertEquals(RunRecordListState(), controller.state.value)
        assertTrue(calls.isEmpty())
        controller.refresh()
        assertFalse(controller.state.value.end)
        assertEquals(1, calls.size)
        controller.more(); controller.more()
        assertEquals(listOf(
            Request("fixture-owner", "fixture-real-schedule", 0, 10),
            Request("fixture-owner", "fixture-real-schedule", 10, 10),
        ), calls)
        assertEquals((0 until 10).map { "r-$it" } + "last", ids(controller))
        assertTrue(controller.state.value.end)
    }

    @Test fun documentedContainerPreservesRealTridMetersAndMillisecondsWithoutMagnitudeGuessing() = runTest {
        val known = buildJsonObject {
            put("trid", 73); put("eName", "Known units")
            put("runLength", 2.75); put("runTime", 1500)
        }
        val unknownUnits = buildJsonObject {
            put("trid", "other"); put("distance", 7); put("runTimeSeconds", 8)
        }
        val controller = RunRecordController(RunRecordPageSource { owner, schedule, offset, limit ->
            assertEquals(Request("owner", "real-plan", 0, 3), Request(owner, schedule, offset, limit))
            ApiResult.Success(response(known, unknownUnits))
        }, "owner", "real-plan", 3)
        controller.refresh()
        val first = controller.state.value.items.first()
        assertEquals("73", first.trid); assertEquals(known, first.raw)
        assertEquals(2.75, first.distanceMeters); assertEquals(1500L, first.elapsedMillis)
        assertNull(controller.state.value.items[1].distanceMeters)
        assertNull(controller.state.value.items[1].elapsedMillis)
        hasWarning(controller, "单位")
        assertTrue(controller.state.value.end)
    }

    @Test fun transportRawCountIncludesMalformedAndDuplicateRowsNotOnlyVisibleItems() = runTest {
        val offsets = mutableListOf<Int>()
        val first = response(row("a"), JsonNull, JsonPrimitive(17), row("a", "Newest a"))
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, limit ->
            assertEquals(4, limit); offsets += offset
            ApiResult.Success(if (offset == 0) first else page("b"))
        }, "owner", "plan", 4)
        controller.refresh()
        assertEquals(listOf("a"), ids(controller)); assertFalse(controller.state.value.end)
        assertEquals("Newest a", controller.state.value.items.single().title)
        controller.more()
        assertEquals(listOf(0, 4), offsets); assertEquals(listOf("a", "b"), ids(controller))
        assertTrue(controller.state.value.end)
        hasWarning(controller, "[1]"); hasWarning(controller, "[2]")
    }

    @Test fun overLimitResponseStillAdvancesByEntireRawSize() = runTest {
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, limit ->
            assertEquals(2, limit); offsets += offset
            ApiResult.Success(if (offset == 0) page("a", "b", "c") else page())
        }, "owner", "plan", 2)
        controller.refresh()
        assertFalse(controller.state.value.end); hasWarning(controller, "limit")
        controller.more()
        assertEquals(listOf(0, 3), offsets); assertEquals(listOf("a", "b", "c"), ids(controller))
        assertTrue(controller.state.value.end)
    }

    @Test fun realShortPageEndsAndFurtherMoreDoesNotLoad() = runTest {
        var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, _, _ ->
            calls++; ApiResult.Success(page("a", "b"))
        }, "owner", "plan", 3)
        controller.more(); repeat(3) { controller.more() }
        assertEquals(1, calls); assertEquals(listOf("a", "b"), ids(controller))
        assertTrue(controller.state.value.end)
        assertNull(controller.state.value.error); assertNull(controller.state.value.moreError)
    }

    @Test fun realEmptyArrayEndsAndSuccessfulRefreshClearsPreviousItems() = runTest {
        var empty = false
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset; ApiResult.Success(if (empty) page() else page("a", "b"))
        }, "owner", "plan", 2)
        controller.refresh(); empty = true; controller.refresh(); controller.more()
        assertEquals(listOf(0, 0), offsets)
        assertTrue(controller.state.value.items.isEmpty()); assertTrue(controller.state.value.end)
        assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.error); assertNull(controller.state.value.moreError)
    }

    @Test fun unknownInitialContainerIsNotEmptySuccessAndMoreRetriesZero() = runTest {
        var unknown = true
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            ApiResult.Success(if (unknown) buildJsonObject { put("list", JsonArray(emptyList())) } else page())
        }, "owner", "plan", 2)
        controller.refresh()
        assertEquals(listOf(0), offsets); assertFalse(controller.state.value.end)
        assertNotNull(controller.state.value.error); assertNull(controller.state.value.moreError)
        assertFalse(controller.state.value.loading); hasWarning(controller, "userErrandTrackRecord")
        unknown = false; controller.more()
        assertEquals(listOf(0, 0), offsets); assertTrue(controller.state.value.end)
        assertNull(controller.state.value.error); assertTrue(controller.state.value.items.isEmpty())
    }

    @Test fun invalidKnownContainersRemainRetryableInsteadOfBecomingEmptyPages() = runTest {
        listOf<JsonElement>(JsonNull, JsonObject(emptyMap()), JsonPrimitive("not-array"), JsonPrimitive("[broken")).forEach { invalid ->
            val offsets = mutableListOf<Int>(); var broken = true
            val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
                offsets += offset
                ApiResult.Success(if (broken) buildJsonObject {
                    put("data", buildJsonObject { put("userErrandTrackRecord", invalid) })
                } else page())
            }, "owner", "plan", 2)
            controller.refresh()
            assertFalse(controller.state.value.end); assertNotNull(controller.state.value.error)
            assertFalse(controller.state.value.loading); hasWarning(controller, "userErrandTrackRecord")
            broken = false; controller.more()
            assertEquals(listOf(0, 0), offsets); assertTrue(controller.state.value.end)
            assertNull(controller.state.value.error)
        }
    }

    @Test fun ignoredOffsetAndIdenticalFullPageStopBoundedlyWithExistingRows() = runTest {
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset; ApiResult.Success(page("a", "b"))
        }, "owner", "plan", 2)
        controller.refresh(); controller.more(); repeat(5) { controller.more() }
        assertEquals(listOf(0, 2), offsets); assertEquals(listOf("a", "b"), ids(controller))
        assertTrue(controller.state.value.end); hasWarning(controller, "offset"); hasWarning(controller, "停止")
        assertNull(controller.state.value.error); assertNull(controller.state.value.moreError)
    }

    @Test fun reorderedDuplicatePageStopsAndUpdatesContentsWithoutMovingExistingRows() = runTest {
        var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            calls++; ApiResult.Success(if (offset == 0) page("a", "b") else response(row("b", "New b"), row("a", "New a")))
        }, "owner", "plan", 2)
        controller.refresh(); controller.more(); controller.more()
        assertEquals(2, calls); assertEquals(listOf("a", "b"), ids(controller))
        assertEquals(listOf("New a", "New b"), controller.state.value.items.map { it.title })
        assertTrue(controller.state.value.end); hasWarning(controller, "停止")
    }

    @Test fun partiallyOverlappingPagesKeepRawOffsetAndAllowNextExplicitPage() = runTest {
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            ApiResult.Success(when (offset) { 0 -> page("a", "b"); 2 -> page("b", "c"); 4 -> page("d"); else -> error("unexpected offset") })
        }, "owner", "plan", 2)
        controller.refresh(); controller.more()
        assertEquals(listOf(0, 2), offsets); assertEquals(listOf("a", "b", "c"), ids(controller))
        assertFalse(controller.state.value.end)
        controller.more(); controller.more()
        assertEquals(listOf(0, 2, 4), offsets); assertEquals(listOf("a", "b", "c", "d"), ids(controller))
        assertTrue(controller.state.value.end)
    }

    @Test fun nonAdjacentAllSeenPageAlsoStopsEvenWhenItsPageOrderWasNeverSeen() = runTest {
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            ApiResult.Success(when (offset) { 0 -> page("a", "b"); 2 -> page("c", "d"); else -> page("b", "c") })
        }, "owner", "plan", 2)
        controller.refresh(); controller.more(); controller.more(); repeat(4) { controller.more() }
        assertEquals(listOf(0, 2, 4), offsets); assertEquals(listOf("a", "b", "c", "d"), ids(controller))
        assertTrue(controller.state.value.end); hasWarning(controller, "停止")
    }

    @Test fun fullMalformedPageStopsWithDiagnosticsInsteadOfDraining() = runTest {
        var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, _, _ ->
            calls++; ApiResult.Success(response(JsonNull, JsonPrimitive(17)))
        }, "owner", "plan", 2)
        controller.refresh(); controller.more()
        assertEquals(1, calls); assertTrue(controller.state.value.items.isEmpty())
        assertTrue(controller.state.value.end); hasWarning(controller, "没有可识别")
        assertNull(controller.state.value.error)
    }

    @Test fun anonymousRowsDeduplicateByStableRawContentDespiteObjectKeyOrder() = runTest {
        val a = Json.parseToJsonElement("""{"id":"display-a","routeid":"route","eName":"Same","extra":{"b":2,"a":1}}""")
        val b = Json.parseToJsonElement("""{"id":"display-b","routeid":"route","eName":"Same","extra":{"a":1,"b":2}}""")
        val reorderedA = Json.parseToJsonElement("""{"extra":{"a":1,"b":2},"eName":"Same","routeid":"route","id":"display-a"}""")
        val reorderedB = Json.parseToJsonElement("""{"extra":{"b":2,"a":1},"routeid":"route","id":"display-b","eName":"Same"}""")
        var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            calls++; ApiResult.Success(if (offset == 0) response(a, b) else response(reorderedB, reorderedA))
        }, "owner", "plan", 2)
        controller.refresh()
        assertEquals(2, controller.state.value.items.size); assertTrue(controller.state.value.items.all { it.trid == null })
        assertFalse(controller.state.value.end)
        controller.more(); controller.more()
        assertEquals(2, calls); assertEquals(2, controller.state.value.items.size)
        assertTrue(controller.state.value.items.all { it.trid == null })
        assertEquals(listOf("display-a", "display-b"), controller.state.value.items.map { it.raw["id"]?.jsonPrimitive?.content })
        assertTrue(controller.state.value.end); hasWarning(controller, "缺少可确认的记录编号")
    }

    @Test fun alternativeOrInvalidIdentifiersNeverBecomeServerTrid() = runTest {
        val fixture = Json.parseToJsonElement("""[
            {"id":"display","errandId":"task","routeid":"route","eName":"Absent"},
            {"trid":true,"id":"display-2","eName":"Boolean"},
            {"trid":1.5,"id":"display-3","eName":"Fractional"},
            {"trid":"undefined","id":"display-4","eName":"Undefined"},
            {"trid":" ","id":"display-5","eName":"Blank"}
        ]""") as JsonArray
        val controller = RunRecordController(RunRecordPageSource { _, _, _, _ -> ApiResult.Success(response(*fixture.toTypedArray())) }, "owner", "plan")
        controller.refresh()
        assertEquals(5, controller.state.value.items.size)
        assertTrue(controller.state.value.items.all { it.trid == null })
        assertEquals(listOf("Absent", "Boolean", "Fractional", "Undefined", "Blank"), controller.state.value.items.map { it.title })
        assertTrue(controller.state.value.end); hasWarning(controller, ".trid")
    }

    @Test fun longAnonymousRowsWithCommonPrefixStayDistinctAndNeverLeakLocalKeysIntoTrid() = runTest {
        val prefix = "x".repeat(32768)
        val a = buildJsonObject { put("eName", "Same"); put("id", "display-only"); put("payload", prefix + "A") }
        val b = buildJsonObject { put("eName", "Same"); put("id", "display-only"); put("payload", prefix + "B") }
        fun reverseFields(value: JsonObject) = JsonObject(value.entries.toList().reversed().associate { it.key to it.value })
        var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            calls++; ApiResult.Success(if (offset == 0) response(a, b) else response(reverseFields(b), reverseFields(a)))
        }, "owner", "plan", 2)
        controller.refresh(); assertEquals(2, controller.state.value.items.size)
        controller.more(); controller.more()
        assertEquals(2, calls); assertEquals(2, controller.state.value.items.size)
        assertTrue(controller.state.value.items.all { it.trid == null })
        assertEquals(listOf(prefix + "A", prefix + "B"), controller.state.value.items.map { it.raw.getValue("payload").jsonPrimitive.content })
        assertTrue(controller.state.value.end)
    }

    @Test fun anonymousRawArraysKeepOrderWhileObjectFieldOrderDoesNotMatter() = runTest {
        val a = Json.parseToJsonElement("""{"eName":"Same","extra":[1,2]}""")
        val b = Json.parseToJsonElement("""{"eName":"Same","extra":[2,1]}""")
        val aAgain = Json.parseToJsonElement("""{"extra":[1,2],"eName":"Same"}""")
        val bAgain = Json.parseToJsonElement("""{"extra":[2,1],"eName":"Same"}""")
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            ApiResult.Success(if (offset == 0) response(a, b) else response(bAgain, aAgain))
        }, "owner", "plan", 2)
        controller.refresh(); controller.more()
        assertEquals(2, controller.state.value.items.size)
        assertEquals(listOf(aAgain, bAgain), controller.state.value.items.map { it.raw })
        assertTrue(controller.state.value.items.all { it.trid == null }); assertTrue(controller.state.value.end)
    }

    @Test fun deeplyNestedRawExtrasUseIterativeStableKeysWithoutInventingIds() = runTest {
        var first: JsonElement = JsonPrimitive("leaf")
        var second: JsonElement = JsonPrimitive("leaf")
        repeat(128) { index ->
            val previousFirst = first; val previousSecond = second
            first = buildJsonObject { put("z", index); put("a", previousFirst) }
            second = buildJsonObject { put("a", previousSecond); put("z", index) }
        }
        val a = buildJsonObject { put("eName", "Anonymous"); put("extra", first) }
        val aAgain = buildJsonObject { put("extra", second); put("eName", "Anonymous") }
        var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            calls++; ApiResult.Success(response(if (offset == 0) a else aAgain))
        }, "owner", "plan", 1)
        controller.refresh(); controller.more(); controller.more()
        assertEquals(2, calls); assertEquals(1, controller.state.value.items.size)
        assertNull(controller.state.value.items.single().trid); assertTrue(controller.state.value.end)
    }

    @Test fun initialFailureRequiresExplicitRetryAndStartsAtOffsetZero() = runTest {
        var fail = true
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            if (fail) ApiResult.Failure(ApiError.Timeout("fixture timeout")) else ApiResult.Success(page("a"))
        }, "owner", "plan", 2)
        controller.refresh()
        assertEquals(listOf(0), offsets); assertEquals("fixture timeout", controller.state.value.error)
        assertNull(controller.state.value.moreError); assertFalse(controller.state.value.end)
        assertFalse(controller.state.value.loading); assertTrue(controller.state.value.items.isEmpty())
        fail = false; controller.more()
        assertEquals(listOf(0, 0), offsets); assertEquals(listOf("a"), ids(controller))
        assertNull(controller.state.value.error); assertTrue(controller.state.value.end)
    }

    @Test fun appendFailurePreservesItemsAndRetriesExactlyTheFailedRawOffset() = runTest {
        var fail = true
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            if (offset > 0 && fail) ApiResult.Failure(ApiError.Network("fixture append failure"))
            else ApiResult.Success(if (offset == 0) page("a", "b") else page("c"))
        }, "owner", "plan", 2)
        controller.refresh(); controller.more()
        assertEquals(listOf("a", "b"), ids(controller)); assertEquals(listOf(0, 2), offsets)
        assertNotNull(controller.state.value.moreError); assertNull(controller.state.value.error)
        assertFalse(controller.state.value.end); assertFalse(controller.state.value.loading)
        fail = false; controller.more()
        assertEquals(listOf(0, 2, 2), offsets); assertEquals(listOf("a", "b", "c"), ids(controller))
        assertNull(controller.state.value.moreError); assertTrue(controller.state.value.end)
    }

    @Test fun failedRefreshAfterEndKeepsOldItemsAndMoreRetriesZeroInsteadOfHonoringOldEnd() = runTest {
        var round = 0
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            when (round) {
                0 -> ApiResult.Success(page("old"))
                1 -> ApiResult.Failure(ApiError.Http(503))
                else -> ApiResult.Success(if (offset == 0) page("new-a", "new-b") else page("new-c"))
            }
        }, "owner", "plan", 2)
        controller.refresh(); assertTrue(controller.state.value.end)
        round = 1; controller.refresh()
        assertEquals(listOf("old"), ids(controller)); assertNotNull(controller.state.value.error)
        assertNull(controller.state.value.moreError); assertFalse(controller.state.value.end)
        round = 2; controller.more()
        assertEquals(listOf(0, 0, 0), offsets); assertEquals(listOf("new-a", "new-b"), ids(controller))
        assertNull(controller.state.value.error); assertFalse(controller.state.value.end)
        controller.more()
        assertEquals(listOf(0, 0, 0, 2), offsets); assertTrue(controller.state.value.end)
    }

    @Test fun failedRefreshAfterSeveralPagesKeepsOldDataUntilOffsetZeroRetryReplacesIt() = runTest {
        var round = 0
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            when (round) {
                0 -> ApiResult.Success(if (offset == 0) page("a", "b") else page("c", "d"))
                1 -> ApiResult.Failure(ApiError.Timeout())
                else -> ApiResult.Success(if (offset == 0) page("x", "y") else page("z"))
            }
        }, "owner", "plan", 2)
        controller.refresh(); controller.more()
        assertEquals(listOf("a", "b", "c", "d"), ids(controller))
        round = 1; controller.refresh()
        assertEquals(listOf("a", "b", "c", "d"), ids(controller)); assertNotNull(controller.state.value.error)
        round = 2; controller.more(); controller.more()
        assertEquals(listOf(0, 2, 0, 0, 2), offsets)
        assertEquals(listOf("x", "y", "z"), ids(controller)); assertTrue(controller.state.value.end)
        assertNull(controller.state.value.error); assertNull(controller.state.value.moreError)
    }

    @Test fun malformedAppendPreservesItemsAndRetriesTheSameOffset() = runTest {
        var malformed = true
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            ApiResult.Success(when {
                offset == 0 -> page("a", "b")
                malformed -> buildJsonObject { put("userErrandTrackRecord", "not-array") }
                else -> page("c")
            })
        }, "owner", "plan", 2)
        controller.refresh(); controller.more()
        assertEquals(listOf("a", "b"), ids(controller)); assertNotNull(controller.state.value.moreError)
        assertNull(controller.state.value.error); assertFalse(controller.state.value.end)
        hasWarning(controller, "userErrandTrackRecord")
        malformed = false; controller.more()
        assertEquals(listOf(0, 2, 2), offsets); assertEquals(listOf("a", "b", "c"), ids(controller))
        assertNull(controller.state.value.moreError); assertTrue(controller.state.value.end)
    }

    @Test fun malformedRefreshRetainsOldItemsAndMakesMoreRetryZero() = runTest {
        var round = 0
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            ApiResult.Success(when (round) {
                0 -> page("a", "b")
                1 -> buildJsonObject { put("unexpected", JsonArray(emptyList())) }
                else -> page("new")
            })
        }, "owner", "plan", 2)
        controller.refresh(); round = 1; controller.refresh()
        assertEquals(listOf("a", "b"), ids(controller)); assertNotNull(controller.state.value.error)
        assertNull(controller.state.value.moreError); assertFalse(controller.state.value.end)
        round = 2; controller.more()
        assertEquals(listOf(0, 0, 0), offsets); assertEquals(listOf("new"), ids(controller))
        assertNull(controller.state.value.error); assertTrue(controller.state.value.end)
        assertTrue(controller.state.value.warnings.isEmpty())
    }

    @Test fun refreshResetsDedupeAndEndStateEvenIfItsFirstPageContainsPreviouslySeenIds() = runTest {
        var round = 0
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            ApiResult.Success(if (round == 0 || offset == 0) page("a", "b") else page("c"))
        }, "owner", "plan", 2)
        controller.refresh(); controller.more()
        assertTrue(controller.state.value.end); hasWarning(controller, "停止")
        round = 1; controller.refresh()
        assertFalse(controller.state.value.end); assertTrue(controller.state.value.warnings.isEmpty())
        assertEquals(listOf("a", "b"), ids(controller))
        controller.more()
        assertEquals(listOf(0, 2, 0, 2), offsets); assertEquals(listOf("a", "b", "c"), ids(controller))
        assertTrue(controller.state.value.end)
    }

    @Test fun doubleInitialRefreshAndMoreTapsCoalesceWithoutQueuedLoads() = runTest {
        val started = CompletableDeferred<Unit>(); val reply = CompletableDeferred<Unit>(); var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, _, _ ->
            calls++; started.complete(Unit); reply.await(); ApiResult.Success(page("a", "b"))
        }, "owner", "plan", 2)
        val pending = async { controller.refresh() }; started.await()
        controller.refresh(); controller.more(); controller.refresh()
        assertEquals(1, calls); assertTrue(controller.state.value.loading)
        reply.complete(Unit); pending.await()
        assertEquals(1, calls); assertEquals(listOf("a", "b"), ids(controller))
        assertFalse(controller.state.value.loading); assertFalse(controller.state.value.end)
    }

    @Test fun doubleAppendAndRefreshCoalesceWithoutDeferringAnOffsetZeroLoad() = runTest {
        val started = CompletableDeferred<Unit>(); val reply = CompletableDeferred<Unit>()
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            if (offset == 2) { started.complete(Unit); reply.await() }
            ApiResult.Success(when (offset) { 0 -> page("a", "b"); 2 -> page("c", "d"); else -> page("e") })
        }, "owner", "plan", 2)
        controller.refresh()
        val pending = async { controller.more() }; started.await()
        controller.more(); controller.refresh(); controller.more()
        assertEquals(listOf(0, 2), offsets); assertTrue(controller.state.value.loading)
        reply.complete(Unit); pending.await()
        assertEquals(listOf(0, 2), offsets); assertFalse(controller.state.value.loading)
        controller.more()
        assertEquals(listOf(0, 2, 4), offsets); assertEquals(listOf("a", "b", "c", "d", "e"), ids(controller))
        assertTrue(controller.state.value.end)
    }

    @Test fun initialCancellationClearsLoadingAndMoreCanExplicitlyRetryZero() = runTest {
        val started = CompletableDeferred<Unit>(); val offsets = mutableListOf<Int>(); var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            if (++calls == 1) { started.complete(Unit); awaitCancellation() }
            ApiResult.Success(page("a"))
        }, "owner", "plan", 2)
        val pending = async { controller.refresh() }; started.await(); pending.cancelAndJoin()
        assertEquals(listOf(0), offsets); assertFalse(controller.state.value.loading)
        assertTrue(controller.state.value.items.isEmpty()); assertFalse(controller.state.value.end)
        controller.more()
        assertEquals(listOf(0, 0), offsets); assertEquals(listOf("a"), ids(controller))
        assertTrue(controller.state.value.end)
    }

    @Test fun appendCancellationKeepsItemsAndRetriesSameRawOffset() = runTest {
        val started = CompletableDeferred<Unit>(); val offsets = mutableListOf<Int>(); var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            if (++calls == 2) { started.complete(Unit); awaitCancellation() }
            ApiResult.Success(if (offset == 0) page("a", "b") else page("c"))
        }, "owner", "plan", 2)
        controller.refresh()
        val pending = async { controller.more() }; started.await(); pending.cancelAndJoin()
        assertEquals(listOf(0, 2), offsets); assertEquals(listOf("a", "b"), ids(controller))
        assertFalse(controller.state.value.loading); assertFalse(controller.state.value.end)
        controller.more()
        assertEquals(listOf(0, 2, 2), offsets); assertEquals(listOf("a", "b", "c"), ids(controller))
        assertTrue(controller.state.value.end)
    }

    @Test fun refreshCancellationKeepsOldItemsAndMoreRetriesZeroEvenAfterOldEnd() = runTest {
        val started = CompletableDeferred<Unit>(); val offsets = mutableListOf<Int>(); var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            if (++calls == 2) { started.complete(Unit); awaitCancellation() }
            ApiResult.Success(if (calls == 1) page("old") else page("new"))
        }, "owner", "plan", 2)
        controller.refresh(); assertTrue(controller.state.value.end)
        val pending = async { controller.refresh() }; started.await(); pending.cancelAndJoin()
        assertEquals(listOf("old"), ids(controller)); assertFalse(controller.state.value.loading)
        assertFalse(controller.state.value.end); assertEquals(listOf(0, 0), offsets)
        controller.more()
        assertEquals(listOf(0, 0, 0), offsets); assertEquals(listOf("new"), ids(controller))
        assertTrue(controller.state.value.end)
    }

    @Test fun swallowedSourceCancellationStillNeverCommitsItsStaleResponse() = runTest {
        val started = CompletableDeferred<Unit>(); val offsets = mutableListOf<Int>(); var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            if (++calls == 1) {
                started.complete(Unit)
                try { awaitCancellation() } catch (_: CancellationException) { /* Deliberately hostile source fixture. */ }
                ApiResult.Success(page("stale"))
            } else ApiResult.Success(page("new"))
        }, "owner", "plan", 2)
        val pending = async { controller.refresh() }; started.await(); pending.cancelAndJoin()
        assertTrue(pending.isCancelled); assertFalse(controller.state.value.loading)
        assertTrue(controller.state.value.items.isEmpty()); assertEquals(listOf(0), offsets)
        controller.more()
        assertEquals(listOf(0, 0), offsets); assertEquals(listOf("new"), ids(controller))
        assertTrue(controller.state.value.end)
    }

    @Test fun sourceExceptionsAreSanitizedAndOnlyExplicitActionsRetry() = runTest {
        var fail = true
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            if (fail) error("secret-fixture-token in raw-response")
            ApiResult.Success(page("a"))
        }, "owner", "plan", 2)
        controller.refresh()
        assertEquals(listOf(0), offsets); assertNotNull(controller.state.value.error)
        assertFalse(controller.state.value.error.orEmpty().contains("secret-fixture-token"))
        assertFalse(controller.state.value.error.orEmpty().contains("raw-response"))
        assertFalse(controller.state.value.loading); assertFalse(controller.state.value.end)
        assertTrue(controller.state.value.warnings.isEmpty())
        fail = false; controller.refresh()
        assertEquals(listOf(0, 0), offsets); assertEquals(listOf("a"), ids(controller))
        assertNull(controller.state.value.error)
    }

    @Test fun missingOwnerOrScheduleNeverLoadsOrInventsABinding() = runTest {
        var calls = 0
        val source = RunRecordPageSource { _, _, _, _ -> calls++; ApiResult.Success(page()) }
        listOf("" to "plan", " \n" to "plan", "owner" to "", "owner" to " \t", "" to "").forEach { (owner, schedule) ->
            val controller = RunRecordController(source, owner, schedule)
            controller.refresh(); controller.more()
            assertNotNull(controller.state.value.error); assertNull(controller.state.value.moreError)
            assertTrue(controller.state.value.items.isEmpty()); assertFalse(controller.state.value.loading)
            assertFalse(controller.state.value.end)
        }
        assertEquals(0, calls)
    }

    @Test fun nonPositivePageSizeIsRejectedBeforeAnyLoad() {
        var calls = 0
        val source = RunRecordPageSource { _, _, _, _ -> calls++; ApiResult.Success(page()) }
        assertFailsWith<IllegalArgumentException> { RunRecordController(source, "owner", "plan", 0) }
        assertFailsWith<IllegalArgumentException> { RunRecordController(source, "owner", "plan", -1) }
        assertEquals(0, calls)
    }

    @Test fun noAutomaticLoadOrDrainOccursEvenWhenEveryPageIsFull() = runTest {
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, limit ->
            offsets += offset
            ApiResult.Success(page(*(offset until offset + limit).map { "r-$it" }.toTypedArray()))
        }, "owner", "plan", 2)
        assertTrue(offsets.isEmpty()); assertEquals(RunRecordListState(), controller.state.value)
        controller.refresh(); assertEquals(listOf(0), offsets)
        assertFalse(controller.state.value.end); assertFalse(controller.state.value.loading)
        controller.more(); assertEquals(listOf(0, 2), offsets)
        assertEquals(listOf("r-0", "r-1", "r-2", "r-3"), ids(controller))
        assertFalse(controller.state.value.end); assertFalse(controller.state.value.loading)
    }

    @Test fun separateImmutableOwnerScheduleBindingsKeepLateResultsIsolated() = runTest {
        val started = CompletableDeferred<Unit>(); val reply = CompletableDeferred<Unit>()
        val calls = mutableListOf<Request>()
        val source = RunRecordPageSource { owner, schedule, offset, limit ->
            calls += Request(owner, schedule, offset, limit)
            if (owner == "owner-a") { started.complete(Unit); reply.await() }
            ApiResult.Success(if (owner == "owner-a") page("a") else page("b"))
        }
        val first = RunRecordController(source, "owner-a", "plan-a", 2)
        val second = RunRecordController(source, "owner-b", "plan-b", 2)
        val pending = async { first.refresh() }; started.await()
        second.refresh()
        assertEquals(listOf("b"), ids(second)); assertTrue(first.state.value.items.isEmpty())
        assertTrue(first.state.value.loading); assertFalse(second.state.value.loading)
        reply.complete(Unit); pending.await()
        assertEquals(listOf(Request("owner-a", "plan-a", 0, 2), Request("owner-b", "plan-b", 0, 2)), calls)
        assertEquals(listOf("a"), ids(first)); assertEquals(listOf("b"), ids(second))
        assertTrue(first.state.value.end); assertTrue(second.state.value.end)
    }
}
