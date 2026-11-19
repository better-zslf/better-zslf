package io.github.betterzslf.ui.schedule

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.betterzslf.ui.AppUiTokens
import top.yukonga.miuix.kmp.basic.*

@Composable
fun SchedulePanel(content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding)) {
        Column(verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap), content = content)
    }
}

@Composable
fun ScheduleProgress(message: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
        CircularProgressIndicator()
        Text(message, modifier = Modifier.weight(1f))
    }
}
