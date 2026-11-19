package io.github.betterzslf.feature.run

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.platform.RunMapCoordinates

/**
 * Pure terminal-wire conversion of one explicitly WGS84 location. This function neither accepts a
 * batch nor grants access to a journal, upload queue, transport, or native location source.
 *
 * Only the numeric location changes. Outside the transform's China bounds the original string is
 * returned verbatim, so callers can retain existing coordinate JSON spelling and whitespace.
 */
fun wgs84ToGcj02WireLocation(location: String): String {
    val parts = location.split(',')
    require(parts.size == 2) { AppText.Run.INVALID_TRACK_COORDINATE_FORMAT }
    val point = GeoPoint(parts[0].toDouble(), parts[1].toDouble())
    val shifted = RunMapCoordinates.wgs84ToGcj02(point)
    return if (shifted == point) location else "${shifted.longitude},${shifted.latitude}"
}
