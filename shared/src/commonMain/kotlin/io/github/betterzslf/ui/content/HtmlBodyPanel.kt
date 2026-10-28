package io.github.betterzslf.ui.content

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.platform.HtmlPanel
import io.github.betterzslf.ui.AppUiTokens
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Reloads only this native document. It never repeats article, like, hit-count or mark-read requests. */
@Composable
internal fun HtmlBodyPanel(html: String, modifier: Modifier = Modifier) {
    val loader = remember(html) { HtmlBodyLoadSession() }
    val state by loader.state.collectAsState()
    val request = state.request
    DisposableEffect(loader) { onDispose { loader.close() } }
    LaunchedEffect(loader, request, state.phase) {
        if (state.phase == HtmlBodyPhase.LOADING) loader.awaitTimeout(request)
    }
    Column(modifier) {
        if (state.phase == HtmlBodyPhase.LOADING) {
            // A sibling status row stays visible even when a native interop view occupies its own layer.
            Row(Modifier.fillMaxWidth().padding(AppUiTokens.CardPadding), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text(AppText.Content.HTML_BODY_LOADING, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        if (state.phase == HtmlBodyPhase.LOADING || state.phase == HtmlBodyPhase.READY) {
            key(loader, request) {
                HtmlPanel(html, Modifier.fillMaxWidth().weight(1f), onLoadState = { loader.onPlatformState(request, it) })
            }
        } else if (state.phase != HtmlBodyPhase.CLOSED) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(AppUiTokens.CardPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when (state.phase) {
                    HtmlBodyPhase.TIMED_OUT -> AppText.Content.HTML_BODY_LOAD_TIMEOUT
                    HtmlBodyPhase.UNAVAILABLE -> AppText.Content.HTML_BODY_UNAVAILABLE
                    else -> AppText.Content.HTML_BODY_LOAD_FAILED
                }, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                if (state.phase != HtmlBodyPhase.UNAVAILABLE) TextButton(AppText.Content.RELOAD_HTML_BODY, onClick = loader::retry)
            }
        }
    }
}
