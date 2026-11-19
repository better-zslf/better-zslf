package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.ApkUpdateSpec

enum class ReleaseIssue {
    UNKNOWN_CURRENT_VERSION, INVALID_RESPONSE, INVALID_RELEASE_VERSION, DRAFT, PRERELEASE,
    NO_APK, AMBIGUOUS_APK, NO_CHECKSUM, INVALID_ASSET, INVALID_CHECKSUM,
    RESPONSE_TOO_LARGE, REDIRECT_BLOCKED, HTTP, NETWORK, TIMEOUT, CLOSED, CANCELLED,
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

