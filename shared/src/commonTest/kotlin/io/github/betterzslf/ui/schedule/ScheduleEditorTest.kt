package io.github.betterzslf.ui.schedule

import io.github.betterzslf.feature.schedule.defaultSchedulePeriods
import kotlin.test.*

class ScheduleEditorTest {
    private fun credentials() = ScheduleBindingDraft(account = "fixture-account", password = "fixture-secret")

    @Test fun verificationDoesNotMeanSavedOrConfirmedUntilItsMatchingServerResult() {
        val input = credentials()
        assertFalse(input.canConfirm("fixture-account", false))
        val verified = input.acceptVerification(input.revision, "fixture-account")
        assertTrue(verified.canConfirm("fixture-account", false))
        assertFalse(verified.canConfirm(null, false))
        assertFalse(verified.canConfirm("different-account", false))
        assertFalse(verified.canConfirm("fixture-account", true))
    }
    @Test fun changingEitherInputImmediatelyInvalidatesTheOldValidation() {
        val verified = credentials().acceptVerification(0, "fixture-account")
        assertFalse(verified.accountChanged("fixture-other").canConfirm("fixture-account", false))
        assertFalse(verified.passwordChanged("different-secret").canConfirm("fixture-account", false))
        assertNull(verified.passwordChanged("different-secret").verifiedRevision)
    }
    @Test fun lateSuccessCannotRevalidateChangedInputEvenWhenAccountTextMatches() {
        val before = credentials()
        val changed = before.passwordChanged("replacement-secret")
        assertEquals(changed, changed.acceptVerification(before.revision, "fixture-account"))
        assertFalse(changed.acceptVerification(before.revision, "fixture-account").canConfirm("fixture-account", false))
    }
    @Test fun changingAnAccountAwayAndBackStillRequiresANewTest() {
        val before = credentials()
        val changed = before.accountChanged("other-fixture").accountChanged(before.account)
        assertEquals(2L, changed.revision)
        assertFalse(changed.acceptVerification(before.revision, before.account).canConfirm(before.account, false))
    }
    @Test fun wrongServerIdentityDoesNotValidateTheDraft() {
        val input = credentials()
        assertNull(input.acceptVerification(input.revision, "wrong-fixture").verifiedRevision)
    }
    @Test fun verificationFailureInvalidatesOnlyTheDraftNotTheInputValues() {
        val verified = credentials().acceptVerification(0, "fixture-account")
        val failed = verified.invalidate()
        assertEquals(verified.account, failed.account)
        assertEquals(verified.password, failed.password)
        assertFalse(failed.canConfirm(verified.account, false))
    }
    @Test fun passwordWhitespaceIsPreservedAndNeverAppearsInDiagnosticStrings() {
        val input = credentials().passwordChanged(" fixture-secret ")
        assertEquals(" fixture-secret ", input.password)
        assertFalse(input.toString().contains(input.password))
        assertFalse(input.toString().contains(input.account))
        assertFalse(credentials().passwordChanged("").acceptVerification(1, "fixture-account").canConfirm("fixture-account", false))
    }
    @Test fun defaultPeriodsNeedAnExplicitPreviewBeforeApplying() {
        val effective = defaultSchedulePeriods()
        val draft = SchedulePeriodEdit(effective)
        assertEquals(12, draft.periods.size)
        assertEquals("08:00", draft.periods.first().start)
        assertEquals("22:10", draft.periods.last().end)
        assertFalse(draft.canApply(effective))
        assertTrue(draft.preview(effective).canApply(effective))
    }
    @Test fun changingAnyPeriodInvalidatesTheOldPreview() {
        val effective = defaultSchedulePeriods()
        val preview = SchedulePeriodEdit(effective).preview(effective)
        val changed = preview.change(0, start = "08:05")
        assertNull(changed.previewRevision)
        assertFalse(changed.canApply(effective))
        assertTrue(changed.preview(effective).canApply(effective))
    }
    @Test fun changingEffectivePeriodsInvalidatesAnOpenOldConfirmation() {
        val effective = defaultSchedulePeriods()
        val preview = SchedulePeriodEdit(effective).change(0, start = "08:05").preview(effective)
        val externallyChanged = effective.mapIndexed { index, period -> if (index == 0) period.copy(start = "08:01") else period }
        assertFalse(preview.canApply(externallyChanged))
        assertTrue(preview.preview(externallyChanged).canApply(externallyChanged))
    }
    @Test fun invalidClockOrderOverlapOrMissingRowsNeverProduceAnApplicablePreview() {
        val effective = defaultSchedulePeriods()
        val invalid = listOf(
            effective.dropLast(1),
            effective.mapIndexed { index, period -> if (index == 0) period.copy(start = "8:00") else period },
            effective.mapIndexed { index, period -> if (index == 0) period.copy(start = "24:00") else period },
            effective.mapIndexed { index, period -> if (index == 0) period.copy(start = "08:45") else period },
            effective.mapIndexed { index, period -> if (index == 1) period.copy(start = "08:40") else period },
            effective.mapIndexed { index, period -> if (index == 1) period.copy(number = 1) else period },
        )
        for (candidate in invalid) {
            assertNull(SchedulePeriodEdit(candidate).preview(effective).previewRevision)
            assertFalse(SchedulePeriodEdit(candidate).preview(effective).canApply(effective))
        }
    }
    @Test fun clockErrorsAreLocatedAtTheVisibleInputRow() {
        val effective = defaultSchedulePeriods()
        assertTrue(schedulePeriodIssueRows(effective).isEmpty())
        val malformed = effective.mapIndexed { index, period -> if (index == 11) period.copy(end = "invalid") else period }
        assertEquals(setOf(11), schedulePeriodIssueRows(malformed))
    }
    @Test fun anOverlapMarksBothNeighbouringRowsWithoutReplacingTheDomainGate() {
        val effective = defaultSchedulePeriods()
        val overlap = effective.mapIndexed { index, period -> if (index == 1) period.copy(start = "08:40") else period }
        assertEquals(setOf(0, 1), schedulePeriodIssueRows(overlap))
        assertFalse(SchedulePeriodEdit(overlap).preview(effective).canApply(effective))
    }
    @Test fun draftChangesAndAPreviewNeverOptimisticallyMutateTheEffectiveSettings() {
        val effective = defaultSchedulePeriods()
        val candidate = SchedulePeriodEdit(effective).change(0, start = "08:05").preview(effective)
        assertEquals("08:00", effective.first().start)
        assertEquals("08:05", candidate.periods.first().start)
        assertEquals(effective, candidate.baseline)
        assertNotSame(effective, candidate.baseline)
    }
}
