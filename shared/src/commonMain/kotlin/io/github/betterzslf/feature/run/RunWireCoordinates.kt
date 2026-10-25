package io.github.betterzslf.feature.run

import io.github.betterzslf.platform.RunMapCoordinates
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

/**
 * Terminal HTTP-body copy of an already canonical WGS84 GPS batch; never a replacement queue batch.
 * Validate the canonical payload before converting only location, using the existing map transform.
 * dis/count/runLength/runTime/speed retain the validated WGS84 physical measurements, not distances
 * recomputed from GCJ02 numeric coordinates. This is a wire policy, not proof of backend acceptance.
 * No frame is guessed here: callers must supply canonical WGS84, not historical/unknown-frame data.
 */
internal fun RunUploadBatch.toGcj02WireBody(): JsonObject {
    val canonical = RunBatcher.validateBatch(this)
    val wire = canonical.map { coordinate ->
        val parts = coordinate.location.split(',')
        val point = GeoPoint(parts[0].toDouble(), parts[1].toDouble())
        val shifted = RunMapCoordinates.wgs84ToGcj02(point)
        if (shifted == point) coordinate
        else coordinate.copy(location = "${shifted.longitude},${shifted.latitude}")
    }
    // Preserve even the nested string's original spelling/whitespace for the outside-China identity.
    val wireCoordinate = if (wire == canonical) payload.coordinate else RunBatcher.json.encodeToString(wire)
    return payload.copy(coordinate = wireCoordinate).toJsonObject()
}
