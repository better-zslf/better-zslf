package io.github.betterzslf.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import io.github.betterzslf.AppGraph
import io.github.betterzslf.Route
import io.github.betterzslf.feature.home.presentation.HomeAction
import io.github.betterzslf.feature.home.ui.HomeContent
import io.github.betterzslf.ui.schedule.ScheduleDetailSelection
import io.github.betterzslf.ui.schedule.ScheduleRoutes
import io.github.betterzslf.ui.schedule.scheduleDisplayClock
import io.github.betterzslf.ui.schedule.scheduleRoute
import kotlinx.coroutines.isActive
import kotlin.time.Clock

/** Product binder only. All home projection and rendering live in the reusable home modules. */
@Composable
fun HomePage(graph: AppGraph, open: (Route) -> Unit) {
    val state by graph.schedule.state.collectAsState()
    val now = scheduleDisplayClock()
    val revision = graph.schedule.ownerRevision
    val adapter = remember(graph, state, now, revision) { HomeScheduleAdapter(state, revision, now) }
    DisposableEffect(adapter) { onDispose { adapter.close() } }
    HomeContent(adapter.uiState) { event ->
        if (!graph.applicationScope.isActive || graph.clearingLocalData) return@HomeContent
        val target = adapter.resolve(event, graph.schedule.state.value, graph.schedule.ownerRevision,
            Clock.System.now().toEpochMilliseconds()) ?: return@HomeContent
        val route = when (target) {
            is HomeNavigation.Detail -> scheduleRoute(ScheduleRoutes.DETAIL,
                ScheduleDetailSelection(target.termId, listOf(target.courseId), target.week).encode())
            is HomeNavigation.Action -> scheduleRoute(when (target.action) {
                HomeAction.OPEN_TIMETABLE -> ScheduleRoutes.ROOT
                HomeAction.BIND_SCHEDULE -> ScheduleRoutes.BIND
                HomeAction.OPEN_TERMS -> ScheduleRoutes.TERMS
            })
        }
        open(route)
    }
}
