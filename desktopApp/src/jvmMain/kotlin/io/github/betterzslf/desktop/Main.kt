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
 * Local UI preview only (Standard composition): boots the same shared App with the JVM host
 * platform — in-memory storage, no device, schedule native bridge inert. Not a product
 * surface; never signed or distributed.
 */
fun main() = application {
    val platform = createPlatformServices()
    val graph = AppGraph(platform)
    Window(
        onCloseRequest = { exitApplication() },
        state = rememberWindowState(size = DpSize(412.dp, 915.dp)),
        title = "better-zslf UI preview (in-memory, Standard)",
    ) {
        App(platform, graphProvider = { graph })
    }
}
