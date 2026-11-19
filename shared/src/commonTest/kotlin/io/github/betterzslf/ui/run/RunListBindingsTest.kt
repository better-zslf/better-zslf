package io.github.betterzslf.ui.run

import io.github.betterzslf.Route
import io.github.betterzslf.composition.ContributedAction
import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.feature.runui.*
import io.github.betterzslf.ui.SessionEpochReadFence
import io.github.betterzslf.ui.profile.runTotals
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Real typed data source + presentation controllers + product-only lookup gates; no runtime is constructed. */
class RunListBindingsTest {
    private val taskPage = """{"code":200,"data":{"errandList":[{"errandId":"fixture-server-task","errandScheduleId":"fixture-server-plan","eName":"Fixture task","minimumDistanceMeters":0}]}}"""
    private val recordPage = """{"code":200,"data":{"userErrandTrackRecord":[{"trid":"fixture-server-record","eName":"Fixture record","runLength":1000,"runTime":300000}]}}"""

    private class Harness(val fixture: ApiTestFixture) {
        val source = assertNotNull(ApiRunReadSource.capture(fixture.api))
        private val epoch = fixture.sessions.revision.value
        val fence = SessionEpochReadFence(epoch, { fixture.sessions.revision.value }, source::isCurrentSession)
        val tasks = RunTaskController(boundRunTaskPages(source, fence, source.boundOwnerId), source.boundOwnerId)
        val records = RunRecordController(boundRunRecordPages(source, fence, source.boundOwnerId), source.boundOwnerId, "fixture-server-plan")
        val taskBindings = RunListBindings("task-row", RunTaskItem::identityKey, fence::isCurrent, { tasks.state.value.items })
        val recordBindings = RunListBindings("record-row", RunRecordItem::identityKey, fence::isCurrent, { records.state.value.items })
        fun close() { taskBindings.retire(); recordBindings.retire(); fence.close(); fixture.close() }
    }

    private fun fixture(load: suspend (path: String, offset: String?, finish: String?) -> String): ApiTestFixture = ApiTestFixture(MockEngine { request ->
        assertEquals("fixture-token", request.headers[HttpHeaders.Authorization])
        val path = request.url.encodedPath.substringAfterLast('/')
        respond(load(path, request.url.parameters["offset"], request.url.parameters["isFinish"]),
            headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })

    @Test fun bothListsUseTheRealReadFactoryAndOnlyNavigateWithRealServerIdentities() = runTest {
        val calls = mutableListOf<String>()
        val h = Harness(fixture { path, _, _ -> calls += path; when (path) {
            "getErrandList" -> taskPage
            "getUserErrandTrackRecord" -> recordPage
            else -> error("Only read-list endpoints may run")
        } })
        try {
            h.tasks.refresh(); h.records.refresh()
            assertEquals(listOf("getErrandList", "getErrandList", "getUserErrandTrackRecord"), calls)
            val tasks = h.taskBindings.render(h.tasks.state.value.items)
            val taskDisplay = taskListDisplay(h.tasks.state.value, tasks, true, true, emptyList())
            val row = taskDisplay.rows.single()
            assertEquals("task-row:1", row.localKey)
            assertEquals(0.0, row.minimumDistanceMeters)
            assertFalse(row.localKey.contains("fixture-server"))
            val task = assertNotNull(h.taskBindings.lookup(tasks, row.localKey))
            val selected = assertNotNull(RunTaskSelection.from(task))
            assertEquals("fixture-server-task", selected.errandId)
            assertEquals("fixture-server-plan", selected.errandScheduleId)
            assertEquals(taskListDestination(task, RunTaskRowAction.DETAIL), taskListDestination(task, RunTaskRowAction.RECORD_REAL))
            assertEquals("rundetail", taskListDestination(task, RunTaskRowAction.RECORD_REAL)?.kind)
            assertEquals("runrecords", taskListDestination(task, RunTaskRowAction.RECORDS)?.kind)
            val records = h.recordBindings.render(h.records.state.value.items)
            val recordDisplay = recordListDisplay(h.records.state.value, records, 0.0, true, null)
            val recordRow = recordDisplay.rows.single()
            assertEquals("record-row:1", recordRow.localKey)
            assertFalse(recordRow.localKey.contains("fixture-server-record"))
            assertFalse(recordRow.toString().contains("fixture-server-record"))
            val record = assertNotNull(h.recordBindings.lookup(records, recordRow.localKey))
            assertEquals("fixture-server-record", recordListDestination(record)?.id)
            assertEquals(3, calls.size) // Row actions do not start, upload, close, or issue a request.
        } finally { h.close() }
    }

    @Test fun refreshReplacementCannotResolveAnOldRenderedKeyAgainstNewServerIds() = runTest {
        var recordCalls = 0
        val h = Harness(fixture { path, _, _ ->
            assertEquals("getUserErrandTrackRecord", path)
            recordCalls++
            if (recordCalls == 1) recordPage else recordPage.replace("fixture-server-record", "replacement-server-record")
        })
        try {
            h.records.refresh()
            val first = h.recordBindings.render(h.records.state.value.items)
            val oldKey = first.rows.keys.single()
            val oldClosure = { h.recordBindings.lookup(first, oldKey)?.let(::recordListDestination) }
            assertEquals("fixture-server-record", oldClosure()?.id)
            h.records.refresh()
            // Reject even before a new Compose render, using the controller's current row-list reference.
            assertNull(oldClosure())
            val second = h.recordBindings.render(h.records.state.value.items)
            assertNull(oldClosure())
            assertNull(h.recordBindings.lookup(second, oldKey))
            assertEquals("replacement-server-record", h.recordBindings.lookup(second, second.rows.keys.single())?.trid)
        } finally { h.close() }
    }

    @Test fun sameRecordIdentityCanKeepItsUiKeyButNeverItsOldRenderAuthority() = runTest {
        var calls = 0
        val h = Harness(fixture { _, _, _ -> calls++; recordPage.replace("Fixture record", "Fixture revision $calls") })
        try {
            h.records.refresh()
            val first = h.recordBindings.render(h.records.state.value.items)
            val key = first.rows.keys.single()
            h.records.refresh()
            val second = h.recordBindings.render(h.records.state.value.items)
            assertEquals(key, second.rows.keys.single())
            assertNull(h.recordBindings.lookup(first, key))
            assertEquals("Fixture revision 2", h.recordBindings.lookup(second, key)?.title)
        } finally { h.close() }
    }

    @Test fun sameSaveAndClearSaveAbaRetireOldClosuresBeforeAnyCollectorTurn() = runTest {
        for (aba in listOf(false, true)) {
            var calls = 0
            val h = Harness(fixture { _, _, _ -> calls++; recordPage })
            try {
                h.records.refresh()
                val render = h.recordBindings.render(h.records.state.value.items)
                val key = render.rows.keys.single()
                val original = assertNotNull(h.fixture.sessions.session.value)
                if (aba) h.fixture.sessions.clear()
                h.fixture.sessions.save(original.copy())
                assertFalse(h.source.isCurrentSession())
                assertNull(h.recordBindings.lookup(render, key))
                assertFailsWith<CancellationException> { h.records.refresh() }
                assertEquals(1, calls)
                val replacement = assertNotNull(ApiRunReadSource.capture(h.fixture.api))
                assertTrue(replacement.isCurrentSession())
            } finally { h.close() }
        }
    }

    @Test fun lateTypedPageCannotPublishAfterTheFixedSourceEpochWasRetired() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val h = Harness(fixture { _, _, _ -> calls++; started.complete(Unit); release.await(); recordPage })
        try {
            val job = launch { h.records.refresh() }
            started.await()
            h.fixture.sessions.save(assertNotNull(h.fixture.sessions.session.value).copy())
            release.complete(Unit)
            job.join()
            assertTrue(h.records.state.value.items.isEmpty())
            assertFalse(h.records.state.value.loading)
            assertEquals(1, calls)
        } finally { h.close() }
    }

    @Test fun exactAllZeroOneBranchesAndRawOffsetsSurviveTheRootTypedAdapter() = runTest {
        val calls = mutableListOf<Pair<String?, String?>>()
        val duplicateMalformedPage = """{"code":200,"data":{"errandList":[{"errandId":"fixture-server-task","errandScheduleId":"fixture-server-plan","eName":"Fixture"},null,{"errandId":"fixture-server-task","errandScheduleId":"fixture-server-plan","eName":"Fixture"}]}}"""
        val f = fixture { path, offset, finish ->
            assertEquals("getErrandList", path); calls += finish to offset
            if (offset == "0") duplicateMalformedPage else """{"code":200,"data":{"errandList":[]}}"""
        }
        val source = assertNotNull(ApiRunReadSource.capture(f.api))
        val epoch = f.sessions.revision.value
        val fence = SessionEpochReadFence(epoch, { f.sessions.revision.value }, source::isCurrentSession)
        val controller = RunTaskController(boundRunTaskPages(source, fence, source.boundOwnerId), source.boundOwnerId, 3)
        try {
            controller.refresh(); controller.more()
            assertEquals(listOf<Pair<String?, String?>>("0" to "0", "1" to "0", "0" to "3", "1" to "3"), calls)
            assertEquals(1, controller.state.value.items.size)
            assertTrue(controller.state.value.end)
            assertTrue(controller.state.value.warnings.isNotEmpty())
        } finally { fence.close(); f.close() }
    }

    @Test fun missingRealIdsStayReadOnlyAndNeverSendOpaqueKeysAsQueryIds() = runTest {
        val taskNoIds = """{"code":200,"data":{"errandList":[{"eName":"Anonymous task"}]}}"""
        val recordNoId = """{"code":200,"data":{"userErrandTrackRecord":[{"eName":"Anonymous record"}]}}"""
        val h = Harness(fixture { path, _, _ -> if (path == "getErrandList") taskNoIds else recordNoId })
        try {
            h.tasks.refresh(); h.records.refresh()
            val taskRender = h.taskBindings.render(h.tasks.state.value.items)
            val task = assertNotNull(h.taskBindings.lookup(taskRender, taskRender.rows.keys.single()))
            assertFalse(taskListDisplay(h.tasks.state.value, taskRender, true, true, emptyList()).rows.single().canOpen)
            assertTrue(RunTaskRowAction.entries.all { taskListDestination(task, it) == null })
            val recordRender = h.recordBindings.render(h.records.state.value.items)
            assertFalse(recordListDisplay(h.records.state.value, recordRender, null, true, null).rows.single().canOpen)
            assertNull(recordListDestination(assertNotNull(h.recordBindings.lookup(recordRender, recordRender.rows.keys.single()))))
        } finally { h.close() }
    }

    @Test fun contributionClosuresResolveOnlyTheirFixedMapAndRenderGeneration() {
        var live = true
        var actions = listOf(ContributedAction("Preview", Route.Page("runs")))
        val bindings = RunListBindings("contribution", { row: ContributedAction -> row.title }, { live }, { actions })
        val old = bindings.render(actions)
        val key = old.rows.keys.single()
        val click = { bindings.lookup(old, key)?.target }
        assertEquals(Route.Page("runs"), click())
        actions = listOf(ContributedAction("Preview", Route.Page("runtracker")))
        assertNull(click())
        val replacement = bindings.render(actions)
        assertEquals(key, replacement.rows.keys.single())
        assertNull(click())
        assertEquals(Route.Page("runtracker"), bindings.lookup(replacement, key)?.target)
        live = false
        assertNull(bindings.lookup(replacement, key))
        bindings.retire()
    }

    @Test fun profileTypedUnknownAndKnownEmptyKeepZeroButFailuresRemainNotProvided() = runTest {
        var body = """{"code":200,"data":{"unrecognized":[]}}"""
        val h = Harness(fixture { path, _, _ -> assertEquals("getUserErrandTrackRecord", path); body })
        try {
            val unknown = h.source.records(h.source.boundOwnerId)
            assertFalse(assertIs<RunReadOutcome.Success<RunRows<RunRecordItem>>>(unknown).data.recognized)
            assertEquals(0, runTotals(unknown)?.count)
            assertEquals(0.0, runTotals(unknown)?.distance)
            body = """{"code":200,"data":{"userErrandTrackRecord":[]}}"""
            val empty = h.source.records(h.source.boundOwnerId)
            assertTrue(assertIs<RunReadOutcome.Success<RunRows<RunRecordItem>>>(empty).data.recognized)
            assertEquals(0, runTotals(empty)?.count)
            assertEquals(0.0, runTotals(empty)?.distance)
            assertNull(runTotals(RunReadOutcome.Failure(RunReadIssue.NETWORK)))
            assertNull(runTotals(null))
        } finally { h.close() }
    }
}
