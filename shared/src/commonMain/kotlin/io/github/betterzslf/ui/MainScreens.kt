package io.github.betterzslf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType


/** Also used by the selected edition facade; no Pro implementation type leaks into common UI. */
@Composable
fun CampusShortcut(title: String, icon: ImageVector, accent: Color, supportingText: String = "", onClick: () -> Unit) {
    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
        colors = CardDefaults.defaultColors(color = Color.Transparent), insideMargin = PaddingValues(vertical = 4.dp),
        pressFeedbackType = PressFeedbackType.Sink, onClick = onClick) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(52.dp).appSquircleClip(16.dp).background(accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(27.dp))
            }
            Text(title, fontSize = 12.sp, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (supportingText.isNotBlank()) Text(supportingText, fontSize = 11.sp, color = MiuixTheme.colorScheme.primary,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ProfileScreen(graph: AppGraph, open: (Route) -> Unit) = io.github.betterzslf.ui.profile.ProfileFeatureScreen(graph, open)

