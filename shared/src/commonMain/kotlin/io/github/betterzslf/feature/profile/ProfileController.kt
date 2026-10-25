package io.github.betterzslf.feature.profile

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.model.Session
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class ProfileReadIssue { AUTHENTICATION, MALFORMED, FAILED }
fun profileReadMessage(issue: ProfileReadIssue): String = when (issue) {
    ProfileReadIssue.AUTHENTICATION -> AppText.Profile.SESSION_CHANGED_RELOGIN_BEFORE_PROFILE_READ
    ProfileReadIssue.MALFORMED -> AppText.Profile.INVALID_RETURNED_PROFILE_FORMAT
    ProfileReadIssue.FAILED -> AppText.Profile.PROFILE_READ_INCOMPLETE_REFRESH_TO_RETRY
}
sealed interface ProfileAvatarState {
    data object Unrequested : ProfileAvatarState
    data object Loading : ProfileAvatarState
    data object Empty : ProfileAvatarState
    data object Missing : ProfileAvatarState
    data class Failed(val issue: AvatarIssue) : ProfileAvatarState
    data class BytesReady(val image: AvatarBytes) : ProfileAvatarState
}
data class ProfileUiState(
    val hasSession: Boolean = false, val loading: Boolean = false, val cancelled: Boolean = false,
    val fields: ProfileFields? = null, val statistics: ProfileStatistics? = null,
    val fieldsIssue: ProfileReadIssue? = null, val statisticsIssue: ProfileReadIssue? = null,
    val photoIssue: ProfileReadIssue? = null, val avatar: ProfileAvatarState = ProfileAvatarState.Unrequested,
) { override fun toString(): String = "ProfileUiState(redacted, loading=$loading)" }

/** Page/UI-dispatcher owned. All three reads and byte/decode results share one generation. */
class ProfileController(
    private val repository: ProfileSource,
    private val sessions: StateFlow<Session?>,
    private val avatars: AvatarSource,
    private val scope: CoroutineScope,
) {
    private var owner = ProfileOwner.from(sessions.value)
    private val mutable = MutableStateFlow(ProfileUiState(hasSession = owner != null))
    val state: StateFlow<ProfileUiState> = mutable.asStateFlow()
    private var generation = 0L
    private var refreshJob: Job? = null
    private var closed = false
    private val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) { sessions.collect { synchronizeSession() } }

    /** Extra synchronous render gate while a StateFlow session event is awaiting its dispatcher. */
    fun visibleState(): ProfileUiState = if (!closed && owner?.matches(sessions.value) == true) mutable.value else ProfileUiState()

    private fun synchronizeSession() {
        if (closed) return
        val session = sessions.value
        if (owner?.matches(session) == true || owner == null && ProfileOwner.from(session) == null) return
        ++generation; refreshJob?.cancel(); refreshJob = null; clearImage()
        owner = ProfileOwner.from(session)
        mutable.value = ProfileUiState(hasSession = owner != null)
    }

    /** No constructor/resume requests; the page's initial read and every retry call this explicitly. */
    fun refresh() {
        if (closed) return
        synchronizeSession()
        if (mutable.value.loading) return
        val operationOwner = owner ?: return
        val bound = repository.boundToCurrentSession() ?: run {
            mutable.value = ProfileUiState(hasSession = true, fieldsIssue = ProfileReadIssue.AUTHENTICATION); return
        }
        val ticket = ++generation
        clearImage()
        mutable.value = ProfileUiState(hasSession = true, loading = true, avatar = ProfileAvatarState.Loading)
        refreshJob = scope.launch {
            try {
                if (!current(ticket, operationOwner, bound)) return@launch
                val fields = bound.readFields()
                if (!current(ticket, operationOwner, bound)) return@launch
                mutable.value = when (fields) {
                    is ApiResult.Success -> mutable.value.copy(fields = fields.data)
                    is ApiResult.Failure -> mutable.value.copy(fieldsIssue = fields.error.profileIssue())
                }
                val stats = bound.readStatistics()
                if (!current(ticket, operationOwner, bound)) return@launch
                mutable.value = when (stats) {
                    is ApiResult.Success -> mutable.value.copy(statistics = stats.data)
                    is ApiResult.Failure -> mutable.value.copy(statisticsIssue = stats.error.profileIssue())
                }
                val photo = bound.readPhotoMetadata()
                if (!current(ticket, operationOwner, bound)) return@launch
                when (photo) {
                    is ApiResult.Failure -> mutable.value = mutable.value.copy(photoIssue = photo.error.profileIssue(), avatar = ProfileAvatarState.Unrequested)
                    is ApiResult.Success -> when (val metadata = photo.data) {
                        ProfilePhotoMetadata.Empty -> mutable.value = mutable.value.copy(avatar = ProfileAvatarState.Empty)
                        ProfilePhotoMetadata.Missing -> mutable.value = mutable.value.copy(avatar = ProfileAvatarState.Missing)
                        ProfilePhotoMetadata.UnsupportedPath -> mutable.value = mutable.value.copy(avatar = ProfileAvatarState.Failed(AvatarIssue.UNSUPPORTED_PATH))
                        is ProfilePhotoMetadata.Resource -> {
                            if (!current(ticket, operationOwner, bound)) return@launch
                            val result = avatars.load(metadata)
                            if (!current(ticket, operationOwner, bound)) {
                                if (result is AvatarLoadResult.Image) result.image.clear()
                                return@launch
                            }
                            mutable.value = mutable.value.copy(avatar = when (result) {
                                is AvatarLoadResult.Image -> ProfileAvatarState.BytesReady(result.image)
                                is AvatarLoadResult.Failed -> ProfileAvatarState.Failed(result.issue)
                            })
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current(ticket, operationOwner, bound)) mutable.value = mutable.value.copy(fieldsIssue = ProfileReadIssue.FAILED, avatar = ProfileAvatarState.Failed(AvatarIssue.NETWORK))
            } finally {
                if (!closed && ticket == generation) {
                    if (!operationOwner.matches(sessions.value)) synchronizeSession()
                    else mutable.value = mutable.value.copy(loading = false)
                    refreshJob = null
                }
            }
        }
    }

    fun cancel() {
        if (closed || !mutable.value.loading) return
        ++generation; refreshJob?.cancel(); refreshJob = null; clearImage()
        mutable.value = ProfileUiState(hasSession = owner?.matches(sessions.value) == true, cancelled = true)
    }
    fun avatarDecodeFailed(expected: AvatarBytes) {
        if (closed || owner?.matches(sessions.value) != true) return
        val ready = mutable.value.avatar as? ProfileAvatarState.BytesReady ?: return
        if (ready.image !== expected) return
        expected.clear(); mutable.value = mutable.value.copy(avatar = ProfileAvatarState.Failed(AvatarIssue.DECODE))
    }
    private fun current(ticket: Long, operationOwner: ProfileOwner, bound: BoundProfileSource): Boolean =
        !closed && ticket == generation && operationOwner.matches(sessions.value) && bound.isBoundToCurrentSession
    private fun clearImage() { (mutable.value.avatar as? ProfileAvatarState.BytesReady)?.image?.clear() }
    fun close() {
        if (closed) return
        closed = true; ++generation; refreshJob?.cancel(); refreshJob = null; observer.cancel(); clearImage()
        owner = null; mutable.value = ProfileUiState()
    }
    private fun ApiError.profileIssue(): ProfileReadIssue = when (this) {
        is ApiError.Authentication -> ProfileReadIssue.AUTHENTICATION
        is ApiError.Decode -> ProfileReadIssue.MALFORMED
        else -> ProfileReadIssue.FAILED
    }
}
