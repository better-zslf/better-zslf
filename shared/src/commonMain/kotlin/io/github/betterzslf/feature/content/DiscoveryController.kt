package io.github.betterzslf.feature.content

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*

data class DiscoveryState(
    val tree: ContentTree = ContentTree(emptyList()),
    val expanded: Set<String> = emptySet(),
    val loading: Boolean = false,
    val error: ApiError? = null,
    val runOpen: Boolean? = null,
    val attendanceRadiusMeters: Int? = null,
    val dictionaryError: ApiError? = null,
) { val visible: List<ContentTreeNode> get() = tree.visible(expanded) }

class DiscoveryController(private val source: ContentSource, private val module: String? = null) {
    private val mutable = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = mutable.asStateFlow()
    private var generation = 0
    suspend fun refresh() {
        val ticket = ++generation
        mutable.value = mutable.value.copy(loading = true, error = null, dictionaryError = null)
        val response = if (module == null) source.discover() else source.columns(module)
        if (ticket != generation) return
        when (response) {
            is ApiResult.Failure -> mutable.value = mutable.value.copy(loading = false, error = response.error)
            is ApiResult.Success -> when (val parsed = parseContentTree(response.data, columns = module != null)) {
                is ApiResult.Failure -> mutable.value = mutable.value.copy(loading = false, error = parsed.error)
                is ApiResult.Success -> mutable.value = mutable.value.copy(tree = parsed.data, loading = false,
                    expanded = mutable.value.expanded.intersect(parsed.data.nodes.map { it.key }.toSet()))
            }
        }
        if (module != null) return
        val runConfig = source.dictionary("schoolRun")
        val radius = source.dictionary("attendanceRadius")
        if (ticket != generation) return
        var error: ApiError? = null
        fun value(result: ApiResult<JsonElement>, name: String): Int? = when (result) {
            is ApiResult.Failure -> { error = result.error; null }
            is ApiResult.Success -> {
                val info = (result.data as? JsonObject)?.get("info") as? JsonArray
                val entry = info?.filterIsInstance<JsonObject>()?.firstOrNull { it.text("name") == name }
                entry?.text("code")?.toIntOrNull() ?: run { error = ApiError.Decode("字典结构异常"); null }
            }
        }
        val open = value(runConfig, "isOpen")?.let { it != 0 }
        val meters = value(radius, "radius")?.takeIf { it > 0 }
        mutable.value = mutable.value.copy(runOpen = open, attendanceRadiusMeters = meters, dictionaryError = error)
    }
    fun toggle(key: String) {
        val state = mutable.value
        if (!state.tree.hasChildren(key)) return
        mutable.value = state.copy(expanded = if (key in state.expanded) state.expanded - key else state.expanded + key)
    }
}
