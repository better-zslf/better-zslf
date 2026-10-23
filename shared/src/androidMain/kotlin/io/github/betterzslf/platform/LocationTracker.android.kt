package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunLocationSample
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private object LocationRuntime {
    val status = MutableStateFlow(LocationTrackingState())
    val fixes = MutableSharedFlow<RunLocationSample>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}

actual fun createLocationTracker(): NativeLocationTracker = AndroidLocationTracker

/** App-process singleton; the foreground Service, not an Activity, owns the GPS listener. */
private object AndroidLocationTracker : NativeLocationTracker {
    override val state = LocationRuntime.status.asStateFlow()
    override val samples = LocationRuntime.fixes.asSharedFlow()
    override fun start(samplingSeconds: Int) {
        val context = AndroidPlatform.context
        if (samplingSeconds !in 10..60) {
            LocationRuntime.status.value = LocationTrackingState(LocationPhase.Failed, AppText.Run.LOCATION_SAMPLING_INTERVAL_OUT_OF_RANGE)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            LocationRuntime.status.value = LocationTrackingState(LocationPhase.PermissionRequired, AppText.Run.PRECISE_LOCATION_PERMISSION_REQUIRED)
            return
        }
        val manager = context.getSystemService(LocationManager::class.java)
        if (manager?.isLocationEnabled != true || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            LocationRuntime.status.value = LocationTrackingState(LocationPhase.LocationDisabled, AppText.Run.ENABLE_DEVICE_LOCATION_AND_GPS)
            return
        }
        LocationRuntime.status.value = LocationTrackingState(LocationPhase.Starting)
        try {
            context.startForegroundService(Intent(context, RunLocationService::class.java).putExtra("samplingSeconds", samplingSeconds))
        } catch (_: SecurityException) {
            LocationRuntime.status.value = LocationTrackingState(LocationPhase.PermissionRequired, AppText.Run.LOCATION_PERMISSION_CHANGED_RECHECK)
        } catch (_: Exception) {
            LocationRuntime.status.value = LocationTrackingState(LocationPhase.Failed, AppText.Run.RESTART_LOCATION_TRACKING_IN_FOREGROUND)
        }
    }
    override fun stop() {
        AndroidPlatform.context.stopService(Intent(AndroidPlatform.context, RunLocationService::class.java))
        LocationRuntime.status.value = LocationTrackingState()
    }
    override fun close() = stop()
}

/** Never restarts tracking after process death, and never sends samples to any network service. */
class RunLocationService : Service(), LocationListener {
    private lateinit var locations: LocationManager
    private var lastAcceptedMillis: Long? = null
    private var intervalMillis = 10_000L
    private var startedAtMillis = 0L

    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(LocationManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, AppText.Run.LOCATION_TRACKING_NOTIFICATION_CHANNEL_DISPLAY_NAME, NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            LocationRuntime.status.value = LocationTrackingState()
            stopSelf()
            return START_NOT_STICKY
        }
        val seconds = intent?.getIntExtra("samplingSeconds", 10) ?: 10
        if (seconds !in 10..60) return fail(LocationPhase.Failed, AppText.Run.LOCATION_SAMPLING_INTERVAL_OUT_OF_RANGE)
        intervalMillis = seconds * 1000L
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return fail(LocationPhase.PermissionRequired, AppText.Run.PRECISE_LOCATION_PERMISSION_REQUIRED)
            }
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            if (!locations.isLocationEnabled || !locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                return fail(LocationPhase.LocationDisabled, AppText.Run.ENABLE_DEVICE_LOCATION_AND_GPS)
            }
            locations.removeUpdates(this)
            startedAtMillis = System.currentTimeMillis()
            lastAcceptedMillis = null
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMillis, 0f, this, Looper.getMainLooper())
        } catch (_: SecurityException) {
            return fail(LocationPhase.PermissionRequired, AppText.Run.LOCATION_PERMISSION_CHANGED_RECHECK)
        } catch (_: Exception) {
            return fail(LocationPhase.Failed, AppText.Run.LOCATION_START_FAILED_RETRY_IN_FOREGROUND)
        }
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (!location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy !in 0f..100f) return
        val timestamp = location.time
        val now = System.currentTimeMillis()
        if (timestamp <= 0 || timestamp < startedAtMillis - 1_000 || timestamp > now + 15_000 || now - timestamp > maxOf(30_000, intervalMillis * 2)) return
        if (lastAcceptedMillis?.let { timestamp - it < intervalMillis } == true) return
        val sample = runCatching {
            RunLocationSample(GeoPoint(location.longitude, location.latitude), timestamp, location.accuracy.toDouble(),
                if (location.hasSpeed() && location.speed.isFinite() && location.speed >= 0) location.speed.toDouble() else null)
        }.getOrNull() ?: return
        lastAcceptedMillis = timestamp
        LocationRuntime.status.value = LocationTrackingState(LocationPhase.Tracking)
        LocationRuntime.fixes.tryEmit(sample)
    }

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) fail(LocationPhase.LocationDisabled, AppText.Run.DEVICE_LOCATION_DISABLED_RECORDING_PAUSED)
    }

    private fun fail(phase: LocationPhase, message: String): Int {
        LocationRuntime.status.value = LocationTrackingState(phase, message)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { locations.removeUpdates(this) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (LocationRuntime.status.value.phase in listOf(LocationPhase.Tracking, LocationPhase.Starting)) {
            LocationRuntime.status.value = LocationTrackingState()
        }
        super.onDestroy()
    }

    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 1, Intent(this, RunLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(AppText.Run.LOCATION_TRACKING_NOTIFICATION_TITLE)
            .setContentText(AppText.Run.HIGH_ACCURACY_LOCATION_ENABLED_RETURN_TO_MANAGE_RECORDING)
            .setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, AppText.Run.STOP_LOCATION_TRACKING_ACTION, stop).build())
            .apply {
                packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
                    setContentIntent(PendingIntent.getActivity(this@RunLocationService, 2, launch,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }.build()
    }

    private companion object {
        const val CHANNEL = "run-tracking"
        const val NOTIFICATION_ID = 1024
        const val ACTION_STOP = "io.github.betterzslf.STOP_LOCATION"
    }
}
