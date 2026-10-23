@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package io.github.betterzslf.platform

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

private val processLocationTracker: NativeLocationTracker by lazy { IosLocationTracker() }

actual fun createLocationTracker(): NativeLocationTracker = processLocationTracker

/** One process-wide tracker; creating/observing it never requests permission or starts a sensor. */
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
    private var generation = 0L
    private val nativeDelegate = IosLocationDelegate(
        onAuthorizationChanged = ::onAuthorizationChanged,
        onLocations = ::onLocations,
        onFailure = ::onFailure,
    )

    override fun start(samplingSeconds: Int) = onMain {
        if (closed) {
            mutableState.value = LocationTrackingState(LocationPhase.Unavailable, "定位记录器已关闭")
            return@onMain
        }
        if (active || startJob?.isActive == true) return@onMain
        accessCheckJob?.cancel()
        accessCheckJob = null
        val currentGeneration = ++generation
        mutableState.value = LocationTrackingState(LocationPhase.Starting, "正在检查定位服务")
        startJob = scope.launch {
            val enabled = withContext(Dispatchers.Default) { CLLocationManager.locationServicesEnabled() }
            ensureActive()
            if (currentGeneration != generation || closed) return@launch
            if (!enabled) {
                terminate(LocationPhase.LocationDisabled, "系统定位服务已关闭，请在设置中开启")
                return@launch
            }
            if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) {
                terminate(LocationPhase.Failed, "请在应用前台明确开始或恢复记录")
                return@launch
            }
            val modes = NSBundle.mainBundle.objectForInfoDictionaryKey("UIBackgroundModes") as? List<*>
            if (modes?.contains("location") != true) {
                terminate(LocationPhase.Unavailable, "当前应用未配置连续后台定位")
                return@launch
            }
            val next = CLLocationManager()
            manager = next
            next.delegate = nativeDelegate
            if (!hasPreciseLocationPermission(next)) {
                terminate(LocationPhase.PermissionRequired, "请先允许使用期间的精确定位，再明确开始记录")
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
            mutableState.value = LocationTrackingState(LocationPhase.Starting, "正在等待精确定位信号")
            next.startUpdatingLocation()
        }
    }

    override fun stop() = onMain {
        if (!closed) terminate(LocationPhase.Idle, null)
    }

    override fun close() = onMain {
        if (closed) return@onMain
        closed = true
        terminate(LocationPhase.Unavailable, "定位记录器已关闭")
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
                terminate(LocationPhase.Failed, "定位样本处理阻塞，请返回前台后重新开始")
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
                mutableState.value = LocationTrackingState(LocationPhase.Starting, "定位信号暂不可用，正在等待新样本")
            }
            else -> terminate(LocationPhase.Failed, "定位服务发生错误，请返回前台后明确重新开始")
        }
    }

    private fun haltForAccessChange() {
        terminate(LocationPhase.PermissionRequired, "精确定位权限已变化，请检查系统设置")
        val currentGeneration = generation
        accessCheckJob = scope.launch {
            val enabled = withContext(Dispatchers.Default) { CLLocationManager.locationServicesEnabled() }
            ensureActive()
            if (closed || currentGeneration != generation) return@launch
            if (!enabled) {
                mutableState.value = LocationTrackingState(LocationPhase.LocationDisabled, "系统定位服务已关闭，请在设置中开启")
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
