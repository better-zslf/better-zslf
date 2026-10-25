package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
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
    return remember { UserRequestLauncher { callback.value(FilePickResult.Failed(AppText.Common.HOST_TEST_FILE_PICKER_NOT_CONNECTED)) } }
}

@Composable
actual fun rememberLocationPermissionRequester(onResult: (Boolean) -> Unit): UserRequestLauncher {
    val callback = rememberUpdatedState(onResult)
    return remember { UserRequestLauncher { callback.value(false) } }
}

actual fun createLocationTracker(): NativeLocationTracker = object : NativeLocationTracker {
    private val status = MutableStateFlow(LocationTrackingState(LocationPhase.Unavailable, AppText.Run.HOST_TEST_LOCATION_SOURCE_NOT_CONNECTED))
    override val state = status.asStateFlow()
    override val samples = emptyFlow<RunLocationSample>()
    override fun start(samplingSeconds: Int) = Unit
    override fun stop() = Unit
    override fun close() = Unit
}
