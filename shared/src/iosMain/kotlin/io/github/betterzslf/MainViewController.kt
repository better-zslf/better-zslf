package io.github.betterzslf

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import io.github.betterzslf.platform.IosStartupDiagnostics
import io.github.betterzslf.platform.PlatformServices
import io.github.betterzslf.platform.createPlatformServices
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController {
    IosStartupDiagnostics.runProbeIfEnabled()
    return ComposeUIViewController {
        if (!IosStartupDiagnostics.enabled) {
            App()
        } else {
            val owner = remember { StartupDiagnosticGraphOwner(createPlatformServices()) }
            val provider = remember(owner) { { owner.create() } }
            // App's injected-factory mode deliberately does not own its graph; this native owner does.
            DisposableEffect(owner) { onDispose { owner.close() } }
            App(platform = owner.platform, graphProvider = provider)
        }
    }
}

private class StartupDiagnosticGraphOwner(val platform: PlatformServices) {
    private var graph: AppGraph? = null
    fun create(): AppGraph {
        close()
        return IosStartupDiagnostics.createGraph { AppGraph(platform) }.also { graph = it }
    }
    fun close() {
        graph?.close()
        graph = null
    }
}
