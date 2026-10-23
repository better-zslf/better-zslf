package io.github.betterzslf.feature.run

enum class RouteParseError { EMPTY, TOO_LARGE, XML_DECLARATION, MALFORMED_XML, WRONG_FORMAT, INVALID_COORDINATE, TOO_MANY_POINTS, NO_ROUTE }

sealed interface RouteParseResult {
    /** Segments are retained: flattening alone does not establish that their joins are traversable. */
    data class Success(val segments: List<List<GeoPoint>>, val source: String) : RouteParseResult {
        val points: List<GeoPoint> get() = segments.flatten()
    }
    data class Failure(val reason: RouteParseError, val message: String) : RouteParseResult
}

/** A bounded XML reader, with no DTD, entity expansion, file access, or network access. */
object RunRouteParser {
    const val MAX_DOCUMENT_CHARS = 4_000_000
    const val MAX_POINTS = 100_000

    fun parseGpx(xml: String): RouteParseResult = parse(xml, gpx = true)
    fun parseKml(xml: String): RouteParseResult = parse(xml, gpx = false)

    private fun parse(xml: String, gpx: Boolean): RouteParseResult {
        if (xml.isBlank()) return RouteParseResult.Failure(RouteParseError.EMPTY, "路线文件为空")
        if (xml.length > MAX_DOCUMENT_CHARS) return RouteParseResult.Failure(RouteParseError.TOO_LARGE, "路线文件超过本地解析上限")
        return try {
            val tracks = mutableListOf<MutableList<GeoPoint>>()
            val routes = mutableListOf<MutableList<GeoPoint>>()
            var current: MutableList<GeoPoint>? = null
            var coordinateText: StringBuilder? = null
            var total = 0
            var rootSeen = false
            fun add(point: GeoPoint) {
                total++
                if (total > MAX_POINTS) fail(RouteParseError.TOO_MANY_POINTS, "路线点数超过本地解析上限")
                current?.add(point) ?: fail(RouteParseError.MALFORMED_XML, "坐标缺少路线段")
            }
            SmallXmlReader(xml).read(
                start = { name, attributes, ancestors ->
                    val local = name.substringAfter(':')
                    if (ancestors.isEmpty()) {
                        rootSeen = true
                        if (local != if (gpx) "gpx" else "kml") fail(RouteParseError.WRONG_FORMAT, "文件格式与导入类型不符")
                    }
                    val parent = ancestors.lastOrNull()?.substringAfter(':')
                    if (gpx) {
                        if ((local == "trkseg" && parent == "trk") || (local == "rte" && parent == "gpx")) {
                            if (current != null) fail(RouteParseError.MALFORMED_XML, "路线段嵌套异常")
                            current = mutableListOf<GeoPoint>().also { if (local == "trkseg") tracks.add(it) else routes.add(it) }
                        }
                        if ((local == "trkpt" && parent == "trkseg") || (local == "rtept" && parent == "rte")) {
                            val lat = attributes["lat"]?.toDoubleOrNull()
                            val lon = attributes["lon"]?.toDoubleOrNull()
                            add(coordinate(lon, lat))
                        }
                    } else {
                        if (local == "LineString") {
                            if (current != null) fail(RouteParseError.MALFORMED_XML, "路线段嵌套异常")
                            current = mutableListOf<GeoPoint>().also { tracks.add(it) }
                        }
                        if (local == "coordinates" && parent == "LineString") {
                            if (coordinateText != null) fail(RouteParseError.MALFORMED_XML, "坐标节点嵌套异常")
                            coordinateText = StringBuilder()
                        }
                    }
                },
                text = { text, ancestors ->
                    if (!gpx && ancestors.lastOrNull()?.substringAfter(':') == "coordinates" && coordinateText != null) coordinateText!!.append(text)
                },
                end = { name, _ ->
                    val local = name.substringAfter(':')
                    if (!gpx && local == "coordinates" && coordinateText != null) {
                        val content = coordinateText.toString().trim()
                        if (content.isNotEmpty()) for (tuple in content.split(Regex("\\s+"))) {
                            val parts = tuple.split(',')
                            if (parts.size !in 2..3 || (parts.size == 3 && parts[2].toDoubleOrNull()?.isFinite() != true)) {
                                fail(RouteParseError.INVALID_COORDINATE, "KML 坐标须为经度,纬度[,海拔]")
                            }
                            add(coordinate(parts[0].toDoubleOrNull(), parts[1].toDoubleOrNull()))
                        }
                        coordinateText = null
                    }
                    if ((gpx && local in listOf("trkseg", "rte")) || (!gpx && local == "LineString")) current = null
                },
            )
            if (!rootSeen) fail(RouteParseError.MALFORMED_XML, "缺少 XML 根节点")
            // GPX exports sometimes include both an intended route and a recorded track. Do not concatenate them.
            val segments = (if (tracks.any { it.isNotEmpty() }) tracks else routes).filter { it.isNotEmpty() }.map { it.toList() }
            if (segments.sumOf { it.size } < 2) fail(RouteParseError.NO_ROUTE, "路线至少需要两个坐标点")
            RouteParseResult.Success(segments, if (gpx) "GPX" else "KML")
        } catch (error: ParseFailure) {
            RouteParseResult.Failure(error.reason, error.message ?: "路线解析失败")
        } catch (_: IllegalArgumentException) {
            RouteParseResult.Failure(RouteParseError.INVALID_COORDINATE, "路线含无效或越界经纬度")
        }
    }

    private fun coordinate(lon: Double?, lat: Double?): GeoPoint {
        if (lon == null || lat == null || !lon.isFinite() || !lat.isFinite() || lon !in -180.0..180.0 || lat !in -90.0..90.0) {
            fail(RouteParseError.INVALID_COORDINATE, "路线含无效或越界经纬度")
        }
        return GeoPoint(lon, lat)
    }

    private class ParseFailure(val reason: RouteParseError, message: String) : IllegalArgumentException(message)
    private fun fail(reason: RouteParseError, message: String): Nothing = throw ParseFailure(reason, message)

    private class SmallXmlReader(private val input: String) {
        private var position = if (input.startsWith('\uFEFF')) 1 else 0
        private val stack = mutableListOf<String>()
        private var roots = 0
        private var declarationSeen = false

        fun read(start: (String, Map<String, String>, List<String>) -> Unit, text: (String, List<String>) -> Unit, end: (String, List<String>) -> Unit) {
            while (position < input.length) {
                if (input[position] != '<') {
                    val next = input.indexOf('<', position).let { if (it < 0) input.length else it }
                    val content = decode(input.substring(position, next))
                    if (stack.isEmpty() && content.isNotBlank()) malformed()
                    text(content, stack.toList())
                    position = next
                    continue
                }
                when {
                    input.startsWith("<!--", position) -> {
                        val close = input.indexOf("-->", position + 4)
                        if (close < 0 || input.substring(position + 4, close).contains("--")) malformed()
                        position = close + 3
                    }
                    input.startsWith("<![CDATA[", position) -> {
                        if (stack.isEmpty()) malformed()
                        val close = input.indexOf("]]>", position + 9)
                        if (close < 0) malformed()
                        text(input.substring(position + 9, close), stack.toList())
                        position = close + 3
                    }
                    input.startsWith("<!", position) -> fail(RouteParseError.XML_DECLARATION, "路线文件含 DTD 或实体声明")
                    input.startsWith("<?", position) -> {
                        val close = input.indexOf("?>", position + 2)
                        if (close < 0) malformed()
                        val instruction = input.substring(position + 2, close)
                        if (!instruction.startsWith("xml ") && !instruction.startsWith("xml\n") && !instruction.startsWith("xml\r") && !instruction.startsWith("xml\t")) {
                            fail(RouteParseError.XML_DECLARATION, "路线文件含处理指令")
                        }
                        if (declarationSeen || roots > 0 || stack.isNotEmpty()) malformed()
                        declarationSeen = true
                        position = close + 2
                    }
                    input.startsWith("</", position) -> {
                        position += 2
                        val name = name()
                        whitespace()
                        expect('>')
                        if (stack.lastOrNull() != name) malformed()
                        end(name, stack.toList())
                        stack.removeAt(stack.lastIndex)
                    }
                    else -> {
                        position++
                        val name = name()
                        val attributes = linkedMapOf<String, String>()
                        while (true) {
                            val before = position
                            whitespace()
                            if (position >= input.length) malformed()
                            if (input[position] == '>' || input[position] == '/') break
                            if (before == position) malformed()
                            val key = name()
                            whitespace()
                            expect('=')
                            whitespace()
                            if (position >= input.length || input[position] !in listOf('\'', '"')) malformed()
                            val quote = input[position++]
                            val close = input.indexOf(quote, position)
                            if (close < 0) malformed()
                            val raw = input.substring(position, close)
                            if ('<' in raw || attributes.containsKey(key)) malformed()
                            attributes[key] = decode(raw)
                            position = close + 1
                        }
                        val selfClosing = input[position] == '/'
                        if (selfClosing) position++
                        expect('>')
                        if (stack.isEmpty() && ++roots > 1) malformed()
                        if (stack.size >= 64) malformed()
                        start(name, attributes, stack.toList())
                        stack.add(name)
                        if (selfClosing) {
                            end(name, stack.toList())
                            stack.removeAt(stack.lastIndex)
                        }
                    }
                }
            }
            if (stack.isNotEmpty() || roots != 1) malformed()
        }

        private fun name(): String {
            val begin = position
            while (position < input.length && (input[position].isLetterOrDigit() || input[position] in listOf('_', '-', ':', '.'))) position++
            val value = input.substring(begin, position)
            if (!value.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*(?::[A-Za-z_][A-Za-z0-9_.-]*)?"))) malformed()
            return value
        }
        private fun whitespace() { while (position < input.length && input[position].isWhitespace()) position++ }
        private fun expect(char: Char) { if (position >= input.length || input[position++] != char) malformed() }
        private fun malformed(): Nothing = fail(RouteParseError.MALFORMED_XML, "路线 XML 结构异常")

        private fun decode(value: String): String {
            if ('&' !in value) return value
            val output = StringBuilder()
            var i = 0
            while (i < value.length) {
                if (value[i] != '&') { output.append(value[i++]); continue }
                val close = value.indexOf(';', i + 1)
                if (close < 0 || close - i > 16) malformed()
                val entity = value.substring(i + 1, close)
                val code = when (entity) {
                    "amp" -> 38; "lt" -> 60; "gt" -> 62; "quot" -> 34; "apos" -> 39
                    else -> if (entity.startsWith("#x")) entity.substring(2).toIntOrNull(16) else if (entity.startsWith('#')) entity.substring(1).toIntOrNull() else null
                } ?: fail(RouteParseError.XML_DECLARATION, "路线文件含自定义实体")
                if (code !in 1..0x10ffff || code in 0xd800..0xdfff || (code < 32 && code !in listOf(9, 10, 13))) malformed()
                if (code <= 0xffff) output.append(code.toChar()) else {
                    output.append(((code - 0x10000) / 1024 + 0xd800).toChar())
                    output.append(((code - 0x10000) % 1024 + 0xdc00).toChar())
                }
                i = close + 1
            }
            return output.toString()
        }
    }
}
