package io.github.betterzslf.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.basic.Text

/** JVM is an offline test host, not a simulated native map or location provider. */
@Composable
actual fun RunMapPanel(model: RunMapModel, onEvent: (RunMapEvent) -> Unit, modifier: Modifier) {
    LaunchedEffect(Unit) { onEvent(RunMapEvent.Failed(RunMapFailure.UNAVAILABLE)) }
    Text("本机测试环境不显示原生地图；可检查路线与坐标。", modifier = modifier)
}
