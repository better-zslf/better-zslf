package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunGeo
import io.github.betterzslf.platform.PickedFile
import io.github.betterzslf.platform.RunMapCoordinateSystem
import io.github.betterzslf.platform.RunMapCoordinates
import io.github.betterzslf.platform.RunMapEvent
import io.github.betterzslf.platform.RunMapProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RunManualImportControllerTest {
    private val origin = GeoPoint(113.4, 22.5)
    private val wgs = (0..10).map { RunGeo.offset(origin, it * 20.0, 0.0) }
    private val gcj = wgs.map(RunMapCoordinates::wgs84ToGcj02)
    private fun detail(roads: List<List<GeoPoint>> = listOf(gcj)) = RunTaskDetail(
        RunTaskItem(null, "fixture-schedule", "Fixture route", null, null, "0", "fixture-key",
            JsonObject(emptyMap()), errandId = "fixture-errand"),
        emptyList(), roads, 100.0, listOf("fixture-warning"))
    private fun TestScope.controller(task: RunTaskDetail = detail()) =
        RunImportController(task, { 1_700_000_000_000L }, StandardTestDispatcher(testScheduler))
    private fun picked(points: List<GeoPoint> = wgs) = PickedFile("fixture.gpx", "application/gpx+xml",
        ("<gpx><trk><trkseg>" + points.joinToString("") {
            "<trkpt lon=\"${it.longitude}\" lat=\"${it.latitude}\"/>"
        } + "</trkseg></trk></gpx>").encodeToByteArray())
    private fun route(provider: RunMapProvider = RunMapProvider.TIAN_DI_TU,
                      points: List<GeoPoint> = wgs): RunManualRoute {
        val initial = RunManualDraft.empty(provider)
        val edit = initial.appendAll(points.map { RunMapEvent.DrawPoint(it, initial.coordinateSystem) })
        assertTrue(edit.accepted)
        return assertNotNull(edit.draft.commit().route)
    }
    private fun admission(c: RunImportController) = assertNotNull(c.beginManualDraft())

    @Test fun openingAnEditorDoesNotChangeExistingPreviewOrReports() = runTest {
        val c = controller()
        assertTrue(c.read(picked()))
        assertTrue(c.validate(100.0, 200_000))
        val before = c.state.value
        val first = admission(c)
        val second = admission(c)
        assertSame(before, c.state.value)
        assertTrue(c.isManualDraftCurrent(first))
        assertTrue(c.isManualDraftCurrent(second))
    }

    @Test fun editingAndCancellingAnIndependentDraftLeavesGpxUntouched() = runTest {
        val c = controller()
        assertTrue(c.read(picked()))
        val before = c.state.value
        admission(c)
        val edit = RunManualDraft.empty(RunMapProvider.AMAP)
            .append(gcj.first().longitude, gcj.first().latitude, RunMapCoordinateSystem.GCJ02)
        assertTrue(edit.accepted)
        edit.draft.clear()
        assertSame(before, c.state.value)
        assertEquals(RunImportSource.GPX_FILE, c.state.value.source)
    }

    @Test fun explicitWgsCommitReplacesOnlyTheLocalPreview() = runTest {
        val c = controller()
        assertTrue(c.read(picked(wgs.take(3))))
        assertTrue(c.useManual(route(), admission(c)))
        val state = c.state.value
        assertEquals(RunImportSource.MANUAL_MAP, state.source)
        assertEquals(AppText.ManualRoute.SOURCE_LABEL, state.fileName)
        assertEquals(listOf(wgs), state.originalSegments)
        assertEquals(RunMapCoordinateSystem.WGS84, state.originalCoordinateSystem)
        assertEquals(wgs, state.workingPoints)
        assertEquals(RunMapCoordinateSystem.WGS84, state.workingCoordinateSystem)
        assertNull(state.generated)
    }

    @Test fun gcjCommitRetainsOriginalFrameAndDerivesSeparateWgsWorkingCopy() = runTest {
        val c = controller()
        assertTrue(c.useManual(route(RunMapProvider.AMAP, gcj), admission(c)))
        assertEquals(listOf(gcj), c.state.value.originalSegments)
        assertEquals(RunMapCoordinateSystem.GCJ02, c.state.value.originalCoordinateSystem)
        assertEquals(RunMapCoordinateSystem.WGS84, c.state.value.workingCoordinateSystem)
        assertEquals(gcj.map(RunMapCoordinates::gcj02ToWgs84), c.state.value.workingPoints)
        assertNotEquals(gcj, c.state.value.workingPoints)
        assertTrue(wgs.zip(c.state.value.workingPoints).all { (a, b) -> RunGeo.distanceMeters(a, b) < 0.01 })
    }

    @Test fun normalizeConvertsManualGcjByFrameNotByRecommendedSourceEnum() = runTest {
        val c = controller()
        val sparse = listOf(gcj.first(), gcj.last())
        assertTrue(c.useManual(route(RunMapProvider.AMAP, sparse), admission(c)))
        assertTrue(c.normalize())
        assertEquals(sparse, c.state.value.originalPoints)
        assertEquals(RunMapCoordinateSystem.GCJ02, c.state.value.originalCoordinateSystem)
        assertEquals(RunMapCoordinateSystem.WGS84, c.state.value.workingCoordinateSystem)
        assertTrue(c.state.value.workingPoints.size > sparse.size)
        assertTrue(RunGeo.distanceMeters(wgs.first(), c.state.value.workingPoints.first()) < 0.01)
        assertTrue(RunGeo.distanceMeters(wgs.last(), c.state.value.workingPoints.last()) < 0.01)
    }

    @Test fun repeatedNormalizationDoesNotApplyGcjConversionTwice() = runTest {
        val c = controller()
        assertTrue(c.useManual(route(RunMapProvider.AMAP, listOf(gcj.first(), gcj.last())), admission(c)))
        assertTrue(c.normalize())
        val working = c.state.value.workingPoints
        assertTrue(c.normalize())
        assertEquals(working, c.state.value.workingPoints)
        assertEquals(listOf(gcj.first(), gcj.last()), c.state.value.originalPoints)
    }

    @Test fun wgsManualNormalizationKeepsOriginalsAndUsesTheSameEndpoints() = runTest {
        val c = controller()
        val sparse = listOf(wgs.first(), wgs.last())
        assertTrue(c.useManual(route(points = sparse), admission(c)))
        assertTrue(c.normalize())
        assertEquals(sparse, c.state.value.originalPoints)
        assertEquals(sparse.first(), c.state.value.workingPoints.first())
        assertEquals(sparse.last(), c.state.value.workingPoints.last())
        assertTrue(c.state.value.normalized)
    }

    @Test fun genuineNegativeZeroIsNotReformattedOrRelabelledOnCommit() = runTest {
        val c = controller()
        val points = listOf(GeoPoint(-0.0, 0.0), GeoPoint(0.001, 0.0))
        assertTrue(c.useManual(route(points = points), admission(c)))
        assertEquals((-0.0).toBits(), c.state.value.originalPoints.first().longitude.toBits())
        assertEquals((-0.0).toBits(), c.state.value.workingPoints.first().longitude.toBits())
        assertEquals(RunMapCoordinateSystem.WGS84, c.state.value.originalCoordinateSystem)
    }

    @Test fun commitDropsPreviousValidationGenerationParametersAndGeometryFlags() = runTest {
        val c = controller()
        assertTrue(c.useRecommended())
        assertTrue(c.normalize())
        assertTrue(c.supplement(400.0, 400_000))
        // Keep jittered sample gaps below 45m for this 400m fixture; this test verifies state replacement.
        assertTrue(c.generate(100.0, 400_000, seed = 42, samplingSeconds = 20),
            "route=" + c.state.value.validation?.issues?.map { it.code } +
                "; generated=" + c.state.value.generatedValidation?.issues?.map { it.code } +
                "; error=" + c.state.value.error)
        assertNotNull(c.state.value.generated)
        assertNotNull(c.state.value.validation)
        assertNotNull(c.state.value.generatedValidation)
        assertEquals(100.0, c.state.value.targetDistanceMeters)
        assertEquals(400_000L, c.state.value.durationMillis)
        assertEquals(20, c.state.value.generationSamplingSeconds)
        assertTrue(c.state.value.normalized)
        assertTrue(c.state.value.detoured)
        assertTrue(c.useManual(route(), admission(c)))
        val state = c.state.value
        assertNull(state.validation)
        assertNull(state.generated)
        assertNull(state.generatedValidation)
        assertNull(state.targetDistanceMeters)
        assertNull(state.durationMillis)
        assertNull(state.generationSamplingSeconds)
        assertFalse(state.normalized)
        assertFalse(state.detoured)
        assertNull(state.error)
    }

    @Test fun successfulSaveConsumesEveryAdmissionForThePreviousSnapshot() = runTest {
        val c = controller()
        val first = admission(c)
        val concurrent = admission(c)
        assertTrue(c.useManual(route(), first))
        val saved = c.state.value
        assertFalse(c.isManualDraftCurrent(first))
        assertFalse(c.isManualDraftCurrent(concurrent))
        assertFalse(c.useManual(route(points = wgs.take(3)), first))
        assertFalse(c.useManual(route(points = wgs.take(3)), concurrent))
        assertSame(saved, c.state.value)
    }

    @Test fun aNewExplicitEditorCanReplaceAnEarlierManualPreview() = runTest {
        val c = controller()
        assertTrue(c.useManual(route(), admission(c)))
        assertTrue(c.useManual(route(points = wgs.take(3)), admission(c)))
        assertEquals(wgs.take(3), c.state.value.originalPoints)
    }

    @Test fun anotherControllerCannotUseAnAdmissionOrPublishAnError() = runTest {
        val first = controller()
        val other = controller()
        val permit = admission(first)
        val untouched = other.state.value
        assertFalse(other.isManualDraftCurrent(permit))
        assertFalse(other.useManual(route(), permit))
        assertSame(untouched, other.state.value)
        assertTrue(first.isManualDraftCurrent(permit))
    }

    @Test fun aNewFileInvalidatesAnOldEditorBeforeAnyBusyOrErrorPublication() = runTest {
        val c = controller()
        val permit = admission(c)
        assertTrue(c.read(picked()))
        val latest = c.state.value
        assertFalse(c.useManual(route(points = wgs.take(2)), permit))
        assertSame(latest, c.state.value)
        assertEquals(RunImportSource.GPX_FILE, c.state.value.source)
    }

    @Test fun clearInvalidatesAdmissionEvenWhenTheNewEmptyValueEqualsTheOldValue() = runTest {
        val c = controller()
        val before = c.state.value
        val permit = admission(c)
        assertTrue(c.clear())
        assertEquals(before, c.state.value)
        assertNotSame(before, c.state.value)
        val cleared = c.state.value
        assertFalse(c.isManualDraftCurrent(permit))
        assertFalse(c.useManual(route(), permit))
        assertSame(cleared, c.state.value)
    }

    @Test fun aFailedParentOperationAlsoInvalidatesAnEarlierEditor() = runTest {
        val c = controller()
        assertTrue(c.useRecommended())
        val permit = admission(c)
        assertFalse(c.validate(-1.0, 200_000))
        val latest = c.state.value
        assertFalse(c.useManual(route(), permit))
        assertSame(latest, c.state.value)
    }

    @Test fun unconfirmedFrameCannotReplaceExistingOriginalsOrWorkingCopy() = runTest {
        val c = controller()
        assertTrue(c.read(picked()))
        val before = c.state.value
        val invalid = RunManualRoute(RunMapProvider.TIAN_DI_TU, RunMapCoordinateSystem.UNKNOWN, wgs)
        assertFalse(c.useManual(invalid, admission(c)))
        assertEquals(before.originalSegments, c.state.value.originalSegments)
        assertEquals(before.workingPoints, c.state.value.workingPoints)
        assertEquals(RunImportSource.GPX_FILE, c.state.value.source)
        assertNotNull(c.state.value.error)
    }

    @Test fun providerFrameMismatchCannotBeSilentlyConvertedOrRelabelled() = runTest {
        val c = controller()
        val invalid = RunManualRoute(RunMapProvider.AMAP, RunMapCoordinateSystem.WGS84, wgs)
        assertFalse(c.useManual(invalid, admission(c)))
        assertTrue(c.state.value.originalPoints.isEmpty())
        assertNull(c.state.value.source)
    }

    @Test fun evenAnInternallyConstructedOnePointRouteFailsTheCommitGate() = runTest {
        val c = controller()
        val invalid = RunManualRoute(RunMapProvider.TIAN_DI_TU, RunMapCoordinateSystem.WGS84, wgs.take(1))
        assertFalse(c.useManual(invalid, admission(c)))
        assertTrue(c.state.value.originalPoints.isEmpty())
        assertEquals(AppText.ManualRoute.AT_LEAST_TWO_POINTS_REQUIRED, c.state.value.error)
    }

    @Test fun evenAnInternallyConstructedAdjacentDuplicateRouteIsRejected() = runTest {
        val c = controller()
        val invalid = RunManualRoute(RunMapProvider.TIAN_DI_TU, RunMapCoordinateSystem.WGS84, listOf(origin, origin))
        assertFalse(c.useManual(invalid, admission(c)))
        assertTrue(c.state.value.originalPoints.isEmpty())
        assertEquals(AppText.ManualRoute.INVALID_DRAFT, c.state.value.error)
    }

    @Test fun theControllerRechecksTheManualBudgetInsteadOfTrustingTheDto() = runTest {
        val c = controller()
        val points = (0..RunManualDraft.MAX_POINTS).map { GeoPoint(120.0 + it * 0.000001, 30.0) }
        val invalid = RunManualRoute(RunMapProvider.TIAN_DI_TU, RunMapCoordinateSystem.WGS84, points)
        assertFalse(c.useManual(invalid, admission(c)))
        assertTrue(c.state.value.originalPoints.isEmpty())
    }

    @Test fun busyStateBlocksOpeningAndASecondSaveWithoutTouchingTheCurrentOperation() = runTest {
        val c = controller()
        val permit = admission(c)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { c.useRecommended() }
        val busy = c.state.value
        assertTrue(busy.busy)
        assertNull(c.beginManualDraft())
        assertFalse(c.isManualDraftCurrent(permit))
        assertFalse(c.useManual(route(), permit))
        assertSame(busy, c.state.value)
        runCurrent()
        job.join()
        assertFalse(c.state.value.busy)
        assertEquals(RunImportSource.RECOMMENDED, c.state.value.source)
    }

    @Test fun cancellingQueuedSaveRestoresTheParentAndPreventsLatePublication() = runTest {
        val c = controller()
        assertTrue(c.read(picked()))
        val before = c.state.value
        val permit = admission(c)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { c.useManual(route(points = wgs.take(3)), permit) }
        assertTrue(c.state.value.busy)
        job.cancel()
        runCurrent()
        job.join()
        assertEquals(before, c.state.value)
        assertFalse(c.state.value.busy)
        assertFalse(c.isManualDraftCurrent(permit))
        val restored = c.state.value
        assertFalse(c.useManual(route(), permit))
        assertSame(restored, c.state.value)
    }

    private class StagedProcessingDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()
        var completedTasks: Int = 0
            private set
        override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        fun completeNext() {
            check(queued.isNotEmpty())
            queued.removeFirst().run()
            completedTasks++
        }
    }

    @Test fun cancellationAfterComputationButBeforeCallerResumeStillDropsTheResult() = runTest {
        val processing = StagedProcessingDispatcher()
        val c = RunImportController(detail(), { 1_700_000_000_000L }, processing)
        val load = launch(start = CoroutineStart.UNDISPATCHED) { assertTrue(c.read(picked())) }
        processing.completeNext()
        runCurrent()
        load.join()
        val before = c.state.value
        val permit = admission(c)
        val save = launch(start = CoroutineStart.UNDISPATCHED) { c.useManual(route(points = wgs.take(3)), permit) }
        assertTrue(c.state.value.busy)
        processing.completeNext()
        assertEquals(2, processing.completedTasks)
        // The transform has returned, but the parent's TestDispatcher has not resumed it.
        assertTrue(c.state.value.busy)
        assertEquals(RunImportSource.GPX_FILE, c.state.value.source)
        save.cancel()
        runCurrent()
        save.join()
        assertEquals(before, c.state.value)
        assertFalse(c.state.value.busy)
        assertFalse(c.isManualDraftCurrent(permit))
        val restored = c.state.value
        assertFalse(c.useManual(route(), permit))
        assertSame(restored, c.state.value)
    }

    @Test fun aClosedLiveGateAtEntryDoesNotPublishEvenBusyOrErrorState() = runTest {
        val c = controller()
        assertTrue(c.read(picked()))
        val before = c.state.value
        val permit = admission(c)
        assertFalse(c.useManual(route(), permit) { false })
        assertSame(before, c.state.value)
        assertTrue(c.isManualDraftCurrent(permit))
    }

    @Test fun liveIdentityChangeAfterComputationCannotOverwriteTheParentPreview() = runTest {
        val processing = StagedProcessingDispatcher()
        val c = RunImportController(detail(), { 1_700_000_000_000L }, processing)
        val load = launch(start = CoroutineStart.UNDISPATCHED) { assertTrue(c.read(picked())) }
        processing.completeNext(); runCurrent(); load.join()
        val before = c.state.value
        val permit = admission(c)
        var liveIdentity = true
        var result: Boolean? = null
        val save = launch(start = CoroutineStart.UNDISPATCHED) {
            result = c.useManual(route(points = wgs.take(3)), permit) { liveIdentity }
        }
        processing.completeNext()
        assertTrue(c.state.value.busy)
        liveIdentity = false // No Compose SideEffect or job cancellation has run yet.
        runCurrent(); save.join()
        assertEquals(false, result)
        assertEquals(before, c.state.value)
        assertFalse(c.isManualDraftCurrent(permit))
        val fresh = admission(c)
        val subsequent = launch(start = CoroutineStart.UNDISPATCHED) { assertTrue(c.useManual(route(), fresh)) }
        processing.completeNext(); runCurrent(); subsequent.join()
        assertEquals(RunImportSource.MANUAL_MAP, c.state.value.source)
    }

    @Test fun exportedRouteListsCannotChangeTheCommittedControllerSnapshot() = runTest {
        val c = controller()
        val source = route()
        assertTrue(c.useManual(source, admission(c)))
        val detached = source.points as? MutableList<GeoPoint>
        detached?.clear()
        val segment = source.segments.first() as? MutableList<GeoPoint>
        segment?.clear()
        assertEquals(wgs, c.state.value.originalPoints)
        assertEquals(wgs, c.state.value.workingPoints)
        assertEquals(wgs, source.points)
    }

    @Test fun missingTaskRoadsDoNotPreventTheLocalOnlyManualPreview() = runTest {
        val c = controller(detail(roads = emptyList()))
        assertTrue(c.useManual(route(), admission(c)))
        assertEquals(RunImportSource.MANUAL_MAP, c.state.value.source)
        assertNull(c.state.value.generated)
        assertNull(c.state.value.validation)
        assertTrue("fixture-warning" in c.state.value.warnings)
        assertTrue(AppText.ManualRoute.LOCAL_ONLY_WARNING in c.state.value.warnings)
        assertTrue(AppText.ManualRoute.WORKING_COPY_WGS84_HINT in c.state.value.warnings)
    }

    @Test fun admissionDiagnosticsNeverExposeThePreviewOrController() = runTest {
        val c = controller()
        assertTrue(c.read(picked()))
        val text = admission(c).toString()
        assertEquals("RunManualImportAdmission(redacted)", text)
        assertFalse(text.contains("113.4"))
        assertFalse(text.contains("fixture"))
        assertFalse(text.contains("RunImportState"))
    }
}
