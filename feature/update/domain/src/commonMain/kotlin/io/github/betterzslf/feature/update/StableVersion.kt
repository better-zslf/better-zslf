package io.github.betterzslf.feature.update

/** Remote parse stays stable-only; installed RCs enter solely through parseInstalled. No numeric narrowing. */
class StableVersion private constructor(
    val versionName: String,
    private val parts: List<String>,
    private val rcNumber: String? = null,
) : Comparable<StableVersion> {
    override fun compareTo(other: StableVersion): Int {
        for (index in parts.indices) {
            val size = parts[index].length.compareTo(other.parts[index].length)
            if (size != 0) return size
            val digits = parts[index].compareTo(other.parts[index])
            if (digits != 0) return digits
        }
        // Prerelease precedence applies only after every numeric component is equal.
        if (rcNumber == null) return if (other.rcNumber == null) 0 else 1
        if (other.rcNumber == null) return -1
        val size = rcNumber.length.compareTo(other.rcNumber.length)
        return if (size != 0) size else rcNumber.compareTo(other.rcNumber)
    }
    override fun toString(): String = "StableVersion($versionName)"
    companion object {
        private val pattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
        fun parse(value: String, policy: ProductUpdatePolicy = ProductUpdatePolicy.Standard): StableVersion? {
            val normalized = value.removePrefix("v")
            if (normalized.isEmpty() || normalized.length > 128) return null
            // Remote tags/APK specs must name this product exactly. Numeric iOS marketing
            // versions are accepted only by parseInstalled, never as another product's release.
            if (!normalized.endsWith(policy.versionNameSuffix)) return null
            val numeric = normalized.removeSuffix(policy.versionNameSuffix)
            val match = pattern.matchEntire(numeric) ?: return null
            return StableVersion(normalized, (1..3).map { match.groupValues[it] })
        }

        private val installedRcPattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)-rc([1-9][0-9]{0,5})$")

        /** Only the installed version may be an RC; this parser must not validate release tags or APK specs. */
        fun parseInstalled(value: String, policy: ProductUpdatePolicy = ProductUpdatePolicy.Standard): StableVersion? {
            parse(value, policy)?.let { return it }
            val normalized = value.removePrefix("v")
            if (normalized.isEmpty() || normalized.length > 128) return null
            // iOS marketing versions remain numeric even for a suffixed product. This exception
            // is installed-only: it never validates an unsuffixed remote tag or APK install spec.
            if (policy.versionNameSuffix.isNotEmpty()) {
                pattern.matchEntire(normalized)?.let { match ->
                    return StableVersion(normalized, (1..3).map { match.groupValues[it] })
                }
            }
            // An installed RC must still explicitly name its product, after the RC component.
            if (!normalized.endsWith(policy.versionNameSuffix)) return null
            val numeric = normalized.removeSuffix(policy.versionNameSuffix)
            val match = installedRcPattern.matchEntire(numeric) ?: return null
            return StableVersion(normalized, (1..3).map { match.groupValues[it] }, match.groupValues[4])
        }
    }
}

