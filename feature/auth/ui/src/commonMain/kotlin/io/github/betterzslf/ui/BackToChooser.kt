package io.github.betterzslf.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.betterzslf.core.text.AppText
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun BackToChooser(onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.testTag("loginBack")) {
            Icon(BackChevron, contentDescription = AppText.Auth.ENTRY_BACK_CONTENT_DESCRIPTION,
                tint = MiuixTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
        }
    }
}
