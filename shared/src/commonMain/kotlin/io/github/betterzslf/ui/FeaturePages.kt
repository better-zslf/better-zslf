package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.ui.content.ContentPage
import io.github.betterzslf.edition.BuildEdition
import io.github.betterzslf.edition.EditionAccess
import io.github.betterzslf.edition.EditionUnavailablePage
import io.github.betterzslf.ui.run.RunFeaturePage
import io.github.betterzslf.ui.run.RunTaskList
import io.github.betterzslf.ui.campusnet.CampusNetworkFeaturePage
import io.github.betterzslf.ui.update.AppUpdatePage
import androidx.compose.foundation.clickable
import io.ktor.http.encodeURLParameter
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference

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

@Composable
fun FeaturePage(graph: AppGraph, page: Route.Page, onOpen: (Route) -> Unit, onBack: () -> Unit) {
    if (!EditionAccess.allowsPage(page.kind)) {
        EditionUnavailablePage(onBack)
        return
    }
    when (page.kind) {
        "schedule", "schedulebind", "scheduleterms", "scheduledetail", "schedulemanage", "schedulereminders", "schedulewidget", "scheduleperiods" -> io.github.betterzslf.ui.schedule.SchedulePage(graph, page, onOpen, onBack)
        "settings" -> SettingsScreen(graph, onOpen, onBack)
        "password" -> PasswordScreen(graph, onBack)
        "resetpassword" -> io.github.betterzslf.ui.accountrecovery.AccountRecoveryPage(graph, onBack)
        "document" -> ContentPage(graph, page, onOpen, onBack)
        "runs", "rundetail", "runrecords", "runrecord", "runtracker", "runimport" -> RunFeaturePage(graph, page, onOpen, onBack, graph.runRuntime)
        "water" -> graph.editionFeatures.Page(graph, page, onOpen, onBack)
        "campusnet" -> CampusNetworkFeaturePage(graph, onBack)
        // The dorm sign-in page is reachable from the campus tab as well as from the login chooser,
        // because §5.4 puts a "去登录" action on the campus page.
        "dormnetwork" -> io.github.betterzslf.ui.campusnet.DormNetworkFeaturePage(graph) { onOpen(Route.TabRoot(2)) }
        "feedback" -> FeedbackScreen(graph, onBack)
        "updates" -> AppUpdatePage(graph.platform.versionName, graph.githubUpdates, graph.appUpdater, graph.platform::openUrl, onBack)
        "about" -> AboutScreen(graph, onBack) { onOpen(Route.Page("updates", title = AppText.Updates.VERSION_UPDATE_TITLE)) }
        "devtools" -> DebugSettingsPage(graph, onBack)
        else -> UnknownPage(page, onBack)
    }
}

/** Any route whose module has been retired renders this instead of an empty data list. */
@Composable
private fun UnknownPage(page: Route.Page, back: () -> Unit) {
    Screen(page.title.ifBlank { AppText.Common.DETAILS_FALLBACK_LABEL }, back) { padding ->
        PageList(padding) { item { EmptyCard(AppText.Content.UNCONNECTED_CONTENT_PAGE) } }
    }
}

@Composable
private fun FeedbackScreen(graph: AppGraph, back: () -> Unit) {
    val body = AppText.Content.feedbackBody((graph.platform.versionName), (graph.platform.description))
    Screen(AppText.App.FEEDBACK_TITLE, back) { padding -> PageList(padding) {
        item { AppBrandMark(size = 64.dp, modifier = Modifier.padding(vertical = 12.dp)) }
        item { SectionTitle(AppText.App.FEEDBACK_IMPROVEMENT_TITLE) }
        item { InfoBanner(AppText.App.PUBLIC_FEEDBACK_DATA_SCOPE_HINT) }
        item { PrimaryAction(AppText.App.REPORT_ISSUE, onClick = { graph.platform.openUrl("https://github.com/better-zslf/better-zslf/issues/new?title=${AppText.App.FEEDBACK_ISSUE_TITLE_PREFIX.encodeURLParameter()}&body=${body.encodeURLParameter()}") }) }
        item { TextButton(AppText.App.VIEW_EXISTING_ISSUES, onClick = { graph.platform.openUrl("https://github.com/better-zslf/better-zslf/issues") }, modifier = Modifier.fillMaxWidth()) }
        item { InfoBanner(AppText.App.OPEN_BROWSER_NOT_SUBMITTED_HINT, tone = UiTone.Neutral) }
    } }
}

@Composable
private fun AboutScreen(graph: AppGraph, back: () -> Unit, openUpdates: () -> Unit) {
    val settings by graph.settings.settings.collectAsState()
    var versionTaps by remember { mutableIntStateOf(0) }
    var tapNotice by remember { mutableStateOf("") }
    Screen(AppText.App.ABOUT_APP_TITLE, back) { padding -> PageList(padding) {
        item { Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppBrandMark()
            Text(AppText.App.CAMPUS_LIFE_WELCOME_TITLE, style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.title2)
            Text("Oh My ZSC · ${if (BuildEdition.isPro) "Pro" else "Standard"}", color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary)
            // Five taps on the version string toggle the local developer option; nothing is sent anywhere.
            Text(AppText.Content.aboutVersion(graph.platform.versionName),
                color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary,
                modifier = Modifier.heightIn(min = 48.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(AppUiTokens.FieldRadius))
                    .background(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary.copy(alpha = 0.09f))
                    .clickable(role = Role.Button) {
                    if (!settings.debugMode) {
                        versionTaps++
                        tapNotice = if (versionTaps >= DEVELOPER_TAP_COUNT) "" else AppText.DevTools.tapHint(DEVELOPER_TAP_COUNT - versionTaps)
                        if (versionTaps >= DEVELOPER_TAP_COUNT) {
                            versionTaps = 0
                            graph.settings.update { it.copy(debugMode = true) }
                            tapNotice = AppText.DevTools.ENABLED_NOTICE
                        }
                    } else {
                        tapNotice = AppText.DevTools.ALREADY_ENABLED_HINT
                    }
                }.padding(horizontal = 14.dp, vertical = 8.dp)
                    .wrapContentHeight(androidx.compose.ui.Alignment.CenterVertically))
            if (tapNotice.isNotBlank()) Text(tapNotice, color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary)
        } }
        item { InfoBanner(AppText.App.INDEPENDENT_CLIENT_DESCRIPTION, tone = UiTone.Neutral) }
        item { InfoBanner(if (BuildEdition.isPro) AppText.App.PRIVACY_AND_OFFLINE_WATER_PROTOCOL_HINT else AppText.Edition.STANDARD_PRIVACY_HINT,
            title = AppText.App.DATA_USE_WHEN_NEEDED_TITLE) }
        item { Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius, insideMargin = PaddingValues(0.dp)) {
            ArrowPreference(AppText.Updates.PROJECT_RELEASES_AND_UPDATES_LABEL, summary = AppText.Updates.MANUAL_GITHUB_RELEASES_CHECK_SUMMARY, onClick = openUpdates)
            ArrowPreference(AppText.App.PROJECT_SOURCE_LABEL, onClick = { graph.platform.openUrl("https://github.com/better-zslf/better-zslf") })
        } }
    } }
}

/** Consecutive taps on the version string required before the developer option appears. */
internal const val DEVELOPER_TAP_COUNT: Int = 5

@Composable
fun RunTasksScreen(graph: AppGraph, open: (Route) -> Unit, back: (() -> Unit)? = null) = RunTaskList(graph, open, back)
