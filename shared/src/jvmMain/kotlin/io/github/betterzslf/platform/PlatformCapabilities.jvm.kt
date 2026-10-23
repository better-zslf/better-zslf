package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.betterzslf.feature.run.RunLocationSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

@Composable
actual fun rememberFilePicker(onResult: (FilePickResult) -> Unit): UserRequestLauncher {
    val callback = rememberUpdatedState(onResult)
    return remember { UserRequestLauncher { callback.value(FilePickResult.Failed("宿主测试目标未连接文件选择器。")) } }
}

@Composable
actual fun rememberLocationPermissionRequester(onResult: (Boolean) -> Unit): UserRequestLauncher {
    val callback = rememberUpdatedState(onResult)
    return remember { UserRequestLauncher { callback.value(false) } }
}

actual fun createLocationTracker(): NativeLocationTracker = object : NativeLocationTracker {
    private val status = MutableStateFlow(LocationTrackingState(LocationPhase.Unavailable, "宿主测试目标未连接定位源。"))
    override val state = status.asStateFlow()
    override val samples = emptyFlow<RunLocationSample>()
    override fun start(samplingSeconds: Int) = Unit
    override fun stop() = Unit
    override fun close() = Unit
}
