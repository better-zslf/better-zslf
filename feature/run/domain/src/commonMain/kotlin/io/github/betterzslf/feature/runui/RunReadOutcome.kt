package io.github.betterzslf.feature.runui

/** Finite read failures, with no transport messages, credentials or exception details. */
enum class RunReadIssue { AUTHENTICATION, BUSINESS, VALIDATION, TIMEOUT, NETWORK, MALFORMED, HTTP, STORAGE }

sealed interface RunReadOutcome<out T> {
    data class Success<T>(val data: T) : RunReadOutcome<T>
    data class Failure(val issue: RunReadIssue) : RunReadOutcome<Nothing>
}
