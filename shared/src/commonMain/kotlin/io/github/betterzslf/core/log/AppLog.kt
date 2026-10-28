package io.github.betterzslf.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/**
 * Local-only debug log. It stays completely silent (and keeps no strings) until the developer option
 * is switched on, and it never leaves the device: no upload, no file, no platform logging call.
 *
 * All writes are appends to an immutable snapshot guarded by a single monitor; readers always get a
 * fresh read-only copy, so logging can never disturb UI state or throw into a caller.
 */
object AppLog {
    /** Enough context for a debugging session without growing the process unbounded. */
    const val MAX_ENTRIES: Int = 2000

    private val lock = Any()
    private var entries: List<LogEntry> = emptyList()
    private val enabledFlow = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = enabledFlow.asStateFlow()
    /** Emits a new read-only snapshot whenever the buffer changes, so the viewer recomposes. */
    private val entriesFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val records: StateFlow<List<LogEntry>> = entriesFlow.asStateFlow()

    private var clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }

    /** Test seam: production always uses the monotonic-free wall clock above. */
    internal fun useClock(source: () -> Long) { synchronized(lock) { clock = source } }

    fun setEnabled(value: Boolean) {
        if (!value) synchronized(lock) { entries = emptyList() }
        entriesFlow.value = snapshot()
        enabledFlow.value = value
    }

    val isEnabled: Boolean get() = enabledFlow.value

    fun debug(tag: String, message: String) = record(LogLevel.DEBUG, tag, message)
    fun info(tag: String, message: String) = record(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = record(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = record(LogLevel.ERROR, tag, message)

    fun section(title: String) = record(LogLevel.INFO, "section", title)

    /** Structured field list; every value passes the same redaction as a free-form message. */
    fun event(tag: String, name: String, fields: Map<String, String> = emptyMap()) {
        if (!isEnabled) return
        val rendered = fields.entries.joinToString(separator = " ") { (key, value) -> "$key=${LogRedaction.apply(value)}" }
        record(LogLevel.INFO, tag, if (rendered.isEmpty()) name else "$name $rendered")
    }

    /** HTTP summary only: no query string, body, headers or credentials. */
    fun http(method: String, path: String, status: Int?, durationMillis: Long, bytes: Long? = null) {
        if (!isEnabled) return
        val cleanPath = path.substringBefore('?').substringBefore('#')
        val result = status?.toString() ?: "failed"
        val size = bytes?.let { " ${it}B" } ?: ""
        record(LogLevel.INFO, "http", "$method $cleanPath -> $result ${durationMillis}ms$size")
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { entries }

    fun clear() {
        synchronized(lock) { entries = emptyList() }
        entriesFlow.value = emptyList()
    }

    /** Plain text for a bug report; timestamps are UTC so pasted logs stay comparable. */
    fun exportText(): String {
        val copy = snapshot()
        if (copy.isEmpty()) return ""
        return buildString {
            for (entry in copy) {
                append(formatLogTimestamp(entry.timestampMillis))
                append("  ")
                append(entry.level.name.padEnd(5))
                append("  ")
                append(entry.tag)
                append("  ")
                appendLine(entry.message)
            }
        }
    }

    private fun record(level: LogLevel, tag: String, message: String) {
        if (!isEnabled) return
        val safeTag = LogRedaction.apply(tag).take(48)
        val safeMessage = LogRedaction.apply(message).take(4_000)
        val entry = runCatching { LogEntry(level, safeTag, safeMessage, clock()) }.getOrNull() ?: return
        synchronized(lock) {
            val next = entries + entry
            entries = if (next.size > MAX_ENTRIES) next.subList(next.size - MAX_ENTRIES, next.size) else next
            entriesFlow.value = entries
        }
    }

    /** UTC `yyyy-MM-dd HH:mm:ss.SSS`, implemented without java.time so it works on every target. */
    fun formatLogTimestamp(millis: Long): String {
        var remaining = millis
        val days = Math.floorDiv(millis, 86_400_000L)
        remaining -= days * 86_400_000L
        val hours = (remaining / 3_600_000L).toInt()
        remaining -= hours * 3_600_000L
        val minutes = (remaining / 60_000L).toInt()
        remaining -= minutes * 60_000L
        val seconds = (remaining / 1_000L).toInt()
        val millisPart = (remaining - seconds * 1_000L).toInt()
        val (year, month, day) = civilFromDays(days)
        return buildString {
            append(year.toString().padStart(4, '0')); append('-')
            append(month.toString().padStart(2, '0')); append('-')
            append(day.toString().padStart(2, '0')); append(' ')
            append(hours.toString().padStart(2, '0')); append(':')
            append(minutes.toString().padStart(2, '0')); append(':')
            append(seconds.toString().padStart(2, '0')); append('.')
            append(millisPart.toString().padStart(3, '0'))
        }
    }

    /** Howard Hinnant's days-to-civil algorithm; no timezone database needed for UTC output. */
    private fun civilFromDays(days: Long): Triple<Long, Int, Int> {
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        return Triple(if (m <= 2) y + 1 else y, m, d)
    }
}

/** Top-level so UI code can format a stored entry without touching the singleton's clock. */
fun formatLogTimestamp(millis: Long): String = AppLog.formatLogTimestamp(millis)
