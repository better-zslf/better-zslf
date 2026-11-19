package io.github.betterzslf.ui.run

import io.github.betterzslf.feature.runui.RunTaskFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunListDisplayTest {
    @Test fun taskAndRecordRowsCarryOnlyDisplayValuesAndRedactedDiagnostics() {
        val task = RunTaskRowDisplay("task-row:1", "Fixture task", 0.0, "ends", "0", true)
        val record = RunRecordRowDisplay("record-row:1", "Fixture record", 1000.0, 300_000L, "pending", true)
        assertEquals(0.0, task.minimumDistanceMeters)
        assertEquals("ends", task.endText)
        assertEquals("0", task.finish)
        assertEquals("RunTaskRowDisplay(redacted)", task.toString())
        assertEquals("RunRecordRowDisplay(redacted)", record.toString())
        assertFalse(task.toString().contains(task.localKey))
        assertFalse(record.toString().contains(record.localKey))
        assertEquals("RunListContributionDisplay(redacted)", RunListContributionDisplay("contribution:1", "Preview").toString())
    }

    @Test fun missingSelectionAndSignedOutAreExplicitRatherThanSuccessfulEmptyReads() {
        val tasks = RunTaskListDisplay()
        val records = RunRecordListDisplay()
        assertFalse(tasks.signedIn)
        assertEquals(RunTaskFilter.ALL, tasks.filter)
        assertFalse(records.hasSelection)
        assertTrue(tasks.rows.isEmpty()); assertTrue(records.rows.isEmpty())
        assertNull(tasks.error); assertNull(records.error)
        assertNull(records.minimumDistanceMeters)
    }

    @Test fun refreshAndAppendFailuresKeepRowsWarningsAndSummaryIndependent() {
        val row = RunRecordRowDisplay("record-row:1", "Fixture", null, null, null, false)
        val display = RunRecordListDisplay(listOf(row), hasSelection = true, minimumDistanceMeters = 0.0,
            error = "Refresh failed", moreError = "Append failed", warnings = listOf("Warning"), planError = "Plan failed")
        assertEquals(listOf(row), display.rows)
        assertEquals(0.0, display.minimumDistanceMeters)
        assertEquals("Refresh failed", display.error)
        assertEquals("Append failed", display.moreError)
        assertEquals("Plan failed", display.planError)
        assertEquals(listOf("Warning"), display.warnings)
        assertFalse(display.rows.single().canOpen)
    }

    @Test fun recordPaceUsesMetresAndMillisecondsWithoutChangingInputs() {
        val row = RunRecordRowDisplay("record-row:1", "Fixture", 1000.0, 300_000L, "pending", true)
        assertEquals("5:00 /公里", runRecordDisplayPace(row, true))
        assertEquals("8:02 /英里", runRecordDisplayPace(row, false))
        assertEquals("—", runRecordDisplayPace(row.copy(distanceMeters = null), true))
        assertEquals("—", runRecordDisplayPace(row.copy(elapsedMillis = null), true))
        assertEquals(1000.0, row.distanceMeters)
        assertEquals(300_000L, row.elapsedMillis)
    }
}
