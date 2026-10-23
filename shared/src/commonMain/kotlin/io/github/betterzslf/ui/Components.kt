package io.github.betterzslf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
fun Screen(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}, content: @Composable (PaddingValues) -> Unit) {
    Scaffold(containerColor = MiuixTheme.colorScheme.surface, topBar = {
        if (onBack == null) TopAppBar(title = title, actions = actions)
        else SmallTopAppBar(title = title, navigationIcon = { TextButton(text = "返回", onClick = onBack) }, actions = actions)
    }, content = content)
}

@Composable
fun PageList(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun SectionCard(title: String, summary: String = "", onClick: (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp), onClick = onClick) {
        Text(title, style = MiuixTheme.textStyles.title4)
        if (summary.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
    }
}

@Composable
fun AsyncContent(key: Any?, load: suspend () -> ApiResult<JsonElement>, content: @Composable (JsonElement) -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    val loader by rememberUpdatedState(load)
    val result by produceState<ApiResult<JsonElement>?>(null, key, attempt) { value = null; value = loader() }
    when (val state = result) {
        null -> Box(Modifier.fillMaxWidth().padding(32.dp)) { CircularProgressIndicator() }
        is ApiResult.Failure -> SectionCard("加载失败", state.error.message + " · 点按重试", onClick = { attempt++ })
        is ApiResult.Success -> content(state.data)
    }
}

@Composable
fun EmptyCard(message: String = "暂无内容") = SectionCard(message, "可以稍后刷新再看看")
