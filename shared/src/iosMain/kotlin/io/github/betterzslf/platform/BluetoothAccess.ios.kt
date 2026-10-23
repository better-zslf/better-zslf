@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.UnsafeNumber::class)

package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
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
    private val mutableState = MutableStateFlow(BluetoothAccessState(message = AppText.Water.EXPLICIT_BLUETOOTH_PERMISSION_REQUEST_REQUIRED_BEFORE_SCAN))
    override val state: StateFlow<BluetoothAccessState> = mutableState.asStateFlow()
    private var manager: CBCentralManager? = null
    private var disposed = false
    private val nativeDelegate = BluetoothAuthorizationDelegate(::onCentralStateChanged)

    override fun requestPermissions() {
        scope.launch(Dispatchers.Main.immediate) {
            if (disposed) return@launch
            if (UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) {
                mutableState.value = mutableState.value.copy(message = AppText.Water.REQUEST_BLUETOOTH_PERMISSION_IN_FOREGROUND)
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
                message = AppText.Water.ENABLE_BLUETOOTH_AND_ALLOW_APP_PERMISSION_THEN_RECHECK,
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
            !available -> AppText.Water.REQUIRED_BLUETOOTH_FEATURES_UNSUPPORTED
            authorization == CBManagerAuthorizationDenied || authorization == CBManagerAuthorizationRestricted ->
                AppText.Water.BLUETOOTH_PERMISSION_DISABLED_OR_RESTRICTED_CHECK_APP_SETTINGS
            !granted -> AppText.Water.AWAITING_SYSTEM_BLUETOOTH_AUTHORIZATION_RECHECK_AFTER_SELECTION
            central.state == CBManagerStatePoweredOff -> AppText.Water.BLUETOOTH_DISABLED_ENABLE_IN_SYSTEM_SETTINGS
            central.state == CBManagerStateResetting -> AppText.Water.SYSTEM_BLUETOOTH_RESETTING_RECHECK_EXPLICITLY_LATER
            central.state == CBManagerStateUnauthorized -> AppText.Water.SYSTEM_BLUETOOTH_ACCESS_NOT_GRANTED_CHECK_PERMISSION
            enabled -> null
            else -> AppText.Water.AWAITING_SYSTEM_BLUETOOTH_STATE
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
