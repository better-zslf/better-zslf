package io.github.betterzslf.platform

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.edition.BuildEdition
import kotlinx.serialization.json.*
import kotlin.test.*

/** Pure owned-document tests. No WebView, provider CDN, native location or school API. */
class RunMapWebTest {
    private val documentId = "owned_map_document_123"
    private val key = "public_provider_key_123"
    private val beijing = GeoPoint(116.397128, 39.916527)
    private fun model(provider: RunMapProvider = RunMapProvider.AMAP) = RunMapModel(provider = provider, apiKey = key)
    private fun data(model: RunMapModel): JsonObject {
        val html = assertNotNull(RunMapWeb.document(model, documentId).html)
        val json = html.substringAfter("<script id=\"run-map-data\" type=\"application/json\">").substringBefore("</script>")
        return Json.parseToJsonElement(json).jsonObject
    }
    private fun event(type: String, extra: String = "", token: String = documentId): String =
        "{\"documentId\":\"$token\",\"type\":\"$type\"$extra}"
    private fun point(value: JsonElement): GeoPoint = value.jsonArray.let { GeoPoint(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) }

    @Test fun providerMappingNeverInventsAProviderOrDefaultKey() {
        assertEquals(RunMapProvider.AMAP, runMapProviderFor("1"))
        assertEquals(RunMapProvider.TIAN_DI_TU, runMapProviderFor("2"))
        for (value in listOf(null, "", "0", "3", "AMAP", "TDT")) assertNull(runMapProviderFor(value))
        val result = RunMapWeb.document(RunMapModel(), documentId)
        assertNull(result.html)
        assertEquals(RunMapFailure.CONFIGURATION, result.issue)
    }

    @Test fun configurationFailuresAreTypedAndContainNoInputDetails() {
        for (invalid in listOf(model().copy(apiKey = null), model().copy(apiKey = ""),
            model().copy(apiKey = "x".repeat(257)), model().copy(apiKey = "</script>private"), model().copy(provider = null))) {
            val result = RunMapWeb.document(invalid, documentId)
            assertEquals(RunMapFailure.CONFIGURATION, result.issue)
            assertNull(result.html)
            assertFalse(result.toString().contains(key))
            assertNull(RunMapWeb.updateScript(invalid, documentId))
        }
        for (token in listOf("", "bad</script>", "a".repeat(129))) {
            assertEquals(RunMapFailure.CONFIGURATION, RunMapWeb.document(model(), token).issue)
        }
    }

    @Test fun emptyUnknownPathsAreAllowedButNonemptyUnknownFramesAreNot() {
        assertNull(RunMapWeb.document(model(), documentId).issue)
        val path = RunMapPath(listOf(beijing))
        val invalid = listOf(model().copy(actualPath = path), model().copy(recommendedPath = path),
            model().copy(importedPath = path), model().copy(currentLocation = beijing, currentCoordinateSystem = RunMapCoordinateSystem.UNKNOWN),
            model().copy(checkpoints = listOf(RunMapCheckpoint("point", beijing))))
        for (value in invalid) {
            assertEquals(RunMapFailure.UNCONFIRMED_COORDINATES, RunMapWeb.document(value, documentId).issue)
            assertNull(RunMapWeb.updateScript(value, documentId))
        }
    }

    @Test fun pointAndMarkerCapsAreAggregateAndRejectBeforeRendering() {
        val many = RunMapPath(List(20_000) { beijing }, RunMapCoordinateSystem.GCJ02)
        assertNotNull(RunMapWeb.document(model().copy(actualPath = many), documentId).html)
        for (value in listOf(model().copy(actualPath = many.copy(points = many.points + beijing)),
            model().copy(actualPath = many, currentLocation = beijing),
            model().copy(actualPath = many, recommendedPath = RunMapPath(listOf(beijing), RunMapCoordinateSystem.GCJ02)),
            model().copy(actualPath = RunMapPath(segments = List(20_001) { emptyList() })))) {
            assertEquals(RunMapFailure.TOO_MANY_POINTS, RunMapWeb.document(value, documentId).issue)
        }
        val markers = List(256) { RunMapCheckpoint("marker-$it", beijing, coordinateSystem = RunMapCoordinateSystem.GCJ02) }
        assertNotNull(RunMapWeb.document(model().copy(checkpoints = markers), documentId).html)
        assertEquals(RunMapFailure.TOO_MANY_POINTS, RunMapWeb.document(model().copy(checkpoints = markers + markers.first().copy(id = "extra")), documentId).issue)
    }

    @Test fun markerIdsAreUniqueBoundedAndLabelsAreShort() {
        val marker = RunMapCheckpoint("known", beijing, coordinateSystem = RunMapCoordinateSystem.GCJ02)
        for (markers in listOf(listOf(marker, marker), listOf(marker.copy(id = "")),
            listOf(marker.copy(id = "x".repeat(129))), listOf(marker.copy(id = "bad\n")), listOf(marker.copy(label = "x".repeat(81))))) {
            assertEquals(RunMapFailure.CONFIGURATION, RunMapWeb.document(model().copy(checkpoints = markers), documentId).issue)
        }
    }

    @Test fun jsonEscapingPreventsScriptAndMarkupInjectionWithoutLosingLabels() {
        val label = "</script><img src=x onerror=alert(1)>&\u2028\u2029"
        val id = "id</script>&"
        val input = model().copy(checkpoints = listOf(RunMapCheckpoint(id, beijing, label, RunMapCoordinateSystem.GCJ02)))
        val html = assertNotNull(RunMapWeb.document(input, documentId).html)
        assertFalse(html.contains(label))
        assertFalse(html.contains(id))
        assertTrue(html.contains("\\u003c/script\\u003e"))
        assertTrue(html.contains("\\u0026"))
        assertTrue(html.contains("\\u2028"))
        assertTrue(html.contains("\\u2029"))
        val checkpoint = data(input)["checkpoints"]!!.jsonArray.single().jsonObject
        assertEquals(label, checkpoint["label"]!!.jsonPrimitive.content)
        assertEquals(id, checkpoint["id"]!!.jsonPrimitive.content)
        assertTrue(html.contains("node.textContent=text"))
        assertFalse(html.contains(".innerHTML"))
        val update = assertNotNull(RunMapWeb.updateScript(input, documentId))
        assertFalse(update.contains(label))
    }

    @Test fun segmentsTakePrecedenceAndNeverGenerateABridgingPolyline() {
        val a = GeoPoint(110.0, 30.0); val b = GeoPoint(110.1, 30.1)
        val c = GeoPoint(113.0, 33.0); val d = GeoPoint(113.1, 33.1)
        val ignored = GeoPoint(1.0, 1.0)
        val segments = listOf(listOf(a, b), emptyList(), listOf(c, d))
        val path = RunMapPath(listOf(ignored), RunMapCoordinateSystem.GCJ02, segments)
        val value = data(model().copy(actualPath = path))["actual"]!!.jsonArray
        assertEquals(2, value.size)
        assertEquals(listOf(a, b), value[0].jsonArray.map(::point))
        assertEquals(listOf(c, d), value[1].jsonArray.map(::point))
        assertEquals(segments, path.segments)
        assertEquals(listOf(ignored), path.points)
    }

    @Test fun providerFrameConversionIsExplicitAndOriginalSamplesAreUntouched() {
        val source = listOf(beijing)
        val amap = model().copy(actualPath = RunMapPath(source, RunMapCoordinateSystem.WGS84), currentLocation = beijing)
        val amapData = data(amap)
        assertEquals("GCJ02", amapData["frame"]!!.jsonPrimitive.content)
        val shifted = RunMapCoordinates.wgs84ToGcj02(beijing)
        assertEquals(shifted, point(amapData["actual"]!!.jsonArray.single().jsonArray.single()))
        assertEquals(shifted, point(amapData["current"]!!))
        val tdt = model(RunMapProvider.TIAN_DI_TU).copy(recommendedPath = RunMapPath(listOf(shifted), RunMapCoordinateSystem.GCJ02))
        val unshifted = point(data(tdt)["recommended"]!!.jsonArray.single().jsonArray.single())
        assertEquals(beijing.longitude, unshifted.longitude, 1e-7)
        assertEquals(beijing.latitude, unshifted.latitude, 1e-7)
        assertEquals(listOf(beijing), source)
        assertEquals(beijing, amap.currentLocation)
    }

    @Test fun initialCenterUsesARealPointOtherwiseLowZoomMainlandView() {
        val empty = data(model())
        assertEquals(GeoPoint(105.0, 35.0), point(empty["center"]!!))
        assertEquals(4, empty["zoom"]!!.jsonPrimitive.int)
        val located = data(model().copy(currentLocation = beijing))
        assertEquals(RunMapCoordinates.wgs84ToGcj02(beijing), point(located["center"]!!))
        assertEquals(15, located["zoom"]!!.jsonPrimitive.int)
    }

    @Test fun ownedCspUsesOnlyTheSelectedProviderAndDeniesInteractiveCapabilities() {
        for (provider in RunMapProvider.entries) {
            val html = assertNotNull(RunMapWeb.document(model(provider), documentId).html)
            val csp = html.substringAfter("Content-Security-Policy\" content=\"").substringBefore('"')
            for (directive in listOf("default-src 'none'", "frame-src 'none'", "child-src 'none'", "form-action 'none'", "media-src 'none'", "object-src 'none'", "base-uri 'none'")) assertTrue(csp.contains(directive))
            assertFalse(csp.contains("http:"))
            if (provider == RunMapProvider.AMAP) {
                assertTrue(csp.contains("https://*.amap.com")); assertFalse(csp.contains("tianditu"))
                assertTrue(html.contains("v=1.4.15"))
            } else {
                assertTrue(csp.contains("https://*.tianditu.gov.cn")); assertFalse(csp.contains("amap.com"))
                assertTrue(html.contains("api?v=4.0"))
            }
            assertTrue(html.indexOf("if(!permissions())") < html.indexOf("document.write("))
            assertTrue(html.contains("writable:false,configurable:false"))
            assertTrue(html.contains("lock(proto,'geolocation',geo)"))
            assertTrue(html.contains("lock(mp,'getUserMedia',denyMedia)"))
            assertTrue(html.contains("postMessage(message)"))
            assertTrue(html.contains("const message=JSON.stringify(value)"))
            assertFalse(html.contains("console.log"))
        }
    }

    @Test fun updatesCarryTheDocumentTokenAndDoNotRebuildOrRecenterTheMap() {
        val update = assertNotNull(RunMapWeb.updateScript(model().copy(dark = true, editMode = true), documentId))
        assertTrue(update.contains("window.__runMapApply"))
        assertTrue(update.contains(documentId))
        assertFalse(update.contains("new AMap.Map"))
        assertFalse(update.contains("new T.Map"))
        assertFalse(update.contains("centerAndZoom("))
        assertFalse(update.contains("setCenter("))
        val html = assertNotNull(RunMapWeb.document(model(), documentId).html)
        assertTrue(html.contains("next.documentId!==documentId"))
        assertTrue(html.contains("next.apiKey!==state.apiKey"))
    }

    @Test fun eventsRequireCurrentNonceStrictKeysAndOneFlatJsonObject() {
        assertEquals(RunMapEvent.Ready, RunMapWeb.parseEvent(event("ready"), model(), documentId))
        for (message in listOf(event("ready", token = "stale"), "{}", "[]", "null", "bad", "{".repeat(100),
            event("ready", ",\"extra\":true"), event("ready", ",\"nested\":{}"),
            "{\"documentId\":\"stale\",\"documentId\":\"$documentId\",\"type\":\"ready\"}",
            event("nativeLocation"), event("ready") + " ".repeat(4097))) {
            assertNull(RunMapWeb.parseEvent(message, model(), documentId))
        }
    }

    @Test fun failedEventsUseOnlyGenericTypedReasonsNeverProviderDetails() {
        assertEquals(RunMapEvent.Failed(RunMapFailure.NETWORK), RunMapWeb.parseEvent(event("failed", ",\"reason\":\"NETWORK\""), model(), documentId))
        assertEquals(RunMapEvent.Failed(RunMapFailure.SDK), RunMapWeb.parseEvent(event("failed", ",\"reason\":\"SDK\""), model(), documentId))
        for (extra in listOf(",\"reason\":\"private-key-error\"", ",\"reason\":\"SDK\",\"url\":\"https://private\"", ",\"reason\":null")) {
            assertNull(RunMapWeb.parseEvent(event("failed", extra), model(), documentId))
        }
    }

    @Test fun drawEventsAreEditingOnlyAndStayInTheirProviderFrameNotNativeGps() {
        val draw = event("draw", ",\"longitude\":116.4,\"latitude\":39.9,\"frame\":\"GCJ02\"")
        assertNull(RunMapWeb.parseEvent(draw, model(), documentId))
        val expected = if (BuildEdition.isPro) RunMapEvent.DrawPoint(GeoPoint(116.4, 39.9), RunMapCoordinateSystem.GCJ02) else null
        assertEquals(expected, RunMapWeb.parseEvent(draw, model().copy(editMode = true), documentId))
        assertNull(RunMapWeb.parseEvent(draw, model(RunMapProvider.TIAN_DI_TU).copy(editMode = true), documentId))
        assertNull(RunMapWeb.parseEvent(draw.dropLast(1) + ",\"native\":true}", model().copy(editMode = true), documentId))
    }

    @Test fun drawRejectsNonfiniteOutOfRangeQuotedAndMalformedNumbers() {
        for ((longitude, latitude) in listOf("181" to "0", "0" to "91", "1e309" to "0", "NaN" to "0", "\"116.4\"" to "39.9", "0" to "null", "false" to "0")) {
            val message = event("draw", ",\"longitude\":$longitude,\"latitude\":$latitude,\"frame\":\"GCJ02\"")
            assertNull(RunMapWeb.parseEvent(message, model().copy(editMode = true), documentId))
        }
        val edge = event("draw", ",\"longitude\":180,\"latitude\":90,\"frame\":\"WGS84\"")
        val parsedEdge = RunMapWeb.parseEvent(edge, model(RunMapProvider.TIAN_DI_TU).copy(editMode = true), documentId)
        if (BuildEdition.isPro) assertNotNull(parsedEdge) else assertNull(parsedEdge)
    }

    @Test fun checkpointSelectionRequiresAnIdStillPresentInTheLatestModel() {
        val marker = RunMapCheckpoint("known", beijing, coordinateSystem = RunMapCoordinateSystem.GCJ02)
        val input = model().copy(checkpoints = listOf(marker))
        val message = event("checkpoint", ",\"id\":\"known\"")
        assertEquals(RunMapEvent.CheckpointSelected("known"), RunMapWeb.parseEvent(message, input, documentId))
        assertNull(RunMapWeb.parseEvent(message, input.copy(checkpoints = emptyList()), documentId))
        assertNull(RunMapWeb.parseEvent(event("checkpoint", ",\"id\":\"unknown\""), input, documentId))
    }

    @Test fun requestWhitelistChecksSchemePortCredentialsAndExactDomainBoundary() {
        for (url in listOf("https://webapi.amap.com/maps?v=1.4.15", "https://AMAP.COM:443/", "https://wprd01.is.autonavi.com/appmaptile")) assertTrue(RunMapWeb.isAllowedRequest(url, RunMapProvider.AMAP))
        for (url in listOf("https://api.tianditu.gov.cn/api?v=4.0", "https://t0.tianditu.gov.cn/vec_w/wmts", "https://tianditu.com/")) assertTrue(RunMapWeb.isAllowedRequest(url, RunMapProvider.TIAN_DI_TU))
        for (url in listOf("http://webapi.amap.com", "file:///tmp/map", "content://map", "https://school.edu.cn/", "https://evilamap.com/", "https://webapi.amap.com.school.edu.cn/", "https://user:pass@webapi.amap.com/", "https://webapi.amap.com:80/", "https://webapi.amap.com:0443/", "https://webapi.amap.com./", "https://webapi%2eamap.com/", "https://webapi.amap.com\\@school.edu.cn", "https://webapi.amap.com/\n", "https://ｗebapi.amap.com/")) assertFalse(RunMapWeb.isAllowedRequest(url, RunMapProvider.AMAP))
        assertFalse(RunMapWeb.isAllowedRequest("https://api.tianditu.gov.cn/", RunMapProvider.AMAP))
        assertFalse(RunMapWeb.isAllowedRequest("https://webapi.amap.com/", RunMapProvider.TIAN_DI_TU))
        assertFalse(RunMapWeb.isAllowedRequest("https://webapi.amap.com/", null))
    }

    @Test fun onlyNecessaryImageDataAndOwnedBlobResourcesAreAllowed() {
        val owned = "blob:https://map.better-zslf.invalid/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"
        assertTrue(RunMapWeb.isAllowedRequest("data:image/png;base64,AA==", RunMapProvider.AMAP))
        assertTrue(RunMapWeb.isAllowedRequest(owned, RunMapProvider.AMAP))
        for (url in listOf("data:text/html,<script>x</script>", "data:image/svg+xml,<svg/>", "blob:https://school.edu.cn/AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "blob:unknown")) assertFalse(RunMapWeb.isAllowedRequest(url, RunMapProvider.AMAP))
        assertFalse(RunMapWeb.isAllowedRequest(owned, null))
    }

    @Test fun editionSelectionRemovesDrawingBridgeButKeepsRealAndServerPaths() {
        val points = listOf(beijing, GeoPoint(116.398, 39.917))
        val input = model().copy(editMode = true,
            actualPath = RunMapPath(points, RunMapCoordinateSystem.GCJ02),
            recommendedPath = RunMapPath(points, RunMapCoordinateSystem.GCJ02))
        val html = assertNotNull(RunMapWeb.document(input, documentId).html)
        assertEquals(BuildEdition.isPro, html.contains("function clicked(event)"))
        assertEquals(BuildEdition.isPro, html.contains("post('draw'"))
        assertEquals(BuildEdition.isPro, data(input)["editMode"]!!.jsonPrimitive.boolean)
        assertEquals(2, data(input)["actual"]!!.jsonArray.single().jsonArray.size)
        assertEquals(2, data(input)["recommended"]!!.jsonArray.single().jsonArray.size)
        assertTrue(assertNotNull(RunMapWeb.updateScript(input, documentId)).contains("\"editMode\":${BuildEdition.isPro}"))
    }

    @Test fun navigationNeverOpensProviderExternalOrLocalPages() {
        assertTrue(RunMapWeb.isAllowedNavigation(RunMapWeb.baseUrl))
        assertTrue(RunMapWeb.isAllowedNavigation("about:blank"))
        for (url in listOf("https://webapi.amap.com/", "https://school.edu.cn/", "file:///tmp/map", "content://map", RunMapWeb.baseUrl + "other", RunMapWeb.baseUrl + "#fragment", "javascript:alert(1)")) assertFalse(RunMapWeb.isAllowedNavigation(url))
    }

    @Test fun diagnosticsNeverIncludePublicKeyCoordinatesOrHtml() {
        val document = RunMapWeb.document(model().copy(currentLocation = beijing), documentId)
        assertEquals("RunMapDocument(html=true, issue=null)", document.toString())
        for (value in RunMapFailure.entries.map(::runMapFailureMessage) + document.toString()) {
            assertFalse(value.contains(key)); assertFalse(value.contains(beijing.longitude.toString()))
            assertFalse(value.contains(beijing.latitude.toString())); assertFalse(value.contains("<html"))
        }
    }
}
