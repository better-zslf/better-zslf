package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class RunTaskControllerTest {
    private data class Request(val owner: String, val offset: Int, val limit: Int, val finish: String)
    private fun page(vararg ids: String, finish: String? = null, serverTrid: Boolean = true): JsonObject = buildJsonObject {
        put("errandList", JsonArray(ids.map { id -> buildJsonObject {
            if (serverTrid) put("trid", id)
            put("errandId", "errand-$id"); put("errandScheduleId", "plan"); put("eName", "fixture-$id")
            if (finish != null) put("isFinish", finish)
        } }))
    }
    private fun ids(controller: RunTaskController): List<String?> = controller.state.value.items.map { it.trid }
    private fun errandIds(controller: RunTaskController): List<String?> = controller.state.value.items.map { it.errandId }

    @Test fun allUsesOnlyZeroAndOneAndMergesDuplicatesAcrossBranches() = runTest {
        val calls = mutableListOf<Request>()
        val controller = RunTaskController(RunTaskPageSource { owner, offset, limit, finish ->
            calls += Request(owner, offset, limit, finish)
            ApiResult.Success(if (finish == "0") page("shared", "a", finish = "0") else page("shared", "b", finish = "1"))
        }, "fixture-owner", 2)
        controller.refresh()
        assertEquals(listOf(Request("fixture-owner", 0, 2, "0"), Request("fixture-owner", 0, 2, "1")), calls)
        assertEquals(listOf("shared", "a", "b"), ids(controller))
        assertEquals(listOf("errand-shared", "errand-a", "errand-b"), errandIds(controller))
        assertTrue(controller.state.value.items.all { it.scheduleId == "plan" })
        assertEquals("1", controller.state.value.items.first().isFinish)
        assertFalse(controller.state.value.end); assertFalse(controller.state.value.loading)
    }

    @Test fun missingServerTridDoesNotBlockPagingOrCrossBranchDeduplication() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, finish ->
            calls += finish to offset
            ApiResult.Success(when (finish to offset) {
                "0" to 0 -> page("shared", "a", finish = "0", serverTrid = false)
                "1" to 0 -> page("shared", "b", finish = "1", serverTrid = false)
                "0" to 2 -> page("c", finish = "0", serverTrid = false)
                "1" to 2 -> page(serverTrid = false)
                else -> error("unexpected request")
            })
        }, "owner", 2)
        controller.refresh()
        assertEquals(listOf("errand-shared", "errand-a", "errand-b"), errandIds(controller))
        assertTrue(controller.state.value.items.all { it.trid == null && it.scheduleId == "plan" })
        assertEquals("1", controller.state.value.items.first().isFinish)
        assertFalse(controller.state.value.end); assertTrue(controller.state.value.warnings.isEmpty())
        controller.more(); controller.more()
        assertEquals(listOf("0" to 0, "1" to 0, "0" to 2, "1" to 2), calls)
        assertEquals(listOf("errand-shared", "errand-a", "errand-c", "errand-b"), errandIds(controller))
        assertTrue(controller.state.value.items.all { it.trid == null })
        assertTrue(controller.state.value.end); assertFalse(controller.state.value.loading)
        assertNull(controller.state.value.error); assertNull(controller.state.value.moreError)
    }

    @Test fun allBranchesHaveIndependentRawOffsetsAndEndFlags() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, finish ->
            calls += finish to offset
            ApiResult.Success(when (finish to offset) {
                "0" to 0 -> page("a", "b")
                "1" to 0 -> page("b", "e")
                "0" to 2 -> page("b", "c")
                "1" to 2 -> page()
                "0" to 4 -> page("d")
                else -> error("unexpected request")
            })
        }, "owner", 2)
        controller.refresh(); controller.more(); controller.more(); controller.more()
        assertEquals(listOf("0" to 0, "1" to 0, "0" to 2, "1" to 2, "0" to 4), calls)
        assertEquals(setOf("a", "b", "c", "d", "e"), ids(controller).toSet())
        assertTrue(controller.state.value.end)
    }

    @Test fun crossBranchDuplicatesDoNotFalselyStopTheOtherBranchFirstPage() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, finish ->
            calls += finish to offset
            ApiResult.Success(if (offset == 0) page("a", "b") else if (finish == "1") page("c") else page())
        }, "owner", 2)
        controller.refresh(); controller.more()
        assertEquals(listOf("0" to 0, "1" to 0, "0" to 2, "1" to 2), calls)
        assertEquals(listOf("a", "b", "c"), ids(controller)); assertTrue(controller.state.value.end)
    }

    @Test fun singleFiltersUseRealServerPagingAndShortRawPageEndsTheList() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val controller = RunTaskController(RunTaskPageSource { _, offset, limit, finish ->
            assertEquals(2, limit); calls += finish to offset
            ApiResult.Success(when (offset) { 0 -> page("a", "b"); 2 -> page("b", "c"); else -> page("d") })
        }, "owner", 2)
        controller.select(RunTaskFilter.UNFINISHED); controller.more(); controller.more(); controller.more()
        assertEquals(listOf("0" to 0, "0" to 2, "0" to 4), calls)
        assertEquals(listOf("a", "b", "c", "d"), ids(controller)); assertTrue(controller.state.value.end)
        controller.select(RunTaskFilter.FINISHED)
        assertEquals("1" to 0, calls.last()); assertEquals(listOf("a", "b"), ids(controller))
    }

    @Test fun rawPageSizeCountsDuplicateAndMalformedRowsNotOnlyDisplayedTasks() = runTest {
        val offsets = mutableListOf<Int>()
        val first = Json.parseToJsonElement("""{"errandList":[{"trid":"a","errandId":"errand-a","scheduleId":"s"},null,{"trid":"a","errandId":"errand-a","scheduleId":"s"}]}""")
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, _ ->
            offsets += offset
            ApiResult.Success(if (offset == 0) first else page("b"))
        }, "owner", 3)
        controller.select(RunTaskFilter.UNFINISHED)
        assertFalse(controller.state.value.end); assertEquals(listOf("a"), ids(controller))
        controller.more()
        assertEquals(listOf(0, 3), offsets); assertEquals(listOf("a", "b"), ids(controller))
        assertTrue(controller.state.value.end); assertTrue(controller.state.value.warnings.any { "[1]" in it })
    }

    @Test fun repeatedOrReorderedFullPageStopsExplicitlyInsteadOfLooping() = runTest {
        var calls = 0
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, _ ->
            calls++; ApiResult.Success(if (offset == 0) page("a", "b") else page("b", "a"))
        }, "owner", 2)
        controller.select(RunTaskFilter.UNFINISHED); controller.more(); repeat(4) { controller.more() }
        assertEquals(2, calls); assertEquals(listOf("a", "b"), ids(controller))
        assertTrue(controller.state.value.end)
        assertTrue(controller.state.value.warnings.any { "offset" in it && "停止" in it })
    }

    @Test fun successfulEmptyPageIsEndButUnknownShapeIsARetryableError() = runTest {
        var unknown = true
        val controller = RunTaskController(RunTaskPageSource { _, _, _, _ ->
            ApiResult.Success(if (unknown) buildJsonObject { put("unexpected", JsonArray(emptyList())) } else page())
        }, "owner", 2)
        controller.select(RunTaskFilter.UNFINISHED)
        assertFalse(controller.state.value.end); assertNotNull(controller.state.value.error)
        assertTrue(controller.state.value.warnings.isNotEmpty())
        unknown = false; controller.refresh()
        assertTrue(controller.state.value.end); assertNull(controller.state.value.error)
        assertTrue(controller.state.value.items.isEmpty())
    }

    @Test fun fullPageWithNoUsableRowsStopsWithDiagnostics() = runTest {
        var calls = 0
        val controller = RunTaskController(RunTaskPageSource { _, _, _, _ ->
            calls++; ApiResult.Success(Json.parseToJsonElement("{\"errandList\":[null,17]}"))
        }, "owner", 2)
        controller.select(RunTaskFilter.UNFINISHED); controller.more()
        assertEquals(1, calls); assertTrue(controller.state.value.end)
        assertTrue(controller.state.value.warnings.any { "没有可识别" in it })
    }

    @Test fun appendFailurePreservesVisibleRowsAndRetriesExactlyTheFailedOffset() = runTest {
        val offsets = mutableListOf<Int>(); var fail = true
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, _ ->
            offsets += offset
            if (offset > 0 && fail) ApiResult.Failure(ApiError.Network("fixture append failure"))
            else ApiResult.Success(if (offset == 0) page("a", "b") else page("c"))
        }, "owner", 2)
        controller.select(RunTaskFilter.UNFINISHED); controller.more()
        assertEquals(listOf("a", "b"), ids(controller)); assertNotNull(controller.state.value.moreError)
        assertNull(controller.state.value.error); assertFalse(controller.state.value.end)
        fail = false; controller.more()
        assertEquals(listOf(0, 2, 2), offsets); assertEquals(listOf("a", "b", "c"), ids(controller))
        assertNull(controller.state.value.moreError); assertTrue(controller.state.value.end)
    }

    @Test fun failedRefreshKeepsRowsAndMoreRetriesOffsetZeroInsteadOfSkippingTheRefresh() = runTest {
        val offsets = mutableListOf<Int>(); var fail = false; var replacement = false
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, _ ->
            offsets += offset
            if (fail) ApiResult.Failure(ApiError.Timeout("fixture refresh timeout"))
            else ApiResult.Success(if (replacement) page("new") else page("a", "b"))
        }, "owner", 2)
        controller.select(RunTaskFilter.UNFINISHED); fail = true; controller.refresh()
        assertEquals(listOf("a", "b"), ids(controller)); assertNotNull(controller.state.value.error)
        assertNull(controller.state.value.moreError); assertFalse(controller.state.value.loading)
        fail = false; replacement = true; controller.more()
        assertEquals(listOf(0, 0, 0), offsets); assertEquals(listOf("new"), ids(controller))
        assertNull(controller.state.value.error); assertTrue(controller.state.value.end)
    }

    @Test fun partialAllFailureRetainsSuccessfulBranchAndOnlyRetriesFailedBranch() = runTest {
        val calls = mutableListOf<Pair<String, Int>>(); var failFinished = true
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, finish ->
            calls += finish to offset
            if (finish == "1" && failFinished) ApiResult.Failure(ApiError.Network())
            else ApiResult.Success(if (finish == "0") page("a") else page("b"))
        }, "owner", 2)
        controller.refresh()
        assertEquals(listOf("a"), ids(controller)); assertNotNull(controller.state.value.error)
        assertFalse(controller.state.value.end)
        failFinished = false; controller.more()
        assertEquals(listOf("0" to 0, "1" to 0, "1" to 0), calls)
        assertEquals(listOf("a", "b"), ids(controller)); assertTrue(controller.state.value.end)
        assertNull(controller.state.value.error)
    }

    @Test fun failedAllRefreshPreservesThatBranchOldRowsWithoutDiscardingOtherBranchSuccess() = runTest {
        var round = 0
        val controller = RunTaskController(RunTaskPageSource { _, _, _, finish ->
            when {
                round == 1 && finish == "1" -> ApiResult.Failure(ApiError.Http(503))
                round == 0 -> ApiResult.Success(if (finish == "0") page("a") else page("b"))
                else -> ApiResult.Success(if (finish == "0") page("c") else page("d"))
            }
        }, "owner", 2)
        controller.refresh(); round = 1; controller.refresh()
        assertEquals(listOf("c", "b"), ids(controller)); assertNotNull(controller.state.value.error)
        round = 2; controller.more()
        assertEquals(listOf("c", "d"), ids(controller)); assertNull(controller.state.value.error)
    }

    @Test fun malformedAppendKeepsDataAndDoesNotAdvanceItsOffset() = runTest {
        val offsets = mutableListOf<Int>(); var malformed = true
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, _ ->
            offsets += offset
            ApiResult.Success(when {
                offset == 0 -> page("a", "b")
                malformed -> buildJsonObject { put("errandList", "not-array") }
                else -> page("c")
            })
        }, "owner", 2)
        controller.select(RunTaskFilter.UNFINISHED); controller.more()
        assertEquals(listOf("a", "b"), ids(controller)); assertNotNull(controller.state.value.moreError)
        malformed = false; controller.more()
        assertEquals(listOf(0, 2, 2), offsets); assertEquals(listOf("a", "b", "c"), ids(controller))
    }

    @Test fun doubleInitialRefreshSelectAndMoreTapsDoNotDuplicateTheSameLoad() = runTest {
        val started = CompletableDeferred<Unit>(); val reply = CompletableDeferred<Unit>(); var calls = 0
        val controller = RunTaskController(RunTaskPageSource { _, _, _, _ ->
            calls++; started.complete(Unit); reply.await(); ApiResult.Success(page("a"))
        }, "owner", 2)
        val pending = async { controller.select(RunTaskFilter.UNFINISHED) }
        started.await(); controller.refresh(); controller.more(); controller.select(RunTaskFilter.UNFINISHED)
        assertEquals(1, calls); assertTrue(controller.state.value.loading)
        reply.complete(Unit); pending.await()
        assertFalse(controller.state.value.loading); assertEquals(listOf("a"), ids(controller))
    }

    @Test fun doubleMoreTapsCoalesceWhileThePageIsInFlight() = runTest {
        val started = CompletableDeferred<Unit>(); val reply = CompletableDeferred<Unit>(); var calls = 0
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, _ ->
            calls++
            if (offset > 0) { started.complete(Unit); reply.await() }
            ApiResult.Success(if (offset == 0) page("a", "b") else page("c"))
        }, "owner", 2)
        controller.select(RunTaskFilter.UNFINISHED)
        val pending = async { controller.more() }; started.await()
        controller.more(); assertEquals(2, calls)
        reply.complete(Unit); pending.await()
        assertEquals(listOf("a", "b", "c"), ids(controller))
    }

    @Test fun filterChangeFencesStaleAllResponseAndSkipsItsRemainingBranch() = runTest {
        val started = CompletableDeferred<Unit>(); val oldReply = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val controller = RunTaskController(RunTaskPageSource { _, _, _, finish ->
            calls += finish
            if (finish == "0") { started.complete(Unit); oldReply.await(); ApiResult.Success(page("old")) }
            else ApiResult.Success(page("new"))
        }, "owner", 2)
        val old = async { controller.refresh() }; started.await()
        controller.select(RunTaskFilter.FINISHED)
        assertEquals(listOf("new"), ids(controller)); assertEquals(RunTaskFilter.FINISHED, controller.state.value.filter)
        oldReply.complete(Unit); old.await()
        assertEquals(listOf("0", "1"), calls)
        assertEquals(listOf("new"), ids(controller)); assertFalse(controller.state.value.loading)
    }

    @Test fun cancellationClearsLoadingAndAllowsExplicitOffsetZeroRetry() = runTest {
        val started = CompletableDeferred<Unit>(); val offsets = mutableListOf<Int>(); var calls = 0
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, _ ->
            offsets += offset
            if (++calls == 1) { started.complete(Unit); awaitCancellation() }
            ApiResult.Success(page("a"))
        }, "owner", 2)
        val pending = async { controller.select(RunTaskFilter.UNFINISHED) }
        started.await(); pending.cancelAndJoin()
        assertFalse(controller.state.value.loading)
        controller.refresh()
        assertEquals(listOf(0, 0), offsets); assertEquals(listOf("a"), ids(controller))
    }

    @Test fun sourceExceptionsAreRetryableAndNeverExposeTheExceptionMessage() = runTest {
        var fail = true
        val controller = RunTaskController(RunTaskPageSource { _, _, _, _ ->
            if (fail) error("secret-fixture-token")
            ApiResult.Success(page("a"))
        }, "owner", 2)
        controller.select(RunTaskFilter.UNFINISHED)
        assertNotNull(controller.state.value.error)
        assertFalse(controller.state.value.error.orEmpty().contains("secret-fixture-token"))
        assertFalse(controller.state.value.loading)
        fail = false; controller.refresh()
        assertNull(controller.state.value.error); assertEquals(listOf("a"), ids(controller))
    }

    @Test fun noRequestIsMadeForMissingOwnerOrNonPositiveLimit() = runTest {
        var calls = 0
        val source = RunTaskPageSource { _, _, _, _ -> calls++; ApiResult.Success(page()) }
        val controller = RunTaskController(source, " ")
        controller.refresh(); controller.more()
        assertEquals(0, calls); assertNotNull(controller.state.value.error); assertFalse(controller.state.value.loading)
        assertFailsWith<IllegalArgumentException> { RunTaskController(source, "owner", 0) }
        assertFailsWith<IllegalArgumentException> { RunTaskController(source, "owner", -1) }
    }

    @Test fun constructionDoesNotLoadAndActionsNeverAutoDrainFullPages() = runTest {
        var calls = 0
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, finish ->
            calls++; ApiResult.Success(page("$finish-$offset", "$finish-${offset + 1}"))
        }, "owner", 2)
        assertEquals(0, calls)
        controller.refresh(); assertEquals(2, calls)
        controller.more(); assertEquals(4, calls)
        assertFalse(controller.state.value.end)
    }
}
