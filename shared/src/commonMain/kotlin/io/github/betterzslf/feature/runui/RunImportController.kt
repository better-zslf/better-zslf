package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.run.*
import io.github.betterzslf.platform.PickedFile
import io.github.betterzslf.platform.RunMapCoordinateSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** All state is an in-memory local preview; this controller has no store, repository, or upload API. */
enum class RunImportSource { GPX_FILE, KML_FILE, RECOMMENDED }
data class RunImportState(
    val fileName: String? = null,
    val source: RunImportSource? = null,
    val originalSegments: List<List<GeoPoint>> = emptyList(),
    val originalCoordinateSystem: RunMapCoordinateSystem = RunMapCoordinateSystem.UNKNOWN,
    val workingPoints: List<GeoPoint> = emptyList(),
    val workingCoordinateSystem: RunMapCoordinateSystem = RunMapCoordinateSystem.WGS84,
    val normalized: Boolean = false,
    val detoured: Boolean = false,
    val validation: RouteValidationReport? = null,
    val targetDistanceMeters: Double? = null,
    val durationMillis: Long? = null,
    val generationSamplingSeconds: Int? = null,
    val generated: GeneratedRunTrack? = null,
    val generatedValidation: RouteValidationReport? = null,
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    val busy: Boolean = false,
) {
    val originalPoints: List<GeoPoint> get() = originalSegments.flatten()
}

class RunImportController(private val detail: RunTaskDetail, private val nowMillis: () -> Long) {
    private val nativeDetail = detail.inNativeWgs84()
    private val mutableState = MutableStateFlow(RunImportState(warnings = detail.warnings))
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()

    suspend fun read(file: PickedFile): Boolean = edit { old ->
        val extension = file.name.substringAfterLast('.', "").lowercase()
        if (extension !in listOf("gpx", "kml")) return@edit old.copy(error = AppText.Run.SELECT_GPX_OR_KML_COMPRESSED_KMZ_UNSUPPORTED)
        val content = try { file.readBytes().decodeToString(throwOnInvalidSequence = true) }
            catch (_: Exception) { return@edit old.copy(error = AppText.Run.ROUTE_FILE_REQUIRES_VALID_UTF8_XML) }
        val parsed = if (extension == "gpx") RunRouteParser.parseGpx(content) else RunRouteParser.parseKml(content)
        when (parsed) {
            is RouteParseResult.Failure -> old.copy(error = parsed.message)
            is RouteParseResult.Success -> RunImportState(fileName = file.name,
                source = if (extension == "gpx") RunImportSource.GPX_FILE else RunImportSource.KML_FILE,
                originalSegments = parsed.segments.map { it.toList() }, originalCoordinateSystem = RunMapCoordinateSystem.WGS84, workingPoints = parsed.points.toList(),
                warnings = detail.warnings + AppText.Run.IMPORTED_DATA_LOCAL_PREVIEW_ONLY_NO_RECORD_OR_AUTO_UPLOAD)
        }
    }

    suspend fun useRecommended(): Boolean = edit {
        if (detail.walkPaths.sumOf { it.size } < 2) RunImportState(warnings = detail.warnings, error = AppText.Run.SERVER_USABLE_RECOMMENDED_PATH_MISSING)
        else RunImportState(fileName = AppText.Run.SERVER_RECOMMENDED_PATH_SOURCE_LABEL, source = RunImportSource.RECOMMENDED,
            originalSegments = detail.walkPaths.map { it.toList() }, originalCoordinateSystem = RunMapCoordinateSystem.GCJ02, workingPoints = nativeDetail.walkPaths.flatten(),
            warnings = detail.warnings + AppText.Run.RECOMMENDED_PATH_ORIGINAL_FRAME_RETAINED_WORKING_COPY_CONVERTED_HINT)
    }

    /** Explicit geometric operation. Disconnected imported segments are never silently bridged. */
    suspend fun normalize(): Boolean = edit { old ->
        require(old.originalSegments.isNotEmpty()) { AppText.Run.SELECT_ROUTE_FIRST }
        val points = if (!old.detoured) RunRouteNormalizer.normalizeSegments(if (old.source == RunImportSource.RECOMMENDED) nativeDetail.walkPaths else old.originalSegments)
            else RunRouteNormalizer.normalize(old.workingPoints)
        old.copy(workingPoints = points, normalized = true, validation = null, generated = null, generatedValidation = null,
            error = null, warnings = old.warnings + AppText.Run.GEOMETRIC_DENSITY_ADJUSTED_ROAD_TRAVERSABILITY_UNVERIFIED_HINT)
    }

    suspend fun validate(targetDistanceMeters: Double, durationMillis: Long): Boolean = edit { old ->
        val report = RunRouteValidator.validate(old.workingPoints, nativeDetail.checkpoints, requirements(targetDistanceMeters, durationMillis))
        old.copy(validation = report, targetDistanceMeters = targetDistanceMeters, durationMillis = durationMillis, generated = null, generatedValidation = null, error = null)
    }

    suspend fun supplement(targetDistanceMeters: Double, durationMillis: Long): Boolean = edit { old ->
        require(targetDistanceMeters.isFinite() && targetDistanceMeters > 0)
        when (val result = RunDetourPlanner.extend(old.workingPoints, nativeDetail.walkPaths, nativeDetail.checkpoints, maxOf(targetDistanceMeters, detail.minimumDistanceMeters ?: 0.0))) {
            is DetourResult.Failure -> old.copy(error = result.reason, generated = null, generatedValidation = null)
            is DetourResult.Success -> old.copy(workingPoints = result.points, detoured = true, targetDistanceMeters = targetDistanceMeters, durationMillis = durationMillis, generated = null, generatedValidation = null,
                validation = RunRouteValidator.validate(result.points, nativeDetail.checkpoints, requirements(targetDistanceMeters, durationMillis)),
                error = if (result.remainingMeters > 0.01) AppText.Run.supplementDistanceRemaining((result.remainingMeters.toInt())) else null,
                warnings = old.warnings + AppText.Run.DISTANCE_EXTENSION_RECOMMENDED_PATH_AND_TURNAROUND_LIMITS_HINT)
        }
    }

    suspend fun generate(targetDistanceMeters: Double, durationMillis: Long, seed: Int = 0, samplingSeconds: Int = 10): Boolean = edit { old ->
        require(samplingSeconds in 10..60) { AppText.Run.SAMPLING_SETTING_RANGE_REQUIRED }
        val requirements = requirements(targetDistanceMeters, durationMillis)
        val report = RunRouteValidator.validate(old.workingPoints, nativeDetail.checkpoints, requirements)
        if (!report.valid) return@edit old.copy(validation = report, targetDistanceMeters = targetDistanceMeters, durationMillis = durationMillis, generated = null, generatedValidation = null, error = AppText.Run.RESOLVE_ROUTE_COVERAGE_DISTANCE_SPEED_OR_DENSITY_FIRST)
        val generated = RunTrackGenerator.generate(old.workingPoints, TrackGenerationOptions(nowMillis(), durationMillis, seed, samplingSeconds * 1000L))
        val consistency = RunTrackValidator.validate(generated)
        require(consistency.valid) { AppText.Run.GENERATED_TRACK_CONSISTENCY_VALIDATION_FAILED }
        val generatedReport = RunRouteValidator.validate(generated.points.map { it.point }, nativeDetail.checkpoints, requirements)
        old.copy(validation = report, targetDistanceMeters = targetDistanceMeters, durationMillis = durationMillis, generationSamplingSeconds = samplingSeconds, generated = generated, generatedValidation = generatedReport,
            error = if (!generatedReport.valid) AppText.Run.GENERATED_POINTS_ROUTE_INVALID_LOCAL_PREVIEW_ONLY_ADJUST_TARGET else null,
            warnings = old.warnings + AppText.Run.RANDOM_WALK_PREVIEW_NOT_REAL_LOCATION_OR_RECORD_QUEUE_HINT)
    }

    suspend fun clear(): Boolean = edit { RunImportState(warnings = detail.warnings) }

    private fun requirements(targetDistanceMeters: Double, durationMillis: Long): RouteRequirements {
        require(targetDistanceMeters.isFinite() && targetDistanceMeters > 0) { AppText.Run.TARGET_DISTANCE_REQUIRES_POSITIVE_METERS }
        return RouteRequirements(minimumDistanceMeters = maxOf(targetDistanceMeters, detail.minimumDistanceMeters ?: 0.0), durationMillis = durationMillis)
    }

    private suspend fun edit(transform: (RunImportState) -> RunImportState): Boolean {
        if (!mutex.tryLock()) return false
        val old = mutableState.value
        mutableState.value = old.copy(busy = true, error = null)
        return try {
            val next = withContext(Dispatchers.Default) { transform(old) }
            mutableState.value = next.copy(busy = false)
            next.error == null
        } catch (cancelled: CancellationException) {
            mutableState.value = old.copy(busy = false)
            throw cancelled
        } catch (error: IllegalArgumentException) {
            mutableState.value = old.copy(busy = false, error = error.message ?: AppText.Run.ROUTE_PARAMETER_OR_COORDINATE_VALIDATION_FAILED)
            false
        } catch (_: Exception) {
            mutableState.value = old.copy(busy = false, error = AppText.Run.LOCAL_ROUTE_PROCESSING_FAILED_CHECK_FILE_AND_TARGET)
            false
        } finally { mutex.unlock() }
    }
}

/** Display conversion only; all engines/wire values remain metres, seconds and milliseconds. */
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
