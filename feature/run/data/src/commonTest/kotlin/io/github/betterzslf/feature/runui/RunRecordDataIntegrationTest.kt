package io.github.betterzslf.feature.runui

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Six original protocol/paging definitions now exercise the real typed factory and controller. */
class RunRecordDataIntegrationTest {
    private fun response(vararg rows: JsonElement): JsonObject = buildJsonObject {
        put("code", 200)
        put("data", buildJsonObject { put("userErrandTrackRecord", JsonArray(rows.toList())) })
    }
    private fun controller(fixture: RunDataFixture, plan: String = "plan", size: Int = 10): RunRecordController {
        val source = assertNotNull(ApiRunReadSource.capture(fixture.api))
        return RunRecordController(RunRecordPageSource { owner, schedule, offset, limit ->
            source.records(owner, schedule, offset, limit)
        }, "owner", plan, size)
    }
    private fun hasWarning(controller: RunRecordController, text: String) =
        assertTrue(controller.state.value.warnings.any { text in it }, controller.state.value.warnings.joinToString("\n"))
    private fun rawRows(response: JsonObject) = RunDataDecoder.records(response).items

    @Test fun documentedContainerPreservesRealTridMetersAndMillisecondsWithoutMagnitudeGuessing() = runTest {
        val known = buildJsonObject {
            put("trid", 73); put("eName", "Known units")
            put("runLength", 2.75); put("runTime", 1500)
        }
        val unknownUnits = buildJsonObject { put("trid", "other"); put("distance", 7); put("runTimeSeconds", 8) }
        val body = response(known, unknownUnits)
        val fixture = RunDataFixture(MockEngine { request ->
            assertEquals("owner", request.url.parameters["userId"])
            assertEquals("real-plan", request.url.parameters["errandScheduleId"])
            assertEquals("0", request.url.parameters["offset"]); assertEquals("3", request.url.parameters["limit"])
            respond(body.toString())
        })
        try {
            val controller = controller(fixture, "real-plan", 3)
            controller.refresh()
            val first = controller.state.value.items.first()
            assertEquals("73", first.trid); assertEquals(known, rawRows(body).first().raw)
            assertEquals(2.75, first.distanceMeters); assertEquals(1500L, first.elapsedMillis)
            assertNull(controller.state.value.items[1].distanceMeters); assertNull(controller.state.value.items[1].elapsedMillis)
            hasWarning(controller, "单位"); assertTrue(controller.state.value.end)
        } finally { fixture.close() }
    }

    @Test fun anonymousRowsDeduplicateByStableRawContentDespiteObjectKeyOrder() = runTest {
        val a = Json.parseToJsonElement("""{"id":"display-a","routeid":"route","eName":"Same","extra":{"b":2,"a":1}}""")
        val b = Json.parseToJsonElement("""{"id":"display-b","routeid":"route","eName":"Same","extra":{"a":1,"b":2}}""")
        val reorderedA = Json.parseToJsonElement("""{"extra":{"a":1,"b":2},"eName":"Same","routeid":"route","id":"display-a"}""")
        val reorderedB = Json.parseToJsonElement("""{"extra":{"b":2,"a":1},"routeid":"route","id":"display-b","eName":"Same"}""")
        var calls = 0
        val fixture = RunDataFixture(MockEngine { request ->
            calls++; respond((if (request.url.parameters["offset"] == "0") response(a, b) else response(reorderedB, reorderedA)).toString())
        })
        try {
            val controller = controller(fixture, size = 2)
            controller.refresh()
            assertEquals(2, controller.state.value.items.size); assertTrue(controller.state.value.items.all { it.trid == null })
            assertFalse(controller.state.value.end)
            controller.more(); controller.more()
            assertEquals(2, calls); assertEquals(2, controller.state.value.items.size)
            assertTrue(controller.state.value.items.all { it.trid == null })
            assertEquals(listOf("display-a", "display-b"), controller.state.value.items.map { row -> row.safeDisplayFields.single { it.label == "id" }.value })
            assertEquals(listOf(reorderedB, reorderedA), rawRows(response(reorderedB, reorderedA)).map { it.raw })
            assertTrue(controller.state.value.end); hasWarning(controller, "缺少可确认的记录编号")
        } finally { fixture.close() }
    }

    @Test fun alternativeOrInvalidIdentifiersNeverBecomeServerTrid() = runTest {
        val rows = Json.parseToJsonElement("""[
            {"id":"display","errandId":"task","routeid":"route","eName":"Absent"},
            {"trid":true,"id":"display-2","eName":"Boolean"},
            {"trid":1.5,"id":"display-3","eName":"Fractional"},
            {"trid":"undefined","id":"display-4","eName":"Undefined"},
            {"trid":" ","id":"display-5","eName":"Blank"}
        ]""") as JsonArray
        val fixture = RunDataFixture(MockEngine { respond(response(*rows.toTypedArray()).toString()) })
        try {
            val controller = controller(fixture)
            controller.refresh()
            assertEquals(5, controller.state.value.items.size)
            assertTrue(controller.state.value.items.all { it.trid == null })
            assertEquals(listOf("Absent", "Boolean", "Fractional", "Undefined", "Blank"), controller.state.value.items.map { it.title })
            assertTrue(controller.state.value.end); hasWarning(controller, ".trid")
        } finally { fixture.close() }
    }

    @Test fun longAnonymousRowsWithCommonPrefixStayDistinctAndNeverLeakLocalKeysIntoTrid() = runTest {
        val prefix = "x".repeat(32768)
        val a = buildJsonObject { put("eName", "Same"); put("id", "display-only"); put("payload", prefix + "A") }
        val b = buildJsonObject { put("eName", "Same"); put("id", "display-only"); put("payload", prefix + "B") }
        fun reverseFields(value: JsonObject) = JsonObject(value.entries.toList().reversed().associate { it.key to it.value })
        var calls = 0
        val fixture = RunDataFixture(MockEngine { request ->
            calls++; respond((if (request.url.parameters["offset"] == "0") response(a, b) else response(reverseFields(b), reverseFields(a))).toString())
        })
        try {
            val controller = controller(fixture, size = 2)
            controller.refresh(); assertEquals(2, controller.state.value.items.size)
            val keys = controller.state.value.items.map { it.identityKey }
            assertEquals(2, keys.toSet().size)
            controller.more(); controller.more()
            assertEquals(2, calls); assertEquals(2, controller.state.value.items.size)
            assertTrue(controller.state.value.items.all { it.trid == null })
            assertEquals(keys, controller.state.value.items.map { it.identityKey })
            assertEquals(listOf(prefix + "A", prefix + "B"), rawRows(response(a, b)).map { it.raw.getValue("payload").jsonPrimitive.content })
            assertTrue(controller.state.value.end)
        } finally { fixture.close() }
    }

    @Test fun anonymousRawArraysKeepOrderWhileObjectFieldOrderDoesNotMatter() = runTest {
        val a = Json.parseToJsonElement("""{"eName":"Same","extra":[1,2]}""")
        val b = Json.parseToJsonElement("""{"eName":"Same","extra":[2,1]}""")
        val aAgain = Json.parseToJsonElement("""{"extra":[1,2],"eName":"Same"}""")
        val bAgain = Json.parseToJsonElement("""{"extra":[2,1],"eName":"Same"}""")
        val fixture = RunDataFixture(MockEngine { request -> respond((if (request.url.parameters["offset"] == "0") response(a, b) else response(bAgain, aAgain)).toString()) })
        try {
            val controller = controller(fixture, size = 2)
            controller.refresh(); val keys = controller.state.value.items.map { it.identityKey }; controller.more()
            assertEquals(2, controller.state.value.items.size); assertEquals(2, keys.toSet().size)
            assertEquals(keys, controller.state.value.items.map { it.identityKey })
            assertEquals(listOf(aAgain, bAgain), rawRows(response(aAgain, bAgain)).map { it.raw })
            assertTrue(controller.state.value.items.all { it.trid == null }); assertTrue(controller.state.value.end)
        } finally { fixture.close() }
    }

    @Test fun deeplyNestedRawExtrasUseIterativeStableKeysWithoutInventingIds() = runTest {
        var first: JsonElement = JsonPrimitive("leaf"); var second: JsonElement = JsonPrimitive("leaf")
        repeat(128) { index ->
            val previousFirst = first; val previousSecond = second
            first = buildJsonObject { put("z", index); put("a", previousFirst) }
            second = buildJsonObject { put("a", previousSecond); put("z", index) }
        }
        val a = buildJsonObject { put("eName", "Anonymous"); put("extra", first) }
        val aAgain = buildJsonObject { put("extra", second); put("eName", "Anonymous") }
        var calls = 0
        val fixture = RunDataFixture(MockEngine { request ->
            calls++; respond(response(if (request.url.parameters["offset"] == "0") a else aAgain).toString())
        })
        try {
            val controller = controller(fixture, size = 1)
            controller.refresh(); controller.more(); controller.more()
            assertEquals(2, calls); assertEquals(1, controller.state.value.items.size)
            assertNull(controller.state.value.items.single().trid); assertTrue(controller.state.value.end)
        } finally { fixture.close() }
    }
}
