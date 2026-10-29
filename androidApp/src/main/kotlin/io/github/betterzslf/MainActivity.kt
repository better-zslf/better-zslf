package io.github.betterzslf

import android.app.Application
import android.os.Bundle
import android.content.Intent
import io.github.betterzslf.platform.handleScheduleNativeIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
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
            // AppGraph has restored the current lease before a cold-start click is validated.
            LaunchedEffect(Unit) { handleScheduleNativeIntent(intent) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleScheduleNativeIntent(intent)
    }
}
