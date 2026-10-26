package io.github.betterzslf.edition

import androidx.compose.runtime.Composable
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.ui.PageList
import io.github.betterzslf.ui.Screen
import io.github.betterzslf.ui.SectionCard

/** No Pro protocol/controller/editor types cross this common application boundary. */
interface EditionFeatures {
    @Composable fun HomeEntry(open: (Route) -> Unit)
    @Composable fun LoginEntry(open: () -> Unit)
    @Composable fun Page(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit)
}

@Composable
internal fun EditionUnavailablePage(back: () -> Unit) {
    Screen(AppText.Edition.FEATURE_UNAVAILABLE_TITLE, back) { padding ->
        PageList(padding) { item { SectionCard(AppText.Edition.FEATURE_UNAVAILABLE_TITLE, AppText.Edition.REOPEN_AVAILABLE_ENTRY_HINT) } }
    }
}
