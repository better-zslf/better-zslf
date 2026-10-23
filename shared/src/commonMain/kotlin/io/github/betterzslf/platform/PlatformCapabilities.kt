package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import io.github.betterzslf.feature.run.RunLocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

const val MAX_PICKED_FILE_BYTES: Int = 8 * 1024 * 1024

/** Explicit, user-initiated platform UI. Merely remembering a launcher never opens it. */
fun interface UserRequestLauncher { fun launch() }

/** No raw paths; bytes stay in memory until an explicitly confirmed operation uses them. */
class PickedFile(name: String, val mediaType: String, bytes: ByteArray) {
    val name: String = name.substringAfterLast('/').substringAfterLast('\\')
        .filterNot(Char::isISOControl).trim().take(120).ifBlank { "attachment" }
    init { require(bytes.size <= MAX_PICKED_FILE_BYTES) { "文件超过 8 MiB 限制" } }
    private val content = bytes.copyOf()
    val size: Int get() = content.size
    fun readBytes(): ByteArray = content.copyOf()
    override fun toString(): String = "PickedFile(size=$size)"
}

sealed interface FilePickResult {
    data object Cancelled : FilePickResult
    data class Selected(val file: PickedFile) : FilePickResult
    data class Failed(val message: String) : FilePickResult
}

@Composable
expect fun rememberFilePicker(onResult: (FilePickResult) -> Unit): UserRequestLauncher

/** Only precise location is sufficient. Approximate/denied results are false. */
@Composable
expect fun rememberLocationPermissionRequester(onResult: (Boolean) -> Unit): UserRequestLauncher

enum class LocationPhase { Idle, Starting, Tracking, PermissionRequired, LocationDisabled, Failed, Unavailable }
data class LocationTrackingState(val phase: LocationPhase = LocationPhase.Idle, val message: String? = null)

/** One tracker per application process. start/stop are explicit and never send network requests. */
interface NativeLocationTracker {
    val state: StateFlow<LocationTrackingState>
    val samples: Flow<RunLocationSample>
    fun start(samplingSeconds: Int = 10)
    fun stop()
    fun close()
}

expect fun createLocationTracker(): NativeLocationTracker
