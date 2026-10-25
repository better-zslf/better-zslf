package io.github.betterzslf.feature.update

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.platform.ApkUpdateSpec

/** Stable SemVer only. Numeric components are compared as digit strings, never narrowed. */
class StableVersion private constructor(val versionName: String, private val parts: List<String>) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int {
        for (index in parts.indices) {
            val size = parts[index].length.compareTo(other.parts[index].length)
            if (size != 0) return size
            val digits = parts[index].compareTo(other.parts[index])
            if (digits != 0) return digits
        }
        return 0
    }
    override fun toString(): String = "StableVersion($versionName)"
    companion object {
        private val pattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
        fun parse(value: String): StableVersion? {
            val normalized = value.removePrefix("v")
            if (normalized.isEmpty() || normalized.length > 128) return null
            val match = pattern.matchEntire(normalized) ?: return null
            return StableVersion(normalized, (1..3).map { match.groupValues[it] })
        }
    }
}

enum class ReleaseIssue {
    UNKNOWN_CURRENT_VERSION, INVALID_RESPONSE, INVALID_RELEASE_VERSION, DRAFT, PRERELEASE,
    NO_APK, AMBIGUOUS_APK, NO_CHECKSUM, INVALID_ASSET, INVALID_CHECKSUM,
    RESPONSE_TOO_LARGE, REDIRECT_BLOCKED, HTTP, NETWORK, TIMEOUT, CLOSED, CANCELLED,
}

fun releaseIssueMessage(issue: ReleaseIssue): String = when (issue) {
    ReleaseIssue.UNKNOWN_CURRENT_VERSION -> AppText.Updates.INSTALLED_VERSION_FORMAT_UNKNOWN_REVIEW_RELEASE_PAGE
    ReleaseIssue.INVALID_RESPONSE -> AppText.Updates.INVALID_RELEASE_INFORMATION_FORMAT_RETRY_LATER
    ReleaseIssue.INVALID_RELEASE_VERSION -> AppText.Updates.RELEASE_VERSION_NOT_SUPPORTED_STABLE_VERSION
    ReleaseIssue.DRAFT -> AppText.Updates.DRAFT_RELEASE_DOWNLOAD_DISABLED
    ReleaseIssue.PRERELEASE -> AppText.Updates.PRERELEASE_AUTOMATIC_UPDATE_DISABLED
    ReleaseIssue.NO_APK -> AppText.Updates.MATCHING_ANDROID_APK_MISSING
    ReleaseIssue.AMBIGUOUS_APK -> AppText.Updates.MULTIPLE_APKS_REVIEW_RELEASE_PAGE
    ReleaseIssue.NO_CHECKSUM -> AppText.Updates.SHA256SUMS_ASSET_MISSING
    ReleaseIssue.INVALID_ASSET -> AppText.Updates.RELEASE_ASSET_URL_NAME_OR_SIZE_INVALID
    ReleaseIssue.INVALID_CHECKSUM -> AppText.Updates.APK_SHA256_INFORMATION_MISSING_OR_INVALID
    ReleaseIssue.RESPONSE_TOO_LARGE -> AppText.Updates.RELEASE_INFORMATION_READ_LIMIT_EXCEEDED
    ReleaseIssue.REDIRECT_BLOCKED -> AppText.Updates.CHECKSUM_REDIRECT_NOT_ACCEPTED
    ReleaseIssue.HTTP -> AppText.Updates.GITHUB_UPDATE_SERVICE_TEMPORARILY_FAILED_RETRY_LATER
    ReleaseIssue.NETWORK -> AppText.Updates.UPDATE_CHECK_NETWORK_FAILED_RETRY_LATER
    ReleaseIssue.TIMEOUT -> AppText.Updates.UPDATE_CHECK_TIMEOUT_RETRY_LATER
    ReleaseIssue.CLOSED -> AppText.Updates.UPDATE_CHECK_CLOSED_NOTICE
    ReleaseIssue.CANCELLED -> AppText.Updates.UPDATE_CHECK_CANCELLED_NOTICE
}

data class GithubReleaseCandidate(val versionName: String, val notes: String, val apk: ApkUpdateSpec) {
    override fun toString(): String = "GithubReleaseCandidate(redacted)"
}

sealed interface GithubReleaseCheck {
    data object NotPublished : GithubReleaseCheck
    data class UpToDate(val latestVersionName: String, val installedAhead: Boolean = false) : GithubReleaseCheck
    data class Available(val candidate: GithubReleaseCandidate) : GithubReleaseCheck
    data class Failed(val reason: ReleaseIssue, val httpStatus: Int? = null) : GithubReleaseCheck
}

interface GithubReleaseSource { suspend fun check(currentVersionName: String): GithubReleaseCheck }

/** Only this project's public releases; server-provided HTML links never drive browser navigation. */
object GithubReleasePolicy {
    const val RELEASES_URL = "https://github.com/better-zslf/better-zslf/releases"
    const val LATEST_API_URL = "https://api.github.com/repos/better-zslf/better-zslf/releases/latest"
    const val ASSET_PREFIX = "https://github.com/better-zslf/better-zslf/releases/download/"
    const val MAX_APK_BYTES = 250L * 1024 * 1024
    const val MAX_JSON_BYTES = 1024 * 1024
    const val MAX_CHECKSUM_BYTES = 64 * 1024
    private val shaPattern = Regex("^[0-9A-Fa-f]{64}$")
    private val encodedControl = Regex("%(?:00|0a|0d)", RegexOption.IGNORE_CASE)
    private val encodedPathEscape = Regex("%(?:2e|2f|5c)", RegexOption.IGNORE_CASE)

    fun isAssetUrl(url: String, tag: String, name: String): Boolean {
        if (url.length > 2048 || !url.startsWith(ASSET_PREFIX) || url.any { it <= ' ' || it == '\\' || it == '?' || it == '#' }) return false
        val version = StableVersion.parse(tag) ?: return false
        if (tag != "v${version.versionName}" || name !in setOf("better-zslf-${version.versionName}.apk", "SHA256SUMS")) return false
        return url == "$ASSET_PREFIX$tag/$name"
    }

    internal fun isChecksumRedirect(url: String, tag: String): Boolean {
        if (isAssetUrl(url, tag, "SHA256SUMS")) return true
        if (url.length > 8192 || !url.startsWith("https://") || url.any { it <= ' ' || it == '\\' || it == '#' } || encodedControl.containsMatchIn(url)) return false
        val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?')
        if (authority !in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")) return false
        val path = url.removePrefix("https://$authority").substringBefore('?')
        if (!path.startsWith('/') || encodedPathEscape.containsMatchIn(path)) return false
        return path.split('/').none { it == "." || it == ".." }
    }

    fun isInstallSpec(spec: ApkUpdateSpec): Boolean {
        val version = StableVersion.parse(spec.versionName) ?: return false
        if (version.versionName != spec.versionName || !shaPattern.matches(spec.sha256) || spec.sizeBytes !in 1..MAX_APK_BYTES) return false
        val name = "better-zslf-${spec.versionName}.apk"
        return isAssetUrl(spec.downloadUrl, "v${spec.versionName}", name)
    }

    internal fun checksum(text: String, name: String): String? {
        val pattern = Regex("^([0-9A-Fa-f]{64}) [ *]([0-9A-Za-z._+-]+)$")
        val entries = mutableMapOf<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.removeSuffix("\r")
            if (line.isBlank()) continue
            val match = pattern.matchEntire(line) ?: return null
            val filename = match.groupValues[2]
            if (filename == "." || filename == ".." || entries.put(filename, match.groupValues[1].lowercase()) != null) return null
        }
        return entries[name]
    }
}
