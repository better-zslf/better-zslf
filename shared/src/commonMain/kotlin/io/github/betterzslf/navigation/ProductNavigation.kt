package io.github.betterzslf.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.toMutableStateList
import io.github.betterzslf.Route
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import top.yukonga.miuix.kmp.nav.core.NavKey

/**
 * Product-owned closed navigation persistence and admission. A product serializes its own saved-key
 * union, not the open NavKey interface. No reflection or private destination leaks into this API.
 */
interface ProductNavigation {
    val schemaId: String
    fun accepts(key: NavKey): Boolean
    /** Reject unsupported keys rather than silently dropping a live destination while saving. */
    fun encode(stack: List<NavKey>): String
    /** Invalid, incompatible or no-longer-supported state returns empty so the caller uses its seed. */
    fun decode(saved: String): List<NavKey>
    fun isOffline(key: NavKey): Boolean
    fun swipeDismiss(key: NavKey): Boolean
    /** App registers public concrete Route entries; a product adds only its private concrete types. */
    fun registerEntries(builder: NavEntryBuilder, onOpen: (NavKey) -> Unit, onBack: () -> Unit)
}

@Serializable
private data class StandardSavedNavigation(val version: Int, val schemaId: String, val stack: List<Route>)

/** The public product has only the closed public Route hierarchy, never an optional-feature key. */
object StandardNavigation : ProductNavigation {
    override val schemaId: String = "better-zslf.standard.navigation"
    private const val VERSION = 1
    private const val MAX_STACK_SIZE = 128
    private const val MAX_SAVED_CHARS = 262_144
    // Migration denylist only: these old strings are not registered destinations or feature flags.
    private val retiredPrivatePages = setOf("water", "runimport", "runmanual", "runplan", "runsupplement")
    private val pageKind = Regex("[a-z][a-z0-9._-]{0,95}")
    private val routeListSerializer = ListSerializer(Route.serializer())
    private val json = Json { isLenient = false; ignoreUnknownKeys = false; allowSpecialFloatingPointValues = false }

    override fun accepts(key: NavKey): Boolean = when (key) {
        Route.Main, Route.Login -> true
        is Route.TabRoot -> key.index in 0..4
        is Route.Page -> pageKind.matches(key.kind) && key.kind !in retiredPrivatePages &&
            key.id.length <= 2048 && key.id.none(Char::isISOControl) &&
            key.title.length <= 256 && key.title.none(Char::isISOControl)
        else -> false
    }

    override fun encode(stack: List<NavKey>): String {
        require(validStack(stack)) { "Unsupported navigation state" }
        // The explicit closed serializer is portable to Native; no serializer<NavKey>() or cast of a
        // heterogeneous list can accidentally capture an external product's private key hierarchy.
        val routes = stack.map { it as Route }
        return json.encodeToString(StandardSavedNavigation.serializer(), StandardSavedNavigation(VERSION, schemaId, routes))
            .also { require(it.length <= MAX_SAVED_CHARS) { "Navigation state exceeds save limit" } }
    }

    override fun decode(saved: String): List<NavKey> {
        if (saved.length > MAX_SAVED_CHARS) return emptyList()
        return runCatching {
            val input = saved.trim()
            val routes = if (input.startsWith('[')) {
                // MIUIX's previous reified Route saver wrote a bare List<Route>. Preserve public
                // root/five-tab stack values without changing the existing layout save-state key.
                json.decodeFromString(routeListSerializer, input)
            } else {
                val document = json.decodeFromString(StandardSavedNavigation.serializer(), input)
                if (document.version != VERSION || document.schemaId != schemaId) return emptyList()
                document.stack
            }
            if (validStack(routes)) routes.toList() else emptyList()
        }.getOrElse { emptyList() }
    }

    override fun isOffline(key: NavKey): Boolean = accepts(key) && key is Route.Page &&
        key.kind in setOf("document", "resetpassword")

    override fun swipeDismiss(key: NavKey): Boolean = accepts(key) && key is Route.Page &&
        key.kind !in setOf("runtracker", "resetpassword")

    override fun registerEntries(builder: NavEntryBuilder, onOpen: (NavKey) -> Unit, onBack: () -> Unit) {
        // Public entries are registered exactly once by App, not repeated through a supertype entry.
    }

    private fun validStack(stack: List<NavKey>): Boolean = stack.size <= MAX_STACK_SIZE &&
        stack.all(::accepts) && stack.distinct().size == stack.size
}

/** Explicit product codec saver; never asks MIUIX to serialize the open NavKey supertype. */
internal fun productBackStackSaver(navigation: ProductNavigation, seed: NavKey): Saver<NavBackStack, String> {
    require(navigation.accepts(seed)) { "Unsupported navigation seed" }
    return Saver(
        save = { stack -> navigation.encode(stack.toList()) },
        restore = { saved ->
            val restored = runCatching { navigation.decode(saved) }.getOrElse { emptyList() }
            val valid = restored.isNotEmpty() && restored.all(navigation::accepts) && restored.distinct().size == restored.size
            (if (valid) restored else listOf(seed)).toMutableStateList()
        },
    )
}

/** Retains the caller's existing root/tab saveable slot; schema belongs inside the saved document. */
@Composable
fun rememberProductBackStack(navigation: ProductNavigation, seed: NavKey): NavBackStack {
    val saver = remember(navigation, seed) { productBackStackSaver(navigation, seed) }
    return rememberSaveable(navigation.schemaId, seed, saver = saver) { listOf(seed).toMutableStateList() }
}
