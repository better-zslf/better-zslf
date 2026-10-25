package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.betterzslf.feature.water.WaterDateTime
import io.github.betterzslf.feature.water.WaterEpilogueContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random

@Composable
actual fun rememberBluetoothAccess(): BluetoothAccess = remember {
    object : BluetoothAccess {
        private val status = MutableStateFlow(BluetoothAccessState(available = false, message = AppText.Water.HOST_TEST_BLUETOOTH_ADAPTER_NOT_CONNECTED))
        override val state = status.asStateFlow()
        override fun requestPermissions() = Unit
        override fun requestEnable() = Unit
    }
}

actual fun createWaterEpilogueContext(): WaterEpilogueContext {
    val now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
    return WaterEpilogueContext(Random.nextInt(1, 10000), WaterDateTime(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second))
}
