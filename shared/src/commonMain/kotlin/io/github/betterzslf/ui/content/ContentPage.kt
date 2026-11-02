package io.github.betterzslf.ui.content

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.content.*
import io.github.betterzslf.platform.HtmlPanel
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.EmptyCard
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.LocalNavigationBottomInset
import io.github.betterzslf.ui.pageContentPadding
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.UiTone
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * Protocol documents only (用户协议 / 隐私政策 / 使用帮助). Columns, articles, browsing history,
 * announcements, the message inbox and the service directory have been retired.
 */
@Composable
fun ContentPage(graph: AppGraph, page: Route.Page, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    val session by graph.sessions.session.collectAsState()
    val owner = session?.userId.orEmpty()
    val source = remember(graph) { RepositoryContentSource(graph.repositories) }
    // Dispose the page-scoped controller on an account change.
    key(owner, page) {
        if (page.kind == "document") DocumentPage(source, page, onBack)
        else Screen(page.title.ifBlank { AppText.Content.CONTENT_TITLE }, onBack) { padding ->
            LazyColumn(Modifier.fillMaxSize().consumeWindowInsets(padding),
                contentPadding = pageContentPadding(padding, LocalNavigationBottomInset.current, LocalLayoutDirection.current)) {
                item { EmptyCard(AppText.Content.UNCONNECTED_CONTENT_PAGE) }
            }
        }
    }
}

@Composable
private fun DocumentPage(source: ContentSource, page: Route.Page, back: () -> Unit) {
    val controller = remember(source, page) { ArticleController(source, page.id) }
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    val refresh: () -> Unit = { if (!state.loading) scope.launch { controller.load() } }
    LaunchedEffect(controller) { controller.load() }
    Screen(page.title.ifBlank { page.id }, back, actions = {
        TextButton(AppText.Common.REFRESH, onClick = refresh, enabled = !state.loading)
    }) { padding ->
        PullToRefresh(state.loading, refresh, Modifier.fillMaxSize().consumeWindowInsets(padding),
            contentPadding = padding, refreshTexts = contentRefreshTexts()) {
            LazyColumn(Modifier.fillMaxSize().overScrollVertical(), overscrollEffect = null,
                contentPadding = pageContentPadding(padding, LocalNavigationBottomInset.current, LocalLayoutDirection.current),
                verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                if (state.loading && state.detail == null) item { LoadingItem() }
                state.error?.let { error -> item { ContentError(AppText.Common.LOAD_FAILED, error.message, refresh) } }
                state.detail?.let { detail ->
                    item {
                        Text(detail.title.ifBlank { page.title.ifBlank { AppText.Common.UNTITLED } },
                            fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        if (detail.created.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(detail.created, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                    }
                    item {
                        // The document body is the server response; never substitute design sample text.
                        if (detail.html.isBlank()) EmptyCard(AppText.Content.NO_BODY_CONTENT)
                        else Card(Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(0.dp)) {
                            HtmlPanel(detail.html, Modifier.fillMaxWidth().height(580.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentError(title: String, message: String, retry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoBanner(message, title, UiTone.Warning)
        TextButton(AppText.Common.RETRY, onClick = retry)
    }
}

private fun contentRefreshTexts() = listOf(AppText.Common.PULL_TO_REFRESH, AppText.Common.RELEASE_TO_REFRESH, AppText.Common.REFRESHING, "")

@Composable
private fun LoadingItem() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
}
