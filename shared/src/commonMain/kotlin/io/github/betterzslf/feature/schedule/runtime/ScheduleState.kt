package io.github.betterzslf.feature.schedule.runtime

import io.github.betterzslf.feature.schedule.SchedulePeriod
import io.github.betterzslf.feature.schedule.ScheduleSnapshot
import io.github.betterzslf.feature.schedule.ScheduleTerm
import io.github.betterzslf.feature.schedule.defaultSchedulePeriods

/** Presentation state deliberately excludes passwords, cookies and raw server responses. */
data class ScheduleState(
    val owner: String? = null,
    val boundAccount: String? = null,
    val verifiedAccount: String? = null,
    val verifying: Boolean = false,
    val busy: Boolean = false,
    val reauthenticating: Boolean = false,
    val snapshot: ScheduleSnapshot? = null,
    val sourceSnapshot: ScheduleSnapshot? = null,
    val terms: List<ScheduleTerm> = emptyList(),
    val selectedWeek: Int = 1,
    val message: String? = null,
    val needsCredentials: Boolean = false,
    val storageFailure: Boolean = false,
    val showBindingPrompt: Boolean = false,
    val remindersEnabled: Boolean = false,
    val reminderLeadMinutes: Int = 10,
    /** See StoredSchedule.capsuleLongText. */
    val capsuleLongText: Boolean = false,
    val periods: List<SchedulePeriod> = defaultSchedulePeriods(),
    val sourceTermId: String? = null,
    val pendingReminderCourses: Int = 0,
)
