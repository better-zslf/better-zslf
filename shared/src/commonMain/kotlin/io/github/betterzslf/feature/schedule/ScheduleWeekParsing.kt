package io.github.betterzslf.feature.schedule

internal data class AcademicNumbers(val values: List<Int>, val valid: Boolean)

/** Only bounded, fully consumed numeric/range/parity grammar is accepted; unknown never means every week. */
internal fun parseAcademicWeeks(value: String): AcademicNumbers {
    if (value.isBlank() || value.length > 512) return AcademicNumbers(emptyList(), false)
    var text = normalizeAcademicNumbers(value).replace(Regex("\\s+"), "")
        .removePrefix("周次:").removePrefix("周次：").removePrefix("第")
    val periodSuffix = Regex("\\[([^\\]]+)节\\]$").find(text)
    if (periodSuffix != null) text = text.removeRange(periodSuffix.range)
    text = text.replace(Regex("\\(([单双])周?\\)"), "$1")
        .replace("(周)", "").replace("周", "")
    val parityCount = text.count { it == '单' || it == '双' }
    val globalParity = text.lastOrNull()?.takeIf { parityCount == 1 && (it == '单' || it == '双') }
    if (globalParity != null) text = text.dropLast(1)
    val tokens = text.split(',')
    if (tokens.isEmpty() || tokens.size > 100 || tokens.any { it.isEmpty() }) return AcademicNumbers(emptyList(), false)
    val result = mutableSetOf<Int>()
    for (token in tokens) {
        val match = Regex("^([0-9]{1,2})(?:[-~至]([0-9]{1,2}))?([单双])?$").matchEntire(token)
            ?: return AcademicNumbers(emptyList(), false)
        val first = match.groupValues[1].toIntOrNull() ?: return AcademicNumbers(emptyList(), false)
        val last = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: first
        if (first !in 0..99 || last !in first..99) return AcademicNumbers(emptyList(), false)
        val parity = match.groupValues[3].firstOrNull() ?: globalParity
        result += (first..last).filter { parity == null || (parity == '单' && it % 2 == 1) || (parity == '双' && it % 2 == 0) }
    }
    return AcademicNumbers(result.sorted(), result.isNotEmpty())
}

internal fun parseAcademicPeriods(value: String): AcademicNumbers {
    if (value.isBlank() || value.length > 128) return AcademicNumbers(emptyList(), false)
    val text = normalizeAcademicNumbers(value).replace(Regex("\\s+"), "")
        .removePrefix("[").removeSuffix("]").removePrefix("第").removeSuffix("节")
    val result = mutableSetOf<Int>()
    for (token in text.split(',')) {
        val ends = token.split(Regex("[-~至]"))
        if (ends.size !in 1..2) return AcademicNumbers(emptyList(), false)
        val first = academicPeriodNumber(ends[0]) ?: return AcademicNumbers(emptyList(), false)
        val last = if (ends.size == 2) academicPeriodNumber(ends[1]) ?: return AcademicNumbers(emptyList(), false) else first
        if (first !in 1..12 || last !in first..12) return AcademicNumbers(emptyList(), false)
        result += first..last
    }
    return AcademicNumbers(result.sorted(), result.isNotEmpty())
}

private fun academicPeriodNumber(text: String): Int? = text.toIntOrNull() ?: when (text) {
    "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6
    "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10; "十一" -> 11; "十二" -> 12
    else -> null
}

private fun normalizeAcademicNumbers(value: String): String = buildString {
    value.forEach { append(when (it) {
        in '０'..'９' -> '0' + (it - '０')
        '（' -> '('; '）' -> ')'; '［' -> '['; '］' -> ']'
        '，', '、', '；', ';' -> ','
        '－', '—', '–' -> '-'; '～' -> '~'
        else -> it
    }) }
}
