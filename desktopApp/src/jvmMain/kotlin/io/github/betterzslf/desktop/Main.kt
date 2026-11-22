package io.github.betterzslf.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.betterzslf.App
import io.github.betterzslf.AppGraph
import io.github.betterzslf.platform.createPlatformServices

/**
 * Local UI preview (Standard composition): boots the shared App with the JVM host platform.
 * Default uses the real network exactly like an installed app; pass the Gradle property
 * `-PmockData=true` to swap the HTTP edge for canned offline payloads and skip the campus
 * login with a pre-seeded session. In-memory storage, no device, schedule native bridge
 * inert. Not a product surface; never signed or distributed.
 */
fun main() = application {
    val mockData = System.getProperty("betterzslf.preview.mock") == "true"
    val platform = if (mockData) previewPlatform() else createPlatformServices()
    val graph = AppGraph(platform)
    Window(
        onCloseRequest = { exitApplication() },
        state = rememberWindowState(size = DpSize(412.dp, 915.dp)),
        title = "better-zslf UI preview (${if (mockData) "mock data" else "real network"}, in-memory)",
    ) {
        App(platform, graphProvider = { graph })
    }
}
