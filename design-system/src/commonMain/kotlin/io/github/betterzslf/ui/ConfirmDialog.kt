package io.github.betterzslf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.betterzslf.core.text.AppText
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
fun ConfirmDialog(show: Boolean, title: String, summary: String, dismiss: () -> Unit, confirm: () -> Unit) {
    OverlayDialog(show = show, title = title, summary = summary, onDismissRequest = dismiss) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = dismiss, modifier = Modifier.weight(1f), minHeight = 48.dp, cornerRadius = AppUiTokens.ButtonRadius) { Text(AppText.Common.CANCEL) }
            Button(onClick = confirm, modifier = Modifier.weight(1f), minHeight = 48.dp, cornerRadius = AppUiTokens.ButtonRadius,
                colors = primaryActionColors()) { Text(AppText.Common.CONFIRM) }
        }
    }
}
