package io.github.betterzslf.core.log

/** Ordered so a filter can compare severity without extra tables. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * One immutable log record. [message] is already redacted by [AppLog]; nothing here holds
 * credentials, request bodies or personal identifiers.
 */
data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val timestampMillis: Long,
)
