package io.github.betterzslf.feature.run

import kotlin.test.*

class RunGeometryAndImportTest {
    private val origin = GeoPoint(113.4, 22.5)

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
