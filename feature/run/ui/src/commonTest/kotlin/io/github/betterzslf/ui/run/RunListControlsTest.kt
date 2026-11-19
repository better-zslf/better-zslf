package io.github.betterzslf.ui.run

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunListControlsTest {
    @Test fun busyAndEndGatesDoNotGrantHiddenPaginationOrRefreshActions() {
        val disabled = RunListControls(canGoBack = false, canRefresh = false, canMore = false)
        assertFalse(disabled.allows(RunListUiEvent.BACK))
        assertFalse(disabled.allows(RunListUiEvent.REFRESH))
        assertFalse(disabled.allows(RunListUiEvent.MORE))
        assertTrue(disabled.allows(RunListUiEvent.LOCAL_TRACKER))
        assertTrue(disabled.allows(RunListUiEvent.SELECT_TASKS))
        val enabled = RunListControls(true, true, true)
        assertTrue(RunListUiEvent.entries.all(enabled::allows))
    }

    @Test fun taskActionsAreFiniteAndRealRecordingIsOnlyAnIntent() {
        assertEquals(listOf("DETAIL", "RECORDS", "RECORD_REAL"), RunTaskRowAction.entries.map { it.name })
        assertEquals(listOf("BACK", "REFRESH", "MORE", "LOCAL_TRACKER", "SELECT_TASKS"), RunListUiEvent.entries.map { it.name })
    }
}
