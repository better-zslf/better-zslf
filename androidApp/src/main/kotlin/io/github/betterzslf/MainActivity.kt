package io.github.betterzslf

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import io.github.betterzslf.platform.createPlatformServices
import io.github.betterzslf.platform.AndroidPlatform

class BetterZslfApplication : Application() {
    val platform by lazy { createPlatformServices() }
    private var retainedGraph: AppGraph? = null
    @Synchronized fun applicationGraph(): AppGraph = retainedGraph ?: AppGraph(platform).also { retainedGraph = it }
    override fun onCreate() {
        super.onCreate()
        AndroidPlatform.initialize(this, BuildConfig.APP_VERSION)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Screen-level back handling runs first; returning false keeps the platform behaviour.
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!AppBackDispatcher.dispatch()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        val app = application as BetterZslfApplication
        setContent {
            val provider = remember(app) { app::applicationGraph }
            App(app.platform, graphProvider = provider)
        }
    }
}
