package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.storage.KeyValueStore
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.platform.RunMapCoordinates
import kotlinx.serialization.json.*
import kotlin.test.*

/** All identities, time, positions and storage here are offline fixtures. */
class RunTaskLinksTest {
    private val choice = RunTaskSelection("fixture-errand", "fixture-plan")
    private class Memory : KeyValueStore {
        val values = mutableMapOf<String, String>()
        var failNext = false
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) {
            if (failNext) { failNext = false; error("Fixture storage failure") }
            values[key] = value
        }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }
    private fun parse(text: String) = Json.parseToJsonElement(text)
    private fun detail(text: String) = assertNotNull(RunDataDecoder.detail(parse(text)))

    @Test fun selectionCodecRoundTripsOnlyRealTaskAndScheduleFields() {
        assertEquals(choice, RunTaskSelection.decode(choice.encode()))
        assertNull(RunTaskSelection.decode("fixture-trid"))
        assertNull(RunTaskSelection.decode("""{"errandId":"e","trid":"t"}"""))
        assertNull(RunTaskSelection.decode(" ".repeat(2049)))
        assertFailsWith<IllegalArgumentException> { RunTaskSelection("", "s") }
    }

    @Test fun queryContextCarriesObservedIdsWithoutChangingRawResponseOrAllocatingTrid() {
        val original = detail("""{"errandDetail":{"name":"Fixture","geoCoorList":[[113.4,22.5]]}}""")
        val carried = original.withQueryContext(choice)
        assertTrue(carried.canStart)
        assertEquals(choice.errandId, carried.task.errandId)
        assertEquals(choice.errandScheduleId, carried.task.scheduleId)
        assertNull(carried.task.trid)
        assertEquals(original.task.raw, carried.task.raw)
        assertTrue(carried.warnings.any { "本次" in it && "查询" in it })
    }

    @Test fun contradictoryDetailIdsAreNotOverwrittenWithRequestedValues() {
        val original = detail("""{"errandDetail":{"errandId":"another-fixture-task","errandScheduleId":"fixture-plan","geoCoorList":[[113.4,22.5]]}}""")
        val carried = original.withQueryContext(choice)
        assertFalse(carried.canStart)
        assertNull(carried.task.errandId); assertNull(carried.task.scheduleId)
        assertEquals(original.task.raw, carried.task.raw)
        assertTrue(carried.warnings.any { "不一致" in it })
    }

    @Test fun explicitMalformedIdentifiersDoNotGetSilentlyFilledFromQueryContext() {
        val original = detail("""{"errandDetail":{"errandId":"fixture-errand","errandScheduleId":"","geoCoorList":[[113.4,22.5]]}}""")
        assertFalse(original.withQueryContext(choice).canStart)
        assertNull(original.withQueryContext(choice).task.scheduleId)
    }

    @Test fun preparationUsesAccountPlusActualClockOnceAndReusesTheDurablePendingId() {
        val store = Memory(); val links = RunTaskLinks(store)
        val first = links.prepare("fixture-owner", "fixture-account", choice, 1000, emptySet())
        assertEquals("fixture-account1000", first.trid)
        val second = RunTaskLinks(store).prepare("fixture-owner", "fixture-account", choice, 9000, emptySet())
        assertEquals(first, second)
        assertEquals(1, parse(store.get(RunTaskLinks.STORAGE_KEY)!!).jsonObject.getValue("links").jsonArray.size)
        assertNull(store.get(RunSessionCoordinator.STORAGE_KEY))
        assertNull(store.get("run.upload.queue.v1"))
    }

    @Test fun aCommittedSessionCannotBeReusedAsANewRunAndClockCollisionsAreExplicit() {
        val store = Memory(); val links = RunTaskLinks(store)
        val first = links.prepare("fixture-owner", "fixture-account", choice, 1000, emptySet())
        assertFailsWith<IllegalArgumentException> { links.prepare("fixture-owner", "fixture-account", choice, 1000, setOf(first.trid)) }
        val next = links.prepare("fixture-owner", "fixture-account", choice, 2000, setOf(first.trid))
        assertEquals("fixture-account2000", next.trid)
        assertNotEquals(first.trid, next.trid)
    }

    @Test fun failedPreparationPersistenceKeepsOneCandidateForAnExplicitRetry() {
        val store = Memory(); val links = RunTaskLinks(store)
        store.failNext = true
        assertFailsWith<IllegalStateException> { links.prepare("fixture-owner", "fixture-account", choice, 1000, emptySet()) }
        assertNull(store.get(RunTaskLinks.STORAGE_KEY))
        val retried = links.prepare("fixture-owner", "fixture-account", choice, 2000, emptySet())
        assertEquals("fixture-account1000", retried.trid)
        assertEquals(retried, RunTaskLinks(store).lookup("fixture-owner", retried.trid))
    }

    @Test fun lookupAndPreparedReuseAreOwnerAndAccountIsolated() {
        val store = Memory(); val links = RunTaskLinks(store)
        val first = links.prepare("fixture-owner-a", "fixture-account-a", choice, 1000, emptySet())
        assertNull(links.lookup("fixture-owner-b", first.trid))
        val other = links.prepare("fixture-owner-b", "fixture-account-b", choice, 1001, emptySet())
        assertEquals("fixture-account-b1001", other.trid)
        val changedAccount = links.prepare("fixture-owner-a", "fixture-updated-account", choice, 1002, emptySet())
        assertEquals("fixture-updated-account1002", changedAccount.trid)
        assertEquals(first, links.lookup("fixture-owner-a", first.trid))
    }

    @Test fun corruptLinkStorageIsPreservedInsteadOfResetOrGuessingNewIds() {
        val store = Memory(); val broken = "{fixture-broken"
        store.put(RunTaskLinks.STORAGE_KEY, broken)
        assertFails { RunTaskLinks(store).prepare("fixture-owner", "fixture-account", choice, 1000, emptySet()) }
        assertEquals(broken, store.get(RunTaskLinks.STORAGE_KEY))
        assertNull(store.get(RunSessionCoordinator.STORAGE_KEY))
    }

    @Test fun invalidClockAccountAndOwnerNeverCreateAStoredIdentity() {
        val store = Memory(); val links = RunTaskLinks(store)
        assertFailsWith<IllegalArgumentException> { links.prepare("fixture-owner", "", choice, 1000, emptySet()) }
        assertFailsWith<IllegalArgumentException> { links.prepare("", "fixture-account", choice, 1000, emptySet()) }
        assertFailsWith<IllegalArgumentException> { links.prepare("fixture-owner", "fixture-account", choice, -1, emptySet()) }
        assertNull(store.get(RunTaskLinks.STORAGE_KEY))
    }

    @Test fun nativeBoundaryTransformsOnlyCheckpointAndRecommendedPathCopies() {
        val native = GeoPoint(113.4, 22.5)
        val server = RunMapCoordinates.wgs84ToGcj02(native)
        val raw = buildJsonObject { put("errandId", choice.errandId) }
        val original = RunTaskDetail(RunTaskItem(null, choice.errandScheduleId, "Fixture", null, null, null, "fixture-key", raw, errandId = choice.errandId),
            listOf(RunCheckpoint("fixture-point", server)), listOf(listOf(server)), 100.0, emptyList())
        val converted = original.inNativeWgs84()
        assertTrue(RunGeo.distanceMeters(native, converted.checkpoints.single().point) < 0.01)
        assertTrue(RunGeo.distanceMeters(native, converted.walkPaths.single().single()) < 0.01)
        assertEquals(server, original.checkpoints.single().point)
        assertEquals(server, original.walkPaths.single().single())
        assertEquals(raw, converted.task.raw)
        assertEquals(original.task, converted.task)
    }
}
