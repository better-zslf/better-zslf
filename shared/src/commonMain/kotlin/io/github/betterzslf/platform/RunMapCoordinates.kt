package io.github.betterzslf.platform

import io.github.betterzslf.feature.run.GeoPoint
import kotlin.math.*

/** Deterministic coordinate-frame conversion. Never mutates original native samples or timestamps. */
object RunMapCoordinates {
    private const val A = 6_378_245.0
    private const val EE = 0.006693421622965943
    private const val DEG = PI / 180.0

    fun convert(point: GeoPoint, from: RunMapCoordinateSystem, to: RunMapCoordinateSystem): GeoPoint? {
        if (from == RunMapCoordinateSystem.UNKNOWN || to == RunMapCoordinateSystem.UNKNOWN) return null
        if (from == to) return point
        return if (from == RunMapCoordinateSystem.WGS84) wgs84ToGcj02(point) else gcj02ToWgs84(point)
    }

    fun wgs84ToGcj02(point: GeoPoint): GeoPoint {
        if (outsideChina(point)) return point
        val x = point.longitude - 105.0
        val y = point.latitude - 35.0
        var dLat = -100.0 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        dLat += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        dLat += (20 * sin(y * PI) + 40 * sin(y * PI / 3)) * 2 / 3
        dLat += (160 * sin(y * PI / 12) + 320 * sin(y * PI / 30)) * 2 / 3
        var dLon = 300.0 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        dLon += (20 * sin(6 * x * PI) + 20 * sin(2 * x * PI)) * 2 / 3
        dLon += (20 * sin(x * PI) + 40 * sin(x * PI / 3)) * 2 / 3
        dLon += (150 * sin(x * PI / 12) + 300 * sin(x * PI / 30)) * 2 / 3
        val latitude = point.latitude * DEG
        val magic = 1 - EE * sin(latitude).pow(2)
        val root = sqrt(magic)
        dLat = dLat * 180 / ((A * (1 - EE) / (magic * root)) * PI)
        dLon = dLon * 180 / ((A / root * cos(latitude)) * PI)
        return GeoPoint(point.longitude + dLon, point.latitude + dLat)
    }

    fun gcj02ToWgs84(point: GeoPoint): GeoPoint {
        if (outsideChina(point)) return point
        var candidate = point
        repeat(12) {
            val projected = wgs84ToGcj02(candidate)
            val longitudeError = projected.longitude - point.longitude
            val latitudeError = projected.latitude - point.latitude
            if (max(abs(longitudeError), abs(latitudeError)) < 1e-9) return candidate
            candidate = GeoPoint(candidate.longitude - longitudeError, candidate.latitude - latitudeError)
        }
        return candidate
    }

    private fun outsideChina(point: GeoPoint): Boolean =
        point.longitude !in 72.004..137.8347 || point.latitude !in 0.8293..55.8271
}
