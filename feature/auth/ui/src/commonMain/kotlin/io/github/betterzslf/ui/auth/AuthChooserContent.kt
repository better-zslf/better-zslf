package io.github.betterzslf.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.ui.AppBrandMark
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.BackChevron
import io.github.betterzslf.ui.PrimaryAction
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * The login entry chooser, migrated whole from the original LoginChooser. Visuals, brand mark,
 * cards, spacing and test tags are unchanged; the bound dots, the secondary dorm link, the
 * conditional enter action and the document buttons all keep their exact original behavior.
 *
 * This content owns no graph, session, repository, API client, NavKey or route: it renders the
 * safe display booleans it is given and reports the nine typed intents back.
 */
@Composable
fun AuthChooserContent(
    display: AuthChooserDisplay,
    onEvent: (AuthLoginUiEvent) -> Unit,
) {
    val dispatch: (AuthLoginUiEvent) -> Unit = { onEvent(it) }
    Scaffold(containerColor = MiuixTheme.colorScheme.surface) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = 44.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppBrandMark()
                    Text(AppText.Auth.WELCOME_TITLE, fontSize = 28.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(AppText.Auth.ENTRY_ENTRY_HINT, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        textAlign = TextAlign.Center)
                }
            }
            item {
                LoginEntryCard(
                    title = AppText.Auth.ENTRY_CAMPUS_TITLE,
                    subtitle = AppText.Auth.ENTRY_CAMPUS_SUBTITLE,
                    accent = AppUiTokens.Primary,
                    icon = MiuixIcons.Contacts,
                    testTag = "entryCampus",
                    bound = display.campusBound,
                    onClick = { dispatch(AuthLoginUiEvent.CHOOSE_CAMPUS) },
                )
            }
            item {
                LoginEntryCard(
                    title = AppText.Auth.ENTRY_ACADEMIC_TITLE,
                    subtitle = AppText.Auth.ENTRY_ACADEMIC_SUBTITLE,
                    accent = AppUiTokens.Positive,
                    icon = MiuixIcons.Notes,
                    testTag = "entryAcademic",
                    bound = display.academicBound,
                    onClick = { dispatch(AuthLoginUiEvent.CHOOSE_ACADEMIC) },
                )
            }
            if (display.showDormLink) item {
                // Deliberately a plain text link, not a card: dorm network is optional and must
                // read as secondary to the two account cards above it. Miuix's TextButton paints a
                // filled container, which made this look like a third card, so the tap target is a
                // bare clickable text instead.
                Text(
                    AppText.Auth.ENTRY_DORM_LINK,
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AppUiTokens.FieldRadius))
                        .clickable(onClick = { dispatch(AuthLoginUiEvent.CHOOSE_DORM) })
                        .padding(vertical = 12.dp)
                        .testTag("entryDorm"),
                )
            }
            if (display.canEnter) item {
                // Only appears once something is actually usable, so the chooser never offers a way
                // into an app that would have nothing to show.
                PrimaryAction(
                    text = AppText.Auth.ENTER_APP,
                    modifier = Modifier.fillMaxWidth().padding(top = AppUiTokens.SectionGap).testTag("enterApp"),
                    onClick = { dispatch(AuthLoginUiEvent.ENTER_APP) },
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton(AppText.App.USER_AGREEMENT_DISPLAY_LABEL, onClick = { dispatch(AuthLoginUiEvent.USER_AGREEMENT) })
                    TextButton(AppText.App.PRIVACY_POLICY_DISPLAY_LABEL, onClick = { dispatch(AuthLoginUiEvent.PRIVACY_POLICY) })
                }
            }
        }
    }
}

/** The entry card row: accent icon square, title/subtitle, and the bound dot or chevron. */
@Composable
private fun LoginEntryCard(
    title: String,
    subtitle: String,
    accent: Color,
    icon: ImageVector,
    testTag: String,
    bound: Boolean,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().height(72.dp).testTag(testTag),
        cornerRadius = AppUiTokens.CardRadius,
        insideMargin = PaddingValues(AppUiTokens.CardPadding),
        onClick = onClick,
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(accent), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (bound) subtitle + AppText.Auth.ENTRY_BOUND_SUFFIX else subtitle,
                    fontSize = 13.sp,
                    color = if (bound) AppUiTokens.Positive else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (bound) {
                // A filled dot replaces the chevron so the bound state reads at a glance, not only
                // through the subtitle text.
                Box(Modifier.size(10.dp).clip(CircleShape).background(AppUiTokens.Positive))
            } else {
                Icon(BackChevron, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.size(20.dp).rotate(180f))
            }
        }
    }
}
