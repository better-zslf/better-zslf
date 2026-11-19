package io.github.betterzslf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.core.text.AppText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

// Data adaptation and request ownership remain in the application, not the design system.
fun JsonElement.field(name: String): JsonElement = (this as? JsonObject)?.get(name) ?: JsonNull
fun JsonElement.text(name: String): String = (field(name) as? JsonPrimitive)?.contentOrNull.orEmpty()
fun JsonElement.rows(name: String): List<JsonElement> = (field(name) as? JsonArray)?.toList().orEmpty()
fun JsonElement.stringOrEmpty(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

/** Replay is per request key so a later result cannot erase an unrelated result. */
class NavigationResults {
    private val channels = mutableMapOf<String, MutableSharedFlow<String>>()
    fun observe(key: String) = channel(key).asSharedFlow()
    suspend fun deliver(key: String, value: String) { channel(key).emit(value) }
    fun consume(key: String) { channels.remove(key) }
    private fun channel(key: String) = channels.getOrPut(key) { MutableSharedFlow(replay = 1) }
}

@Composable
fun AsyncContent(key: Any?, load: suspend () -> ApiResult<JsonElement>, content: @Composable (JsonElement) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val loader by rememberUpdatedState(load)
    val result by produceState<ApiResult<JsonElement>?>(null, key, attempt) { value = null; value = loader() }
    when (val state = result) {
        null -> Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(AppText.Common.LOADING, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        is ApiResult.Failure -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            InfoBanner(state.error.message, title = AppText.Common.LOAD_FAILED, tone = UiTone.Warning)
            TextButton(text = AppText.Common.RETRY, onClick = { attempt++ })
        }
        is ApiResult.Success -> content(state.data)
    }
}
