package io.github.betterzslf

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import io.github.betterzslf.platform.*
import kotlinx.coroutines.flow.collect

/** Local native integration probe. No account, HTTP client, upload, or release manifest entry. */
@android.annotation.SuppressLint("SetTextI18n") // Stable machine-readable selectors in a debug-only local test UI.
class CapabilityProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val tracker = remember { createLocationTracker() }
            val status by tracker.state.collectAsState()
            var samples by remember { mutableIntStateOf(0) }
            var permission by remember { mutableStateOf("not requested") }
            var fileResult by remember { mutableStateOf("none") }
            val permissions = rememberLocationPermissionRequester { permission = "granted=$it" }
            val files = rememberFilePicker { result ->
                fileResult = when (result) {
                    FilePickResult.Cancelled -> "cancelled"
                    is FilePickResult.Failed -> "error:${result.message}"
                    is FilePickResult.Selected -> "selected:${result.file.size}"
                }
            }
            LaunchedEffect(tracker) { tracker.samples.collect { samples++ } }
            DisposableEffect(tracker) { onDispose { tracker.stop() } }
            AndroidView(factory = { context ->
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(32, 48, 32, 32)
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    fun label(text: String, key: String? = null) = TextView(context).apply {
                        this.text = text; textSize = 19f; tag = key; setPadding(0, 10, 0, 10)
                    }.also { addView(it) }
                    fun action(text: String, block: () -> Unit) = Button(context).apply {
                        this.text = text; setOnClickListener { block() }
                    }.also { addView(it) }
                    label("Local capability probe (debug only)")
                    label("No school services or uploads")
                    label("", "status")
                    label("", "samples")
                    label("", "permission")
                    action("Request location permission") { permissions.launch() }
                    action("Start location") { tracker.start(10) }
                    action("Stop location") { tracker.stop() }
                    label("", "file")
                    action("Choose file (8 MiB maximum)") { files.launch() }
                }
            }, update = { view ->
                view.findViewWithTag<TextView>("status").text = "Location:${status.phase}"
                view.findViewWithTag<TextView>("samples").text = "Samples:$samples"
                view.findViewWithTag<TextView>("permission").text = "Permission:$permission"
                view.findViewWithTag<TextView>("file").text = "File:$fileResult"
            })
        }
    }
}
