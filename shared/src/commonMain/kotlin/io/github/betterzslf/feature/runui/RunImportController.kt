package io.github.betterzslf.feature.runui

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
        if (extension !in listOf("gpx", "kml")) return@edit old.copy(error = "请选择 GPX 或 KML 文件；当前不解析压缩 KMZ")
        val content = try { file.readBytes().decodeToString(throwOnInvalidSequence = true) }
            catch (_: Exception) { return@edit old.copy(error = "路线文件须为有效 UTF-8 XML") }
        val parsed = if (extension == "gpx") RunRouteParser.parseGpx(content) else RunRouteParser.parseKml(content)
        when (parsed) {
            is RouteParseResult.Failure -> old.copy(error = parsed.message)
            is RouteParseResult.Success -> RunImportState(fileName = file.name,
                source = if (extension == "gpx") RunImportSource.GPX_FILE else RunImportSource.KML_FILE,
                originalSegments = parsed.segments.map { it.toList() }, originalCoordinateSystem = RunMapCoordinateSystem.WGS84, workingPoints = parsed.points.toList(),
                warnings = detail.warnings + "原始导入数据仅作本机预览；不会写入运动记录或自动上传")
        }
    }

    suspend fun useRecommended(): Boolean = edit {
        if (detail.walkPaths.sumOf { it.size } < 2) RunImportState(warnings = detail.warnings, error = "服务端未提供可用的推荐路径")
        else RunImportState(fileName = "服务端推荐路径", source = RunImportSource.RECOMMENDED,
            originalSegments = detail.walkPaths.map { it.toList() }, originalCoordinateSystem = RunMapCoordinateSystem.GCJ02, workingPoints = nativeDetail.walkPaths.flatten(),
            warnings = detail.warnings + "推荐路径原值保留 GCJ02；工作副本与打卡点明确转换为 WGS84 做本机几何计算，保持分段，不覆盖原值")
    }

    /** Explicit geometric operation. Disconnected imported segments are never silently bridged. */
    suspend fun normalize(): Boolean = edit { old ->
        require(old.originalSegments.isNotEmpty()) { "请先选择路线" }
        val points = if (!old.detoured) RunRouteNormalizer.normalizeSegments(if (old.source == RunImportSource.RECOMMENDED) nativeDetail.walkPaths else old.originalSegments)
            else RunRouteNormalizer.normalize(old.workingPoints)
        old.copy(workingPoints = points, normalized = true, validation = null, generated = null, generatedValidation = null,
            error = null, warnings = old.warnings + "已按几何线段抽稀/加密；插值不代表已验证真实道路通行性")
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
                error = if (result.remainingMeters > 0.01) "约束内仍有 ${result.remainingMeters.toInt()} 米未补足，请查看距离校验" else null,
                warnings = old.warnings + "补距仅沿已有推荐路径计算；最多 12 轮，保留终点前 50 米折返点")
        }
    }

    suspend fun generate(targetDistanceMeters: Double, durationMillis: Long, seed: Int = 0, samplingSeconds: Int = 10): Boolean = edit { old ->
        require(samplingSeconds in 10..60) { "采样设置须在 10 至 60 秒之间" }
        val requirements = requirements(targetDistanceMeters, durationMillis)
        val report = RunRouteValidator.validate(old.workingPoints, nativeDetail.checkpoints, requirements)
        if (!report.valid) return@edit old.copy(validation = report, targetDistanceMeters = targetDistanceMeters, durationMillis = durationMillis, generated = null, generatedValidation = null, error = "请先处理路线覆盖、距离、速度或点密度问题")
        val generated = RunTrackGenerator.generate(old.workingPoints, TrackGenerationOptions(nowMillis(), durationMillis, seed, samplingSeconds * 1000L))
        val consistency = RunTrackValidator.validate(generated)
        require(consistency.valid) { "本地生成轨迹一致性校验失败" }
        val generatedReport = RunRouteValidator.validate(generated.points.map { it.point }, nativeDetail.checkpoints, requirements)
        old.copy(validation = report, targetDistanceMeters = targetDistanceMeters, durationMillis = durationMillis, generationSamplingSeconds = samplingSeconds, generated = generated, generatedValidation = generatedReport,
            error = if (!generatedReport.valid) "生成点的路线校验仍有问题，仅保留本地预览，请调整目标参数" else null,
            warnings = old.warnings + "这是带随机游走的本机生成预览，不是真实定位样本，也不会进入真实记录队列")
    }

    suspend fun clear(): Boolean = edit { RunImportState(warnings = detail.warnings) }

    private fun requirements(targetDistanceMeters: Double, durationMillis: Long): RouteRequirements {
        require(targetDistanceMeters.isFinite() && targetDistanceMeters > 0) { "目标距离须为正数米值" }
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
            mutableState.value = old.copy(busy = false, error = error.message ?: "路线参数或坐标校验失败")
            false
        } catch (_: Exception) {
            mutableState.value = old.copy(busy = false, error = "本机路线处理失败，请检查文件和目标参数")
            false
        } finally { mutex.unlock() }
    }
}

/** Display conversion only; all engines/wire values remain metres, seconds and milliseconds. */
object RunDisplay {
    fun distance(meters: Double?, metric: Boolean): String {
        if (meters == null || !meters.isFinite() || meters < 0) return "—"
        return if (metric) {
            if (meters < 1000) "${decimal(meters, 1)} 米" else "${decimal(meters / 1000, 2)} 公里"
        } else {
            if (meters < 1609.344) "${decimal(meters / 0.3048, 1)} 英尺" else "${decimal(meters / 1609.344, 2)} 英里"
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
        return "${duration((seconds * 1000).toLong())} /${if (metric) "公里" else "英里"}"
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
