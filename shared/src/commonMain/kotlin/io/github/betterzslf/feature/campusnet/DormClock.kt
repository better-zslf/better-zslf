package io.github.betterzslf.feature.campusnet

/**
 * Wall-clock helpers for the dialer's `local-time` field.
 *
 * The value must be the device's *local* time, matching the reference's `%Y-%m-%d %H:%M:%S`. A
 * single offset captured at start-up is enough here: the field is only ever stamped within one
 * attempt, and an offset change mid-attempt would only ever move it by the size of a DST step.
 */
expect fun currentEpochMillis(): Long

/** Minutes east of UTC, as the platform currently reports it. */
expect fun localOffsetMinutes(): Int
