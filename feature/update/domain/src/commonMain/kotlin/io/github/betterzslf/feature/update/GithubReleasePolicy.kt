package io.github.betterzslf.feature.update

import io.github.betterzslf.platform.ApkUpdateSpec

/** Product-pinned public releases; server-provided HTML links never drive browser navigation. */
object GithubReleasePolicy {
    // Compatibility aliases are always Standard, not a mutable process-wide selected product.
    const val RELEASES_URL = "https://github.com/better-zslf/better-zslf/releases"
    const val LATEST_API_URL = "https://api.github.com/repos/better-zslf/better-zslf/releases/latest"
    const val ASSET_PREFIX = "https://github.com/better-zslf/better-zslf/releases/download/"
    const val MAX_APK_BYTES = 250L * 1024 * 1024
    const val MAX_JSON_BYTES = 1024 * 1024
    const val MAX_CHECKSUM_BYTES = 64 * 1024
    private val shaPattern = Regex("^[0-9A-Fa-f]{64}$")
    fun isAssetUrl(url: String, tag: String, name: String, policy: ProductUpdatePolicy = ProductUpdatePolicy.Standard): Boolean {
        if (url.length > 2048 || !url.startsWith(policy.assetUrlPrefix) || url.any { it <= ' ' || it == '\\' || it == '?' || it == '#' }) return false
        val version = StableVersion.parse(tag, policy) ?: return false
        if (tag != "v${version.versionName}" || name !in setOf(policy.apkAssetName(version.versionName), "SHA256SUMS")) return false
        return url == "${policy.assetUrlPrefix}$tag/$name"
    }

    fun isInstallSpec(spec: ApkUpdateSpec, policy: ProductUpdatePolicy = ProductUpdatePolicy.Standard): Boolean {
        val version = StableVersion.parse(spec.versionName, policy) ?: return false
        if (version.versionName != spec.versionName || !shaPattern.matches(spec.sha256) || spec.sizeBytes !in 1..MAX_APK_BYTES) return false
        val name = policy.apkAssetName(spec.versionName)
        return isAssetUrl(spec.downloadUrl, "v${spec.versionName}", name, policy)
    }
}
