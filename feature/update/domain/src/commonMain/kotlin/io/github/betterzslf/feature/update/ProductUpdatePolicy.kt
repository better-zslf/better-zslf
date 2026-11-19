package io.github.betterzslf.feature.update

/** Remote automatic updates remain stable-only; installed RCs do not open a remote RC channel. */
enum class ProductUpdateChannel { STABLE }

/**
 * Immutable trust configuration supplied by the product composition root, never by settings,
 * release JSON or a download spec. This is deliberately not serializable and has no mutable/global
 * current policy. Only GitHub owner/repository names are configurable, not URLs, headers or tokens.
 * Android always requires the incoming APK's signer set to equal the currently installed signer set;
 * a product policy cannot disable that check or the system installer's signature verification.
 */
class ProductUpdatePolicy(
    val repository: String,
    val apkAssetPrefix: String,
    val androidApplicationId: String,
    val versionNameSuffix: String,
    val channel: ProductUpdateChannel = ProductUpdateChannel.STABLE,
) {
    init {
        require(repository.matches(Regex("""[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}""")) &&
            repository.substringAfter('/') !in setOf(".", "..")) { "Invalid product update repository" }
        require(apkAssetPrefix.matches(Regex("""[A-Za-z0-9][A-Za-z0-9._-]{0,79}-"""))) { "Invalid product APK asset prefix" }
        require(androidApplicationId.length <= 255 &&
            androidApplicationId.matches(Regex("""[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+"""))) { "Invalid product application ID" }
        require(versionNameSuffix.isEmpty() ||
            versionNameSuffix.matches(Regex("""-[a-z][a-z0-9]{0,15}""")) &&
            !versionNameSuffix.matches(Regex("""-(?:rc|alpha|beta)[0-9]*"""))) { "Invalid product version suffix" }
    }

    val releasesUrl: String = "https://github.com/$repository/releases"
    val latestApiUrl: String = when (channel) {
        ProductUpdateChannel.STABLE -> "https://api.github.com/repos/$repository/releases/latest"
    }
    val assetUrlPrefix: String = "https://github.com/$repository/releases/download/"

    /** Naming only; callers must still validate the stable version and the exact asset URL. */
    fun apkAssetName(versionName: String): String = "$apkAssetPrefix$versionName.apk"

    override fun toString(): String = "ProductUpdatePolicy(redacted)"

    companion object {
        /** Public product default. Other products explicitly supply their configuration at their root. */
        val Standard: ProductUpdatePolicy = ProductUpdatePolicy(
            repository = "better-zslf/better-zslf",
            apkAssetPrefix = "better-zslf-",
            androidApplicationId = "io.github.betterzslf",
            versionNameSuffix = "",
        )
    }
}
