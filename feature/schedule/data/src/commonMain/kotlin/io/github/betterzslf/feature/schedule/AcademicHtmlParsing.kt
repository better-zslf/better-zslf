package io.github.betterzslf.feature.schedule

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

internal const val ACADEMIC_ORIGIN = "https://jwgln.zsc.edu.cn"
internal const val ACADEMIC_BASE_PATH = "/jsxsd/"
private val academicIdPattern = Regex("^[A-Za-z0-9_.-]{1,80}$")
private val accountPattern = Regex("^[A-Za-z0-9_.-]{1,64}$")

internal fun academicDocument(html: String) = if (html.length in 1..2_097_152)
    runCatching { Ksoup.parse(html, ACADEMIC_ORIGIN) }.getOrElse { throw AcademicProtocolException() }
    else throw AcademicProtocolException()

internal fun academicLoginShell(html: String): Boolean {
    val doc = academicDocument(html)
    return doc.select("form[action]").any { it.attr("action").substringBefore('?').endsWith("/xk/LoginToXk") } ||
        (doc.select("input[type=password]").isNotEmpty() && doc.select("input[name=userAccount],input#userAccount").isNotEmpty())
}

/** Explicit student-account fields only. An arbitrary occurrence of the requested account is not identity proof. */
internal fun parseAcademicIdentity(html: String, expected: String): AcademicIdentity? {
    if (academicLoginShell(html)) return null
    val doc = academicDocument(html)
    val accounts = mutableSetOf<String>()
    // Fixed student menu, confirmed structurally by Lead's new-account read-only probe, never substring proof.
    val menu = doc.select("div#Top1_divLoginName.Nsb_top_menu_nc").singleOrNull()
    val menuIdentity = menu?.ownText()?.let(::clean)?.let {
        Regex("^(.{1,160})\\(([A-Za-z0-9_.-]{1,64})\\)$").matchEntire(it)
    }
    menuIdentity?.let { accounts += it.groupValues[2] }
    for (element in doc.select("input[name=xh],input#xh,input[name=xsxh],input#xsxh,input[name=studentNo],span#xh,span#xsxh")) {
        val text = clean(if (element.tagName() == "input") element.attr("value") else element.text())
        if (accountPattern.matches(text)) accounts += text
    }
    for (row in doc.select("tr")) {
        val cells = row.children().filter { it.tagName() in setOf("td", "th") }
        for ((left, right) in cells.zipWithNext()) {
            if (clean(left.text()).trimEnd(':', '：') == "学号") {
                val text = clean(right.text())
                if (accountPattern.matches(text)) accounts += text
            }
        }
    }
    val inlineAccount = Regex("^学号[：:]\\s*([A-Za-z0-9_.-]{1,64})$")
    for (element in doc.select("span,label,li")) inlineAccount.matchEntire(clean(element.text()))?.let { accounts += it.groupValues[1] }
    // A literal account-labelled line following a br is a second observed shape, not arbitrary body text.
    for (parent in doc.select("br").mapNotNull { it.parent() }.distinct()) {
        for (line in parent.html().split(Regex("(?i)<br\\s*/?>"))) {
            inlineAccount.matchEntire(clean(Ksoup.parse(line).text()))?.let { accounts += it.groupValues[1] }
        }
    }
    if (accounts.singleOrNull() != expected) return null
    val name = menuIdentity?.groupValues?.get(1)?.trim() ?: doc.select("input[name=xm],input#xm,span#xm").firstOrNull()?.let {
        clean(if (it.tagName() == "input") it.attr("value") else it.text()).take(80)
    }.orEmpty()
    return AcademicIdentity(expected, name)
}

internal fun parseAcademicTerms(html: String): List<ScheduleTerm> {
    val doc = academicDocument(html)
    val select = doc.select("select#xnxq01id,select[name=xnxq01id]").firstOrNull() ?: throw AcademicProtocolException()
    val options = select.select("option[value]").filter { it.attr("value").isNotBlank() }
    if (options.isEmpty() || options.size > 100 || options.count { it.hasAttr("selected") } > 1) throw AcademicProtocolException()
    val terms = options.map {
        val id = it.attr("value")
        val name = clean(it.text())
        if (!academicIdPattern.matches(id) || name.isEmpty() || name.length > 128) throw AcademicProtocolException()
        ScheduleTerm(id, name, it.hasAttr("selected"))
    }
    if (terms.map { it.id }.distinct().size != terms.size) throw AcademicProtocolException()
    return terms
}

internal data class AcademicCalendar(val mondays: Map<Int, String>, val warnings: List<String>)

/** Calendar dates are read from actual date-bearing rows, including week zero; they are never extrapolated. */
internal fun parseAcademicCalendar(html: String): AcademicCalendar {
    val doc = academicDocument(html)
    val dates = linkedMapOf<Int, String>()
    var invalid = false
    val datePattern = Regex("^(\\d{4})年(\\d{1,2})月(\\d{1,2})日?$")
    for (row in doc.select("tr")) {
        val cells = row.children().filter { it.tagName() in setOf("td", "th") }
        val week = cells.firstOrNull()?.text()?.trim()?.toIntOrNull()?.takeIf { it in 0..99 } ?: continue
        val rawDates = row.select("[title]").mapNotNull { datePattern.matchEntire(it.attr("title")) }
        if (rawDates.isEmpty()) continue
        val parsed = rawDates.mapNotNull { match -> runCatching {
            LocalDate(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt())
        }.getOrNull() }
        if (parsed.size != rawDates.size || parsed.firstOrNull()?.dayOfWeek != DayOfWeek.MONDAY) { invalid = true; continue }
        val monday = parsed.first().toString()
        if (dates[week]?.let { it != monday } == true) invalid = true else dates[week] = monday
    }
    if (invalid || dates.values.distinct().size != dates.size) return AcademicCalendar(emptyMap(), listOf("CALENDAR_INVALID"))
    return AcademicCalendar(dates.toMap(), if (dates.isEmpty()) listOf("CALENDAR_UNAVAILABLE") else emptyList())
}

internal fun parseAcademicSchedule(html: String, term: ScheduleTerm, calendar: AcademicCalendar, syncedAtMillis: Long): ScheduleSnapshot {
    val selected = parseAcademicTerms(html).singleOrNull { it.isDefault } ?: throw AcademicProtocolException()
    if (selected.id != term.id) throw AcademicProtocolException()
    val table = academicDocument(html).select("table#kbtable").singleOrNull() ?: throw AcademicProtocolException()
    val rows = table.select("tr").filter { row -> row.parents().firstOrNull { it.tagName() == "table" } === table }
    if (rows.isEmpty() || rows.size > 200) throw AcademicProtocolException()
    val grid = academicGrid(rows.toList())
    val dayColumns = mutableMapOf<Int, Int>()
    for (row in grid) {
        val header = row.mapNotNull { (column, cell) -> headerDay(clean(cell.element.text()))?.let { column to it } }.toMap()
        if (header.values.toSet() == (1..7).toSet()) { dayColumns.putAll(header); break }
    }
    if (dayColumns.values.toSet() != (1..7).toSet()) throw AcademicProtocolException()
    val firstDayColumn = dayColumns.keys.minOrNull() ?: throw AcademicProtocolException()
    val rowPeriods = grid.map { row -> row.filterKeys { it < firstDayColumn }.values.distinctBy { it.element }.mapNotNull {
        val text = clean(it.element.text())
        if (text.startsWith("第") && text.endsWith("节")) parseAcademicPeriods(text).takeIf { result -> result.valid }?.values else null
    }.firstOrNull().orEmpty() }
    if (rowPeriods.none { it.isNotEmpty() }) throw AcademicProtocolException()
    val courses = mutableListOf<ScheduleCourse>()
    val remarks = mutableListOf<String>()
    val warnings = calendar.warnings.toMutableSet()
    var complete = true
    for ((rowIndex, row) in grid.withIndex()) {
        if (row.values.any { clean(it.element.text()).startsWith("备注") }) {
            remarks += row.values.distinctBy { it.element }.filter { it.originRow == rowIndex && !clean(it.element.text()).startsWith("备注") }
                .map { clean(it.element.text()).take(1000) }.filter { it.isNotEmpty() }
            continue
        }
        if (rowPeriods[rowIndex].isEmpty()) continue
        for (cell in row.values.distinctBy { it.element }.filter { it.originRow == rowIndex }) {
            val days = (cell.originColumn until cell.originColumn + cell.columnSpan).mapNotNull { dayColumns[it] }.distinct()
            if (days.isEmpty()) continue
            if (days.size != 1 && clean(cell.element.text()).isNotEmpty()) { complete = false; warnings += "DAY_GRID_AMBIGUOUS"; continue }
            val day = days.singleOrNull() ?: continue
            val gridPeriods = (rowIndex until (rowIndex + cell.rowSpan).coerceAtMost(grid.size)).flatMap { rowPeriods[it] }.distinct().sorted()
            val details = cell.element.select("div.kbcontent").filter { clean(it.text()).isNotEmpty() }
            val blocks = if (details.isNotEmpty()) details else cell.element.select("div.kbcontent1").filter { clean(it.text()).isNotEmpty() }
            if (blocks.isEmpty()) {
                if (clean(cell.element.text()).isNotEmpty()) { complete = false; warnings += "COURSE_FORMAT_UNRECOGNIZED" }
                continue
            }
            for (block in blocks) for (raw in block.html().split(Regex("(?i)(?:^|<br\\s*/?>)\\s*[-－─]{4,}\\s*(?:<br\\s*/?>|$)"))) {
                val segment = raw.replace(Regex("(?i)^(?:\\s|<br\\s*/?>|&nbsp;)+"), "")
                if (segment.isBlank()) continue
                val fragment = Ksoup.parse(segment)
                val fonts = fragment.select("font[title]")
                val prefix = segment.split(Regex("(?i)<br\\b|<font\\b"), limit = 2).first()
                val title = clean(Ksoup.parse(prefix).text()).ifBlank {
                    fonts.firstOrNull { it.attr("title") in setOf("课程", "课程名称") }?.let { clean(it.text()) }.orEmpty()
                }
                if (title.isBlank() || title.length > 256) { complete = false; warnings += "COURSE_FORMAT_UNRECOGNIZED"; continue }
                val teacher = fonts.filter { it.attr("title") in setOf("老师", "教师") }.map { clean(it.text()) }.distinct().joinToString(" / ").take(256)
                val location = fonts.firstOrNull { it.attr("title") == "教室" }?.let { clean(it.text()) }?.take(256).orEmpty()
                val weekText = fonts.firstOrNull { it.attr("title") in setOf("周次(节次)", "周次", "上课周次") }?.let { clean(it.text()) }.orEmpty()
                val weeks = parseAcademicWeeks(weekText)
                if (!weeks.valid) { complete = false; warnings += "WEEK_FORMAT_UNRECOGNIZED" }
                val periodSuffix = Regex("\\[([^\\]]+)节\\]$").find(weekText)
                val explicitPeriods = periodSuffix?.let { parseAcademicPeriods(it.groupValues[1]) }
                val periods = if (explicitPeriods?.valid == true) explicitPeriods.values else gridPeriods
                if (periods.isEmpty() || explicitPeriods?.valid == false || periods.any { it !in gridPeriods }) {
                    complete = false; warnings += "PERIOD_FORMAT_UNRECOGNIZED"
                }
                val id = scheduleStableId("course", title, teacher, location, day.toString(), periods.joinToString(","), weeks.values.joinToString(","))
                courses += ScheduleCourse(id, title, teacher, location, day, periods, weeks.values, weekText.take(512))
                if (courses.size > 2000) throw AcademicProtocolException()
            }
        }
    }
    return ScheduleSnapshot(term, courses.distinctBy { it.id }, calendar.mondays, remarks.distinct(), warnings.toList(), syncedAtMillis, complete)
}

private data class AcademicCell(val element: Element, val originRow: Int, val originColumn: Int, val rowSpan: Int, val columnSpan: Int)

/** A bounded logical grid preserves th/td rowspan and colspan instead of treating physical cell index as weekday. */
private fun academicGrid(rows: List<Element>): List<Map<Int, AcademicCell>> {
    val result = List(rows.size) { mutableMapOf<Int, AcademicCell>() }
    for ((rowIndex, row) in rows.withIndex()) {
        var column = 0
        for (element in row.children().filter { it.tagName() in setOf("td", "th") }) {
            while (result[rowIndex].containsKey(column)) column++
            val rowSpan = element.attr("rowspan").ifBlank { "1" }.toIntOrNull()?.takeIf { it in 1..200 } ?: throw AcademicProtocolException()
            val colSpan = element.attr("colspan").ifBlank { "1" }.toIntOrNull()?.takeIf { it in 1..20 } ?: throw AcademicProtocolException()
            if (column + colSpan > 20 || rowIndex + rowSpan > rows.size) throw AcademicProtocolException()
            val cell = AcademicCell(element, rowIndex, column, rowSpan, colSpan)
            for (r in rowIndex until rowIndex + rowSpan) for (c in column until column + colSpan) {
                if (result[r].put(c, cell) != null) throw AcademicProtocolException()
            }
            column += colSpan
        }
    }
    return result.map { it.toMap() }
}

private fun headerDay(value: String): Int? = when (value.replace(Regex("\\s+"), "").substringBefore('(').removePrefix("星期").removePrefix("周")) {
    "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; "日", "天" -> 7
    else -> null
}

private fun clean(value: String): String = value.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
