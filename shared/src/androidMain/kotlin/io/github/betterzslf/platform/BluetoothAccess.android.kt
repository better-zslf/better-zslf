package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.betterzslf.feature.water.WaterDateTime
import io.github.betterzslf.feature.water.WaterEpilogueContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

private fun bluetoothState(context: Context): BluetoothAccessState {
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        ?: return BluetoothAccessState(available = false, message = AppText.Water.BLUETOOTH_ADAPTER_UNAVAILABLE)
    val granted = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    if (!granted) return BluetoothAccessState(message = AppText.Water.NEARBY_DEVICES_PERMISSION_REQUIRED)
    val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
    return BluetoothAccessState(granted = true, enabled = enabled, message = if (enabled) null else AppText.Water.ENABLE_BLUETOOTH_REQUIRED)
}

@Composable
actual fun rememberBluetoothAccess(): BluetoothAccess {
    val context = LocalContext.current.applicationContext
    val status = remember(context) { MutableStateFlow(bluetoothState(context)) }
    val refresh = { status.value = bluetoothState(context) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh() }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { refresh() }
        }
        // Bluetooth broadcasts originate from a privileged Bluetooth UID, not necessarily system UID.
        // No extras are trusted; the receiver only re-reads actual OS permission and adapter state.
        context.registerReceiver(receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return remember(context, permissions, enable) {
        object : BluetoothAccess {
            override val state = status.asStateFlow()
            override fun requestPermissions() {
                runCatching { permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)) }
                    .onFailure { status.value = status.value.copy(message = AppText.Water.NEARBY_DEVICES_PERMISSION_REQUEST_FAILED_CHECK_SETTINGS) }
            }
            override fun requestEnable() {
                if (!bluetoothState(context).granted) {
                    status.value = bluetoothState(context)
                    return
                }
                runCatching { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    .onFailure { status.value = status.value.copy(message = AppText.Water.ENABLE_BLUETOOTH_IN_SYSTEM_SETTINGS) }
            }
        }
    }
}

actual fun createWaterEpilogueContext(): WaterEpilogueContext {
    val now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
    return WaterEpilogueContext(Random.nextInt(1, 10000), WaterDateTime(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second))
}
