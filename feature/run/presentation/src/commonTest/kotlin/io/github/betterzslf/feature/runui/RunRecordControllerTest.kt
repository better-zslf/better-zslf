package io.github.betterzslf.feature.runui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Already-decoded paging fixtures; wire/identity proofs are in the data integration tests. */
class RunRecordControllerTest {
    private data class Request(val ownerId: String, val scheduleId: String, val offset: Int, val limit: Int)

    private fun row(id: String, title: String = "fixture-$id") = RunRecordItem(
        trid = id, title = title, distanceMeters = null, elapsedMillis = null, status = null,
        points = emptyList(), safeDisplayFields = emptyList(), warnings = emptyList(), identityKey = "fixture-key:$id",
    )

    private fun response(vararg rows: RunRecordItem): RunRows<RunRecordItem> =
        RunRows(rows.toList(), recognized = true, warnings = emptyList())
    private fun page(vararg ids: String): RunRows<RunRecordItem> = response(*ids.map { row(it) }.toTypedArray())
    private fun ids(controller: RunRecordController): List<String?> = controller.state.value.items.map { it.trid }
    private fun hasWarning(controller: RunRecordController, text: String) =
        assertTrue(controller.state.value.warnings.any { text in it }, controller.state.value.warnings.joinToString("\n"))

    @Test fun defaultPagingPassesExactOwnerScheduleOffsetAndLimitWithoutPageNumberConversion() = runTest {
        val calls = mutableListOf<Request>()
        val controller = RunRecordController(RunRecordPageSource { owner, schedule, offset, limit ->
            calls += Request(owner, schedule, offset, limit)
            RunReadOutcome.Success(if (offset == 0) page(*(0 until 10).map { "r-$it" }.toTypedArray()) else page("last"))
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

    @Test fun transportRawCountIncludesMalformedAndDuplicateRowsNotOnlyVisibleItems() = runTest {
        val offsets = mutableListOf<Int>()
        val first = response(row("a"), row("a", "Newest a")).copy(rawSize = 4,
            warnings = listOf("userErrandTrackRecord[1]", "userErrandTrackRecord[2]"))
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, limit ->
            assertEquals(4, limit); offsets += offset
            RunReadOutcome.Success(if (offset == 0) first else page("b"))
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
            RunReadOutcome.Success(if (offset == 0) page("a", "b", "c") else page())
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
            calls++; RunReadOutcome.Success(page("a", "b"))
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
            offsets += offset; RunReadOutcome.Success(if (empty) page() else page("a", "b"))
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
            RunReadOutcome.Success(if (unknown) RunRows(emptyList(), recognized = false, warnings = listOf("userErrandTrackRecord")) else page())
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
        // Distinct unrecognized decoder diagnostics share the same explicit retry contract.
        listOf("missing", "object", "not-array", "broken").forEach { reason ->
            val offsets = mutableListOf<Int>(); var broken = true
            val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
                offsets += offset
                RunReadOutcome.Success(if (broken) RunRows(emptyList(), recognized = false,
                    warnings = listOf("userErrandTrackRecord: $reason")) else page())
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
            offsets += offset; RunReadOutcome.Success(page("a", "b"))
        }, "owner", "plan", 2)
        controller.refresh(); controller.more(); repeat(5) { controller.more() }
        assertEquals(listOf(0, 2), offsets); assertEquals(listOf("a", "b"), ids(controller))
        assertTrue(controller.state.value.end); hasWarning(controller, "offset"); hasWarning(controller, "停止")
        assertNull(controller.state.value.error); assertNull(controller.state.value.moreError)
    }

    @Test fun reorderedDuplicatePageStopsAndUpdatesContentsWithoutMovingExistingRows() = runTest {
        var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            calls++; RunReadOutcome.Success(if (offset == 0) page("a", "b") else response(row("b", "New b"), row("a", "New a")))
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
            RunReadOutcome.Success(when (offset) { 0 -> page("a", "b"); 2 -> page("b", "c"); 4 -> page("d"); else -> error("unexpected offset") })
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
            RunReadOutcome.Success(when (offset) { 0 -> page("a", "b"); 2 -> page("c", "d"); else -> page("b", "c") })
        }, "owner", "plan", 2)
        controller.refresh(); controller.more(); controller.more(); repeat(4) { controller.more() }
        assertEquals(listOf(0, 2, 4), offsets); assertEquals(listOf("a", "b", "c", "d"), ids(controller))
        assertTrue(controller.state.value.end); hasWarning(controller, "停止")
    }

    @Test fun fullMalformedPageStopsWithDiagnosticsInsteadOfDraining() = runTest {
        var calls = 0
        val controller = RunRecordController(RunRecordPageSource { _, _, _, _ ->
            calls++; RunReadOutcome.Success(RunRows(emptyList(), recognized = true,
                warnings = listOf("userErrandTrackRecord[0]：没有可识别记录"), rawSize = 2))
        }, "owner", "plan", 2)
        controller.refresh(); controller.more()
        assertEquals(1, calls); assertTrue(controller.state.value.items.isEmpty())
        assertTrue(controller.state.value.end); hasWarning(controller, "没有可识别")
        assertNull(controller.state.value.error)
    }

    @Test fun initialFailureRequiresExplicitRetryAndStartsAtOffsetZero() = runTest {
        var fail = true
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            if (fail) RunReadOutcome.Failure(RunReadIssue.TIMEOUT) else RunReadOutcome.Success(page("a"))
        }, "owner", "plan", 2)
        controller.refresh()
        assertEquals(listOf(0), offsets); assertEquals(runReadFailureText(RunReadIssue.TIMEOUT), controller.state.value.error)
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
            if (offset > 0 && fail) RunReadOutcome.Failure(RunReadIssue.NETWORK)
            else RunReadOutcome.Success(if (offset == 0) page("a", "b") else page("c"))
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
                0 -> RunReadOutcome.Success(page("old"))
                1 -> RunReadOutcome.Failure(RunReadIssue.HTTP)
                else -> RunReadOutcome.Success(if (offset == 0) page("new-a", "new-b") else page("new-c"))
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
                0 -> RunReadOutcome.Success(if (offset == 0) page("a", "b") else page("c", "d"))
                1 -> RunReadOutcome.Failure(RunReadIssue.TIMEOUT)
                else -> RunReadOutcome.Success(if (offset == 0) page("x", "y") else page("z"))
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
            RunReadOutcome.Success(when {
                offset == 0 -> page("a", "b")
                malformed -> RunRows(emptyList(), recognized = false, warnings = listOf("userErrandTrackRecord"))
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
            RunReadOutcome.Success(when (round) {
                0 -> page("a", "b")
                1 -> RunRows(emptyList(), recognized = false, warnings = listOf("userErrandTrackRecord"))
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
            RunReadOutcome.Success(if (round == 0 || offset == 0) page("a", "b") else page("c"))
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
            calls++; started.complete(Unit); reply.await(); RunReadOutcome.Success(page("a", "b"))
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
            RunReadOutcome.Success(when (offset) { 0 -> page("a", "b"); 2 -> page("c", "d"); else -> page("e") })
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
            RunReadOutcome.Success(page("a"))
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
            RunReadOutcome.Success(if (offset == 0) page("a", "b") else page("c"))
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
            RunReadOutcome.Success(if (calls == 1) page("old") else page("new"))
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
                RunReadOutcome.Success(page("stale"))
            } else RunReadOutcome.Success(page("new"))
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
            RunReadOutcome.Success(page("a"))
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
        val source = RunRecordPageSource { _, _, _, _ -> calls++; RunReadOutcome.Success(page()) }
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
        val source = RunRecordPageSource { _, _, _, _ -> calls++; RunReadOutcome.Success(page()) }
        assertFailsWith<IllegalArgumentException> { RunRecordController(source, "owner", "plan", 0) }
        assertFailsWith<IllegalArgumentException> { RunRecordController(source, "owner", "plan", -1) }
        assertEquals(0, calls)
    }

    @Test fun noAutomaticLoadOrDrainOccursEvenWhenEveryPageIsFull() = runTest {
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, limit ->
            offsets += offset
            RunReadOutcome.Success(page(*(offset until offset + limit).map { "r-$it" }.toTypedArray()))
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
            RunReadOutcome.Success(if (owner == "owner-a") page("a") else page("b"))
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
