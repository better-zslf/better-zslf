package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.CampusNetworkRepository
import io.github.betterzslf.data.CampusPortalRepository

/** Every method uses the same captured repository; conversion does not recapture a newer login. */
class RepositoryCampusNetworkSource private constructor(private val repository: CampusNetworkRepository) : CampusNetworkSource {
    override fun isCurrentSession(): Boolean = repository.isBoundToCurrentSession
    override suspend fun accounts(): CampusOutcome<List<CampusAccountRecord>> = repository.accounts().campusDecode(::parseCampusAccounts)
    override suspend fun checkPhone(phone: String): CampusOutcome<Unit> = repository.checkPhone(phone).campusMap { Unit }
    override suspend fun updatePassword(accountId: String, password: String): CampusOutcome<Unit> =
        repository.updatePassword(accountId, password).campusMap { Unit }
    override fun toString(): String = "RepositoryCampusNetworkSource(redacted)"

    companion object {
        fun capture(repository: CampusNetworkRepository): RepositoryCampusNetworkSource? =
            repository.boundToCurrentSession()?.let(::RepositoryCampusNetworkSource)
    }
}

class RepositoryCampusPortalSource private constructor(private val repository: CampusPortalRepository) : CampusPortalSource {
    override fun isCurrentSession(): Boolean = repository.isBoundToCurrentSession
    override suspend fun internetInfo(): CampusOutcome<CampusOnlineStatus> = repository.internetInfo().campusMap(::parseCampusOnlineStatus)
    override suspend fun redirectTarget(): CampusOutcome<CampusRedirectTarget> = repository.redirectTarget().campusMap(::parseCampusRedirectTarget)
    override suspend fun dialIn(portalUrl: String): CampusOutcome<CampusDialResult> = repository.dialIn(portalUrl).campusMap(::parseCampusDialResult)
    override suspend fun offline(): CampusOutcome<Unit> = repository.offline().campusMap { Unit }
    override fun toString(): String = "RepositoryCampusPortalSource(redacted)"

    companion object {
        fun capture(repository: CampusPortalRepository): RepositoryCampusPortalSource? =
            repository.boundToCurrentSession()?.let(::RepositoryCampusPortalSource)
    }
}

/** An independent, fixed-bound read capability, not the dorm runtime or its credentials. */
class RepositoryCampusProvisionedPhoneSource private constructor(private val repository: CampusNetworkRepository) : CampusProvisionedPhoneSource {
    override fun isCurrentSession(): Boolean = repository.isBoundToCurrentSession
    override suspend fun readProvisionedPhone(): CampusOutcome<String?> = repository.accounts().campusMap(::parseCampusProvisionedPhone)
    override fun toString(): String = "RepositoryCampusProvisionedPhoneSource(redacted)"

    companion object {
        fun capture(repository: CampusNetworkRepository): RepositoryCampusProvisionedPhoneSource? =
            repository.boundToCurrentSession()?.let(::RepositoryCampusProvisionedPhoneSource)
    }
}

private inline fun <T, R> ApiResult<T>.campusMap(decode: (T) -> R): CampusOutcome<R> = when (this) {
    is ApiResult.Success -> CampusOutcome.Success(decode(data))
    is ApiResult.Failure -> CampusOutcome.Failure(error.campusIssue())
}

private inline fun <T, R> ApiResult<T>.campusDecode(decode: (T) -> CampusOutcome<R>): CampusOutcome<R> = when (this) {
    is ApiResult.Success -> decode(data)
    is ApiResult.Failure -> CampusOutcome.Failure(error.campusIssue())
}

private fun ApiError.campusIssue(): CampusIssue = when (this) {
    is ApiError.Authentication -> CampusIssue.AUTHENTICATION
    is ApiError.Business -> CampusIssue.BUSINESS
    is ApiError.Validation -> CampusIssue.VALIDATION
    is ApiError.Timeout -> CampusIssue.TIMEOUT
    is ApiError.Network -> CampusIssue.NETWORK
    is ApiError.Decode -> CampusIssue.MALFORMED
    is ApiError.Http -> CampusIssue.HTTP
    is ApiError.Storage -> CampusIssue.STORAGE
}
