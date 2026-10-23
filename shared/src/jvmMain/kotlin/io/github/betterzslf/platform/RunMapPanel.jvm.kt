package io.github.betterzslf.platform

import io.github.betterzslf.core.text.AppText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Text

/** JVM is an offline test host, not a simulated native map or location provider. */
@Composable
actual fun RunMapPanel(model: RunMapModel, onEvent: (RunMapEvent) -> Unit, modifier: Modifier) {
    LaunchedEffect(Unit) { onEvent(RunMapEvent.Failed(RunMapFailure.UNAVAILABLE)) }
    Text(AppText.Run.HOST_TEST_NATIVE_MAP_UNAVAILABLE_INSPECT_ROUTE_AND_COORDINATES, modifier = modifier)
}
