package io.github.betterzslf.feature.schedule

import io.github.betterzslf.core.text.AppText
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * The timetable's "live" state for the ongoing notification / Android 16 Live Update.
 *
 * Pure and platform free so the two states, their progress fractions and their rendered copy can
 * be unit tested without a device. Android 16 promotes a ProgressStyle notification that carries
 * an ongoing, user-started journey; older versions fall back to the same content as a plain
 * ongoing notification. Neither path may invent a class the school did not publish.
 */
enum class ScheduleLivePhase { NONE, IN_CLASS, BEFORE_NEXT }

/** One resolved class block on a real date, already normalised by feature.schedule. */
data class ScheduleLiveBlock(
    val id: String,
    val title: String,
    val location: String,
    val startsAtMillis: Long,
    val endsAtMillis: Long,
)

data class ScheduleLiveState(
    val phase: ScheduleLivePhase,
    val title: String,
    val text: String,
    /** 0f..1f progress of the current phase; null means the phase has no bounded progress. */
    val progress: Float?,
    val startsAtMillis: Long = 0,
    val endsAtMillis: Long = 0,
    val nextTitle: String = "",
    val nextLocation: String = "",
    val nextStartsAtMillis: Long = 0,
    /** Compact chip text for the promoted-ongoing view; empty means "no chip". */
    val chip: String = "",
) {
    val active: Boolean get() = phase != ScheduleLivePhase.NONE
}

/**
 * Whole minutes until [deadline], rounded **up**: with 59 seconds left this is 1, not 0.
 * Truncating printed "剩余 0 分钟" while the class was still running, which reads as a broken
 * countdown. Any positive remainder therefore reports at least 1 minute.
 */
fun scheduleLiveMinutes(remainingMillis: Long): Long =
    if (remainingMillis <= 0L) 0L else (remainingMillis - 1L) / 60_000L + 1L

/**
 * Chip text for the promoted-ongoing compact view. Android suggests <= 7 characters for
 * `setShortCriticalText`; clamping here keeps the number readable instead of letting the
 * system clip mid-glyph.
 */
fun scheduleLiveChip(minutes: Long): String = AppText.Schedule.liveChipMinutes(minutes)

/**
 * Resolve the live state for [nowMillis] from the day's blocks.
 *
 * Rules, in order:
 * 1. A block whose window contains now wins (end exclusive), so "in class" is never claimed one
 *    second before it starts or after it ends.
 * 2. Otherwise, if a later block starts today, the notification counts down to it.
 * 3. Otherwise there is no live state at all -- no "no more classes today" ongoing notification,
 *    because a permanent empty notification is noise the user cannot dismiss.
 *
 * A block that has already ended is never resurrected: only blocks starting after now can become
 * "next", which also means an all-finished day clears the notification instead of pinning the last
 * class forever.
 */
fun scheduleLiveState(blocks: List<ScheduleLiveBlock>, nowMillis: Long): ScheduleLiveState {
    val ordered = blocks.sortedBy { it.startsAtMillis }
    val current = ordered.firstOrNull { nowMillis >= it.startsAtMillis && nowMillis < it.endsAtMillis }
    if (current != null) {
        val span = (current.endsAtMillis - current.startsAtMillis).coerceAtLeast(1L)
        val done = (nowMillis - current.startsAtMillis).coerceIn(0L, span)
        val title = AppText.Schedule.liveInClassTitle(current.title)
        val text = listOf(
            current.location.takeIf(String::isNotBlank),
            AppText.Schedule.liveRemaining(scheduleLiveMinutes(current.endsAtMillis - nowMillis)),
            AppText.Schedule.liveUntil(ScheduleLiveClock.format(current.endsAtMillis)),
        ).filterNotNull().joinToString(" · ")
        return ScheduleLiveState(
            phase = ScheduleLivePhase.IN_CLASS,
            title = title,
            text = text,
            progress = done.toFloat() / span.toFloat(),
            startsAtMillis = current.startsAtMillis,
            endsAtMillis = current.endsAtMillis,
            chip = scheduleLiveChip(scheduleLiveMinutes(current.endsAtMillis - nowMillis)),
        )
    }
    val next = ordered.firstOrNull { it.startsAtMillis > nowMillis }
    if (next != null) {
        val gapMillis = next.startsAtMillis - nowMillis
        return ScheduleLiveState(
            phase = ScheduleLivePhase.BEFORE_NEXT,
            title = AppText.Schedule.liveNextTitle(next.title),
            text = listOf(
                next.location.takeIf(String::isNotBlank),
                AppText.Schedule.liveStartsIn(scheduleLiveMinutes(gapMillis)),
                AppText.Schedule.liveAt(ScheduleLiveClock.format(next.startsAtMillis)),
            ).filterNotNull().joinToString(" · "),
            // The countdown has no natural denominator (the gap depends on when the day started),
            // so it stays indeterminate rather than faking a percentage.
            progress = null,
            startsAtMillis = next.startsAtMillis,
            nextTitle = next.title,
            nextLocation = next.location,
            nextStartsAtMillis = next.startsAtMillis,
            chip = scheduleLiveChip(scheduleLiveMinutes(gapMillis)),
        )
    }
    return ScheduleLiveState(phase = ScheduleLivePhase.NONE, title = "", text = "", progress = null)
}

/** Millisecond formatting shared by the live notification and its tests. */
object ScheduleLiveClock {
    /** Epoch millis -> "HH:mm" in the school time zone. */
    fun format(epochMillis: Long): String = runCatching {
        val local = kotlinx.datetime.Instant.fromEpochMilliseconds(epochMillis)
            .toLocalDateTime(kotlinx.datetime.TimeZone.of(SCHEDULE_TIME_ZONE_ID))
        val hour = local.hour.toString().padStart(2, '0')
        val minute = local.minute.toString().padStart(2, '0')
        return "$hour:$minute"
    }.getOrElse { "" }
}
