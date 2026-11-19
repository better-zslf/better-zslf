package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import io.github.betterzslf.feature.run.RunLocationSample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Explicit, user-initiated platform UI. Merely remembering a launcher never opens it. */
fun interface UserRequestLauncher { fun launch() }

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
