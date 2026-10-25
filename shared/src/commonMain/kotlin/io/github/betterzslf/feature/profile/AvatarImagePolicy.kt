package io.github.betterzslf.feature.profile

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.net.ApiConfig

enum class AvatarIssue { UNSUPPORTED_PATH, UNSUPPORTED_FORMAT, MALFORMED_IMAGE, DIMENSIONS, TOO_LARGE, HTTP, REDIRECT, NETWORK, TIMEOUT, DECODE, CLOSED }
fun avatarIssueMessage(issue: AvatarIssue): String = when (issue) {
    AvatarIssue.UNSUPPORTED_PATH -> AppText.Profile.AVATAR_PATH_FORMAT_UNSUPPORTED
    AvatarIssue.UNSUPPORTED_FORMAT -> AppText.Profile.AVATAR_FORMAT_REQUIRES_PNG_OR_JPEG
    AvatarIssue.MALFORMED_IMAGE -> AppText.Profile.INVALID_AVATAR_FILE_HEADER
    AvatarIssue.DIMENSIONS -> AppText.Profile.AVATAR_DIMENSIONS_LIMIT_EXCEEDED
    AvatarIssue.TOO_LARGE -> AppText.Profile.AVATAR_FILE_READ_SIZE_LIMIT_EXCEEDED
    AvatarIssue.HTTP -> AppText.Profile.AVATAR_UNAVAILABLE_NO_ADDED_CREDENTIALS_OR_AUTO_RETRY
    AvatarIssue.REDIRECT -> AppText.Profile.AVATAR_REDIRECT_READ_NOT_CONTINUED
    AvatarIssue.NETWORK -> AppText.Profile.AVATAR_CONNECTION_FAILED_REFRESH_TO_RETRY
    AvatarIssue.TIMEOUT -> AppText.Profile.AVATAR_READ_TIMEOUT_REFRESH_TO_RETRY
    AvatarIssue.DECODE -> AppText.Profile.AVATAR_DECODE_FAILED_REFRESH_TO_RETRY
    AvatarIssue.CLOSED -> AppText.Profile.AVATAR_READING_CLOSED
}
enum class AvatarFormat { PNG, JPEG }
internal data class AvatarHeader(val width: Int, val height: Int, val format: AvatarFormat)
internal sealed interface AvatarHeaderCheck {
    data class Accepted(val header: AvatarHeader) : AvatarHeaderCheck
    data class Rejected(val issue: AvatarIssue) : AvatarHeaderCheck
}

/** Encoded bytes only, not proof of successful decode. Cleared on refresh, owner change/dispose. */
class AvatarBytes internal constructor(internal val encoded: ByteArray, val width: Int, val height: Int, val format: AvatarFormat) {
    internal fun clear() { encoded.fill(0) }
    override fun toString(): String = "AvatarBytes(redacted)"
}
sealed interface AvatarLoadResult {
    data class Image(val image: AvatarBytes) : AvatarLoadResult
    data class Failed(val issue: AvatarIssue) : AvatarLoadResult
}
interface AvatarSource { suspend fun load(resource: ProfilePhotoMetadata.Resource): AvatarLoadResult }

object AvatarImagePolicy {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_DIMENSION = 2048
    const val MAX_PIXELS = 4_000_000L
    internal val filesBaseUrl: String = ApiConfig().filesBaseUrl.trimEnd('/') + "/"

    /** Do not add a second userfiles prefix, resolve an absolute URL, or guess an attachment filename. */
    fun relativePath(raw: String): String? {
        if (raw.isEmpty() || raw.length > 2048 || raw.startsWith("//") || raw.any { it <= ' ' || it.code == 127 || it in ":\\?#%" }) return null
        val path = raw.removePrefix("/")
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." } || parts.first().equals("userfiles", ignoreCase = true)) return null
        return path
    }
    internal fun isAllowedUrl(url: String): Boolean {
        if (!filesBaseUrl.startsWith("https://") || !url.startsWith(filesBaseUrl)) return false
        val raw = url.removePrefix(filesBaseUrl)
        return relativePath(raw) == raw
    }
    internal fun inspect(bytes: ByteArray): AvatarHeaderCheck {
        if (bytes.size > MAX_BYTES) return AvatarHeaderCheck.Rejected(AvatarIssue.TOO_LARGE)
        val png = intArrayOf(137, 80, 78, 71, 13, 10, 26, 10)
        if (bytes.size >= 8 && png.indices.all { bytes[it].u() == png[it] }) {
            if (bytes.size < 33 || bytes.u32(8) != 13L || (12..15).map { bytes[it].u().toChar() }.joinToString("") != "IHDR" ||
                bytes[24].u() !in setOf(1, 2, 4, 8, 16) || bytes[25].u() !in setOf(0, 2, 3, 4, 6) ||
                bytes[26].u() != 0 || bytes[27].u() != 0 || bytes[28].u() !in 0..1) return AvatarHeaderCheck.Rejected(AvatarIssue.MALFORMED_IMAGE)
            return dimensions(bytes.u32(16), bytes.u32(20), AvatarFormat.PNG)
        }
        if (bytes.size >= 2 && bytes[0].u() == 255 && bytes[1].u() == 216) {
            var offset = 2
            val sof = setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)
            while (offset < bytes.size) {
                if (bytes[offset].u() != 255) return AvatarHeaderCheck.Rejected(AvatarIssue.MALFORMED_IMAGE)
                while (offset < bytes.size && bytes[offset].u() == 255) offset++
                if (offset >= bytes.size) break
                val marker = bytes[offset++].u()
                if (marker == 0xda || marker == 0xd9 || marker == 0xd8 || marker == 0) break
                if (marker in 0xd0..0xd7 || marker == 1) continue
                if (offset + 2 > bytes.size) break
                val length = bytes.u16(offset)
                if (length < 2 || length > bytes.size - offset) break
                if (marker in sof) {
                    if (length < 11 || bytes[offset + 2].u() !in setOf(8, 12, 16) ||
                        bytes[offset + 7].u() !in 1..4 || length != 8 + 3 * bytes[offset + 7].u()) break
                    return dimensions(bytes.u16(offset + 5).toLong(), bytes.u16(offset + 3).toLong(), AvatarFormat.JPEG)
                }
                offset += length
            }
            return AvatarHeaderCheck.Rejected(AvatarIssue.MALFORMED_IMAGE)
        }
        return AvatarHeaderCheck.Rejected(AvatarIssue.UNSUPPORTED_FORMAT)
    }
    private fun dimensions(width: Long, height: Long, format: AvatarFormat): AvatarHeaderCheck {
        if (width !in 1..MAX_DIMENSION.toLong() || height !in 1..MAX_DIMENSION.toLong() || width * height > MAX_PIXELS) return AvatarHeaderCheck.Rejected(AvatarIssue.DIMENSIONS)
        return AvatarHeaderCheck.Accepted(AvatarHeader(width.toInt(), height.toInt(), format))
    }
    private fun Byte.u() = toInt() and 255
    private fun ByteArray.u16(index: Int) = this[index].u() * 256 + this[index + 1].u()
    private fun ByteArray.u32(index: Int): Long = (this[index].u().toLong() shl 24) or (this[index + 1].u().toLong() shl 16) or (this[index + 2].u().toLong() shl 8) or this[index + 3].u().toLong()
}
