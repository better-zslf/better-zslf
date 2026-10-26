package io.github.betterzslf.feature.runui

import io.github.betterzslf.feature.run.*
import io.github.betterzslf.platform.MAX_PICKED_FILE_BYTES
import io.github.betterzslf.platform.PickedFile
import io.github.betterzslf.platform.RunMapCoordinates
import io.github.betterzslf.platform.RunMapCoordinateSystem
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class RunImportControllerTest {
    private val origin = GeoPoint(113.4, 22.5)
    private val points = (0..10).map { RunGeo.offset(origin, it * 20.0, 0.0) }
    private val serverPoints = points.map(RunMapCoordinates::wgs84ToGcj02)
    private fun detail(minimum: Double? = 100.0, checkpoints: List<RunCheckpoint> = listOf(RunCheckpoint("fixture-checkpoint", origin)), roads: List<List<GeoPoint>> = listOf(points)) = RunTaskDetail(
        RunTaskItem(null, "fixture-schedule", "Fixture route", null, null, "0", "fixture-key", JsonObject(emptyMap()), errandId = "fixture-errand"),
        checkpoints.map { it.copy(point = RunMapCoordinates.wgs84ToGcj02(it.point)) }, roads.map { it.map(RunMapCoordinates::wgs84ToGcj02) }, minimum, emptyList())
    private fun controller(detail: RunTaskDetail = detail()) = RunImportController(detail) { 1_700_000_000_000L }
    private fun gpx(segments: List<List<GeoPoint>> = listOf(points)) = "<gpx><trk>" + segments.joinToString("") { segment ->
        "<trkseg>" + segment.joinToString("") { "<trkpt lon=\"${it.longitude}\" lat=\"${it.latitude}\"/>" } + "</trkseg>"
    } + "</trk></gpx>"
    private fun picked(text: String, name: String = "fixture.gpx") = PickedFile(name, "application/gpx+xml", text.encodeToByteArray())

    @Test fun gpxRetainsOriginalSegmentsAndDoesNotGenerateOrPersistAnything() = runTest {
        val c = controller()
        assertTrue(c.read(picked(gpx())))
        assertEquals(listOf(points), c.state.value.originalSegments)
        assertEquals(points, c.state.value.workingPoints)
        assertEquals(RunImportSource.GPX_FILE, c.state.value.source)
        assertNull(c.state.value.validation)
        assertNull(c.state.value.generated)
        assertFalse(c.state.value.normalized)
    }

    @Test fun kmlUsesLongitudeLatitudeAndPreservesGenuineZeroCoordinate() = runTest {
        val c = controller()
        assertTrue(c.read(picked("<kml><Document><Placemark><LineString><coordinates>0,0 0.0001,0</coordinates></LineString></Placemark></Document></kml>", "fixture.KML")))
        assertEquals(RunImportSource.KML_FILE, c.state.value.source)
        assertEquals(GeoPoint(0.0, 0.0), c.state.value.originalPoints.first())
    }

    @Test fun failedFileSelectionKeepsPreviousOriginalAndWorkingCopy() = runTest {
        val c = controller()
        c.read(picked(gpx()))
        assertFalse(c.read(picked("not xml")))
        assertEquals(points, c.state.value.originalPoints)
        assertEquals(points, c.state.value.workingPoints)
        assertNotNull(c.state.value.error)
    }

    @Test fun invalidUtf8HasAnExplicitErrorAndDoesNotApplyReplacementCharacters() = runTest {
        val c = controller()
        assertFalse(c.read(PickedFile("fixture.gpx", "application/gpx+xml", byteArrayOf(0xc3.toByte(), 0x28))))
        assertTrue(c.state.value.error!!.contains("UTF-8"))
        assertTrue(c.state.value.originalSegments.isEmpty())
    }

    @Test fun aWrongExtensionAndCompressedKmzStayOutsideTheXmlRouteParser() = runTest {
        val c = controller()
        assertFalse(c.read(picked(gpx(), "fixture.txt")))
        assertFalse(c.read(picked(gpx(), "fixture.kmz")))
        assertTrue(c.state.value.originalPoints.isEmpty())
    }

    @Test fun xmlExternalEntitiesAndDocumentTypesAreRejectedWithoutResolution() = runTest {
        val c = controller()
        val xml = "<!DOCTYPE gpx [<!ENTITY external SYSTEM 'https://fixture.invalid/entity'>]><gpx><trk><trkseg><trkpt lon='113.4' lat='22.5'/><trkpt lon='113.4001' lat='22.5'/></trkseg></trk></gpx>"
        assertFalse(c.read(picked(xml)))
        assertTrue(c.state.value.originalPoints.isEmpty())
    }

    @Test fun filePickerAndParserBothRetainTheirIndependentBounds() = runTest {
        assertFailsWith<IllegalArgumentException> { PickedFile("fixture.gpx", "application/gpx+xml", ByteArray(MAX_PICKED_FILE_BYTES + 1)) }
        val c = controller()
        assertFalse(c.read(picked(" ".repeat(RunRouteParser.MAX_DOCUMENT_CHARS + 1))))
        assertNotNull(c.state.value.error)
    }

    @Test fun recommendedSegmentsAreNotAutomaticallyNormalizedOrGenerated() = runTest {
        val c = controller()
        assertTrue(c.useRecommended())
        assertEquals(RunImportSource.RECOMMENDED, c.state.value.source)
        assertEquals(listOf(serverPoints), c.state.value.originalSegments)
        assertEquals(RunMapCoordinateSystem.GCJ02, c.state.value.originalCoordinateSystem)
        assertEquals(RunMapCoordinateSystem.WGS84, c.state.value.workingCoordinateSystem)
        assertTrue(points.zip(c.state.value.workingPoints).all { (a, b) -> RunGeo.distanceMeters(a, b) < 0.01 })
        assertFalse(c.state.value.normalized)
        assertNull(c.state.value.generated)
    }

    @Test fun missingRecommendedPathsAreAnExplicitEmptyState() = runTest {
        val c = controller(detail(roads = emptyList()))
        assertFalse(c.useRecommended())
        assertNotNull(c.state.value.error)
    }

    @Test fun normalizeIsExplicitAndNeverMutatesTheOriginalImportedPoints() = runTest {
        val sparse = listOf(origin, RunGeo.offset(origin, 200.0, 0.0))
        val c = controller()
        c.read(picked(gpx(listOf(sparse))))
        c.validate(100.0, 200_000)
        assertTrue(c.state.value.validation!!.issues.any { it.code == RouteIssueCode.SPARSE })
        assertTrue(c.normalize())
        assertEquals(sparse, c.state.value.originalPoints)
        assertTrue(c.state.value.workingPoints.size > sparse.size)
        assertTrue(c.state.value.normalized)
        assertNull(c.state.value.validation)
    }

    @Test fun distantSegmentsRemainSeparateAndCannotBeSilentlyBridgedByNormalize() = runTest {
        val segments = listOf(points.take(2), listOf(RunGeo.offset(origin, 500.0, 0.0), RunGeo.offset(origin, 520.0, 0.0)))
        val c = controller()
        c.read(picked(gpx(segments)))
        assertFalse(c.normalize())
        assertEquals(segments, c.state.value.originalSegments)
        assertFalse(c.state.value.normalized)
        assertNull(c.state.value.generated)
    }

    @Test fun validationShowsFourIndependentRuleFamiliesWithoutInventingServerSuccess() = runTest {
        val missed = RunCheckpoint("missed", RunGeo.offset(origin, 1000.0, 0.0))
        val c = controller(detail(checkpoints = listOf(missed)))
        c.read(picked(gpx(listOf(listOf(origin, RunGeo.offset(origin, 200.0, 0.0))))))
        assertTrue(c.validate(500.0, 10_000))
        val codes = c.state.value.validation!!.issues.map { it.code }
        assertTrue(RouteIssueCode.MISSING_CHECKPOINT in codes)
        assertTrue(RouteIssueCode.DISTANCE_TOO_SHORT in codes)
        assertTrue(RouteIssueCode.SPEED in codes)
        assertTrue(RouteIssueCode.SPARSE in codes)
        assertNull(c.state.value.generated)
    }

    @Test fun aServerMinimumCannotBeLoweredByTypingAShorterLocalTarget() = runTest {
        val c = controller(detail(minimum = 1000.0))
        c.useRecommended(); c.validate(10.0, 300_000)
        assertTrue(c.state.value.validation!!.issues.any { it.code == RouteIssueCode.DISTANCE_TOO_SHORT })
        assertEquals(10.0, c.state.value.targetDistanceMeters)
    }

    @Test fun invalidTargetDurationAndSamplingAreReportedWithoutReplacingTheRoute() = runTest {
        val c = controller()
        c.useRecommended()
        for (distance in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) assertFalse(c.validate(distance, 200_000))
        assertFalse(c.validate(100.0, 0))
        assertFalse(c.generate(100.0, 200_000, samplingSeconds = 9))
        assertEquals(serverPoints, c.state.value.originalPoints)
        assertNull(c.state.value.generated)
    }

    @Test fun invalidGeometryCannotBeSentToThePreviewGenerator() = runTest {
        val c = controller()
        c.read(picked(gpx(listOf(listOf(origin, RunGeo.offset(origin, 200.0, 0.0))))))
        assertFalse(c.generate(100.0, 200_000))
        assertNull(c.state.value.generated)
        assertNotNull(c.state.value.validation)
    }

    @Test fun generatedPreviewIsDeterministicConsistentAndSeparateFromOriginalRoute() = runTest {
        val c = controller()
        c.useRecommended()
        c.generate(100.0, 200_000, seed = 42)
        val generated = assertNotNull(c.state.value.generated)
        assertTrue(RunTrackValidator.validate(generated).valid)
        assertEquals(serverPoints, c.state.value.originalPoints)
        assertEquals(points.size, c.state.value.workingPoints.size)
        assertTrue(points.zip(c.state.value.workingPoints).all { (a, b) -> RunGeo.distanceMeters(a, b) < 0.01 })
        assertNotEquals(points, generated.points.map { it.point })
        c.generate(100.0, 200_000, seed = 42)
        assertEquals(generated, c.state.value.generated)
        assertEquals(1_700_000_000_000L, generated.startTimeMillis)
    }

    @Test fun generationUsesTheExplicitSamplingSettingWithoutAlteringWireUnits() = runTest {
        val c = controller()
        c.useRecommended()
        c.generate(100.0, 200_000, seed = 7, samplingSeconds = 20)
        val generated = assertNotNull(c.state.value.generated)
        assertEquals(20, c.state.value.generationSamplingSeconds)
        assertEquals(200_000L, generated.endTimeMillis - generated.startTimeMillis)
        assertEquals(200.0, generated.runTimeSeconds)
        assertTrue(generated.points.zipWithNext().all { (a, b) -> b.locatetime > a.locatetime })
        assertTrue(generated.points.zipWithNext().any { (a, b) -> b.locatetime - a.locatetime in 18_000L..22_000L })
    }

    @Test fun legalDetourLeavesOriginalAndFinalEndpointUnchangedAndIsRevalidated() = runTest {
        val c = controller()
        c.useRecommended()
        assertTrue(c.supplement(400.0, 400_000))
        assertEquals(serverPoints, c.state.value.originalPoints)
        assertTrue(c.state.value.detoured)
        assertTrue(RunGeo.distanceMeters(points.last(), c.state.value.workingPoints.last()) < 0.01)
        assertNotNull(c.state.value.validation)
        assertNull(c.state.value.generated)
    }

    @Test fun absentRecommendedRoadsDoNotTurnIntoStraightLineDetourFallback() = runTest {
        val c = controller(detail(roads = emptyList()))
        c.read(picked(gpx()))
        assertFalse(c.supplement(400.0, 400_000))
        assertEquals(points, c.state.value.workingPoints)
        assertFalse(c.state.value.detoured)
    }

    @Test fun startingAnotherFileClearsOnlyLocalPreviewAndDoesNotRetainOldGeneration() = runTest {
        val c = controller()
        c.useRecommended(); c.generate(100.0, 200_000)
        assertNotNull(c.state.value.generated)
        c.read(picked(gpx()))
        assertNull(c.state.value.generated)
        assertNull(c.state.value.validation)
        assertFalse(c.state.value.detoured)
    }

    @Test fun clearDropsOnlyTheTransientDraft() = runTest {
        val c = controller()
        c.useRecommended(); c.generate(100.0, 200_000)
        assertTrue(c.clear())
        assertTrue(c.state.value.originalPoints.isEmpty())
        assertTrue(c.state.value.workingPoints.isEmpty())
        assertNull(c.state.value.generated)
        assertNull(c.state.value.fileName)
    }
}
