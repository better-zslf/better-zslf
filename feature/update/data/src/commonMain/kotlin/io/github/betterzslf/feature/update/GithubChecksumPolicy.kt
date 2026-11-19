package io.github.betterzslf.feature.update

/** Transport-only checksum parsing and redirect gates. No widening of the product asset policy. */
internal object GithubChecksumPolicy {
    private val encodedControl = Regex("%(?:00|0a|0d)", RegexOption.IGNORE_CASE)
    private val encodedPathEscape = Regex("%(?:2e|2f|5c)", RegexOption.IGNORE_CASE)

    fun isChecksumRedirect(url: String, tag: String, policy: ProductUpdatePolicy = ProductUpdatePolicy.Standard): Boolean {
        // CDN redirects are admissible only for a valid stable tag of this product.
        val version = StableVersion.parse(tag, policy) ?: return false
        if (tag != "v${version.versionName}") return false
        if (GithubReleasePolicy.isAssetUrl(url, tag, "SHA256SUMS", policy)) return true
        if (url.length > 8192 || !url.startsWith("https://") || url.any { it <= ' ' || it == '\\' || it == '#' } || encodedControl.containsMatchIn(url)) return false
        val authority = url.removePrefix("https://").substringBefore('/').substringBefore('?')
        if (authority !in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")) return false
        val path = url.removePrefix("https://$authority").substringBefore('?')
        if (!path.startsWith('/') || encodedPathEscape.containsMatchIn(path)) return false
        return path.split('/').none { it == "." || it == ".." }
    }

    fun checksum(text: String, name: String): String? {
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
