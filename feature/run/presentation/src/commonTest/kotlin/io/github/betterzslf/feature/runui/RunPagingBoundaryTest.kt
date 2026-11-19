package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.text.AppText
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** New typed-boundary coverage, separate from the 54 migrated controller/display cases. */
class RunPagingBoundaryTest {
    private fun task(key: String, trid: String? = null) = RunTaskItem(
        trid = trid, scheduleId = "server-plan", title = "Fixture task", startText = null, endText = null,
        isFinish = null, identityKey = key, errandId = "server-task",
    )
    private fun record(key: String, trid: String? = null, title: String = "Fixture record") = RunRecordItem(
        trid, title, null, null, null, emptyList(), emptyList(), emptyList(), key,
    )

    @Test fun taskOffsetCountsRawRowsAndStopsBeforeIntegerOverflow() = runTest {
        val offsets = mutableListOf<Int>()
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, _ ->
            offsets += offset
            val item = task(if (offset == 0) "opaque-first" else "opaque-second")
            RunReadOutcome.Success(RunRows(listOf(item), true, emptyList(), Int.MAX_VALUE))
        }, "owner", Int.MAX_VALUE)
        controller.select(RunTaskFilter.UNFINISHED)
        assertFalse(controller.state.value.end)
        controller.more(); controller.more()
        assertEquals(listOf(0, Int.MAX_VALUE), offsets)
        assertEquals(2, controller.state.value.items.size)
        assertTrue(controller.state.value.end)
        assertTrue(controller.state.value.warnings.any { "offset" in it })
    }

    @Test fun recordOffsetCountsRawRowsAndStopsBeforeIntegerOverflow() = runTest {
        val offsets = mutableListOf<Int>()
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            offsets += offset
            val item = record(if (offset == 0) "opaque-first" else "opaque-second")
            RunReadOutcome.Success(RunRows(listOf(item), true, emptyList(), Int.MAX_VALUE))
        }, "owner", "plan", Int.MAX_VALUE)
        controller.refresh()
        assertFalse(controller.state.value.end)
        controller.more(); controller.more()
        assertEquals(listOf(0, Int.MAX_VALUE), offsets)
        assertEquals(2, controller.state.value.items.size)
        assertTrue(controller.state.value.end)
        assertTrue(controller.state.value.warnings.contains(AppText.Run.PAGINATION_OFFSET_INTEGER_RANGE_EXCEEDED_STOPPED))
    }

    @Test fun taskPageIdentityIsStructuralNotDelimiterJoinedOrGuessedFromServerIds() = runTest {
        val calls = mutableListOf<Int>()
        val keys = listOf(listOf("opaque,a", "opaque-b"), listOf("opaque", "a,opaque-b"))
        val controller = RunTaskController(RunTaskPageSource { _, offset, _, _ ->
            calls += offset
            val items = keys[if (offset == 0) 0 else 1].map { task(it, trid = "same-display-trid") }
            RunReadOutcome.Success(RunRows(items, true, emptyList()))
        }, "owner", 2)
        controller.select(RunTaskFilter.UNFINISHED); controller.more()
        assertEquals(listOf(0, 2), calls)
        assertEquals(4, controller.state.value.items.size)
        assertFalse(controller.state.value.end)
        controller.more(); controller.more()
        assertEquals(listOf(0, 2, 4), calls)
        assertTrue(controller.state.value.end)
        assertTrue(controller.state.value.items.all { it.trid == "same-display-trid" })
    }

    @Test fun recordUsesTheDataOwnedOpaqueKeyWithoutDerivingIdentityFromTridOrTitle() = runTest {
        val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
            RunReadOutcome.Success(RunRows(if (offset == 0) listOf(
                record("opaque-a", "same-trid"), record("opaque-b", "same-trid"),
            ) else listOf(record("opaque-a", "same-trid", "Updated")), true, emptyList()))
        }, "owner", "plan", 2)
        controller.refresh()
        assertEquals(listOf("opaque-a", "opaque-b"), controller.state.value.items.map { it.identityKey })
        controller.more()
        assertEquals(listOf("Updated", "Fixture record"), controller.state.value.items.map { it.title })
        assertTrue(controller.state.value.items.all { it.trid == "same-trid" })
        assertTrue(controller.state.value.end)
    }

    @Test fun allFiniteIssuesHaveOnlyFixedPresentationCopy() {
        val expected = listOf(
            AppText.Auth.USER_ID_MISSING_LOG_IN_AND_REFRESH,
            AppText.Common.OPERATION_UNSUCCESSFUL_TRY_AGAIN_LATER,
            AppText.Common.CHECK_REQUIRED_PARAMETERS,
            AppText.Common.REQUEST_TIMEOUT_TRY_AGAIN_LATER,
            AppText.Common.NETWORK_CONNECTION_FAILED_CHECK_AND_RETRY,
            AppText.Common.INVALID_SERVICE_RESPONSE_FORMAT,
            AppText.Common.OPERATION_UNSUCCESSFUL_TRY_AGAIN_LATER,
            AppText.Common.LOCAL_DATA_SAVE_FAILED,
        )
        assertEquals(8, RunReadIssue.entries.size)
        assertEquals(expected, RunReadIssue.entries.map(::runReadFailureText))
    }

    @Test fun finiteIssuesPropagateWithoutDiscardingThePendingZeroRetry() = runTest {
        for (issue in RunReadIssue.entries) {
            val calls = mutableListOf<Int>()
            var failed = true
            val controller = RunRecordController(RunRecordPageSource { _, _, offset, _ ->
                calls += offset
                if (failed) RunReadOutcome.Failure(issue)
                else RunReadOutcome.Success(RunRows(listOf(record("opaque")), true, emptyList()))
            }, "owner", "plan", 2)
            controller.refresh()
            assertEquals(runReadFailureText(issue), controller.state.value.error)
            assertFalse(controller.state.value.loading)
            assertFalse(controller.state.value.end)
            failed = false
            controller.more()
            assertEquals(listOf(0, 0), calls)
            assertNull(controller.state.value.error)
            assertTrue(controller.state.value.end)
        }
    }
}
