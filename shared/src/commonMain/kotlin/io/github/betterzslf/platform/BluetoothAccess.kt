package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import io.github.betterzslf.feature.water.WaterEpilogueContext
import kotlinx.coroutines.flow.StateFlow

data class BluetoothAccessState(
    val available: Boolean = true,
    val granted: Boolean = false,
    val enabled: Boolean = false,
    val message: String? = null,
)

/** These actions only request OS access/power UI; they do not scan, connect, or authenticate. */
interface BluetoothAccess {
    val state: StateFlow<BluetoothAccessState>
    fun requestPermissions()
    fun requestEnable()
}

@Composable
expect fun rememberBluetoothAccess(): BluetoothAccess

/** A fresh seed and Gregorian Asia/Shanghai components, sampled only for an explicit protocol operation. */
expect fun createWaterEpilogueContext(): WaterEpilogueContext
