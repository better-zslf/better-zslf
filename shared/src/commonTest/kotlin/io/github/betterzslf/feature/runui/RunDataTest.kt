package io.github.betterzslf.feature.runui

import io.github.betterzslf.feature.run.GeoPoint
import kotlinx.serialization.json.*
import kotlin.test.*

/** Offline fixtures only: no production task ids, account, endpoint probes, or network clients. */
class RunDataTest {
    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)
    private fun detail(fields: String = ""): RunTaskDetail = assertNotNull(RunDataDecoder.detail(json("""
        {"errandDetail":{"trid":"server-run","errandId":"server-errand","errandScheduleId":"server-plan",
        "geoCoorList":[{"lon":113.4,"lat":22.5,"name":"P"}]$fields}}
    """)))
    private fun has(warnings: List<String>, text: String) = assertTrue(warnings.any { text in it }, warnings.joinToString("\n"))

    @Test fun recognizesDocumentedRowsBareArraysAndEnvelopes() {
        val row = """{"trid":"t","errandId":"e","errandScheduleId":"s","eName":"校园任务","isFinish":0,"startTime":"09:00","endTime":"10:00"}"""
        listOf("[$row]", "{\"errandList\":[$row]}", "{\"code\":200,\"data\":{\"errandList\":[$row]}}").forEach { value ->
            val decoded = RunDataDecoder.tasks(json(value))
            assertTrue(decoded.recognized)
            val item = decoded.items.single()
            assertEquals("t", item.trid); assertEquals("e", item.errandId); assertEquals("s", item.scheduleId)
            assertEquals("校园任务", item.title); assertEquals("0", item.isFinish)
            assertEquals("09:00", item.startText); assertEquals("10:00", item.endText)
            assertEquals(json(row), item.raw)
        }
    }

    @Test fun emptyArraysAndUnknownStructuresAreDifferent() {
        listOf("[]", "{\"errandList\":[]}", "{\"data\":{\"errandList\":[]}}").forEach {
            val rows = RunDataDecoder.tasks(json(it))
            assertTrue(rows.recognized); assertEquals(0, rows.rawSize); assertTrue(rows.items.isEmpty())
        }
        listOf("null", "{}", "{\"list\":[]}", "{\"errandList\":null}", "{\"errandList\":{}}").forEach {
            val rows = RunDataDecoder.tasks(json(it))
            assertFalse(rows.recognized); assertTrue(rows.warnings.isNotEmpty())
        }
    }

    @Test fun rawSizeIncludesMalformedAndDuplicateRows() {
        val result = RunDataDecoder.tasks(json("""{"errandList":[{"trid":"a","errandId":"e","errandScheduleId":"s"},null,19,{"trid":"a","errandId":"e","errandScheduleId":"s"}]}"""))
        assertTrue(result.recognized); assertEquals(4, result.rawSize); assertEquals(2, result.items.size)
        has(result.warnings, "errandList[1]"); has(result.warnings, "errandList[2]")
    }

    @Test fun nestedJsonRowsAreBoundedAndMalformedJsonIsReported() {
        val encoded = JsonPrimitive(JsonPrimitive("""{"errandList":[{"trid":"t","errandId":"e","errandScheduleId":"s"}]}""").toString())
        assertEquals("t", RunDataDecoder.tasks(encoded).items.single().trid)
        val broken = RunDataDecoder.tasks(buildJsonObject { put("errandList", "[broken") })
        assertFalse(broken.recognized); has(broken.warnings, "JSON")
        var deep: JsonElement = json("{\"errandList\":[]}")
        repeat(15) { deep = JsonPrimitive(deep.toString()) }
        assertFalse(RunDataDecoder.tasks(deep).recognized)
        has(RunDataDecoder.tasks(deep).warnings, "深度")
    }

    @Test fun listIdIsAnObservedScheduleOnlyInListContextAndNeverATridOrErrand() {
        val source = json("""{"errandList":[{"id":"id-only","errandId":"e","routeid":"r","title":"Display"}]}""")
        val item = RunDataDecoder.tasks(source).items.single()
        assertNull(item.trid); assertEquals("e", item.errandId); assertEquals("id-only", item.scheduleId)
        assertTrue(item.identityKey.isNotBlank())
        val rawDetail = json("""{"errandDetail":{"id":"id-only","routeid":"r","geoCoorList":[[0,0]]}}""")
        val result = assertNotNull(RunDataDecoder.detail(rawDetail))
        assertNull(result.task.trid); assertNull(result.task.errandId); assertNull(result.task.scheduleId)
        assertFalse(result.canStart)
        assertEquals(listOf("errandId", "errandScheduleId"), result.missingFields)
    }

    @Test fun taskListPlanIdCannotContradictAnExplicitPlanField() {
        val conflicting = RunDataDecoder.tasks(json("""{"errandList":[{"errandId":"e","id":"plan-a","errandScheduleId":"plan-b"}]}""")).items.single()
        assertNull(conflicting.trid); assertNull(conflicting.scheduleId)
        has(conflicting.warnings, "冲突")
        val matching = RunDataDecoder.tasks(json("""{"errandList":[{"errandId":"e","id":"plan-a","errandScheduleId":"plan-a"}]}""")).items.single()
        assertEquals("plan-a", matching.scheduleId)
        val selection = assertNotNull(RunTaskSelection.from(matching))
        assertEquals("e", selection.errandId)
        assertEquals("plan-a", selection.errandScheduleId)
    }

    @Test fun missingServerTridStillAllowsRealErrandScheduleAndOrderedCheckpoints() {
        val result = assertNotNull(RunDataDecoder.detail(json("""{"errandDetail":{
            "id":"local-only","errandId":"real-errand","errandScheduleId":"real-plan",
            "geoCoorList":[[113.5,22.6],[113.4,22.5],[113.5,22.6]]
        }}""")))
        assertNull(result.task.trid)
        assertEquals("real-errand", result.task.errandId); assertEquals("real-plan", result.task.scheduleId)
        assertTrue(result.canStart); assertTrue(result.missingFields.isEmpty())
        assertEquals(listOf(113.5, 113.4, 113.5), result.checkpoints.map { it.point.longitude })
        assertEquals(listOf("checkpoint-0", "checkpoint-1", "checkpoint-2"), result.checkpoints.map { it.id })
        assertTrue(result.warnings.none { ".trid" in it })
    }

    @Test fun errandIdIsNeverSynthesizedFromIdTridOrRouteId() {
        listOf("", ",\"errandId\":null", ",\"errandId\":true", ",\"errandId\":{}",
            ",\"errandId\":[]", ",\"errandId\":1.5", ",\"errandId\":\"null\"",
            ",\"errandId\":\"undefined\"", ",\"errandId\":\" \"").forEach { field ->
            val result = assertNotNull(RunDataDecoder.detail(json("""{"errandDetail":{
                "id":"not-errand","trid":"display-trid","routeid":"not-errand-either",
                "errandScheduleId":"real-plan","geoCoorList":[[113.4,22.5]]$field
            }}""")))
            assertNull(result.task.errandId); assertEquals("display-trid", result.task.trid)
            assertFalse(result.canStart); assertEquals(listOf("errandId"), result.missingFields)
            has(result.warnings, "errandId")
        }
    }

    @Test fun realErrandIdIsIndependentOfDisplayTridAndFallbackListId() {
        val result = assertNotNull(RunDataDecoder.detail(json("""{"errandDetail":{
            "id":"local-id","trid":"display-trid","errandId":"real-errand",
            "errandScheduleId":"real-plan","geoCoorList":[[113.4,22.5]]
        }}""")))
        assertEquals("real-errand", result.task.errandId); assertEquals("display-trid", result.task.trid)
        assertTrue(result.canStart)
        val withoutErrand = result.copy(task = result.task.copy(errandId = null))
        assertFalse(withoutErrand.canStart); assertEquals(listOf("errandId"), withoutErrand.missingFields)
        val blankErrand = result.copy(task = result.task.copy(errandId = " "))
        assertFalse(blankErrand.canStart); assertEquals(listOf("errandId"), blankErrand.missingFields)
        val blankPlan = result.copy(task = result.task.copy(scheduleId = " "))
        assertFalse(blankPlan.canStart); assertEquals(listOf("errandScheduleId"), blankPlan.missingFields)
    }

    @Test fun oldPositionalTaskConstructorKeepsFieldOrderAndDefaultsNewErrandId() {
        val raw = buildJsonObject { put("fixture", "legacy-constructor") }
        val item = RunTaskItem("display-trid", "real-plan", "Title", "start", "end", "0", "local-key", raw, listOf("warning"))
        assertEquals("display-trid", item.trid); assertEquals("real-plan", item.scheduleId)
        assertEquals("Title", item.title); assertEquals("start", item.startText); assertEquals("end", item.endText)
        assertEquals("0", item.isFinish); assertEquals("local-key", item.identityKey); assertEquals(raw, item.raw)
        assertEquals(listOf("warning"), item.warnings); assertNull(item.errandId)
    }

    @Test fun identifiersAcceptActualIntegerFieldsButRejectBooleansAndObjects() {
        val rows = RunDataDecoder.tasks(json("""[
            {"trid":123,"errandId":456,"scheduleId":0},
            {"trid":true,"errandId":true,"scheduleId":{}},
            {"trid":" ","errandId":"undefined","scheduleId":"null"}
        ]"""))
        assertEquals("123", rows.items[0].trid); assertEquals("456", rows.items[0].errandId); assertEquals("0", rows.items[0].scheduleId)
        assertNull(rows.items[1].trid); assertNull(rows.items[1].errandId); assertNull(rows.items[1].scheduleId)
        assertNull(rows.items[2].trid); assertNull(rows.items[2].errandId); assertNull(rows.items[2].scheduleId)
        has(rows.warnings, "标识"); has(rows.warnings, "标量")
    }

    @Test fun scheduleRequiresRealUnambiguousScheduleFields() {
        listOf("errandScheduleId", "errandSchedule", "scheduleId").forEach { alias ->
            val result = assertNotNull(RunDataDecoder.detail(json("""{
                "errandId":"e","$alias":"s","geoCoorList":[[0,0]]
            }""")))
            assertEquals("s", result.task.scheduleId); assertTrue(result.canStart)
            assertEquals("s", assertNotNull(RunDataDecoder.detail(json("""{"$alias":"s"}"""))).task.scheduleId)
        }
        val matching = detail(",\"errandSchedule\":\"server-plan\",\"scheduleId\":\"server-plan\"")
        assertEquals("server-plan", matching.task.scheduleId); assertTrue(matching.canStart)
        assertTrue(matching.warnings.none { "冲突" in it })
    }

    @Test fun everyPairOfScheduleAliasesRejectsConflictsWithoutPrecedence() {
        val aliases = listOf("errandScheduleId", "errandSchedule", "scheduleId")
        aliases.forEachIndexed { index, first -> aliases.drop(index + 1).forEach { second ->
            val result = assertNotNull(RunDataDecoder.detail(json("""{
                "errandId":"e","$first":"first-plan","$second":"other-plan","geoCoorList":[[0,0]]
            }""")))
            assertNull(result.task.scheduleId); assertFalse(result.canStart)
            assertEquals(listOf("errandScheduleId"), result.missingFields)
            has(result.warnings, "errandScheduleId"); has(result.warnings, "冲突")
        } }
    }

    @Test fun threeScheduleAliasesStayNullForAnyDisagreeingValue() {
        listOf(listOf("a", "a", "b"), listOf("a", "b", "a"), listOf("b", "a", "a"), listOf("a", "b", "c")).forEach { values ->
            val result = assertNotNull(RunDataDecoder.detail(json("""{
                "errandId":"e","errandScheduleId":"${values[0]}","errandSchedule":"${values[1]}",
                "scheduleId":"${values[2]}","geoCoorList":[[0,0]]
            }""")))
            assertNull(result.task.scheduleId); assertFalse(result.canStart)
            assertEquals(listOf("errandScheduleId"), result.missingFields); has(result.warnings, "冲突")
        }
        val normalized = assertNotNull(RunDataDecoder.detail(json("""{
            "errandId":"e","errandScheduleId":7,"errandSchedule":"7","scheduleId":" 7 ","geoCoorList":[[0,0]]
        }""")))
        assertEquals("7", normalized.task.scheduleId); assertTrue(normalized.canStart)
    }

    @Test fun localIdentityIsStableAcrossCompletionFieldsAndObjectKeyOrder() {
        val rows = RunDataDecoder.tasks(json("""[
            {"title":"Same","custom":{"b":2,"a":1},"isFinish":0},
            {"isFinish":1,"custom":{"a":1,"b":2},"title":"Same"},
            {"title":"Different","custom":{"a":1,"b":2}}
        ]"""))
        assertEquals(rows.items[0].identityKey, rows.items[1].identityKey)
        assertNotEquals(rows.items[0].identityKey, rows.items[2].identityKey)
        assertTrue(rows.items.all { it.trid == null })
    }

    @Test fun sameTaskInDifferentRealSchedulesHasDifferentLocalKeys() {
        listOf(
            """[{"trid":"t","errandId":"e","scheduleId":"a"},{"trid":"t","errandId":"e","scheduleId":"b"}]""",
            """[{"errandId":"e","errandScheduleId":"a"},{"errandId":"e","errandSchedule":"b"}]""",
        ).forEach { source ->
            val rows = RunDataDecoder.tasks(json(source))
            assertNotEquals(rows.items[0].identityKey, rows.items[1].identityKey)
        }
    }

    @Test fun duplicateCheckpointNamesKeepUniqueOriginalIndexIdsAndOrder() {
        val result = assertNotNull(RunDataDecoder.detail(json("""{"errandDetail":{"trid":"t","errandId":"e","scheduleId":"s","geoCoorList":[
            {"lon":113.4,"lat":22.5,"name":"P3"},{"lon":113.5,"lat":22.6,"name":"P1"},{"lon":113.4,"lat":22.5,"name":"P3"}
        ]}}""")))
        assertTrue(result.canStart)
        assertEquals(listOf("checkpoint-0", "checkpoint-1", "checkpoint-2"), result.checkpoints.map { it.id })
        assertEquals(listOf("P3", "P1", "P3"), result.checkpoints.map { result.checkpointNames[it.id] })
        assertEquals(listOf(113.4, 113.5, 113.4), result.checkpoints.map { it.point.longitude })
        assertEquals(3, result.checkpointSourceCount)
    }

    @Test fun invalidCheckpointsAreDisplayOnlyAndNeverRenumberLaterVisits() {
        val result = assertNotNull(RunDataDecoder.detail(json("""{"errandDetail":{"trid":"t","errandId":"e","scheduleId":"s","geoCoorList":[
            [113.4,22.5],{"lon":113.5},[113.6,22.7]
        ]}}""")))
        assertEquals(listOf("checkpoint-0", "checkpoint-2"), result.checkpoints.map { it.id })
        assertEquals(3, result.checkpointSourceCount); assertFalse(result.canStart)
        assertEquals(listOf("geoCoorList.coordinates"), result.missingFields); has(result.warnings, "[1]")
    }

    @Test fun missingRouteAndTaskCenterDoNotDisableValidCheckpoints() {
        val result = detail()
        assertTrue(result.canStart); assertTrue(result.walkPaths.isEmpty())
        assertEquals(1, result.checkpoints.size); has(result.warnings, "walkPaths")
    }

    @Test fun missingOrUnknownCheckpointFieldsAreDegradedWithoutInventingPoints() {
        listOf(
            "{}",
            "{\"errandId\":\"e\",\"errandScheduleId\":\"s\"}",
            "{\"errandId\":\"e\",\"errandScheduleId\":\"s\",\"geoCoorList\":{}}",
            "{\"errandId\":\"e\",\"errandScheduleId\":\"s\",\"geoCoorList\":[]}",
            "{\"errandId\":\"e\",\"errandScheduleId\":\"s\",\"geoCoorList\":[[999,22]]}",
        ).forEach {
            val result = assertNotNull(RunDataDecoder.detail(json("{\"errandDetail\":$it}")))
            assertFalse(result.canStart); assertTrue(result.checkpoints.isEmpty()); has(result.warnings, "geoCoorList")
            assertTrue("geoCoorList" in result.missingFields)
        }
        val unknown = json("{\"mystery\":[]}")
        assertNull(RunDataDecoder.detail(unknown)); assertTrue(RunDataDecoder.detailWarnings(unknown).isNotEmpty())
    }

    @Test fun walkPathsRetainExplicitSegmentsAndSplitOnlyDocumentedZeroSentinels() {
        val result = detail(""", "walkPaths":[[[113.4,22.5],[113.41,22.51],[0,0],[113.42,22.52]],[[114,23],[114.1,23.1]]]""")
        assertEquals(listOf(2, 1, 2), result.walkPaths.map { it.size })
        assertEquals(113.42, result.walkPaths[1].single().longitude)
        has(result.warnings, "分隔符")
    }

    @Test fun flatWalkPathsAndNestedJsonSegmentsKeepRealBoundaries() {
        val flat = detail(""", "walkPaths":[[113.4,22.5],[0,0],[113.5,22.6]]""")
        assertEquals(listOf(1, 1), flat.walkPaths.map { it.size })
        val encoded = JsonPrimitive("""[[[113.4,22.5],[113.5,22.6]],[[114,23],[114.1,23.1]]]""").toString()
        val nested = detail(",\"walkPaths\":$encoded")
        assertEquals(listOf(2, 2), nested.walkPaths.map { it.size })
    }

    @Test fun badWalkCoordinatesBreakRatherThanInventRoadConnections() {
        val result = detail(""", "walkPaths":[[[113.4,22.5],[999,22],[113.5,22.6]],{"unknown":"road"}]""")
        assertEquals(listOf(1, 1), result.walkPaths.map { it.size })
        has(result.warnings, "断开路线"); has(result.warnings, "未知")
    }

    @Test fun damagedFlatWalkPathRetainsBothSidesAsSeparateRealSegments() {
        val result = detail(""", "walkPaths":[[113.4,22.5],null,[113.5,22.6],[1,2,3],[113.6,22.7]]""")
        assertEquals(listOf(1, 1, 1), result.walkPaths.map { it.size })
        assertEquals(listOf(113.4, 113.5, 113.6), result.walkPaths.map { it.single().longitude })
        has(result.warnings, "断开路线")
    }

    @Test fun zeroZeroIsAnOrdinaryCheckpointAndTrackPointOutsideWalkPaths() {
        val result = assertNotNull(RunDataDecoder.detail(json("""{"errandDetail":{"trid":"t","errandId":"e","scheduleId":"s","geoCoorList":[[0,0]]}}""")))
        assertTrue(result.canStart); assertEquals(GeoPoint(0.0, 0.0), result.checkpoints.single().point)
        assertEquals(listOf(GeoPoint(0.0, 0.0)), RunDataDecoder.track(json("{\"trackPointList\":[{\"location\":\"0,0\"}]}")))
    }

    @Test fun parsesTrackChunksInOrderIncludingTwiceEncodedCoordinateJson() {
        val chunk = JsonPrimitive(JsonPrimitive("""[{"location":"113.4,22.5","locatetime":1000},{"location":"113.5,22.6"}]""").toString()).toString()
        val track = json("""{"data":{"trackPointList":[{"coordinate":$chunk},{"coordinate":"[{\"location\":\"114,23\"}]"}]}}""")
        assertEquals(listOf(GeoPoint(113.4, 22.5), GeoPoint(113.5, 22.6), GeoPoint(114.0, 23.0)), RunDataDecoder.track(track))
        assertTrue(RunDataDecoder.trackWarnings(track).isEmpty())
    }

    @Test fun twoCoordinateStringsAreTwoPointsNotOneNumericPair() {
        val expected = listOf(GeoPoint(113.4, 22.5), GeoPoint(113.5, 22.6))
        assertEquals(expected, RunDataDecoder.track(json("""["113.4,22.5","113.5,22.6"]""")))
        assertEquals(expected, RunDataDecoder.track(json("""["[113.4,22.5]","[113.5,22.6]"]""")))
    }

    @Test fun supportedNamedCoordinatesAreLongitudeFirstWithNoImplicitSwap() {
        val points = RunDataDecoder.track(json("""[{"lng":"113.4","lat":"22.5"},{"longitude":-180,"latitude":-90},[180,90],[22,113]]"""))
        assertEquals(listOf(GeoPoint(113.4, 22.5), GeoPoint(-180.0, -90.0), GeoPoint(180.0, 90.0)), points)
    }

    @Test fun nonFiniteOutOfRangeNonDecimalAndAmbiguousCoordinatesAreReported() {
        val input = json("""[{"location":"NaN,22"},{"location":"Infinity,22"},[181,0],[0,-91],
            ["0x1p0",22],["113f",22],[true,22],{"lon":113,"lng":114,"lat":22},{"x":113,"y":22}]
        """)
        assertTrue(RunDataDecoder.track(input).isEmpty())
        val warnings = RunDataDecoder.trackWarnings(input)
        assertTrue(warnings.size >= 9); has(warnings, "有限"); has(warnings, "冲突"); has(warnings, "未知")
    }

    @Test fun knownEmptyAndUnknownOrDamagedTracksHaveObservableDiagnostics() {
        assertTrue(RunDataDecoder.trackRows(json("{\"trackPointList\":[]}")).recognized)
        assertTrue(RunDataDecoder.trackWarnings(json("{\"trackPointList\":[]}")).isEmpty())
        val unknown = json("{\"points\":[[1,2]]}")
        assertFalse(RunDataDecoder.trackRows(unknown).recognized)
        assertTrue(RunDataDecoder.track(unknown).isEmpty()); has(RunDataDecoder.trackWarnings(unknown), "未知")
        val broken = json("""{"trackPointList":[{"coordinate":"[broken"},{"location":"113.4,22.5"}]}""")
        assertEquals(1, RunDataDecoder.track(broken).size); has(RunDataDecoder.trackWarnings(broken), "JSON")
    }

    @Test fun runLengthMetresRunTimeMillisecondsAndStatusArePreservedWithoutGuessing() {
        val source = json("""{"recordMap":{"trid":"t","runLength":"2.5","runTime":60000,"status":"pending-server-label"}}""")
        val result = assertNotNull(RunDataDecoder.record(source))
        assertEquals(2.5, result.distanceMeters); assertEquals(60_000L, result.elapsedMillis)
        assertEquals("pending-server-label", result.status); assertEquals((source as JsonObject)["recordMap"], result.raw)
        assertEquals("1", assertNotNull(RunDataDecoder.record(json("{\"recordMap\":{\"status\":1}}"))).status)
    }

    @Test fun recordsUseDocumentedContainerAndKeepMissingIdsNonActionable() {
        val rows = RunDataDecoder.records(json("""{"userErrandTrackRecord":[{"id":"not-trid","runLength":0,"runTime":"0","status":0}]}"""))
        assertTrue(rows.recognized); assertEquals(1, rows.rawSize)
        val row = rows.items.single()
        assertNull(row.trid); assertEquals(0.0, row.distanceMeters); assertEquals(0L, row.elapsedMillis); assertEquals("0", row.status)
        has(row.warnings, "trid")
        assertFalse(RunDataDecoder.records(json("{\"other\":[]}")).recognized)
    }

    @Test fun unknownRecordUnitsAndInvalidKnownNumbersAreNotGuessed() {
        val unknown = assertNotNull(RunDataDecoder.record(json("""{"recordMap":{"distance":2,"duration":60,"runDistance":2000}}""")))
        assertNull(unknown.distanceMeters); assertNull(unknown.elapsedMillis)
        has(unknown.warnings, "单位")
        listOf("-1", "\"NaN\"", "\"Infinity\"", "true").forEach { number ->
            val row = assertNotNull(RunDataDecoder.record(json("""{"recordMap":{"runLength":$number,"runTime":$number}}""")))
            assertNull(row.distanceMeters); assertNull(row.elapsedMillis); assertTrue(row.warnings.isNotEmpty())
        }
        listOf("1.5", "\"9223372036854775808\"").forEach { number ->
            val row = assertNotNull(RunDataDecoder.record(json("""{"recordMap":{"runTime":$number}}""")))
            assertNull(row.elapsedMillis); has(row.warnings, "毫秒")
        }
    }

    @Test fun knownAbsentRecordIsDifferentFromUnknownRecordShape() {
        assertNull(RunDataDecoder.record(json("{\"recordMap\":\"\"}")))
        assertTrue(RunDataDecoder.recordWarnings(json("{\"recordMap\":\"\"}")).isEmpty())
        assertNull(RunDataDecoder.record(json("{\"other\":[]}")))
        assertTrue(RunDataDecoder.recordWarnings(json("{\"other\":[]}")).isNotEmpty())
    }

    @Test fun recordTrackBadFieldsAreReportedWhileGoodPointsRemain() {
        val row = assertNotNull(RunDataDecoder.record(json("""{"recordMap":{"trid":"t","coordinate":"[{\"location\":\"0,0\"},{\"location\":\"broken\"},{\"location\":\"113.4,22.5\"}]"}}""")))
        assertEquals(listOf(GeoPoint(0.0, 0.0), GeoPoint(113.4, 22.5)), row.points)
        has(row.warnings, "[1]")
    }

    @Test fun minimumDistanceRequiresExplicitUnitsNotBareAmbiguousNumbers() {
        val bare = detail(",\"minDistance\":2")
        assertNull(bare.minimumDistanceMeters); has(bare.warnings, "单位"); assertTrue(bare.canStart)
        assertEquals(2000.0, detail(",\"minDistance\":2,\"distanceUnit\":\"km\"").minimumDistanceMeters)
        assertEquals(2000.0, detail(",\"minimumDistance\":2000,\"minimumDistanceUnit\":\"m\"").minimumDistanceMeters)
        assertEquals(2.0, detail(",\"minDistanceMeters\":2").minimumDistanceMeters)
        assertEquals(0.0, detail(",\"minimumDistanceMeters\":0").minimumDistanceMeters)
        assertNull(detail(",\"minDistance\":2,\"distanceUnit\":\"mile\"").minimumDistanceMeters)
    }

    @Test fun conflictingSameUnitRecordFieldsStayUnknownInsteadOfSilentlyChoosing() {
        val row = assertNotNull(RunDataDecoder.record(json("""{"recordMap":{"runLength":2000,"distanceMeters":3000,"runTime":60000,"elapsedMillis":60001}}""")))
        assertNull(row.distanceMeters); assertNull(row.elapsedMillis); has(row.warnings, "冲突")
    }
}
