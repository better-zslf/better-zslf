package io.github.betterzslf.platform

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
        private val status = MutableStateFlow(BluetoothAccessState(available = false, message = "宿主测试目标未连接蓝牙适配器。"))
        override val state = status.asStateFlow()
        override fun requestPermissions() = Unit
        override fun requestEnable() = Unit
    }
}

actual fun createWaterEpilogueContext(): WaterEpilogueContext {
    val now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
    return WaterEpilogueContext(Random.nextInt(1, 10000), WaterDateTime(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second))
}
