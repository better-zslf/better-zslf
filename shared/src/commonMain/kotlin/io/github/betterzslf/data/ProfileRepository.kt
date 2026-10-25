package io.github.betterzslf.data

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.net.ApiEndpoint
import io.github.betterzslf.core.net.ApiSessionBinding
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.feature.profile.*
import kotlinx.serialization.json.JsonElement

/** Existing raw calls stay compatible; the interactive profile uses one opaque bound owner. */
class ProfileRepository private constructor(private val api: ApiClient, private val binding: ApiSessionBinding?) : ProfileSource, BoundProfileSource {
    constructor(api: ApiClient) : this(api, null)

    override val isBoundToCurrentSession: Boolean get() = binding != null && api.isBindingCurrent(binding)
    override fun boundToCurrentSession(): ProfileRepository? {
        if (binding != null) return if (api.isBindingCurrent(binding)) this else null
        return api.captureSessionBinding()?.let { ProfileRepository(api, it) }
    }

    suspend fun userInfo() = api.execute(ApiEndpoint.USER_INFO, expectedSession = binding)
    suspend fun photo() = api.execute(ApiEndpoint.USER_PHOTO, expectedSession = binding)
    suspend fun statistics() = api.execute(ApiEndpoint.USER_STATISTICS, expectedSession = binding)

    override suspend fun readFields(): ApiResult<ProfileFields> = read(ApiEndpoint.USER_INFO, ProfileDataDecoder::fields)
    override suspend fun readStatistics(): ApiResult<ProfileStatistics> = read(ApiEndpoint.USER_STATISTICS, ProfileDataDecoder::statistics)
    override suspend fun readPhotoMetadata(): ApiResult<ProfilePhotoMetadata> = read(ApiEndpoint.USER_PHOTO) { ProfileDataDecoder.photo(it, api::fileUrl) }

    private suspend fun <T : Any> read(endpoint: ApiEndpoint, decode: (JsonElement) -> T?): ApiResult<T> {
        if (!isBoundToCurrentSession) return ApiResult.Failure(ApiError.Authentication())
        val result = api.execute(endpoint, expectedSession = binding)
        // These are reads: hide a late result after owner/token changes, without clearing a new session.
        if (!isBoundToCurrentSession) return ApiResult.Failure(ApiError.Authentication())
        return when (result) {
            is ApiResult.Failure -> result
            is ApiResult.Success -> decode(result.data)?.let { ApiResult.Success(it, result.message) }
                ?: ApiResult.Failure(ApiError.Decode(AppText.Profile.INVALID_PROFILE_RESPONSE_FORMAT))
        }
    }
    override fun toString(): String = "ProfileRepository(bound=${binding != null})"
}
