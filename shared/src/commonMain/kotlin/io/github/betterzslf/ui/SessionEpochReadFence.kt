package io.github.betterzslf.ui

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Page-local read admission/publication only; it owns neither an account nor a native runtime. */
internal class SessionEpochReadFence(
    private val expected: Long,
    private val currentRevision: () -> Long,
    private val capabilityIsCurrent: () -> Boolean = { true },
) {
    private var mounted = true
    fun isCurrent(): Boolean = mounted && currentRevision() == expected &&
        runCatching(capabilityIsCurrent).getOrDefault(false)

    suspend fun <T> read(load: suspend () -> ApiResult<T>): ApiResult<T> {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) return ApiResult.Failure(ApiError.Authentication())
        val result = load()
        currentCoroutineContext().ensureActive()
        return if (isCurrent()) result else ApiResult.Failure(ApiError.Authentication())
    }

    suspend fun <T> readCurrent(load: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Page read retired")
        val result = load()
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Page read retired")
        return result
    }

    fun close() { mounted = false }
    override fun toString(): String = "SessionEpochReadFence(redacted)"
}
