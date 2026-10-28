package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.betterzslf.core.storage.KeyValueStore
import io.ktor.client.HttpClient

interface PlatformServices {
    val storage: KeyValueStore
    val versionName: String
    val description: String
    fun httpClient(): HttpClient
    fun openUrl(url: String)
    /** Copies local debug text to the system clipboard; returns false when the platform cannot. */
    fun copyToClipboard(text: String): Boolean
}

expect fun createPlatformServices(): PlatformServices

/** null follows the OS; manual themes must update native status/navigation bars too. */
@Composable
expect fun PlatformAppearance(dark: Boolean?)

/** Native document status only; READY is emitted from a real page-finished callback, never a timer. */
enum class HtmlPanelLoadState { LOADING, READY, FAILED, UNAVAILABLE }

/** Article rendering does not execute JavaScript or expose a native bridge. No native error text is exported. */
@Composable
expect fun HtmlPanel(
    html: String,
    modifier: Modifier = Modifier,
    onLoadState: (HtmlPanelLoadState) -> Unit = {},
)
