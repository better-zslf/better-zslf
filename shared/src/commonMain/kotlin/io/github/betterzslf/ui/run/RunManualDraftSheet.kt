package io.github.betterzslf.ui.run

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import io.github.betterzslf.ui.AppUiTokens
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.UiTone
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.runui.RunManualDraft
import io.github.betterzslf.feature.runui.RunManualDraftCommit
import io.github.betterzslf.feature.runui.RunManualDraftError
import io.github.betterzslf.platform.RunMapCoordinateSystem
import io.github.betterzslf.platform.RunMapPanel
import io.github.betterzslf.platform.runMapFailureMessage
import kotlinx.coroutines.flow.collect
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/** Own editor, not the read-only RunMapPreview. The root Scaffold supplies its overlay host. */
@Composable
internal fun RunManualDraftSheet(
    session: RunManualDraftSession,
    availableHeight: Dp,
    dark: Boolean,
    onCancel: () -> Unit,
    onCommit: (RunManualDraftCommit) -> Unit,
) {
    val state by session.state.collectAsState()
    val show = state.phase !in setOf(RunManualDraftPhase.CLOSED, RunManualDraftPhase.STALE, RunManualDraftPhase.SAVED)
    val cancel: () -> Unit = {
        session.close() // synchronous, before the host starts a hide animation
        onCancel()
    }
    DisposableEffect(session) { onDispose { session.close() } }
    OverlayBottomSheet(
        show = show,
        renderInRootScaffold = true, enableNestedScroll = false, allowDismiss = false,
        onDismissRequest = cancel,
    ) {
        // The built-in sheet dismiss callback runs after its animation. This child owns commit first.
        PredictiveBackHandler(enabled = show, onProgress = { events -> events.collect { } }, onCommit = cancel, onCancel = {})
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val windowSize = LocalWindowInfo.current.containerSize
        val windowWidth = with(density) { windowSize.width.toDp() }
        val windowHeight = if (windowSize.height > 0) with(density) { windowSize.height.toDp() } else availableHeight
        val navigationPadding = WindowInsets.navigationBars.asPaddingValues()
        val safeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val compact = (windowSize.width > 0 && windowWidth < 360.dp) || availableHeight < 480.dp || density.fontScale > 1.15f
        val bodyHeight = if (compact) windowHeight - safeTop - 48.dp else availableHeight * 0.75f
        val height = bodyHeight.coerceAtMost(560.dp).coerceAtLeast(0.dp)
        // Raw safe navigation space belongs inside this root popup's fixed height; IME stays library-owned.
        Column(Modifier.fillMaxWidth().height(height).padding(
            start = navigationPadding.calculateStartPadding(layoutDirection),
            end = navigationPadding.calculateEndPadding(layoutDirection),
            bottom = navigationPadding.calculateBottomPadding()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Keep title and actions on separate rows; the library's three header slots overlap at narrow widths.
            Text(AppText.ManualRoute.TITLE, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AppUiTokens.Gap)) {
                RunAction(AppText.ManualRoute.CANCEL, onClick = cancel, modifier = Modifier.weight(1f))
                RunAction(AppText.ManualRoute.SAVE_PREVIEW, enabled = state.canSave, primary = true,
                    onClick = { session.takeCommit()?.let(onCommit) }, modifier = Modifier.weight(1f))
            }
            // Allocate only the measured remainder after the sticky title/actions, never a guessed control budget.
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // One stable mount/callsite across window, font and inset changes, including an empty draft.
                key(session.mount) {
                    val identity = session.identity
                    val mount = session.mount
                    RunMapPanel(session.mapModel(dark), { event -> session.onEvent(identity, mount, event) },
                        Modifier.fillMaxWidth().weight(0.6f).clip(RoundedCornerShape(AppUiTokens.CardRadius)))
                }
                Column(Modifier.fillMaxWidth().weight(0.4f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(AppText.ManualRoute.UNDO, enabled = state.canUndo, onClick = { session.undo() }, modifier = Modifier.weight(1f))
                        TextButton(AppText.ManualRoute.CLEAR, enabled = state.canUndo, onClick = { session.clear() }, modifier = Modifier.weight(1f))
                    }
                    val frameLabel = when (session.identity.frame) {
                        RunMapCoordinateSystem.GCJ02 -> AppText.ManualRoute.GCJ02_LABEL
                        RunMapCoordinateSystem.WGS84 -> AppText.ManualRoute.WGS84_LABEL
                        RunMapCoordinateSystem.UNKNOWN -> AppText.Common.UNKNOWN
                    }
                    RunStatus(AppText.ManualRoute.draftSummary(state.pointCount, frameLabel), UiTone.Info)
                    Text(manualDraftMessage(state), fontSize = 13.sp)
                    InfoBanner(AppText.ManualRoute.LOCAL_ONLY_WARNING, tone = UiTone.Neutral)
                    if (state.pointCount == 0) Text(AppText.ManualRoute.DEFAULT_VIEW_HINT, fontSize = 12.sp)
                    else if (state.pointCount == 1) Text(AppText.ManualRoute.FIRST_POINT_HINT, fontSize = 12.sp)
                    Text(AppText.ManualRoute.pointBudget(state.pointCount, RunManualDraft.MAX_POINTS), fontSize = 12.sp)
                }
            }
        }
    }
}

internal fun manualDraftMessage(state: RunManualDraftState): String = when (state.phase) {
    RunManualDraftPhase.SAVING -> AppText.ManualRoute.SAVING_PREVIEW
    RunManualDraftPhase.SAVE_FAILED -> AppText.ManualRoute.SAVE_FAILED_REOPEN
    RunManualDraftPhase.STALE -> AppText.ManualRoute.SOURCE_CHANGED
    RunManualDraftPhase.CLOSED, RunManualDraftPhase.SAVED -> ""
    RunManualDraftPhase.EDITING -> when {
        state.mapStatus == RunManualMapStatus.FAILED -> state.failure?.let(::runMapFailureMessage) ?: AppText.ManualRoute.INVALID_DRAFT
        state.editError != null -> when (state.editError) {
            RunManualDraftError.POINT_LIMIT_REACHED -> AppText.ManualRoute.POINT_LIMIT
            RunManualDraftError.ADJACENT_DUPLICATE -> AppText.ManualRoute.DUPLICATE_POINT
            RunManualDraftError.EMPTY_DRAFT -> AppText.ManualRoute.EMPTY_DRAFT
            RunManualDraftError.TOO_FEW_POINTS -> AppText.ManualRoute.AT_LEAST_TWO_POINTS_REQUIRED
            else -> AppText.ManualRoute.INVALID_POINT
        }
        state.mapStatus == RunManualMapStatus.READY -> AppText.ManualRoute.MAP_READY
        else -> AppText.ManualRoute.WAITING_FOR_MAP
    }
}
