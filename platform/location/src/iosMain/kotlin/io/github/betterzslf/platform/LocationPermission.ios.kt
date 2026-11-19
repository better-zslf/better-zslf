@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreLocation.CLAccuracyAuthorization
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.Foundation.NSError
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UIKit.UIViewController
import platform.darwin.NSObject

@Composable
actual fun rememberLocationPermissionRequester(onResult: (Boolean) -> Unit): UserRequestLauncher {
    val callback = rememberUpdatedState(onResult)
    val controller = LocalUIViewController.current
    val scope = rememberCoroutineScope()
    val launcher = remember(controller, scope) {
        IosLocationPermissionLauncher(controller, scope) { callback.value(it) }
    }
    DisposableEffect(launcher) { onDispose { launcher.dispose() } }
    return launcher
}

private class IosLocationPermissionLauncher(
    private val presenter: UIViewController,
    private val scope: CoroutineScope,
    private var onResult: ((Boolean) -> Unit)?,
) : UserRequestLauncher {
    private var manager: CLLocationManager? = null
    private var pending = false
    private var disposed = false
    private val nativeDelegate = IosLocationDelegate(onAuthorizationChanged = ::onAuthorizationChanged)

    override fun launch() {
        scope.launch(Dispatchers.Main.immediate) {
            if (disposed || pending) return@launch
            if (presenter.viewIfLoaded?.window == null ||
                UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive
            ) {
                onResult?.invoke(false)
                return@launch
            }
            pending = true
            val enabled = withContext(Dispatchers.Default) { CLLocationManager.locationServicesEnabled() }
            if (disposed) return@launch
            if (!enabled || presenter.viewIfLoaded?.window == null ||
                UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive
            ) {
                finish(false)
                return@launch
            }
            // Manager construction/delegates and the system prompt remain on the main run loop.
            val next = CLLocationManager()
            manager = next
            next.delegate = nativeDelegate
            if (next.authorizationStatus == kCLAuthorizationStatusNotDetermined) {
                next.requestWhenInUseAuthorization()
            } else {
                finish(hasPreciseLocationPermission(next))
            }
        }
    }

    private fun onAuthorizationChanged(manager: CLLocationManager) {
        if (disposed || !pending || manager != this.manager) return
        // A newly-created manager can report notDetermined before the user answers the prompt.
        if (manager.authorizationStatus == kCLAuthorizationStatusNotDetermined) return
        finish(hasPreciseLocationPermission(manager))
    }

    private fun finish(granted: Boolean) {
        manager?.delegate = null
        manager = null
        pending = false
        if (!disposed) onResult?.invoke(granted)
    }

    fun dispose() {
        disposed = true
        onResult = null
        manager?.delegate = null
        manager = null
        pending = false
        nativeDelegate.clear()
    }
}

/** Kept strongly by the Kotlin owner; native CLLocationManager holds its delegate weakly. */
internal class IosLocationDelegate(
    private var onAuthorizationChanged: ((CLLocationManager) -> Unit)? = null,
    private var onLocations: ((CLLocationManager, List<*>) -> Unit)? = null,
    private var onFailure: ((CLLocationManager, NSError) -> Unit)? = null,
) : NSObject(), CLLocationManagerDelegateProtocol {
    override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
        onAuthorizationChanged?.invoke(manager)
    }

    override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
        onLocations?.invoke(manager, didUpdateLocations)
    }

    override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
        onFailure?.invoke(manager, didFailWithError)
    }

    fun clear() {
        onAuthorizationChanged = null
        onLocations = null
        onFailure = null
    }
}

internal fun hasPreciseLocationPermission(manager: CLLocationManager): Boolean =
    (manager.authorizationStatus == kCLAuthorizationStatusAuthorizedWhenInUse ||
        manager.authorizationStatus == kCLAuthorizationStatusAuthorizedAlways) &&
        manager.accuracyAuthorization == CLAccuracyAuthorization.CLAccuracyAuthorizationFullAccuracy
