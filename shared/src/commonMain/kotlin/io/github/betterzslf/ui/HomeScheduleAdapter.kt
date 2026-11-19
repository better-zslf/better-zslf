package io.github.betterzslf.ui

import io.github.betterzslf.feature.home.presentation.HomeAction
import io.github.betterzslf.feature.home.presentation.HomeEvent
import io.github.betterzslf.feature.home.presentation.HomeInput
import io.github.betterzslf.feature.home.presentation.HomeUiState
import io.github.betterzslf.feature.home.presentation.homeUiState
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.runtime.ScheduleState
import io.github.betterzslf.feature.schedule.scheduleOccurrences

/** Product-local typed targets: the reusable content never sees route encoding or a term selection. */
internal sealed interface HomeNavigation {
    data class Detail(val termId: String, val courseId: String, val week: Int) : HomeNavigation {
        override fun toString(): String = "HomeNavigation.Detail(redacted)"
    }
    data class Action(val action: HomeAction) : HomeNavigation
}

/**
 * Synchronous adapter for one rendered snapshot. Replacing a term/binding/owner retires every
 * old click; stable occurrence ids are resolved only through this exact captured term's index.
 */
internal class HomeScheduleAdapter(state: ScheduleState, private val ownerRevision: String, nowMillis: Long) {
    private val owner = state.owner
    private val boundAccount = state.boundAccount
    private val selectedWeek = state.selectedWeek
    private val snapshotIdentity = state.snapshot
    private val snapshot = state.snapshot?.navigationCopy()
    private val periods = state.periods.toList()
    private var mounted = true
    val uiState: HomeUiState = homeUiState(HomeInput(snapshot, periods, selectedWeek, nowMillis, boundAccount != null))
    private val index = snapshot?.takeIf { it.term.id.isNotBlank() }?.let { captured ->
        scheduleOccurrences(captured, periods, nowMillis - 86_400_000L, nowMillis + 14 * 86_400_000L)
            .groupBy { it.id }.filterValues { it.size == 1 }.mapValues { (_, occurrences) ->
                val occurrence = occurrences.single()
                HomeNavigation.Detail(captured.term.id, occurrence.courseId, occurrence.week)
            }
    }.orEmpty()

    fun resolve(event: HomeEvent, current: ScheduleState, currentRevision: String, nowMillis: Long): HomeNavigation? {
        if (!mounted || currentRevision != ownerRevision || current.owner != owner ||
            current.boundAccount != boundAccount || current.selectedWeek != selectedWeek ||
            current.snapshot !== snapshotIdentity || current.snapshot != snapshot || current.periods != periods ||
            !uiState.accepts(event)) return null
        val currentUi = homeUiState(HomeInput(current.snapshot, current.periods, current.selectedWeek,
            nowMillis, current.boundAccount != null))
        if (!currentUi.accepts(event)) return null
        return when (event) {
            is HomeEvent.ActionSelected -> HomeNavigation.Action(event.action)
            is HomeEvent.OpenOccurrence -> {
                if (owner == null || boundAccount == null) return null
                val target = index[event.id.value] ?: return null
                val live = current.snapshot ?: return null
                if (live.term.id != target.termId) return null
                val occurrence = scheduleOccurrences(live, current.periods,
                    nowMillis - 86_400_000L, nowMillis + 14 * 86_400_000L)
                    .singleOrNull { it.id == event.id.value } ?: return null
                target.takeIf { occurrence.courseId == it.courseId && occurrence.week == it.week }
            }
        }
    }

    fun close() { mounted = false }
    override fun toString(): String = "HomeScheduleAdapter(redacted)"
}

/** Private backing copy used only to detect in-place mutation and to freeze the navigation index. */
private fun ScheduleSnapshot.navigationCopy(): ScheduleSnapshot = copy(
    courses = courses.map { it.copy(periods = it.periods.toList(), weeks = it.weeks.toList()) },
    weekMondays = weekMondays.toMap(), remarks = remarks.toList(), warnings = warnings.toList(),
)
