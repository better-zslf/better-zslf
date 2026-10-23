@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.betterzslf.feature.water.WaterDateTime
import io.github.betterzslf.feature.water.WaterEpilogueContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import platform.CoreBluetooth.*
import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.darwin.NSObject
import kotlin.random.Random

@Composable
actual fun rememberBluetoothAccess(): BluetoothAccess {
    val scope = rememberCoroutineScope()
    val access = remember(scope) { IosBluetoothAccess(scope) }
    // Reading an existing manager is passive. Neither remember nor this effect creates a manager.
    SideEffect { access.refreshIfInitialized() }
    DisposableEffect(access) { onDispose { access.dispose() } }
    return access
}

private class IosBluetoothAccess(private val scope: CoroutineScope) : BluetoothAccess {
    private val mutableState = MutableStateFlow(BluetoothAccessState(message = "请明确请求蓝牙权限后再开始扫描"))
    override val state: StateFlow<BluetoothAccessState> = mutableState.asStateFlow()
    private var manager: CBCentralManager? = null
    private var disposed = false
    private val nativeDelegate = BluetoothAuthorizationDelegate(::onCentralStateChanged)

    override fun requestPermissions() {
        scope.launch(Dispatchers.Main.immediate) {
            if (disposed) return@launch
            if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) {
                mutableState.value = mutableState.value.copy(message = "请在应用前台请求蓝牙权限")
                return@launch
            }
            // This is the ONLY creation site: initialization can display the OS authorization UI.
            // A null queue delivers delegate events on main. No scanning or connection is performed.
            val current = manager ?: CBCentralManager(
                delegate = nativeDelegate,
                queue = null,
                options = mapOf(CBCentralManagerOptionShowPowerAlertKey to false),
            ).also { manager = it }
            refresh(current)
        }
    }

    override fun requestEnable() {
        scope.launch(Dispatchers.Main.immediate) {
            if (disposed) return@launch
            refreshIfInitialized()
            mutableState.value = mutableState.value.copy(
                message = "请在系统设置 → 蓝牙中打开蓝牙；若权限已关闭，请在本应用设置中允许蓝牙后返回并重新检查",
            )
        }
    }

    private fun onCentralStateChanged(central: CBCentralManager) {
        if (!disposed && central == manager) refresh(central)
    }

    fun refreshIfInitialized() {
        if (!disposed) manager?.let(::refresh)
    }

    private fun refresh(central: CBCentralManager) {
        val authorization = CBManager.authorization
        val granted = authorization == CBManagerAuthorizationAllowedAlways
        val available = central.state != CBManagerStateUnsupported
        val enabled = central.state == CBManagerStatePoweredOn
        val message = when {
            !available -> "此设备不支持所需蓝牙功能"
            authorization == CBManagerAuthorizationDenied || authorization == CBManagerAuthorizationRestricted ->
                "蓝牙权限已关闭或受系统限制，请检查本应用的系统设置"
            !granted -> "等待系统蓝牙授权；选择后可再次点击检查权限"
            central.state == CBManagerStatePoweredOff -> "蓝牙已关闭，请在系统设置 → 蓝牙中打开"
            central.state == CBManagerStateResetting -> "系统蓝牙正在重置，请稍后明确重新检查"
            central.state == CBManagerStateUnauthorized -> "系统尚未允许使用蓝牙，请检查权限"
            enabled -> null
            else -> "正在等待系统蓝牙状态"
        }
        mutableState.value = BluetoothAccessState(available, granted, enabled, message)
    }

    fun dispose() {
        disposed = true
        manager?.delegate = null
        manager = null
        nativeDelegate.clear()
    }
}

/** Native delegates implement only Objective-C protocols, never the common Kotlin capability. */
private class BluetoothAuthorizationDelegate(
    private var onStateChanged: ((CBCentralManager) -> Unit)?,
) : NSObject(), CBCentralManagerDelegateProtocol {
    override fun centralManagerDidUpdateState(central: CBCentralManager) {
        onStateChanged?.invoke(central)
    }

    fun clear() { onStateChanged = null }
}

/** Only an explicit protocol operation invokes this function; never sample time/seeds on remember. */
actual fun createWaterEpilogueContext(): WaterEpilogueContext {
    val calendar = NSCalendar.calendarWithIdentifier(NSCalendarIdentifierGregorian)
        ?: error("Gregorian calendar is unavailable")
    calendar.timeZone = NSTimeZone.timeZoneWithName("Asia/Shanghai")
        ?: error("Shanghai time zone is unavailable")
    val components = calendar.components(
        NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
            NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond,
        fromDate = NSDate(),
    )
    return WaterEpilogueContext(
        userIdSeed = Random.nextInt(1, 10_000),
        dateTime = WaterDateTime(
            year = components.year.toInt(),
            month = components.month.toInt(),
            day = components.day.toInt(),
            hour = components.hour.toInt(),
            minute = components.minute.toInt(),
            second = components.second.toInt(),
        ),
    )
}
