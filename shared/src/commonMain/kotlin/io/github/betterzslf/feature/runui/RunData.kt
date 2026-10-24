package io.github.betterzslf.feature.runui

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunCheckpoint
import kotlinx.serialization.json.*

/** errandId/scheduleId are server task fields; trid is optional server display data. identityKey is ONLY a local list key. */
data class RunTaskItem(
    val trid: String?,
    val scheduleId: String?,
    val title: String,
    val startText: String?,
    val endText: String?,
    val isFinish: String?,
    val identityKey: String,
    val raw: JsonObject,
    val warnings: List<String> = emptyList(),
    val errandId: String? = null,
)

data class RunTaskDetail(
    val task: RunTaskItem,
    val checkpoints: List<RunCheckpoint>,
    val walkPaths: List<List<GeoPoint>>,
    val minimumDistanceMeters: Double?,
    val warnings: List<String>,
    /** Names are keyed by local checkpoint id, so repeated names never collapse an ordered visit. */
    val checkpointNames: Map<String, String> = emptyMap(),
    /** Original list length, before invalid coordinates were removed for display. */
    val checkpointSourceCount: Int = checkpoints.size,
) {
    val missingFields: List<String> get() = buildList {
        if (task.errandId.isNullOrBlank()) add("errandId")
        if (task.scheduleId.isNullOrBlank()) add("errandScheduleId")
        if (checkpoints.isEmpty()) add("geoCoorList")
        else if (checkpoints.size != checkpointSourceCount) add("geoCoorList.coordinates")
    }
    val canStart: Boolean get() = missingFields.isEmpty()
}

data class RunRecordItem(
    val trid: String?,
    val title: String,
    val distanceMeters: Double?,
    val elapsedMillis: Long?,
    val status: String?,
    val points: List<GeoPoint>,
    val raw: JsonObject,
    val warnings: List<String>,
)

data class RunRows<T>(
    val items: List<T>,
    val recognized: Boolean,
    val warnings: List<String>,
    /** Transport pagination counts ALL raw rows, including malformed or duplicate rows. */
    val rawSize: Int = items.size,
)

/**
 * Offline read contracts from docs/13, docs/15 and the captured official callsites.
 * No arbitrary "first array", coordinate guessing, synthetic trid, or third-party query parameters.
 * Nested JSON is bounded; warnings contain field paths, never the full response or credentials.
 */
object RunDataDecoder {
    private const val MAX_DEPTH = 12
    private val containers = setOf("errandList", "errandDetail", "userErrandTrackRecord", "recordMap", "trackPointList")
    private val envelopeKeys = setOf("data", "code", "message", "msg", "success")
    private val taskKeys = setOf("trid", "errandScheduleId", "errandSchedule", "scheduleId", "errandId", "eName", "errandName", "geoCoorList", "walkPaths")
    private val recordKeys = setOf("trid", "runLength", "runTime", "status", "distanceMeters", "elapsedMillis")
    private val coordinateKeys = setOf("lon", "lng", "longitude", "lat", "latitude", "location", "coordinate")
    private val decimal = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    fun tasks(data: JsonElement): RunRows<RunTaskItem> = rows(data, "errandList", ::task)
    fun records(data: JsonElement): RunRows<RunRecordItem> = rows(data, "userErrandTrackRecord", ::recordItem)
    fun detail(data: JsonElement): RunTaskDetail? = decodeDetail(data).value
    fun record(data: JsonElement): RunRecordItem? = decodeRecord(data).value
    fun track(data: JsonElement): List<GeoPoint> = trackRows(data).items

    /** Stateless diagnostics: callers can distinguish a known empty track from an unknown shape. */
    fun trackWarnings(data: JsonElement): List<String> = trackRows(data).warnings
    fun detailWarnings(data: JsonElement): List<String> = decodeDetail(data).warnings
    fun recordWarnings(data: JsonElement): List<String> = decodeRecord(data).warnings

    fun trackRows(data: JsonElement): RunRows<GeoPoint> {
        val warnings = mutableListOf<String>()
        val root = root(data, warnings) ?: return RunRows(emptyList(), false, warnings)
        val decoded = trackValue(root, "track", warnings, 0)
        return RunRows(decoded.points, decoded.recognized, warnings.distinct())
    }

    private data class Decoded<T>(val value: T?, val warnings: List<String>)
    private data class TrackPoints(val points: List<GeoPoint>, val recognized: Boolean)

    private fun <T> rows(
        data: JsonElement,
        container: String,
        decode: (JsonObject, String, MutableList<String>) -> T,
    ): RunRows<T> {
        val warnings = mutableListOf<String>()
        val root = root(data, warnings)
        val value = when (root) {
            is JsonArray -> root
            is JsonObject -> if (container in root) materialize(root.getValue(container), container, warnings) else null
            else -> null
        }
        val array = value as? JsonArray
        if (array == null) {
            warnings += "$container：未识别到数组响应，保留已有数据后重试"
            return RunRows(emptyList(), false, warnings.distinct(), 0)
        }
        val items = array.mapIndexedNotNull { index, element ->
            val path = "$container[$index]"
            val row = materialize(element, path, warnings) as? JsonObject
            if (row == null) { warnings += "$path：任务/记录应为对象，已跳过"; null }
            else decode(row, path, warnings)
        }
        return RunRows(items, true, warnings.distinct(), array.size)
    }

    private fun task(raw: JsonObject, path: String, output: MutableList<String>): RunTaskItem {
        val warnings = mutableListOf<String>()
        // Only the real fields may supply errandId/trid. A local list key is never an action identifier.
        val errandId = identifier(raw, "errandId", path, warnings)
        val trid = identifier(raw, "trid", path, warnings)
        // The original task-list click forwards row.id as errandScheduleId (task18 trace).
        // A detail object's generic id has no such evidence and never supplies a plan.
        val planKeys = listOf("errandScheduleId", "errandSchedule", "scheduleId") + if (path.startsWith("errandList[")) listOf("id") else emptyList()
        val scheduleValues = planKeys.mapNotNull { identifier(raw, it, path, warnings) }.distinct()
        val schedule = scheduleValues.singleOrNull()
        if (scheduleValues.size > 1) warnings += "$path.errandScheduleId：真实计划字段相互冲突，开始功能保持停用"
        val title = firstText(raw, listOf("eName", "errandName", "title", "name"), path, warnings) ?: "校园跑任务"
        val start = text(raw, "startTime", path, warnings)
        val end = text(raw, "endTime", path, warnings)
        val finish = firstText(raw, listOf("isFinish", "isExecute"), path, warnings)
        if (errandId == null) warnings += "$path.errandId：服务端未提供真实任务标识，开始功能保持停用"
        if (schedule == null) warnings += "$path.errandScheduleId：服务端未提供计划标识，开始功能保持停用"
        val stableId = errandId ?: identifier(raw, "id", path, warnings)
        val identity = when {
            trid != null -> "trid:" + keyParts(trid, schedule, start, end)
            stableId != null -> "local-task:" + keyParts(stableId, schedule, start, end)
            else -> "local-row:" + canonical(JsonObject(raw.filterKeys { it !in setOf("isFinish", "isExecute", "status", "updateTime") }))
        }
        output += warnings
        return RunTaskItem(trid, schedule, title, start, end, finish, identity, raw, warnings, errandId = errandId)
    }

    private fun decodeDetail(data: JsonElement): Decoded<RunTaskDetail> {
        val warnings = mutableListOf<String>()
        val root = root(data, warnings)
        val detail = when (root) {
            is JsonObject -> when {
                "errandDetail" in root -> materialize(root.getValue("errandDetail"), "errandDetail", warnings) as? JsonObject
                root.keys.any { it in taskKeys } -> root
                else -> null
            }
            else -> null
        }
        if (detail == null) {
            warnings += "errandDetail：未识别到任务详情对象"
            return Decoded(null, warnings.distinct())
        }
        val item = task(detail, "errandDetail", warnings)
        val checkpointField = detail["geoCoorList"]?.let { materialize(it, "errandDetail.geoCoorList", warnings) }
        val checkpointRows = checkpointField as? JsonArray
        val names = linkedMapOf<String, String>()
        if (checkpointRows == null) warnings += "errandDetail.geoCoorList：缺少有效打卡点数组，仅展示任务信息"
        else if (checkpointRows.isEmpty()) warnings += "errandDetail.geoCoorList：打卡点为空，开始功能保持停用"
        val checkpoints = checkpointRows?.mapIndexedNotNull { index, value ->
            val path = "errandDetail.geoCoorList[$index]"
            val decoded = materialize(value, path, warnings)
            val point = decoded?.let { point(it, path, warnings, 0) }
            if (point == null) null else {
                // This id describes a UI visit at its ORIGINAL position, not a server run identifier.
                val localId = "checkpoint-$index"
                val name = (decoded as? JsonObject)?.let { text(it, "name", path, warnings) }
                if (name != null) names[localId] = name
                RunCheckpoint(localId, point)
            }
        }.orEmpty()
        if (checkpointRows != null && checkpoints.size != checkpointRows.size) {
            warnings += "errandDetail.geoCoorList：部分坐标无效；保留原始顺序展示有效点，完整点序列恢复前停用开始"
        }
        val paths = if ("walkPaths" in detail) walkPaths(detail.getValue("walkPaths"), warnings) else {
            warnings += "errandDetail.walkPaths：服务端未提供推荐路线，降级为打卡点展示"
            emptyList()
        }
        val minimum = minimumDistance(detail, warnings)
        val result = RunTaskDetail(item, checkpoints, paths, minimum, warnings.distinct(), names, checkpointRows?.size ?: 0)
        return Decoded(result, result.warnings)
    }

    private fun decodeRecord(data: JsonElement): Decoded<RunRecordItem> {
        val warnings = mutableListOf<String>()
        val root = root(data, warnings)
        val value = when (root) {
            is JsonObject -> when {
                "recordMap" in root -> materialize(root.getValue("recordMap"), "recordMap", warnings)
                root.keys.any { it in recordKeys } -> root
                else -> null
            }
            else -> null
        }
        // docs/13 captured recordMap:"": this is a known absence, not a malformed record object.
        if (root is JsonObject && "recordMap" in root &&
            (value == JsonNull || (value is JsonPrimitive && value.isString && value.content.isEmpty()))) {
            return Decoded(null, warnings.distinct())
        }
        val raw = value as? JsonObject
        if (raw == null) {
            warnings += "recordMap：未识别到记录对象"
            return Decoded(null, warnings.distinct())
        }
        return Decoded(recordItem(raw, "recordMap", warnings), warnings.distinct())
    }

    private fun recordItem(raw: JsonObject, path: String, output: MutableList<String>): RunRecordItem {
        val warnings = mutableListOf<String>()
        val trid = identifier(raw, "trid", path, warnings)
        if (trid == null) warnings += "$path.trid：缺少真实标识，轨迹查询保持停用"
        val title = firstText(raw, listOf("eName", "errandName", "title", "name"), path, warnings) ?: "跑步记录"
        // runLength is documented in metres and runTime in milliseconds. No magnitude heuristics.
        val distance = firstNumber(raw, listOf("runLength", "distanceMeters"), path, warnings)
        val elapsed = firstMillis(raw, listOf("runTime", "elapsedMillis"), path, warnings)
        if (distance == null && listOf("distance", "length", "runDistance").any { it in raw }) {
            warnings += "$path.distance：未知距离字段或单位，未换算为米"
        }
        if (elapsed == null && listOf("time", "duration", "elapsed", "runTimeSeconds").any { it in raw }) {
            warnings += "$path.duration：未知用时字段或单位，未换算为毫秒"
        }
        val status = text(raw, "status", path, warnings)
        val trackFields = listOf("trackPointList", "coordinate", "location").filter { it in raw }
        val decodedTracks = trackFields.map { key ->
            if (key == "trackPointList") trackValue(buildJsonObject { put(key, raw.getValue(key)) }, path, warnings, 0).points
            else trackValue(raw.getValue(key), "$path.$key", warnings, 0).points
        }
        if (trackFields.size > 1) warnings += "$path.track：同时返回多个轨迹字段，逐项校验后仅展示 ${trackFields.first()}，未猜测拼接顺序"
        if (listOf("points", "track", "coordinates").any { it in raw }) warnings += "$path.track：附加轨迹字段结构未确认，未猜测坐标"
        val points = decodedTracks.firstOrNull().orEmpty()
        output += warnings
        return RunRecordItem(trid, title, distance, elapsed, status, points, raw, warnings.distinct())
    }

    /** Bare minDistance is not known to be metres; only explicit unit-bearing fields are converted. */
    private fun minimumDistance(raw: JsonObject, warnings: MutableList<String>): Double? {
        val path = "errandDetail"
        firstNumber(raw, listOf("minimumDistanceMeters", "minDistanceMeters"), path, warnings)?.let { return it }
        val key = listOf("minDistance", "minimumDistance").firstOrNull { it in raw } ?: return null
        val number = number(raw.getValue(key), "$path.$key", warnings) ?: return null
        val unit = firstText(raw, listOf("${key}Unit", "distanceUnit"), path, warnings)?.trim()?.lowercase()
        val factor = when (unit) {
            "m", "metre", "metres", "meter", "meters", "米" -> 1.0
            "km", "kilometre", "kilometres", "kilometer", "kilometers", "千米", "公里" -> 1000.0
            else -> {
                warnings += "$path.$key：缺少已确认的距离单位，未猜测最低距离"
                return null
            }
        }
        val meters = number * factor
        if (!meters.isFinite()) { warnings += "$path.$key：距离换算超出有限数值范围"; return null }
        return meters
    }

    private fun walkPaths(value: JsonElement, warnings: MutableList<String>): List<List<GeoPoint>> {
        val path = "errandDetail.walkPaths"
        val array = materialize(value, path, warnings) as? JsonArray
        if (array == null) { warnings += "$path：推荐路线应为坐标数组或分段数组，已降级"; return emptyList() }
        if (array.isEmpty()) { warnings += "$path：推荐路线为空，降级为打卡点展示"; return emptyList() }
        val elements = array.mapIndexed { index, element -> materialize(element, "$path[$index]", warnings) }
        val segments = mutableListOf<List<GeoPoint>>()
        // A damaged flat path still has useful points on either side of the hole. Only positive
        // nested-point evidence selects the segmented format; a null/triple is not such evidence.
        val segmented = elements.any { element -> element is JsonArray && element.any { child ->
            child is JsonArray || child is JsonObject || (child is JsonPrimitive && child.isString &&
                (',' in child.content || child.content.trimStart().startsWith("[") || child.content.trimStart().startsWith("{")))
        } }
        if (!segmented) {
            walkSegment(elements, path, warnings, segments)
        } else {
            elements.forEachIndexed { index, element ->
                if (element is JsonArray && !pointLike(element)) {
                    val decoded = element.mapIndexed { pointIndex, p -> materialize(p, "$path[$index][$pointIndex]", warnings) }
                    walkSegment(decoded, "$path[$index]", warnings, segments)
                } else {
                    warnings += "$path[$index]：未知/混合路线分段结构，已跳过该段"
                }
            }
        }
        return segments
    }

    private fun walkSegment(
        values: List<JsonElement?>,
        path: String,
        warnings: MutableList<String>,
        output: MutableList<List<GeoPoint>>,
    ) {
        var segment = mutableListOf<GeoPoint>()
        fun flush() { if (segment.isNotEmpty()) { output += segment.toList(); segment = mutableListOf() } }
        if (values.isEmpty()) warnings += "$path：推荐路线包含空分段"
        values.forEachIndexed { index, value ->
            val p = value?.let { point(it, "$path[$index]", warnings, 0) }
            when {
                p == null -> { flush(); warnings += "$path[$index]：坐标缺损处断开路线，未连接两侧道路" }
                // Only walkPaths has a documented (0,0) separator. Other data keeps real (0,0).
                p.longitude == 0.0 && p.latitude == 0.0 -> {
                    flush(); warnings += "$path[$index]：按已记载的 walkPaths (0,0) 分隔符断段"
                }
                else -> segment += p
            }
        }
        flush()
    }

    private fun trackValue(value: JsonElement, path: String, warnings: MutableList<String>, depth: Int): TrackPoints {
        if (depth > MAX_DEPTH) { warnings += "$path：轨迹嵌套过深，已停止解析"; return TrackPoints(emptyList(), false) }
        val decoded = materialize(value, path, warnings) ?: return TrackPoints(emptyList(), false)
        if (decoded is JsonObject) {
            val primary = when { "trackPointList" in decoded -> "trackPointList"; "coordinate" in decoded -> "coordinate"; else -> null }
            if (primary != null) {
                val additional = listOf("coordinate", "location").filter { it != primary && it in decoded }
                additional.forEach { key -> trackValue(decoded.getValue(key), "$path.$key", warnings, depth + 1) }
                val named = JsonObject(decoded.filterKeys { it in setOf("lon", "lng", "longitude", "lat", "latitude") })
                if (named.isNotEmpty()) point(named, "$path.namedCoordinates", warnings, depth + 1)
                if (additional.isNotEmpty() || named.isNotEmpty()) warnings += "$path：附加坐标字段已校验，按 $primary 展示，未猜测多字段拼接顺序"
            }
        }
        return when (decoded) {
            is JsonArray -> {
                if (pointLike(decoded)) TrackPoints(listOfNotNull(point(decoded, path, warnings, depth + 1)), true)
                else TrackPoints(decoded.flatMapIndexed { index, element -> trackValue(element, "$path[$index]", warnings, depth + 1).points }, true)
            }
            is JsonObject -> when {
                "trackPointList" in decoded -> {
                    val points = materialize(decoded.getValue("trackPointList"), "$path.trackPointList", warnings)
                    if (points !is JsonArray) {
                        warnings += "$path.trackPointList：轨迹列表应为数组"
                        TrackPoints(emptyList(), false)
                    } else TrackPoints(points.flatMapIndexed { index, element -> trackValue(element, "$path.trackPointList[$index]", warnings, depth + 1).points }, true)
                }
                "coordinate" in decoded -> trackValue(decoded.getValue("coordinate"), "$path.coordinate", warnings, depth + 1)
                decoded.keys.any { it in coordinateKeys } -> TrackPoints(listOfNotNull(point(decoded, path, warnings, depth + 1)), true)
                else -> { warnings += "$path：未知轨迹对象结构，未猜测坐标"; TrackPoints(emptyList(), false) }
            }
            is JsonPrimitive -> {
                if (decoded.isString && ',' in decoded.content) TrackPoints(listOfNotNull(point(decoded, path, warnings, depth + 1)), true)
                else { warnings += "$path：未知或损坏的轨迹字段"; TrackPoints(emptyList(), false) }
            }
        }
    }

    /** A pair is always [longitude, latitude]; invalid coordinates are never swapped or clamped. */
    private fun point(value: JsonElement, path: String, warnings: MutableList<String>, depth: Int): GeoPoint? {
        if (depth > MAX_DEPTH) { warnings += "$path：坐标嵌套过深"; return null }
        val decoded = materialize(value, path, warnings) ?: return null
        val pair: Pair<JsonElement, JsonElement> = when (decoded) {
            is JsonArray -> {
                if (decoded.size != 2) { warnings += "$path：坐标数组须恰有经度、纬度两项"; return null }
                decoded[0] to decoded[1]
            }
            is JsonPrimitive -> {
                if (!decoded.isString) { warnings += "$path：坐标须为经纬度字符串、对象或二项数组"; return null }
                val parts = decoded.content.split(',')
                if (parts.size != 2) { warnings += "$path：坐标字符串须为 经度,纬度"; return null }
                JsonPrimitive(parts[0].trim()) to JsonPrimitive(parts[1].trim())
            }
            is JsonObject -> {
                val longitudeKeys = listOf("lon", "lng", "longitude").filter { it in decoded }
                val latitudeKeys = listOf("lat", "latitude").filter { it in decoded }
                if (longitudeKeys.isNotEmpty() || latitudeKeys.isNotEmpty()) {
                    if (longitudeKeys.isEmpty() || latitudeKeys.isEmpty()) {
                        warnings += "$path：经纬度字段不完整"; return null
                    }
                    val lonValues = longitudeKeys.map { coordinateNumber(decoded.getValue(it)) }
                    val latValues = latitudeKeys.map { coordinateNumber(decoded.getValue(it)) }
                    if (lonValues.any { it == null } || latValues.any { it == null } || lonValues.distinct().size != 1 || latValues.distinct().size != 1) {
                        warnings += "$path：经纬度字段无效或别名值冲突"; return null
                    }
                    decoded.getValue(longitudeKeys.first()) to decoded.getValue(latitudeKeys.first())
                } else {
                    val nested = listOf("location", "coordinate").filter { it in decoded }
                    if (nested.size != 1) { warnings += "$path：未知或有歧义的坐标对象结构"; return null }
                    return point(decoded.getValue(nested.single()), "$path.${nested.single()}", warnings, depth + 1)
                }
            }
        }
        val longitude = coordinateNumber(pair.first)
        val latitude = coordinateNumber(pair.second)
        if (longitude == null || latitude == null || longitude !in -180.0..180.0 || latitude !in -90.0..90.0) {
            warnings += "$path：经纬度须为有限数值且分别位于 [-180,180]、[-90,90]"
            return null
        }
        return GeoPoint(longitude, latitude)
    }

    private fun pointLike(value: JsonElement): Boolean = when (value) {
        is JsonArray -> value.size == 2 && value.all {
            it is JsonPrimitive && it != JsonNull && (!it.isString ||
                (',' !in it.content && !it.content.trimStart().startsWith("[") &&
                    !it.content.trimStart().startsWith("{") && !it.content.trimStart().startsWith("\"")))
        }
        is JsonPrimitive -> value.isString
        is JsonObject -> true
    }

    private fun coordinateNumber(value: JsonElement): Double? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive == JsonNull || (!primitive.isString && primitive.booleanOrNull != null)) return null
        val text = primitive.content.trim()
        if (!decimal.matches(text)) return null
        return text.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun root(data: JsonElement, warnings: MutableList<String>): JsonElement? {
        var current = materialize(data, "data", warnings) ?: return null
        repeat(MAX_DEPTH) {
            val obj = current as? JsonObject ?: return current
            if ("data" !in obj || obj.keys.any { it in containers } || !obj.keys.all { it in envelopeKeys }) return current
            current = materialize(obj.getValue("data"), "data", warnings) ?: return null
        }
        warnings += "data：响应封装嵌套过深"
        return null
    }

    private fun materialize(value: JsonElement, path: String, warnings: MutableList<String>): JsonElement? {
        var current = value
        repeat(MAX_DEPTH) {
            val primitive = current as? JsonPrimitive ?: return current
            if (!primitive.isString) return current
            val text = primitive.content.trim()
            if (!(text.startsWith("[") || text.startsWith("{") || text.startsWith("\""))) return current
            current = try { Json.parseToJsonElement(text) } catch (_: Exception) {
                warnings += "$path：嵌套 JSON 损坏，已跳过"
                return null
            }
        }
        warnings += "$path：嵌套 JSON 超过解析深度"
        return null
    }

    private fun text(raw: JsonObject, key: String, path: String, warnings: MutableList<String>): String? {
        val value = raw[key] ?: return null
        if (value == JsonNull) return null
        val primitive = value as? JsonPrimitive
        if (primitive == null) { warnings += "$path.$key：应为标量字段"; return null }
        return primitive.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun identifier(raw: JsonObject, key: String, path: String, warnings: MutableList<String>): String? {
        val primitive = raw[key] as? JsonPrimitive
        val value = text(raw, key, path, warnings)?.trim() ?: return null
        if (primitive == null || (!primitive.isString && primitive.longOrNull == null) || value in setOf("null", "undefined")) {
            warnings += "$path.$key：标识须为非空字符串或整数"
            return null
        }
        return value
    }

    private fun firstText(raw: JsonObject, keys: List<String>, path: String, warnings: MutableList<String>): String? =
        keys.mapNotNull { text(raw, it, path, warnings) }.firstOrNull()

    private fun number(value: JsonElement, path: String, warnings: MutableList<String>): Double? {
        val parsed = coordinateNumber(value)
        if (parsed == null || parsed < 0) { warnings += "$path：应为非负有限数值，未猜测或修正单位"; return null }
        return parsed
    }

    private fun firstNumber(raw: JsonObject, keys: List<String>, path: String, warnings: MutableList<String>): Double? {
        val values = keys.mapNotNull { key -> raw[key]?.takeUnless { it == JsonNull }?.let { number(it, "$path.$key", warnings) } }.distinct()
        if (values.size > 1) warnings += "$path.${keys.first()}：同单位数值字段冲突，未选择其中一项"
        return values.singleOrNull()
    }

    private fun firstMillis(raw: JsonObject, keys: List<String>, path: String, warnings: MutableList<String>): Long? {
        val values = keys.mapNotNull { key ->
            val value = raw[key]
            if (value == null || value == JsonNull) null else {
                val parsed = (value as? JsonPrimitive)?.content?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
                if (parsed == null) warnings += "$path.$key：已知单位为毫秒，但值不是非负整数或超出 Long 范围"
                parsed
            }
        }.distinct()
        if (values.size > 1) warnings += "$path.${keys.first()}：毫秒字段冲突，未选择其中一项"
        return values.singleOrNull()
    }

    private fun keyParts(vararg parts: String?): String = parts.joinToString("|") { part -> part?.let { "${it.length}:$it" } ?: "-" }

    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (key, child) -> "${JsonPrimitive(key)}:${canonical(child)}" }
        is JsonArray -> value.joinToString(",", "[", "]", transform = ::canonical)
        is JsonPrimitive -> value.toString()
    }
}
