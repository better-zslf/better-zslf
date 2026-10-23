package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.betterzslf.core.storage.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import top.yukonga.miuix.kmp.basic.Text

actual fun createPlatformServices(): PlatformServices = object : PlatformServices {
    private val values = mutableMapOf<String, String>()
    override val storage = object : KeyValueStore {
        override fun get(key: String) = values[key]
        override fun put(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() { values.clear() }
    }
    override val versionName = "host-test"
    override val description = "JVM host tests"
    override fun httpClient() = HttpClient(CIO)
    override fun openUrl(url: String) = Unit
}

@Composable
actual fun PlatformAppearance(dark: Boolean?) = Unit

@Composable
actual fun HtmlPanel(html: String, modifier: Modifier) { Text(html, modifier = modifier) }
