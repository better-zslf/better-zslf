package io.github.betterzslf.feature.profile

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Page-owned fresh PUBLIC client, never ApiClient. Only its engine is reused, without inherited
 * Auth, Cookies, DefaultRequest or Logging plugins. Construction does zero HTTP; no retries/disk.
 */
class AvatarByteRepository(private val owner: HttpClient) : AvatarSource {
    private val closed = MutableStateFlow(false)
    private val client = HttpClient(owner.engine) {
        expectSuccess = false; followRedirects = false
        install(HttpTimeout) { requestTimeoutMillis = 12_000; connectTimeoutMillis = 8_000; socketTimeoutMillis = 12_000 }
    }
    override suspend fun load(resource: ProfilePhotoMetadata.Resource): AvatarLoadResult {
        if (closed.value) return AvatarLoadResult.Failed(AvatarIssue.CLOSED)
        if (!AvatarImagePolicy.isAllowedUrl(resource.url)) return AvatarLoadResult.Failed(AvatarIssue.UNSUPPORTED_PATH)
        return try {
            client.prepareGet(resource.url) {
                header(HttpHeaders.Accept, "image/png, image/jpeg")
                header(HttpHeaders.AcceptEncoding, "identity")
            }.execute { response ->
                val channel = response.bodyAsChannel()
                if (response.status.value in 300..399) { channel.cancel(null); return@execute AvatarLoadResult.Failed(AvatarIssue.REDIRECT) }
                if (response.status.value != 200) { channel.cancel(null); return@execute AvatarLoadResult.Failed(AvatarIssue.HTTP) }
                val declared = response.headers[HttpHeaders.ContentLength]
                if (declared != null && (declared.toLongOrNull() == null || declared.toLong() < 0)) {
                    channel.cancel(null); return@execute AvatarLoadResult.Failed(AvatarIssue.MALFORMED_IMAGE)
                }
                if (declared?.toLongOrNull()?.let { it > AvatarImagePolicy.MAX_BYTES } == true) {
                    channel.cancel(null); return@execute AvatarLoadResult.Failed(AvatarIssue.TOO_LARGE)
                }
                val buffer = ByteArray(AvatarImagePolicy.MAX_BYTES + 1)
                try {
                    var used = 0
                    while (used <= AvatarImagePolicy.MAX_BYTES) {
                        val count = channel.readAvailable(buffer, used, buffer.size - used)
                        if (count < 0) break
                        used += count
                        if (used > AvatarImagePolicy.MAX_BYTES) { channel.cancel(null); return@execute AvatarLoadResult.Failed(AvatarIssue.TOO_LARGE) }
                    }
                    val encoded = buffer.copyOf(used)
                    when (val check = AvatarImagePolicy.inspect(encoded)) {
                        is AvatarHeaderCheck.Rejected -> { encoded.fill(0); AvatarLoadResult.Failed(check.issue) }
                        is AvatarHeaderCheck.Accepted -> AvatarLoadResult.Image(AvatarBytes(encoded, check.header.width, check.header.height, check.header.format))
                    }
                } finally { buffer.fill(0) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: HttpRequestTimeoutException) { AvatarLoadResult.Failed(AvatarIssue.TIMEOUT) }
        catch (_: ConnectTimeoutException) { AvatarLoadResult.Failed(AvatarIssue.TIMEOUT) }
        catch (_: SocketTimeoutException) { AvatarLoadResult.Failed(AvatarIssue.TIMEOUT) }
        catch (_: Exception) { AvatarLoadResult.Failed(AvatarIssue.NETWORK) }
    }
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.coroutineContext.cancel(); client.close()
        owner.coroutineContext.cancel(); owner.close()
    }
    override fun toString(): String = "AvatarByteRepository(redacted)"
}
