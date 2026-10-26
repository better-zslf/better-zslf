package io.github.betterzslf.feature.runui

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.platform.RunMapCoordinateSystem
import io.github.betterzslf.platform.RunMapEvent
import io.github.betterzslf.platform.RunMapProvider
import kotlin.test.*

/** Pure fixtures only. No map document, GPS sample, IO, persistence or worker is created. */
class RunManualDraftTest {
    private val amap = RunMapProvider.AMAP
    private val tdt = RunMapProvider.TIAN_DI_TU
    private val gcj = RunMapCoordinateSystem.GCJ02
    private val wgs = RunMapCoordinateSystem.WGS84
    private val first = GeoPoint(113.12345678901234, 22.98765432109876)
    private val second = GeoPoint(113.12345678902234, 22.98765432110876)
    private val third = GeoPoint(113.12345678903234, 22.98765432111876)

    private fun draw(point: GeoPoint = first, frame: RunMapCoordinateSystem = gcj) = RunMapEvent.DrawPoint(point, frame)
    private fun applied(edit: RunManualDraftEdit): RunManualDraft {
        assertTrue(edit.accepted); assertNull(edit.error)
        return edit.draft
    }
    private fun rejected(before: RunManualDraft, edit: RunManualDraftEdit, error: RunManualDraftError) {
        assertFalse(edit.accepted); assertEquals(error, edit.error); assertSame(before, edit.draft)
    }
    private fun two(provider: RunMapProvider = amap): RunManualDraft {
        val draft = RunManualDraft.empty(provider)
        return applied(draft.appendAll(listOf(draw(first, draft.coordinateSystem), draw(second, draft.coordinateSystem))))
    }
    private fun full(): RunManualDraft = applied(RunManualDraft.empty(amap).appendAll(
        List(RunManualDraft.MAX_POINTS) { draw(GeoPoint(100.0 + it / 10_000.0, 20.0)) },
    ))
    private fun attemptClear(list: List<*>) {
        try { (list as? MutableList<*>)?.clear() } catch (_: UnsupportedOperationException) { /* Detached read-only copy. */ }
    }

    @Test fun amapStartsEmptyAndRequiresGcj02() {
        val draft = RunManualDraft.empty(amap)
        assertEquals(amap, draft.provider); assertEquals(gcj, draft.coordinateSystem)
        assertEquals(0, draft.pointCount); assertTrue(draft.points.isEmpty()); assertFalse(draft.canCommit)
    }

    @Test fun tianDiTuStartsEmptyAndRequiresWgs84() {
        val draft = RunManualDraft.empty(tdt)
        assertEquals(tdt, draft.provider); assertEquals(wgs, draft.coordinateSystem)
        assertEquals(0, draft.pointCount); assertFalse(draft.canCommit)
    }

    @Test fun amapAppendsTheRealExplicitFrameEventWithoutConvertingNumbers() {
        val old = RunManualDraft.empty(amap)
        val next = applied(old.append(draw()))
        assertEquals(listOf(first), next.points); assertEquals(1, next.pointCount)
        assertEquals(first.longitude.toBits(), next.points.single().longitude.toBits())
        assertEquals(first.latitude.toBits(), next.points.single().latitude.toBits())
        assertEquals(0, old.pointCount); assertFalse(next.canCommit)
    }

    @Test fun tianDiTuAppendsTheRealExplicitFrameEventWithoutConvertingNumbers() {
        val old = RunManualDraft.empty(tdt)
        val next = applied(old.append(draw(first, wgs)))
        assertEquals(listOf(first), next.points); assertEquals(wgs, next.coordinateSystem)
        assertEquals(first.longitude.toBits(), next.points.single().longitude.toBits())
        assertEquals(first.latitude.toBits(), next.points.single().latitude.toBits())
        assertEquals(0, old.pointCount)
    }

    @Test fun amapRejectsWgsEventsInsteadOfGuessingOrConverting() {
        val draft = RunManualDraft.empty(amap)
        rejected(draft, draft.append(draw(first, wgs)), RunManualDraftError.FRAME_MISMATCH)
    }

    @Test fun tianDiTuRejectsGcjEventsInsteadOfGuessingOrConverting() {
        val draft = RunManualDraft.empty(tdt)
        rejected(draft, draft.append(draw()), RunManualDraftError.FRAME_MISMATCH)
    }

    @Test fun bothProvidersRejectUnknownFrames() {
        for (provider in listOf(amap, tdt)) {
            val draft = RunManualDraft.empty(provider)
            rejected(draft, draft.append(draw(first, RunMapCoordinateSystem.UNKNOWN)), RunManualDraftError.UNKNOWN_FRAME)
        }
    }

    @Test fun nanCoordinatesAreExplicitFailuresBeforeGeoPointConstruction() {
        for (provider in listOf(amap, tdt)) {
            val draft = RunManualDraft.empty(provider)
            for ((lon, lat) in listOf(Double.NaN to 22.0, 113.0 to Double.NaN, Double.NaN to Double.NaN)) {
                rejected(draft, draft.append(lon, lat, draft.coordinateSystem), RunManualDraftError.INVALID_COORDINATE)
            }
        }
    }

    @Test fun positiveAndNegativeInfinityAreExplicitFailures() {
        val draft = two()
        for (bad in listOf(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            rejected(draft, draft.append(bad, 22.0, gcj), RunManualDraftError.INVALID_COORDINATE)
            rejected(draft, draft.append(113.0, bad, gcj), RunManualDraftError.INVALID_COORDINATE)
        }
        assertEquals(listOf(first, second), draft.points)
    }

    @Test fun longitudesOutsideTheClosedWorldRangeAreRejected() {
        val draft = RunManualDraft.empty(amap)
        for (bad in listOf(-180.0000001, 180.0000001, -Double.MAX_VALUE, Double.MAX_VALUE)) {
            rejected(draft, draft.append(bad, 0.0, gcj), RunManualDraftError.INVALID_COORDINATE)
        }
    }

    @Test fun latitudesOutsideTheClosedWorldRangeAreRejected() {
        val draft = RunManualDraft.empty(tdt)
        for (bad in listOf(-90.0000001, 90.0000001, -Double.MAX_VALUE, Double.MAX_VALUE)) {
            rejected(draft, draft.append(0.0, bad, wgs), RunManualDraftError.INVALID_COORDINATE)
        }
    }

    @Test fun legalCoordinateBoundariesAndZeroAreNotTreatedAsSentinels() {
        for (provider in listOf(amap, tdt)) {
            val empty = RunManualDraft.empty(provider)
            val expected = listOf(GeoPoint(-180.0, -90.0), GeoPoint(180.0, 90.0), GeoPoint(0.0, 0.0))
            val draft = applied(empty.appendAll(expected.map { draw(it, empty.coordinateSystem) }))
            assertEquals(expected, draft.points); assertTrue(draft.canCommit)
        }
    }

    @Test fun invalidNumericAppendPreservesExistingDraftAndExport() {
        val draft = two()
        val exported = assertNotNull(draft.commit().route)
        rejected(draft, draft.append(181.0, 22.0, gcj), RunManualDraftError.INVALID_COORDINATE)
        assertEquals(listOf(first, second), draft.points); assertEquals(draft.points, exported.points)
    }

    @Test fun anAdjacentExactDuplicateIsAnExplicitNoOp() {
        val draft = applied(RunManualDraft.empty(amap).append(draw()))
        rejected(draft, draft.append(draw(first.copy())), RunManualDraftError.ADJACENT_DUPLICATE)
        assertEquals(1, draft.pointCount); assertFalse(draft.canCommit)
    }

    @Test fun signedZeroIsPreservedAndNumericDuplicateZeroDoesNotCreateASecondPoint() {
        val draft = applied(RunManualDraft.empty(amap).append(-0.0, -0.0, gcj))
        assertEquals((-0.0).toBits(), draft.points.single().longitude.toBits())
        assertEquals((-0.0).toBits(), draft.points.single().latitude.toBits())
        rejected(draft, draft.append(0.0, 0.0, gcj), RunManualDraftError.ADJACENT_DUPLICATE)
        assertFalse(draft.canCommit)
    }

    @Test fun aSingleRepresentableStepIsNotApproximatelyMerged() {
        val longitude = Double.fromBits(first.longitude.toBits() + 1)
        val latitude = Double.fromBits(first.latitude.toBits() + 1)
        val one = applied(RunManualDraft.empty(amap).append(draw()))
        val two = applied(one.append(longitude, latitude, gcj))
        assertEquals(2, two.pointCount); assertTrue(two.canCommit)
        assertEquals(longitude.toBits(), two.points.last().longitude.toBits())
        assertEquals(latitude.toBits(), two.points.last().latitude.toBits())
    }

    @Test fun nonAdjacentReturnToTheFirstPointIsAnAllowedLoop() {
        val draft = applied(two().append(draw(first)))
        assertEquals(listOf(first, second, first), draft.points)
        assertTrue(draft.canCommit); assertTrue(draft.commit().accepted)
    }

    @Test fun bulkAppendKeepsOrderAndDoesNotRetainTheCallerMutableEventList() {
        val input = mutableListOf(draw(first), draw(second), draw(third))
        val draft = applied(RunManualDraft.empty(amap).appendAll(input))
        input[0] = draw(GeoPoint(10.0, 20.0)); input.clear()
        assertEquals(listOf(first, second, third), draft.points)
    }

    @Test fun bulkAppendIsAtomicWhenALaterFrameIsWrong() {
        val before = applied(RunManualDraft.empty(amap).append(draw(first)))
        rejected(before, before.appendAll(listOf(draw(second), draw(third, wgs))), RunManualDraftError.FRAME_MISMATCH)
        assertEquals(listOf(first), before.points)
    }

    @Test fun bulkAppendIsAtomicWhenALaterFrameIsUnknown() {
        val before = RunManualDraft.empty(amap)
        rejected(before, before.appendAll(listOf(draw(first), draw(second, RunMapCoordinateSystem.UNKNOWN))), RunManualDraftError.UNKNOWN_FRAME)
        assertTrue(before.points.isEmpty())
    }

    @Test fun bulkAppendIsAtomicForAnAdjacentDuplicateInsideTheBatch() {
        val before = RunManualDraft.empty(amap)
        rejected(before, before.appendAll(listOf(draw(first), draw(second), draw(second))), RunManualDraftError.ADJACENT_DUPLICATE)
        assertEquals(0, before.pointCount)
    }

    @Test fun bulkAppendChecksTheBoundaryAgainstTheExistingLastPoint() {
        val before = two()
        rejected(before, before.appendAll(listOf(draw(second), draw(third))), RunManualDraftError.ADJACENT_DUPLICATE)
    }

    @Test fun emptyBulkAppendIsAnAcceptedIdentityNoOp() {
        val draft = two()
        val edit = draft.appendAll(emptyList())
        assertTrue(edit.accepted); assertNull(edit.error); assertSame(draft, edit.draft)
    }

    @Test fun manualPointBudgetIsFiniteAndBelowTheKnownRendererBudget() {
        assertTrue(RunManualDraft.MAX_POINTS in 2..20_000)
        assertEquals(RunManualDraft.MAX_POINTS, full().pointCount)
    }

    @Test fun aFullDraftRejectsAnotherDistinctPointWithoutTruncation() {
        val draft = full()
        rejected(draft, draft.append(100.5, 20.0, gcj), RunManualDraftError.POINT_LIMIT_REACHED)
        assertEquals(RunManualDraft.MAX_POINTS, draft.pointCount)
    }

    @Test fun oversizedBulkInputDoesNotSilentlyAcceptItsPrefix() {
        val draft = RunManualDraft.empty(amap)
        val oversized = List(RunManualDraft.MAX_POINTS + 1) { draw(GeoPoint(100.0 + it / 10_000.0, 20.0)) }
        rejected(draft, draft.appendAll(oversized), RunManualDraftError.POINT_LIMIT_REACHED)
        assertEquals(0, draft.pointCount)
    }

    @Test fun bulkCapacityCountsTheAlreadyRetainedPoints() {
        val draft = applied(RunManualDraft.empty(amap).append(draw(first)))
        val events = List(RunManualDraft.MAX_POINTS) { draw(GeoPoint(100.0 + it / 10_000.0, 20.0)) }
        rejected(draft, draft.appendAll(events), RunManualDraftError.POINT_LIMIT_REACHED)
        assertEquals(1, draft.pointCount)
    }

    @Test fun undoRemovesOnlyTheLastPointAndLeavesPriorDraftUnchanged() {
        val before = applied(two().append(draw(third)))
        val after = applied(before.undo())
        assertEquals(listOf(first, second), after.points)
        assertEquals(listOf(first, second, third), before.points)
        assertEquals(amap, after.provider); assertEquals(gcj, after.coordinateSystem)
    }

    @Test fun undoingOnePointProducesAnUncommittableEmptyDraft() {
        val one = applied(RunManualDraft.empty(tdt).append(draw(first, wgs)))
        val empty = applied(one.undo())
        assertEquals(0, empty.pointCount); assertFalse(empty.canCommit)
        assertEquals(wgs, empty.coordinateSystem); assertEquals(1, one.pointCount)
    }

    @Test fun undoOnEmptyIsAnExplicitRejectedNoOp() {
        val draft = RunManualDraft.empty(amap)
        rejected(draft, draft.undo(), RunManualDraftError.EMPTY_DRAFT)
    }

    @Test fun undoFreesCapacityAndANewPointCanBeAppended() {
        val before = full()
        val undone = applied(before.undo())
        val refilled = applied(undone.append(100.5, 20.0, gcj))
        assertEquals(RunManualDraft.MAX_POINTS - 1, undone.pointCount)
        assertEquals(RunManualDraft.MAX_POINTS, refilled.pointCount)
        assertEquals(GeoPoint(100.5, 20.0), refilled.points.last())
        assertEquals(RunManualDraft.MAX_POINTS, before.pointCount)
    }

    @Test fun clearPreservesProviderFrameButNotPointsAndAllowsNewDrawing() {
        val before = two(tdt)
        val empty = before.clear()
        assertEquals(tdt, empty.provider); assertEquals(wgs, empty.coordinateSystem)
        assertEquals(0, empty.pointCount); assertFalse(empty.canCommit)
        val next = applied(empty.append(draw(third, wgs)))
        assertEquals(listOf(third), next.points); assertEquals(listOf(first, second), before.points)
    }

    @Test fun clearingEmptyIsIdempotentAndDoesNotReportAnError() {
        val empty = RunManualDraft.empty(amap)
        assertSame(empty, empty.clear()); assertTrue(empty.points.isEmpty())
    }

    @Test fun providerChangeUsesANewEmptyDraftAndRejectsTheOldFrame() {
        val previous = two(amap)
        val replacement = RunManualDraft.empty(tdt)
        rejected(replacement, replacement.append(draw(third, gcj)), RunManualDraftError.FRAME_MISMATCH)
        assertEquals(0, replacement.pointCount); assertEquals(2, previous.pointCount)
    }

    @Test fun emptyAndSinglePointCommitFailWithoutAnyRoute() {
        val empty = RunManualDraft.empty(amap)
        val one = applied(empty.append(draw()))
        for (draft in listOf(empty, one)) {
            assertFalse(draft.canCommit)
            val result = draft.commit()
            assertFalse(result.accepted); assertNull(result.route)
            assertEquals(RunManualDraftError.TOO_FEW_POINTS, result.error)
        }
    }

    @Test fun commitExportsOneSegmentWithItsOriginalProviderFrameAndOrder() {
        for (provider in listOf(amap, tdt)) {
            val draft = two(provider)
            val result = draft.commit()
            assertTrue(result.accepted); assertNull(result.error)
            val route = assertNotNull(result.route)
            assertEquals(provider, route.provider); assertEquals(draft.coordinateSystem, route.coordinateSystem)
            assertEquals(2, route.pointCount); assertEquals(listOf(first, second), route.points)
            assertEquals(listOf(listOf(first, second)), route.segments)
        }
    }

    @Test fun committedSnapshotSurvivesFurtherAppendUndoAndClear() {
        val before = two()
        val route = assertNotNull(before.commit().route)
        val edited = applied(before.append(draw(third)))
        applied(edited.undo()).clear()
        assertEquals(listOf(first, second), route.points); assertEquals(listOf(first, second), before.points)
        assertEquals(listOf(first, second, third), edited.points)
    }

    @Test fun draftPointReadsAreDetachedAndCallerMutationCannotChangeState() {
        val draft = two()
        val copy = draft.points
        assertNotSame(copy, draft.points)
        attemptClear(copy)
        assertEquals(listOf(first, second), draft.points); assertEquals(2, draft.pointCount)
        assertTrue(draft.canCommit)
    }

    @Test fun exportedPointAndNestedSegmentReadsAreDetached() {
        val route = assertNotNull(two().commit().route)
        val points = route.points
        val segments = route.segments
        assertNotSame(points, route.points); assertNotSame(segments.single(), route.segments.single())
        attemptClear(points); attemptClear(segments.single()); attemptClear(segments)
        assertEquals(listOf(first, second), route.points)
        assertEquals(listOf(listOf(first, second)), route.segments)
    }

    @Test fun exportedRouteCopiesItsInputCollection() {
        val input = mutableListOf(first, second)
        val route = RunManualRoute(amap, gcj, input)
        input[0] = third; input.clear()
        assertEquals(listOf(first, second), route.points)
    }

    @Test fun repeatedCommitsDoNotAliasMutablePointLists() {
        val draft = two()
        val firstExport = assertNotNull(draft.commit().route)
        val nextExport = assertNotNull(draft.commit().route)
        assertNotSame(firstExport, nextExport)
        val detached = firstExport.points
        attemptClear(detached)
        assertEquals(listOf(first, second), nextExport.points)
        assertEquals(listOf(first, second), draft.points)
    }

    @Test fun allModelDebugStringsRedactCoordinates() {
        val draft = two()
        val edit = draft.append(draw(third))
        val commit = draft.commit()
        val route = assertNotNull(commit.route)
        for (text in listOf(draft.toString(), edit.toString(), commit.toString(), route.toString())) {
            assertTrue(text.contains("provider=AMAP")); assertTrue(text.contains("frame=GCJ02"))
            assertTrue(text.contains("count="))
            assertFalse(text.contains(first.longitude.toString())); assertFalse(text.contains(first.latitude.toString()))
            assertFalse(text.contains("GeoPoint")); assertFalse(text.contains("longitude")); assertFalse(text.contains("latitude"))
        }
    }

    @Test fun failureDebugStringsAlsoRedactTheRetainedCoordinates() {
        val draft = two()
        val failure = draft.append(draw(second))
        val failedCommit = RunManualDraft.empty(amap).commit()
        for (text in listOf(failure.toString(), failedCommit.toString())) {
            assertFalse(text.contains(first.longitude.toString())); assertFalse(text.contains(first.latitude.toString()))
            assertFalse(text.contains(second.longitude.toString())); assertFalse(text.contains("GeoPoint"))
        }
    }

    @Test fun errorsAreLocaleFreeEnumsNotPresentationMessages() {
        for (error in RunManualDraftError.entries) assertTrue(Regex("^[A-Z_]+$").matches(error.name))
    }
}
