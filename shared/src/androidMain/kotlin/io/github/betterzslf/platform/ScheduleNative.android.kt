package io.github.betterzslf.platform

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import io.github.betterzslf.schedule.ScheduleNativeEngine
import io.github.betterzslf.schedule.ScheduleNativeWork
import io.github.betterzslf.schedule.ScheduleNextWidgetProvider
import io.github.betterzslf.schedule.ScheduleNotificationPermissionActivity
import io.github.betterzslf.schedule.ScheduleTodayWidgetProvider

actual fun createScheduleNativeBridge(): ScheduleNativeBridge = AndroidScheduleNativeBridge(AndroidPlatform.context)

/** Call after owner restoration on cold start, and from MainActivity.onNewIntent for warm starts. */
fun handleScheduleNativeIntent(intent: Intent?): Boolean {
    val handled = ScheduleNativeEngine.from(AndroidPlatform.context).handleOpenIntent(intent)
    if (handled) { intent?.action = null; intent?.data = null }
    return handled
}

private class AndroidScheduleNativeBridge(private val context: Context) : ScheduleNativeBridge {
    private val engine = ScheduleNativeEngine.from(context)
    override val supported: Boolean = true
    override val capabilities = engine.capabilities
    override val openRequests = engine.openRequests
    init { refreshCapabilities() }
    override fun refreshCapabilities() {
        engine.refreshCapabilities()
        ScheduleNativeWork.execute { engine.reconcile() }
    }
    override fun requestNotificationPermission(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return openNotificationSettings()
        return start(Intent(context, ScheduleNotificationPermissionActivity::class.java))
    }
    override fun openNotificationSettings(): Boolean = start(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    override fun openExactAlarmSettings(): Boolean = start(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:" + context.packageName)))
    override fun openBatteryOptimizationSettings(): Boolean = start(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    override fun requestPinWidget(kind: ScheduleWidgetKind): Boolean = runCatching {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return@runCatching false
        val provider = if (kind == ScheduleWidgetKind.TODAY) ScheduleTodayWidgetProvider::class.java else ScheduleNextWidgetProvider::class.java
        manager.requestPinAppWidget(ComponentName(context, provider), null, null)
    }.getOrDefault(false)
    override fun publish(publication: ScheduleNativePublication): Boolean = engine.publish(publication)
    override fun clear(status: ScheduleNativeStatus, ownerRevision: String?) = engine.clear(status, ownerRevision)
    override fun consumeOpenRequest() = engine.consumeOpenRequest()
    override fun testLiveNotification(): Boolean = engine.testLiveNotification()
    override fun close() = Unit // Native publication and its alarms intentionally outlive AppGraph's UI.
    private fun start(intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}
