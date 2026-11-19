package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.text.AppText

/** Display conversion only; shared by real recording and Pro previews. */
object RunDisplay {
    fun distance(meters: Double?, metric: Boolean): String {
        if (meters == null || !meters.isFinite() || meters < 0) return "—"
        return if (metric) {
            if (meters < 1000) AppText.Run.distanceInMeters((decimal(meters, 1))) else AppText.Run.distanceInKilometers((decimal(meters / 1000, 2)))
        } else {
            if (meters < 1609.344) AppText.Run.distanceInFeet((decimal(meters / 0.3048, 1))) else AppText.Run.distanceInMiles((decimal(meters / 1609.344, 2)))
        }
    }
    fun duration(millis: Long?): String {
        if (millis == null || millis < 0) return "—"
        val seconds = millis / 1000
        val minutes = seconds / 60
        return if (minutes >= 60) "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
            else "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
    }
    fun pace(distanceMeters: Double, elapsedMillis: Long, metric: Boolean): String {
        if (!distanceMeters.isFinite() || distanceMeters <= 0 || elapsedMillis <= 0) return "—"
        val seconds = elapsedMillis / 1000.0 / (distanceMeters / if (metric) 1000.0 else 1609.344)
        if (!seconds.isFinite() || seconds > Long.MAX_VALUE / 1000.0) return "—"
        return "${duration((seconds * 1000).toLong())} /${if (metric) AppText.Run.KILOMETERS_PACE_UNIT_LABEL else AppText.Run.MILES_PACE_UNIT_LABEL}"
    }
    fun decimal(value: Double, digits: Int = 1): String {
        if (!value.isFinite()) return "—"
        require(digits in 0..4)
        val factor = when (digits) { 0 -> 1L; 1 -> 10L; 2 -> 100L; 3 -> 1000L; else -> 10000L }
        if (kotlin.math.abs(value) > Long.MAX_VALUE.toDouble() / factor) return value.toString()
        val rounded = kotlin.math.round(value * factor).toLong()
        if (digits == 0) return rounded.toString()
        val absolute = kotlin.math.abs(rounded)
        return "${if (rounded < 0) "-" else ""}${absolute / factor}.${(absolute % factor).toString().padStart(digits, '0')}"
    }
}
