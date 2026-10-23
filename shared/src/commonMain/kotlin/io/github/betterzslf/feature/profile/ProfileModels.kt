package io.github.betterzslf.feature.profile

import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.result.ApiResult
import kotlinx.serialization.json.*

/** Fields evidenced in getUserInfo.data.userInfo. No guessed department/organization aliases. */
data class ProfileFields(
    val name: String?, val schoolName: String?, val studentNumber: String?,
    val account: String?, val identity: String?, val gender: String?,
) { override fun toString(): String = "ProfileFields(redacted)" }

data class ProfileStatistics(val collected: Long?, val comments: Long?, val history: Long?) {
    override fun toString(): String = "ProfileStatistics(redacted)"
}

sealed interface ProfilePhotoMetadata {
    data object Empty : ProfilePhotoMetadata
    data object Missing : ProfilePhotoMetadata
    data object UnsupportedPath : ProfilePhotoMetadata
    class Resource internal constructor(internal val url: String) : ProfilePhotoMetadata {
        override fun toString(): String = "ProfilePhotoResource(redacted)"
    }
}

interface ProfileSource { fun boundToCurrentSession(): BoundProfileSource? }
interface BoundProfileSource {
    val isBoundToCurrentSession: Boolean
    suspend fun readFields(): ApiResult<ProfileFields>
    suspend fun readStatistics(): ApiResult<ProfileStatistics>
    suspend fun readPhotoMetadata(): ApiResult<ProfilePhotoMetadata>
}

/** Opaque in-memory owner/token comparison; never appears in display/debug state. */
internal class ProfileOwner private constructor(private val id: String, private val token: String) {
    fun matches(session: Session?): Boolean = session != null && session.userId == id && session.token == token
    override fun toString(): String = "ProfileOwner(redacted)"
    companion object {
        fun from(session: Session?): ProfileOwner? {
            val current = session ?: return null
            val id = current.userId?.takeIf { it.isNotBlank() } ?: return null
            if (current.token.isBlank()) return null
            return ProfileOwner(id, current.token)
        }
    }
}

object ProfileDataDecoder {
    fun fields(data: JsonElement): ProfileFields? {
        val user = (data as? JsonObject)?.get("userInfo") as? JsonObject ?: return null
        return ProfileFields(user.text("name"), user.text("schoolName"), user.text("studentNum"),
            user.text("account"), user.text("typeValue"), user.text("sexValue"))
    }
    fun statistics(data: JsonElement): ProfileStatistics? {
        val root = data as? JsonObject ?: return null
        return ProfileStatistics(root.count("collect"), root.count("comment"), root.count("history"))
    }
    fun photo(data: JsonElement, fileUrl: (String) -> String?): ProfilePhotoMetadata? {
        val root = data as? JsonObject ?: return null
        val attachment = root["attachment"] ?: return ProfilePhotoMetadata.Missing
        if (attachment == JsonNull) return ProfilePhotoMetadata.Missing
        val obj = attachment as? JsonObject ?: return null
        val value = obj["filepath"] ?: return ProfilePhotoMetadata.Missing
        if (value == JsonNull) return ProfilePhotoMetadata.Missing
        val path = (value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        if (path.isBlank()) return ProfilePhotoMetadata.Empty
        val relative = AvatarImagePolicy.relativePath(path) ?: return ProfilePhotoMetadata.UnsupportedPath
        val url = fileUrl(relative) ?: return ProfilePhotoMetadata.UnsupportedPath
        return ProfilePhotoMetadata.Resource(url)
    }
    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }
        ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.count(name: String): Long? {
        val value = (get(name) as? JsonPrimitive)?.contentOrNull ?: return null
        if (!Regex("^(0|[1-9][0-9]*)$").matches(value)) return null
        return value.toLongOrNull()?.takeIf { it >= 0 }
    }
}
