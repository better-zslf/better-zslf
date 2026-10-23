package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.ApkUpdateSpec
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
import kotlinx.serialization.json.*

/**
 * Takes ownership of a dedicated fresh platform HttpClient. Only its engine is reused:
 * no school ApiClient, default headers, Auth, Cookies or Logging plugins are inherited.
 * Construction does zero HTTP. close() cancels/owns both clients and is idempotent.
 */
class GithubReleaseRepository(private val httpClient: HttpClient) : GithubReleaseSource {
    private val closed = MutableStateFlow(false)
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(httpClient.engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 15_000
        }
    }

    override suspend fun check(currentVersionName: String): GithubReleaseCheck {
        if (closed.value) return failure(ReleaseIssue.CLOSED)
        val current = StableVersion.parse(currentVersionName)?.takeIf { it.versionName == currentVersionName }
            ?: return failure(ReleaseIssue.UNKNOWN_CURRENT_VERSION)
        return try {
            val latest = fetch(GithubReleasePolicy.LATEST_API_URL, GithubReleasePolicy.MAX_JSON_BYTES)
            if (latest.status == 404 && latest.failure == null) return GithubReleaseCheck.NotPublished
            latest.failure?.let { return failure(it, latest.status.takeIf { code -> code > 0 }) }
            if (latest.status != 200) return failure(ReleaseIssue.HTTP, latest.status)
            val root = parseObject(latest.bytes) ?: return failure(ReleaseIssue.INVALID_RESPONSE)
            val draft = root.boolean("draft") ?: return failure(ReleaseIssue.INVALID_RESPONSE)
            val prerelease = root.boolean("prerelease") ?: return failure(ReleaseIssue.INVALID_RESPONSE)
            if (draft) return failure(ReleaseIssue.DRAFT)
            if (prerelease) return failure(ReleaseIssue.PRERELEASE)
            val tag = root.string("tag_name") ?: return failure(ReleaseIssue.INVALID_RESPONSE)
            val version = StableVersion.parse(tag) ?: return failure(ReleaseIssue.INVALID_RELEASE_VERSION)
            val notesValue = root["body"]
            val notes = if (notesValue == null || notesValue == JsonNull) "" else
                root.string("body") ?: return failure(ReleaseIssue.INVALID_RESPONSE)
            // Name is not used for trust/navigation, but reject a malformed provided value.
            if (root["name"] != null && root["name"] != JsonNull && root.string("name") == null) return failure(ReleaseIssue.INVALID_RESPONSE)
            val rawAssets = root["assets"] as? JsonArray ?: return failure(ReleaseIssue.INVALID_RESPONSE)
            if (rawAssets.size > 256) return failure(ReleaseIssue.INVALID_RESPONSE)
            val assets = rawAssets.map { raw ->
                val obj = raw as? JsonObject ?: return failure(ReleaseIssue.INVALID_RESPONSE)
                Asset(obj.string("name") ?: return failure(ReleaseIssue.INVALID_RESPONSE),
                    obj.string("browser_download_url") ?: return failure(ReleaseIssue.INVALID_RESPONSE),
                    obj.number("size") ?: return failure(ReleaseIssue.INVALID_RESPONSE))
            }
            val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            if (apks.isEmpty()) return failure(ReleaseIssue.NO_APK)
            if (apks.size != 1) return failure(ReleaseIssue.AMBIGUOUS_APK)
            val apk = apks.single()
            val filename = "better-zslf-${version.versionName}.apk"
            if (apk.name != filename || apk.size !in 1..GithubReleasePolicy.MAX_APK_BYTES ||
                !GithubReleasePolicy.isAssetUrl(apk.url, tag, filename)) return failure(ReleaseIssue.INVALID_ASSET)
            val checksums = assets.filter { it.name == "SHA256SUMS" }
            if (checksums.isEmpty()) return failure(ReleaseIssue.NO_CHECKSUM)
            if (checksums.size != 1) return failure(ReleaseIssue.INVALID_CHECKSUM)
            val sums = checksums.single()
            if (sums.size !in 1..GithubReleasePolicy.MAX_CHECKSUM_BYTES.toLong() ||
                !GithubReleasePolicy.isAssetUrl(sums.url, tag, "SHA256SUMS")) return failure(ReleaseIssue.INVALID_ASSET)
            val order = version.compareTo(current)
            if (order <= 0) return GithubReleaseCheck.UpToDate(version.versionName, installedAhead = order < 0)
            val checksum = fetch(sums.url, GithubReleasePolicy.MAX_CHECKSUM_BYTES, tag)
            checksum.failure?.let { return failure(it, checksum.status.takeIf { code -> code > 0 }) }
            if (checksum.status != 200) return failure(ReleaseIssue.HTTP, checksum.status)
            val bytes = checksum.bytes ?: return failure(ReleaseIssue.INVALID_CHECKSUM)
            if (bytes.size.toLong() != sums.size) return failure(ReleaseIssue.INVALID_CHECKSUM)
            val text = decode(bytes) ?: return failure(ReleaseIssue.INVALID_CHECKSUM)
            val sha = GithubReleasePolicy.checksum(text, filename) ?: return failure(ReleaseIssue.INVALID_CHECKSUM)
            val spec = ApkUpdateSpec(version.versionName, apk.url, sha, apk.size)
            if (!GithubReleasePolicy.isInstallSpec(spec)) return failure(ReleaseIssue.INVALID_ASSET)
            GithubReleaseCheck.Available(GithubReleaseCandidate(version.versionName,
                notes.filter { it >= ' ' || it == '\n' || it == '\t' }.take(32_000), spec))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpRequestTimeoutException) {
            failure(ReleaseIssue.TIMEOUT)
        } catch (_: ConnectTimeoutException) {
            failure(ReleaseIssue.TIMEOUT)
        } catch (_: SocketTimeoutException) {
            failure(ReleaseIssue.TIMEOUT)
        } catch (_: Exception) {
            // Ktor exception messages can include URLs. Never propagate/log them.
            failure(ReleaseIssue.NETWORK)
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        client.coroutineContext.cancel()
        client.close()
        httpClient.coroutineContext.cancel()
        httpClient.close()
    }

    private class Asset(val name: String, val url: String, val size: Long) {
        override fun toString(): String = "ReleaseAsset(redacted)"
    }
    private class Fetch(val status: Int, val bytes: ByteArray? = null, val redirect: String? = null, val failure: ReleaseIssue? = null) {
        override fun toString(): String = "ReleaseFetch(redacted)"
    }

    /** Only checksum downloads may redirect, at most three times, never to arbitrary hosts. */
    private suspend fun fetch(initial: String, limit: Int, tag: String? = null): Fetch {
        var url = initial
        val visited = mutableSetOf<String>()
        for (hop in 0..3) {
            if (!visited.add(url)) return Fetch(0, failure = ReleaseIssue.REDIRECT_BLOCKED)
            val response = client.prepareGet(url) {
                header(HttpHeaders.Accept, if (tag == null) "application/vnd.github+json" else "text/plain, application/octet-stream")
                header(HttpHeaders.AcceptEncoding, "identity")
                header(HttpHeaders.UserAgent, "better-zslf-updater")
                header("X-GitHub-Api-Version", "2022-11-28")
            }.execute { received ->
                val status = received.status.value
                val channel = received.bodyAsChannel()
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = received.headers[HttpHeaders.Location]
                    channel.cancel(null)
                    return@execute Fetch(status, redirect = location, failure = if (location == null) ReleaseIssue.REDIRECT_BLOCKED else null)
                }
                if (status != 200) { channel.cancel(null); return@execute Fetch(status) }
                val contentLength = received.headers[HttpHeaders.ContentLength]
                if (contentLength != null && (contentLength.toLongOrNull() == null || contentLength.toLong() < 0)) {
                    channel.cancel(null); return@execute Fetch(status, failure = ReleaseIssue.INVALID_RESPONSE)
                }
                if (contentLength?.toLongOrNull()?.let { it > limit } == true) {
                    channel.cancel(null); return@execute Fetch(status, failure = ReleaseIssue.RESPONSE_TOO_LARGE)
                }
                // execute{...} retains a streaming response: no automatic full-body save.
                val bytes = ByteArray(limit + 1)
                var used = 0
                while (used <= limit) {
                    val count = channel.readAvailable(bytes, used, bytes.size - used)
                    if (count < 0) break
                    used += count
                    if (used > limit) { channel.cancel(null); return@execute Fetch(status, failure = ReleaseIssue.RESPONSE_TOO_LARGE) }
                }
                Fetch(status, bytes.copyOf(used))
            }
            response.failure?.let { return response }
            val location = response.redirect ?: return response
            if (tag == null) return Fetch(response.status, failure = ReleaseIssue.HTTP)
            if (hop == 3 || !GithubReleasePolicy.isChecksumRedirect(location, tag)) return Fetch(response.status, failure = ReleaseIssue.REDIRECT_BLOCKED)
            url = location
        }
        return Fetch(0, failure = ReleaseIssue.REDIRECT_BLOCKED)
    }

    private fun parseObject(bytes: ByteArray?): JsonObject? = try {
        bytes?.let(::decode)?.let { json.parseToJsonElement(it) as? JsonObject }
    } catch (_: Exception) { null }
    private fun decode(bytes: ByteArray): String? = try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: Exception) { null }
    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.boolean(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun failure(issue: ReleaseIssue, status: Int? = null) = GithubReleaseCheck.Failed(issue, status)
}
