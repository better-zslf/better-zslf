package io.github.betterzslf.feature.update

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
    ReleaseIssue.UNKNOWN_CURRENT_VERSION -> "当前应用版本格式未知，请前往发布页核对。"
    ReleaseIssue.INVALID_RESPONSE -> "发布信息格式异常，请稍后重试。"
    ReleaseIssue.INVALID_RELEASE_VERSION -> "发布版本不是受支持的稳定版本。"
    ReleaseIssue.DRAFT -> "发布信息仍为草稿，暂不下载。"
    ReleaseIssue.PRERELEASE -> "此版本为预发布版，暂不自动更新。"
    ReleaseIssue.NO_APK -> "此发布尚未提供匹配的 Android APK。"
    ReleaseIssue.AMBIGUOUS_APK -> "此发布包含多个 APK，请前往发布页核对。"
    ReleaseIssue.NO_CHECKSUM -> "此发布尚未提供 SHA256SUMS 校验文件。"
    ReleaseIssue.INVALID_ASSET -> "发布资产地址、名称或大小不符合更新要求。"
    ReleaseIssue.INVALID_CHECKSUM -> "APK 的 SHA-256 校验信息缺失或异常。"
    ReleaseIssue.RESPONSE_TOO_LARGE -> "发布信息超过读取上限。"
    ReleaseIssue.REDIRECT_BLOCKED -> "校验文件重定向不符合更新要求。"
    ReleaseIssue.HTTP -> "GitHub 更新服务暂时异常，请稍后重试。"
    ReleaseIssue.NETWORK -> "更新检查网络连接失败，请稍后重试。"
    ReleaseIssue.TIMEOUT -> "更新检查超时，请稍后重试。"
    ReleaseIssue.CLOSED -> "更新检查已关闭。"
    ReleaseIssue.CANCELLED -> "已取消更新检查。"
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
