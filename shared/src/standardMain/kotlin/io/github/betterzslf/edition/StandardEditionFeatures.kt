package io.github.betterzslf.edition

import androidx.compose.runtime.Composable
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import kotlinx.coroutines.CoroutineScope

/** Empty source-selected facade: no Bluetooth dependency, Pro UI, protocol or controller. */
internal fun createEditionFeatures(scope: CoroutineScope): EditionFeatures = StandardEditionFeatures

private object StandardEditionFeatures : EditionFeatures {
    @Composable override fun HomeEntry(open: (Route) -> Unit) = Unit
    @Composable override fun LoginEntry(open: () -> Unit) = Unit
    @Composable override fun Page(graph: AppGraph, page: Route.Page, open: (Route) -> Unit, back: () -> Unit) = EditionUnavailablePage(back)
}
