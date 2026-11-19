package io.github.betterzslf.navigation

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.toMutableStateList
import io.github.betterzslf.Route
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.yukonga.miuix.kmp.nav.core.NavKey
import kotlin.test.*

/** Closed serializers and explicit savers only; no JVM reflection, native UI, network or IO. */
class ProductNavigationTest {
    private data class UnsupportedKey(val id: String) : NavKey
    private val serializer = ListSerializer(Route.serializer())
    private val saveScope = object : SaverScope { override fun canBeSaved(value: Any): Boolean = value is String }
    private fun oldState(routes: List<Route>): String = Json.encodeToString(serializer, routes)
    private fun save(stack: List<NavKey>, seed: NavKey): String {
        val saver = productBackStackSaver(StandardNavigation, seed)
        return assertNotNull(with(saver) { saveScope.save(stack.toMutableStateList()) })
    }

    @Test fun closedPublicHierarchyRoundTripsInAVersionedProductDocument() {
        val stack = listOf<NavKey>(Route.Main, Route.Login, Route.Page("article", "fixture-id", "Fixture title"))
        val encoded = StandardNavigation.encode(stack)
        val document = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(setOf("version", "schemaId", "stack"), document.keys)
        assertEquals("1", document.getValue("version").jsonPrimitive.content)
        assertEquals(StandardNavigation.schemaId, document.getValue("schemaId").jsonPrimitive.content)
        assertEquals(stack, StandardNavigation.decode(encoded))
        assertEquals(stack, StandardNavigation.decode(StandardNavigation.encode(StandardNavigation.decode(encoded))))
    }

    @Test fun previousBareRouteListsMigrateWithoutLosingRootOrFiveTabHistories() {
        val root = listOf<Route>(Route.Main, Route.Login, Route.Page("document", "privacy", "Privacy"))
        val stacks = listOf(root) + List(5) { index ->
            listOf(Route.TabRoot(index), Route.Page("article", "fixture-$index", "Tab $index"))
        }
        for (stack in stacks) {
            val migrated = StandardNavigation.decode(oldState(stack))
            assertEquals(stack, migrated)
            val versioned = StandardNavigation.encode(migrated)
            assertEquals(stack, StandardNavigation.decode(versioned))
        }
    }

    @Test fun explicitSaverPreservesAllFiveDistinctMutableBackStacks() {
        val restored = (0..4).map { index ->
            val seed = Route.TabRoot(index)
            val stack = listOf<NavKey>(seed, Route.Page("article", "fixture-$index"))
            val saver = productBackStackSaver(StandardNavigation, seed)
            assertNotNull(saver.restore(save(stack, seed))).also { assertEquals(stack, it.toList()) }
        }
        restored[0].add(Route.Page("document", "privacy"))
        for (index in 1..4) {
            assertEquals(2, restored[index].size)
            assertEquals(Route.TabRoot(index), restored[index].first())
            assertEquals(Route.Page("article", "fixture-$index"), restored[index].last())
        }
    }

    @Test fun legacyPrivatePageStringsAndFormerExtensionRoutesCannotBeRestored() {
        for (kind in listOf("water", "runimport", "runmanual", "runplan", "runsupplement", "extension:water", "extension:unknown")) {
            val old = oldState(listOf(Route.Main, Route.Page(kind, "fixture")))
            assertFalse(StandardNavigation.accepts(Route.Page(kind)))
            assertEquals(emptyList(), StandardNavigation.decode(old))
            val saver = productBackStackSaver(StandardNavigation, Route.Main)
            assertEquals(listOf<NavKey>(Route.Main), assertNotNull(saver.restore(old)).toList())
            assertFailsWith<IllegalArgumentException> { StandardNavigation.encode(listOf(Route.Main, Route.Page(kind))) }
        }
    }

    @Test fun unknownTypedKeysNeverFallThroughAnOpenPolymorphicSerializer() {
        val privateKey = UnsupportedKey("sensitive-fixture-id")
        assertFalse(StandardNavigation.accepts(privateKey))
        val failure = assertFailsWith<IllegalArgumentException> { StandardNavigation.encode(listOf(Route.Main, privateKey)) }
        assertFalse(failure.message.orEmpty().contains(privateKey.id))
        assertFailsWith<IllegalArgumentException> { productBackStackSaver(StandardNavigation, privateKey) }
        val saver = productBackStackSaver(StandardNavigation, Route.Main)
        assertFailsWith<IllegalArgumentException> {
            with(saver) { saveScope.save(listOf<NavKey>(Route.Main, privateKey).toMutableStateList()) }
        }
    }

    @Test fun malformedVersionSchemaUnknownTypeAndUnknownFieldsFailClosed() {
        val valid = StandardNavigation.encode(listOf(Route.Main))
        val typeName = Json.parseToJsonElement(valid).jsonObject.getValue("stack").jsonArray.single()
            .jsonObject.getValue("type").jsonPrimitive.content
        val incompatible = listOf("", "bad", "null", "{}", "[{}]", "[", "{\"version\":1}",
            valid.replace("\"version\":1", "\"version\":2"),
            valid.replace(StandardNavigation.schemaId, "another.product.navigation"),
            valid.replace(typeName, "external.product.PrivateDestination"),
            valid.dropLast(1) + ",\"extra\":true}")
        for (saved in incompatible) {
            assertEquals(emptyList(), StandardNavigation.decode(saved), saved)
            assertEquals(listOf<NavKey>(Route.Main), assertNotNull(productBackStackSaver(StandardNavigation, Route.Main).restore(saved)).toList())
        }
    }

    @Test fun boundedValuesInvalidTabRootsAndDuplicateEntryKeysAreRejected() {
        for (route in listOf(Route.TabRoot(-1), Route.TabRoot(5), Route.TabRoot(Int.MAX_VALUE),
            Route.Page(""), Route.Page(" Article"), Route.Page("document", "\u0000"),
            Route.Page("article", "x".repeat(2049)), Route.Page("article", title = "x".repeat(257)))) {
            assertFalse(StandardNavigation.accepts(route))
            assertFailsWith<IllegalArgumentException> { StandardNavigation.encode(listOf(Route.Main, route)) }
            assertEquals(emptyList(), StandardNavigation.decode(oldState(listOf(route))))
        }
        assertEquals(emptyList(), StandardNavigation.decode(" ".repeat(262_145)))
        assertEquals(emptyList(), StandardNavigation.decode(oldState(List(129) { Route.Page("article", "$it") })))
        assertFailsWith<IllegalArgumentException> { StandardNavigation.encode(listOf(Route.Main, Route.Main)) }
        assertEquals(emptyList(), StandardNavigation.decode(oldState(listOf(Route.Main, Route.Main))))
    }

    @Test fun emptyOrRejectedDecodedStateUsesTheExactCallerSeed() {
        for (seed in listOf<Route>(Route.Main, Route.Login) + (0..4).map { Route.TabRoot(it) }) {
            val saver = productBackStackSaver(StandardNavigation, seed)
            for (saved in listOf("[]", StandardNavigation.encode(emptyList()), "invalid")) {
                assertEquals(listOf<NavKey>(seed), assertNotNull(saver.restore(saved)).toList())
            }
        }
    }

    @Test fun saverRevalidatesAProductDecodeResultBeforeAdmittingItsKeys() {
        val unsupported = UnsupportedKey("fixture")
        for (result in listOf(listOf(unsupported), listOf(Route.Main, unsupported), listOf(Route.Main, Route.Main))) {
            val product = object : ProductNavigation by StandardNavigation {
                override fun decode(saved: String): List<NavKey> = result
            }
            assertEquals(listOf<NavKey>(Route.Main), assertNotNull(productBackStackSaver(product, Route.Main).restore("fixture")).toList())
        }
        val broken = object : ProductNavigation by StandardNavigation {
            override fun decode(saved: String): List<NavKey> = error("Malformed saved state")
        }
        assertEquals(listOf<NavKey>(Route.Main), assertNotNull(productBackStackSaver(broken, Route.Main).restore("fixture")).toList())
    }

    @Test fun publicUnknownPageKindsRemainSafeDispatchValuesNotPrivateTargets() {
        val article = Route.Page("article", "fixture")
        val unknown = Route.Page("future-public-page", "fixture")
        assertTrue(StandardNavigation.accepts(article))
        assertTrue(StandardNavigation.accepts(unknown))
        assertEquals(listOf<NavKey>(Route.Main, unknown), StandardNavigation.decode(StandardNavigation.encode(listOf(Route.Main, unknown))))
        assertFalse(StandardNavigation.isOffline(unknown))
    }
}
