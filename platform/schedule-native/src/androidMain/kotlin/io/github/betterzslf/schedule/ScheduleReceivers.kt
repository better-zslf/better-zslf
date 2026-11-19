package io.github.betterzslf.schedule

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

internal object ScheduleNativeWork {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun execute(result: BroadcastReceiver.PendingResult? = null, work: () -> Unit) {
        scope.launch {
            try { runCatching(work) } finally { result?.finish() }
        }
    }
}

/** Explicit immutable alarm pending intents only; never a notification-to-Activity trampoline. */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScheduleNativeWork.execute(goAsync()) { ScheduleNativeEngine.from(context).onAlarm(intent) }
    }
}

/**
 * Drives the one-minute live-card demonstration.
 *
 * A Handler cannot do this job: it lives in the app's process, and Android freezes or kills a
 * backgrounded process, so the demo stopped the moment the user switched away -- which is precisely
 * when they would be looking at the notification. An alarm broadcast starts the process for each
 * step instead, the same mechanism the real schedule uses, so the demo survives being backgrounded.
 */
class ScheduleDemoTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScheduleNativeWork.execute(goAsync()) {
            io.github.betterzslf.schedule.vendor.VendorDiagnostics.onAlarmTick(context, intent)
        }
    }
}

/** Device restart, clock/zone changes, app update and exact-alarm grant rearm from the private seed. */
class ScheduleReconcileReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScheduleNativeWork.execute(goAsync()) { ScheduleNativeEngine.from(context).reconcile(force = true) }
    }
}

/** User-initiated permission host. No credentials, login, network or course data are placed in its intent. */
class ScheduleNotificationPermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            ScheduleNativeWork.execute { ScheduleNativeEngine.from(this).reconcile() }
            finish()
        } else if (savedInstanceState == null) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9701)
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 9701) {
            ScheduleNativeWork.execute { ScheduleNativeEngine.from(this).reconcile() }
            finish()
        }
    }
}
