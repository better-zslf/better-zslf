package io.github.betterzslf.feature.schedule.runtime

/** Passive authority for one runtime epoch; neither a stored revision nor a credential container. */
class ScheduleBindingLease internal constructor(
    internal val runtime: ScheduleRuntime,
    internal val epoch: Long,
    internal val generation: Long,
    internal val owner: String,
    internal val revision: String,
) {
    override fun toString(): String = "ScheduleBindingLease(<redacted>)"
}

/** Returned synchronously before authentication, so its owner can cancel an in-flight attempt. */
class ScheduleVerificationAttempt internal constructor(
    internal val runtime: ScheduleRuntime,
    internal val epoch: Long,
    internal val generation: Long,
    internal val inputGeneration: Long,
    internal val owner: String,
) {
    internal var phase = ScheduleVerificationPhase.CREATED
    override fun toString(): String = "ScheduleVerificationAttempt(<redacted>)"
}

internal enum class ScheduleVerificationPhase { CREATED, VERIFYING, VERIFIED, FAILED, CONFIRMING, COMMITTED, RETIRED }

/** Fixed at the successful local commit, before refresh; usable for at most one continuation. */
class ScheduleBindingCommit internal constructor(
    internal val runtime: ScheduleRuntime,
    internal val epoch: Long,
    internal val generation: Long,
    internal val owner: String,
    internal val revision: String,
) {
    internal var consumed = false
    override fun toString(): String = "ScheduleBindingCommit(<redacted>)"
}
