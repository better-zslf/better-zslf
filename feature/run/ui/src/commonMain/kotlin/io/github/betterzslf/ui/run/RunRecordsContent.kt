package io.github.betterzslf.ui.run

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
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
import io.github.betterzslf.ui.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** The full schedule-record list, including its missing-selection page. No runtime or query identity is supplied. */
@Composable
fun RunRecordsContent(
    state: RunRecordListDisplay,
    controls: RunListControls,
    list: LazyListState,
    onEvent: (RunListUiEvent) -> Unit,
    onOpenRow: (localKey: String) -> Unit,
) {
    val dispatch: (RunListUiEvent) -> Unit = { if (controls.allows(it)) onEvent(it) }
    Screen(AppText.Run.SCHEDULE_RUN_RECORDS_TITLE,
        onBack = if (controls.canGoBack) ({ dispatch(RunListUiEvent.BACK) }) else null) { padding ->
        if (!state.hasSelection) {
            Box(Modifier.fillMaxSize().padding(padding).padding(AppUiTokens.PagePadding)) {
                RunEmpty(AppText.Run.SELECT_TASK_SCHEDULE_FIRST, AppText.Run.SCHEDULE_PAGINATION_SCOPE_HINT,
                    AppText.Run.SELECT_FROM_MY_TASKS, { dispatch(RunListUiEvent.SELECT_TASKS) })
            }
        } else {
            val listPadding = pageContentPadding(padding, LocalNavigationBottomInset.current, LocalLayoutDirection.current)
            PullToRefresh(state.loading, { dispatch(RunListUiEvent.REFRESH) }, modifier = Modifier.fillMaxSize().consumeWindowInsets(padding),
                contentPadding = padding, refreshTexts = appRefreshTexts()) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item("summary") { MetricStrip(listOf(
                        UiMetric(state.rows.size.toString(), AppText.Run.LOADED_RECORDS_LABEL),
                        UiMetric(RunDisplay.distance(state.minimumDistanceMeters, state.metricUnits), AppText.Run.SCHEDULE_REQUIREMENT_LABEL),
                        UiMetric("—", AppText.Run.OTHER_SUMMARY_PENDING_LABEL),
                    )) }
                    if (state.loading) item("loading") { CircularProgressIndicator() }
                    state.error?.let { error -> item("error") {
                        SectionCard(AppText.Run.RECORD_LOAD_FAILED_CONTENT_RETAINED, error, { dispatch(RunListUiEvent.REFRESH) })
                    } }
                    if (!state.loading && state.error == null && state.rows.isEmpty()) item("empty") {
                        RunEmpty(AppText.Run.NO_RECORDS_FOR_SCHEDULE, AppText.Run.SERVER_RECORD_LIST_EMPTY)
                    }
                    items(state.rows, key = { it.localKey }) { row ->
                        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = AppUiTokens.CardRadius,
                            insideMargin = PaddingValues(AppUiTokens.CardPadding),
                            onClick = if (row.canOpen) ({ onOpenRow(row.localKey) }) else null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(row.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    Text(listOf(RunDisplay.distance(row.distanceMeters, state.metricUnits), RunDisplay.duration(row.elapsedMillis),
                                        runRecordDisplayPace(row, state.metricUnits)).joinToString(" · "),
                                        fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
                                RunStatus(row.status ?: "—")
                            }
                            if (!row.canOpen) Text(AppText.Run.MISSING_RECORD_ID_READ_ONLY_NEWLINE_SUFFIX, fontSize = 12.sp)
                        }
                    }
                    state.moreError?.let { error -> item("more-error") {
                        SectionCard(AppText.Run.NEXT_RECORD_PAGE_FAILED_PREVIOUS_RETAINED, error, { dispatch(RunListUiEvent.MORE) })
                    } }
                    if (!state.end && state.rows.isNotEmpty()) item("more") {
                        TextButton(AppText.Common.LOAD_NEXT_PAGE, enabled = controls.canMore, onClick = { dispatch(RunListUiEvent.MORE) })
                    }
                    if (state.end && state.rows.isNotEmpty()) item("end") {
                        Text(AppText.Run.recordsPaginationEnd(state.rows.size), fontSize = 12.sp)
                    }
                    runWarnings(state.warnings)
                    state.planError?.let { error -> item("plan-feedback") { InfoBanner(error, tone = UiTone.Warning) } }
                }
            }
        }
    }
}

internal fun runRecordDisplayPace(row: RunRecordRowDisplay, metric: Boolean): String =
    if (row.distanceMeters != null && row.elapsedMillis != null) RunDisplay.pace(row.distanceMeters, row.elapsedMillis, metric) else "—"
