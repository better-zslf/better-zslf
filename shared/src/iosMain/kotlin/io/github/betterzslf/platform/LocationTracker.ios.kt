@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunLocationSample
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.CoreLocation.*
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSThread
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private var processLocationTracker: IosLocationTracker? = null

actual fun createLocationTracker(): NativeLocationTracker {
    check(NSThread.isMainThread) { "Acquire the location tracker on the main thread" }
    return processLocationTracker?.takeUnless { it.isClosed }
        ?: IosLocationTracker().also { processLocationTracker = it }
}

/** One live process-wide tracker; an explicitly closed owner is replaced after local-data reset. */
private class IosLocationTracker : NativeLocationTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(LocationTrackingState())
    private val mutableSamples = MutableSharedFlow<RunLocationSample>(replay = 0)
    override val state: StateFlow<LocationTrackingState> = mutableState.asStateFlow()
    override val samples: Flow<RunLocationSample> = mutableSamples.asSharedFlow()
    private var manager: CLLocationManager? = null
    private var startJob: Job? = null
    private var accessCheckJob: Job? = null
    private var emissionJob: Job? = null
    private var gate: IosLocationSampleGate? = null
    private var active = false
    private var closed = false
    val isClosed: Boolean get() = closed
    private var generation = 0L
    private val nativeDelegate = IosLocationDelegate(
        onAuthorizationChanged = ::onAuthorizationChanged,
        onLocations = ::onLocations,
        onFailure = ::onFailure,
    )

    override fun start(samplingSeconds: Int) = onMain {
        if (closed) {
            mutableState.value = LocationTrackingState(LocationPhase.Unavailable, AppText.Run.LOCATION_TRACKER_CLOSED)
            return@onMain
        }
        if (active || startJob?.isActive == true) return@onMain
        accessCheckJob?.cancel()
        accessCheckJob = null
        val currentGeneration = ++generation
        mutableState.value = LocationTrackingState(LocationPhase.Starting, AppText.Run.CHECKING_LOCATION_SERVICES)
        startJob = scope.launch {
            val enabled = withContext(Dispatchers.Default) { CLLocationManager.locationServicesEnabled() }
            ensureActive()
            if (currentGeneration != generation || closed) return@launch
            if (!enabled) {
                terminate(LocationPhase.LocationDisabled, AppText.Run.SYSTEM_LOCATION_SERVICES_DISABLED_ENABLE_IN_SETTINGS)
                return@launch
            }
            if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) {
                terminate(LocationPhase.Failed, AppText.Run.EXPLICIT_RECORDING_START_OR_RESUME_IN_FOREGROUND_REQUIRED)
                return@launch
            }
            val modes = NSBundle.mainBundle.objectForInfoDictionaryKey("UIBackgroundModes") as? List<*>
            if (modes?.contains("location") != true) {
                terminate(LocationPhase.Unavailable, AppText.Run.CONTINUOUS_BACKGROUND_LOCATION_NOT_CONFIGURED)
                return@launch
            }
            val next = CLLocationManager()
            manager = next
            next.delegate = nativeDelegate
            if (!hasPreciseLocationPermission(next)) {
                terminate(LocationPhase.PermissionRequired, AppText.Run.ALLOW_PRECISE_LOCATION_WHILE_USING_BEFORE_EXPLICIT_RECORDING)
                return@launch
            }
            gate = IosLocationSampleGate(samplingSeconds, currentWallMillis())
            next.desiredAccuracy = kCLLocationAccuracyBestForNavigation
            next.distanceFilter = kCLDistanceFilterNone
            next.activityType = CLActivityTypeFitness
            next.pausesLocationUpdatesAutomatically = false
            next.showsBackgroundLocationIndicator = true
            next.allowsBackgroundLocationUpdates = true
            active = true
            mutableState.value = LocationTrackingState(LocationPhase.Starting, AppText.Run.AWAITING_PRECISE_LOCATION_SIGNAL)
            next.startUpdatingLocation()
        }
    }

    override fun stop() = onMain {
        if (!closed) terminate(LocationPhase.Idle, null)
    }

    override fun close() = onMain {
        if (closed) return@onMain
        closed = true
        terminate(LocationPhase.Unavailable, AppText.Run.LOCATION_TRACKER_CLOSED)
        nativeDelegate.clear()
        scope.cancel()
    }

    private fun onAuthorizationChanged(manager: CLLocationManager) {
        if (!active || manager != this.manager || closed) return
        if (!hasPreciseLocationPermission(manager)) haltForAccessChange()
    }

    private fun onLocations(manager: CLLocationManager, didUpdateLocations: List<*>) {
        if (!active || manager != this.manager || closed) return
        if (!hasPreciseLocationPermission(manager)) {
            haltForAccessChange()
            return
        }
        val currentGate = gate ?: return
        val now = currentWallMillis()
        val receipt = (NSProcessInfo.processInfo.systemUptime * 1_000.0).toLong()
        // Batches can contain cached/out-of-order fixes. Prefer the newest valid fix, at most one
        // per receipt; enforce both native timestamps and monotonic receipt-time throttling.
        val candidates = didUpdateLocations.filterIsInstance<CLLocation>()
            .sortedByDescending { it.timestamp.timeIntervalSince1970 }
        for (location in candidates) {
            val seconds = location.timestamp.timeIntervalSince1970
            if (!seconds.isFinite()) continue
            val timestamp = (seconds * 1_000.0).toLong()
            val coordinate = location.coordinate.useContents { latitude to longitude }
            if (!currentGate.accept(coordinate.first, coordinate.second, timestamp, location.horizontalAccuracy, now, receipt)) {
                continue
            }
            if (emissionJob?.isActive == true) {
                // Never silently queue/drop unbounded private coordinates behind a stalled consumer.
                terminate(LocationPhase.Failed, AppText.Run.LOCATION_SAMPLE_PROCESSING_BLOCKED_RESTART_IN_FOREGROUND)
                return
            }
            val sample = RunLocationSample(
                point = GeoPoint(longitude = coordinate.second, latitude = coordinate.first),
                timestampMillis = timestamp,
                accuracyMeters = location.horizontalAccuracy,
                speedMetersPerSecond = location.speed.takeIf { it.isFinite() && it >= 0.0 },
            )
            mutableState.value = LocationTrackingState(LocationPhase.Tracking)
            emissionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                mutableSamples.emit(sample)
            }
            break
        }
    }

    private fun onFailure(manager: CLLocationManager, didFailWithError: NSError) {
        if (!active || manager != this.manager || closed) return
        when {
            didFailWithError.domain == kCLErrorDomain && didFailWithError.code == kCLErrorDenied -> haltForAccessChange()
            didFailWithError.domain == kCLErrorDomain &&
                (didFailWithError.code == kCLErrorLocationUnknown || didFailWithError.code == kCLErrorNetwork) -> {
                // Standard updates remain active; no request/restart/retry is issued here.
                mutableState.value = LocationTrackingState(LocationPhase.Starting, AppText.Run.LOCATION_SIGNAL_UNAVAILABLE_AWAITING_NEW_SAMPLE)
            }
            else -> terminate(LocationPhase.Failed, AppText.Run.LOCATION_SERVICE_ERROR_RESTART_EXPLICITLY_IN_FOREGROUND)
        }
    }

    private fun haltForAccessChange() {
        terminate(LocationPhase.PermissionRequired, AppText.Run.PRECISE_LOCATION_PERMISSION_CHANGED_CHECK_SYSTEM_SETTINGS)
        val currentGeneration = generation
        accessCheckJob = scope.launch {
            val enabled = withContext(Dispatchers.Default) { CLLocationManager.locationServicesEnabled() }
            ensureActive()
            if (closed || currentGeneration != generation) return@launch
            if (!enabled) {
                mutableState.value = LocationTrackingState(LocationPhase.LocationDisabled, AppText.Run.SYSTEM_LOCATION_SERVICES_DISABLED_ENABLE_IN_SETTINGS)
            }
        }
    }

    /** All state/delegate/flow changes happen on the main run loop, including off-main callers. */
    private fun onMain(action: () -> Unit) {
        if (NSThread.isMainThread) action() else dispatch_async(dispatch_get_main_queue()) { action() }
    }

    private fun terminate(phase: LocationPhase, message: String?) {
        ++generation
        active = false
        gate = null
        startJob?.cancel()
        startJob = null
        accessCheckJob?.cancel()
        accessCheckJob = null
        emissionJob?.cancel()
        emissionJob = null
        manager?.let {
            it.stopUpdatingLocation()
            it.allowsBackgroundLocationUpdates = false
            it.showsBackgroundLocationIndicator = false
            it.delegate = null
        }
        manager = null
        mutableState.value = LocationTrackingState(phase, message)
    }
}

private fun currentWallMillis(): Long = (NSDate().timeIntervalSince1970 * 1_000.0).toLong()
