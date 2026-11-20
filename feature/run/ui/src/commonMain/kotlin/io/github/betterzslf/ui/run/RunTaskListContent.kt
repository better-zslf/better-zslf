package io.github.betterzslf.ui.run

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.runui.RunDisplay
import io.github.betterzslf.feature.runui.RunTaskFilter
import io.github.betterzslf.ui.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Complete task-list layout. It receives display projections and finite intents, never read capabilities. */
@Composable
fun RunTaskListContent(
    state: RunTaskListDisplay,
    controls: RunListControls,
    list: LazyListState,
    onEvent: (RunListUiEvent) -> Unit,
    onFilter: (RunTaskFilter) -> Unit,
    onRowAction: (localKey: String, action: RunTaskRowAction) -> Unit,
    onContribution: (localKey: String) -> Unit,
) {
    val dispatch: (RunListUiEvent) -> Unit = { if (controls.allows(it)) onEvent(it) }
    Screen(AppText.Navigation.CAMPUS_RUN,
        onBack = if (controls.canGoBack) ({ dispatch(RunListUiEvent.BACK) }) else null,
        actions = { TextButton(AppText.Run.LOCAL_RECORD_TITLE, onClick = { dispatch(RunListUiEvent.LOCAL_TRACKER) }) }) { padding ->
        val contentPadding = pageContentPadding(padding, LocalNavigationBottomInset.current, LocalLayoutDirection.current)
        PullToRefresh(state.loading, { dispatch(RunListUiEvent.REFRESH) },
            modifier = Modifier.fillMaxSize().consumeWindowInsets(padding), contentPadding = padding,
            refreshTexts = appRefreshTexts()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                item("filters") {
                    val filters = listOf(RunTaskFilter.ALL, RunTaskFilter.UNFINISHED, RunTaskFilter.FINISHED)
                    TabRow(tabs = listOf(AppText.Common.ALL, AppText.Common.INCOMPLETE, AppText.Common.COMPLETED),
                        selectedTabIndex = filters.indexOf(state.filter).coerceAtLeast(0),
                        onTabSelected = { index -> if (!state.loading) filters.getOrNull(index)?.let(onFilter) },
                        cornerRadius = AppUiTokens.ButtonRadius, height = 44.dp)
                }
                if (!state.signedIn) item("login") { RunEmpty(AppText.Auth.LOGIN_REQUIRED, AppText.Run.CURRENT_ACCOUNT_TASKS_ONLY_HINT) }
                if (state.loading) item("loading") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                }
                state.error?.let { error -> item("error") { RunCard {
                    InfoBanner(error, AppText.Run.TASK_LOAD_FAILED, UiTone.Warning)
                    TextButton(AppText.Common.REFRESH, onClick = { dispatch(RunListUiEvent.REFRESH) })
                } } }
                if (!state.loading && state.error == null && state.rows.isEmpty() && state.signedIn) item("empty") {
                    RunEmpty(AppText.Run.NO_RUN_TASKS, AppText.Run.NO_SERVER_TASKS_LOCAL_TOOLS_AVAILABLE_HINT)
                }
                items(state.rows, key = { it.localKey }) { row ->
                    AppCard(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                        insideMargin = PaddingValues(AppUiTokens.CardPadding),
                        onClick = if (row.canOpen) ({ onRowAction(row.localKey, RunTaskRowAction.DETAIL) }) else null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                            Text(row.title, modifier = Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            when (row.finish) {
                                "1" -> RunStatus(AppText.Common.COMPLETED, UiTone.Positive)
                                "0" -> RunStatus(AppText.Common.INCOMPLETE, UiTone.Info)
                                else -> RunStatus(row.finish ?: AppText.Common.UNKNOWN)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(listOfNotNull("${AppText.Run.DISTANCE_REQUIREMENT_TITLE} · ${RunDisplay.distance(row.minimumDistanceMeters, state.metricUnits)}",
                            row.endText?.let(AppText.Run::endsAt)).joinToString(" · "),
                            fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        if (!row.canOpen) InfoBanner(AppText.Run.MISSING_TASK_OR_SCHEDULE_READ_ONLY, tone = UiTone.Warning)
                        Spacer(Modifier.height(AppUiTokens.Gap))
                        Row(horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                            RunAction(AppText.Run.SCHEDULE_SCORES_AND_HISTORY, enabled = row.canOpen, modifier = Modifier.weight(1f),
                                onClick = { onRowAction(row.localKey, RunTaskRowAction.RECORDS) })
                            RunAction(AppText.Run.START_REAL_RECORDING, enabled = row.canOpen, primary = true, modifier = Modifier.weight(1f),
                                onClick = { onRowAction(row.localKey, RunTaskRowAction.RECORD_REAL) })
                        }
                    }
                }
                state.moreError?.let { error -> item("more-error") {
                    SectionCard(AppText.Run.NEXT_TASK_PAGE_FAILED_PREVIOUS_RETAINED, error, { dispatch(RunListUiEvent.MORE) })
                } }
                if (!state.end && state.rows.isNotEmpty()) item("more") {
                    TextButton(AppText.Common.LOAD_NEXT_PAGE, enabled = controls.canMore, onClick = { dispatch(RunListUiEvent.MORE) })
                }
                if (state.end && state.rows.isNotEmpty()) item("end") {
                    Text(AppText.Run.tasksPaginationEnd(state.rows.size), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                items(state.contributions, key = { "contribution:${it.localKey}" }) { action ->
                    TextButton(action.title, onClick = { onContribution(action.localKey) }, modifier = Modifier.fillMaxWidth())
                }
                runWarnings(state.warnings)
            }
        }
    }
}
