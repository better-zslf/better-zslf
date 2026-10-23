package io.github.betterzslf.feature.run

import kotlin.math.abs
import kotlin.test.*

class RunGeometryAndImportTest {
    private val origin = GeoPoint(113.4, 22.5)

    @Test fun haversineMatchesKnownEquatorialArcAndDateLine() {
        assertEquals(111_195.0802, RunGeo.distanceMeters(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0)), 0.01)
        assertEquals(222_390.1605, RunGeo.distanceMeters(GeoPoint(179.0, 0.0), GeoPoint(-179.0, 0.0)), 0.01)
        assertEquals(0.0, RunGeo.distanceMeters(origin, origin))
        assertEquals(RunGeo.distanceMeters(origin, GeoPoint(0.0, 0.0)), RunGeo.distanceMeters(GeoPoint(0.0, 0.0), origin), 1e-8)
    }

    @Test fun interpolationAndOffsetAreGeodesic() {
        val east = RunGeo.offset(origin, 100.0, 0.0)
        assertEquals(100.0, RunGeo.distanceMeters(origin, east), 1e-6)
        assertEquals(50.0, RunGeo.distanceMeters(origin, RunGeo.interpolate(origin, east, 0.5)), 1e-6)
        val middle = RunGeo.interpolate(GeoPoint(179.0, 0.0), GeoPoint(-179.0, 0.0), 0.5)
        assertEquals(180.0, abs(middle.longitude), 1e-6)
        assertFailsWith<IllegalArgumentException> { GeoPoint(Double.NaN, 0.0) }
        assertFailsWith<IllegalArgumentException> { GeoPoint(181.0, 0.0) }
        assertFailsWith<IllegalArgumentException> { GeoPoint(0.0, 91.0) }
        assertFailsWith<IllegalArgumentException> { RunGeo.interpolate(GeoPoint(0.0, 0.0), GeoPoint(180.0, 0.0), 0.5) }
    }

    @Test fun accuracyAndReportedSpeedThresholdsAreInclusive() {
        val filter = RunLocationFilter()
        val accepted = RunLocationSample(origin, 0, 50.0, 5.0)
        assertIs<LocationFilterResult.Accepted>(filter.evaluate(null, accepted))
        assertEquals(LocationRejection.ACCURACY, assertIs<LocationFilterResult.Rejected>(filter.evaluate(null, accepted.copy(accuracyMeters = 50.001))).reason)
        assertEquals(LocationRejection.SPEED, assertIs<LocationFilterResult.Rejected>(filter.evaluate(null, accepted.copy(speedMetersPerSecond = 5.001))).reason)
        assertEquals(LocationRejection.INVALID_ACCURACY, assertIs<LocationFilterResult.Rejected>(filter.evaluate(null, accepted.copy(accuracyMeters = Double.NaN))).reason)
        assertEquals(LocationRejection.INVALID_SPEED, assertIs<LocationFilterResult.Rejected>(filter.evaluate(null, accepted.copy(speedMetersPerSecond = -1.0))).reason)
    }

    @Test fun inferredSpeedCannotBeHiddenByReportedZero() {
        val filter = RunLocationFilter()
        val first = RunLocationSample(origin, 1_000, 10.0)
        val boundary = RunLocationSample(RunGeo.offset(origin, 50.0, 0.0), 11_000, 10.0, 0.0)
        assertIs<LocationFilterResult.Accepted>(filter.evaluate(first, boundary))
        assertEquals(LocationRejection.SPEED, assertIs<LocationFilterResult.Rejected>(filter.evaluate(first, boundary.copy(point = RunGeo.offset(origin, 50.1, 0.0)))).reason)
        assertEquals(LocationRejection.NON_INCREASING_TIME, assertIs<LocationFilterResult.Rejected>(filter.evaluate(first, first)).reason)
        assertEquals(LocationRejection.INVALID_TIME, assertIs<LocationFilterResult.Rejected>(filter.evaluate(first.copy(timestampMillis = -1), boundary)).reason)
    }

    @Test fun checkpointProgressRequiresTheExpectedOrderAndPunchIndex() {
        val end = RunGeo.offset(origin, 200.0, 0.0)
        val progress = RunCheckpointProgress(listOf(RunCheckpoint("a", origin), RunCheckpoint("b", end)))
        assertFalse(progress.observe(end))
        assertFalse(progress.finished)
        assertFalse(progress.acknowledgePunch(1))
        assertTrue(progress.observe(origin))
        assertEquals(1, progress.nextIndex)
        assertTrue(progress.acknowledgePunch(1))
        assertTrue(progress.finished)
        assertNull(progress.distanceToNext(origin))
        assertFalse(progress.acknowledgePunch(1))
    }

    @Test fun parsesNamespacedGpxTrackAndRouteWithoutMixingThem() {
        val xml = """<?xml version="1.0"?><g:gpx xmlns:g="http://www.topografix.com/GPX/1/1">
            <g:rte><g:rtept lat='0' lon='0'/><g:rtept lon='1' lat='1'/></g:rte>
            <g:trk><g:trkseg><g:trkpt lon="113.4" lat="22.5"><g:ele>12</g:ele></g:trkpt>
            <g:trkpt lat="22.5001" lon="113.4001"/></g:trkseg></g:trk>
            </g:gpx>"""
        val parsed = assertIs<RouteParseResult.Success>(RunRouteParser.parseGpx(xml))
        assertEquals(2, parsed.points.size)
        assertEquals(origin, parsed.points.first())
        val route = assertIs<RouteParseResult.Success>(RunRouteParser.parseGpx("<gpx><rte><rtept lat='1' lon='2'/><rtept lat='1.1' lon='2.1'/></rte></gpx>"))
        assertEquals(GeoPoint(2.0, 1.0), route.points.first())
    }

    @Test fun retainsDisconnectedSegmentsRatherThanInventingRoads() {
        val parsed = assertIs<RouteParseResult.Success>(RunRouteParser.parseGpx("""<gpx><trk>
            <trkseg><trkpt lon='0' lat='0'/><trkpt lon='0.0001' lat='0'/></trkseg>
            <trkseg><trkpt lon='1' lat='1'/><trkpt lon='1.0001' lat='1'/></trkseg>
            </trk></gpx>"""))
        assertEquals(2, parsed.segments.size)
        assertFailsWith<IllegalArgumentException> { RunRouteNormalizer.normalizeSegments(parsed.segments) }
    }

    @Test fun parsesKmlLonLatAltitudeAndIgnoresPlacemarkPoints() {
        val parsed = assertIs<RouteParseResult.Success>(RunRouteParser.parseKml("""<k:kml xmlns:k='urn:kml'><k:Document>
            <k:Placemark><k:Point><k:coordinates>0,0,0</k:coordinates></k:Point></k:Placemark>
            <k:Placemark><k:LineString><k:coordinates><![CDATA[113.4,22.5,12
            113.4001,22.5001,13]]></k:coordinates></k:LineString></k:Placemark>
            </k:Document></k:kml>"""))
        assertEquals(listOf(origin, GeoPoint(113.4001, 22.5001)), parsed.points)
    }

    @Test fun rejectsDtdEntitiesProcessingInstructionsAndMalformedXml() {
        val documents = listOf(
            "<!DOCTYPE gpx [<!ENTITY x SYSTEM 'file:///fixture'>]><gpx>&x;</gpx>",
            "<?xml-stylesheet href='https://fixture.invalid/file'?><gpx/>",
            "<gpx><trk><trkseg></trk></gpx>",
            "<gpx/><gpx/>",
            "<gpx><rte><rtept lon='&custom;' lat='0'/></rte></gpx>",
            "<gpx><rte><rtept lon='1' lon='2' lat='0'/></rte></gpx>",
        )
        documents.forEach { assertIs<RouteParseResult.Failure>(RunRouteParser.parseGpx(it)) }
    }

    @Test fun rejectsInvalidCoordinatesEmptyAndWrongFormats() {
        assertEquals(RouteParseError.INVALID_COORDINATE, assertIs<RouteParseResult.Failure>(RunRouteParser.parseGpx("<gpx><rte><rtept lat='91' lon='0'/><rtept lat='1' lon='1'/></rte></gpx>")).reason)
        assertEquals(RouteParseError.INVALID_COORDINATE, assertIs<RouteParseResult.Failure>(RunRouteParser.parseGpx("<gpx><rte><rtept lat='NaN' lon='0'/><rtept lat='1' lon='1'/></rte></gpx>")).reason)
        assertEquals(RouteParseError.EMPTY, assertIs<RouteParseResult.Failure>(RunRouteParser.parseGpx("  ")).reason)
        assertEquals(RouteParseError.WRONG_FORMAT, assertIs<RouteParseResult.Failure>(RunRouteParser.parseGpx("<kml/>")).reason)
        assertEquals(RouteParseError.NO_ROUTE, assertIs<RouteParseResult.Failure>(RunRouteParser.parseGpx("<gpx/>")).reason)
        assertEquals(RouteParseError.TOO_LARGE, assertIs<RouteParseResult.Failure>(RunRouteParser.parseGpx(" ".repeat(RunRouteParser.MAX_DOCUMENT_CHARS) + "<gpx/>")).reason)
    }
}
