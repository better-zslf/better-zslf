package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.github.betterzslf.data.CampusNetworkRepository
import kotlinx.serialization.json.*

/** The production adapter is created from exactly one opaque, owner+token-bound repository. */
interface CampusNetworkSource {
    fun isCurrentSession(): Boolean
    suspend fun accounts(): ApiResult<JsonElement>
    suspend fun checkPhone(phone: String): ApiResult<JsonElement>
    suspend fun updatePassword(accountId: String, password: String): ApiResult<JsonElement>
}

class RepositoryCampusNetworkSource private constructor(private val repository: CampusNetworkRepository) : CampusNetworkSource {
    override fun isCurrentSession() = repository.isBoundToCurrentSession
    override suspend fun accounts() = repository.accounts()
    override suspend fun checkPhone(phone: String) = repository.checkPhone(phone)
    override suspend fun updatePassword(accountId: String, password: String) = repository.updatePassword(accountId, password)
    override fun toString() = "RepositoryCampusNetworkSource(redacted)"

    companion object {
        fun capture(repository: CampusNetworkRepository): RepositoryCampusNetworkSource? =
            repository.boundToCurrentSession()?.let(::RepositoryCampusNetworkSource)
    }
}

/** These are presentation records, not raw backend rows. Neither account ids nor full accounts are exposed. */
data class CampusAccount(val key: String, val maskedAccount: String, val typeStatus: String, val canChangePassword: Boolean)
data class CampusPasswordPreview(val id: Long, val maskedAccount: String, val maskedPhone: String)
enum class CampusPasswordPhase { EDITING, PREVIEW, SUBMITTING, COMPLETED, FAILED, UNCERTAIN, SESSION_CHANGED }

data class CampusNetworkState(
    val accounts: List<CampusAccount> = emptyList(),
    val loading: Boolean = false,
    val checkingPhone: Boolean = false,
    val expanded: Boolean = false,
    val selectedAccountKey: String? = null,
    val phoneChecked: Boolean = false,
    val queryMessage: String? = null,
    val formMessage: String? = null,
    val phase: CampusPasswordPhase = CampusPasswordPhase.EDITING,
    val preview: CampusPasswordPreview? = null,
    val inputRevision: Long = 0,
) {
    val busy: Boolean get() = loading || checkingPhone || phase == CampusPasswordPhase.SUBMITTING
    val terminal: Boolean get() = phase in setOf(CampusPasswordPhase.COMPLETED, CampusPasswordPhase.FAILED,
        CampusPasswordPhase.UNCERTAIN, CampusPasswordPhase.SESSION_CHANGED)
}

internal class CampusAccountsSnapshot(val rows: List<CampusAccount>, val targets: Map<String, String>) {
    override fun toString() = "CampusAccountsSnapshot(rows=${rows.size})"
}

/** docs13 §5.7: operatorAccount[] contains id/operatorAccount/typeStatus. No phone or target aliases are invented. */
internal fun parseCampusAccounts(data: JsonElement, generation: Long): ApiResult<CampusAccountsSnapshot> {
    val rows = (data as? JsonObject)?.get("operatorAccount") as? JsonArray
        ?: return ApiResult.Failure(ApiError.Decode(AppText.CampusNetwork.BROADBAND_ACCOUNT_LIST_STRUCTURE_UNCONFIRMED))
    if (rows.any { it !is JsonObject }) return ApiResult.Failure(ApiError.Decode(AppText.CampusNetwork.BROADBAND_ACCOUNT_ITEM_STRUCTURE_UNCONFIRMED))
    fun JsonObject.value(key: String): String = (get(key) as? JsonPrimitive)?.let {
        if (it.isString || it.longOrNull != null) it.contentOrNull.orEmpty() else ""
    }.orEmpty()
    val objects = rows.map { it as JsonObject }
    val ids = objects.map { it.value("id") }
    val duplicateIds = ids.filter(String::isNotBlank).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val targets = mutableMapOf<String, String>()
    val result = objects.mapIndexed { index, row ->
        val id = ids[index]
        val account = row.value("operatorAccount")
        val key = "account:$generation:$index"
        val known = id.isNotBlank() && id == id.trim() && id.none(Char::isISOControl) && id !in duplicateIds && account.isNotBlank()
        if (known) targets[key] = id
        CampusAccount(key, campusMask(account), row.value("typeStatus").take(32), known)
    }
    return ApiResult.Success(CampusAccountsSnapshot(result, targets.toMap()))
}

internal fun campusMask(value: String): String = when {
    value.isBlank() -> AppText.CampusNetwork.ACCOUNT_NOT_RETURNED
    value.length <= 4 -> "••••"
    else -> value.take(2) + "••••" + value.takeLast(2)
}

/** Only a basic local input check; no undocumented operator password complexity policy is asserted. */
fun campusPhoneValidation(phone: String): String? =
    if (phone.length == 11 && phone.all { it in '0'..'9' }) null else AppText.Common.MOBILE_NUMBER_MUST_HAVE_ELEVEN_DIGITS

fun campusPasswordValidation(password: String, confirmation: String): String? = when {
    password.isBlank() -> AppText.CampusNetwork.NEW_BROADBAND_PASSWORD_REQUIRED
    password.any(Char::isISOControl) -> AppText.Common.PASSWORD_MUST_NOT_CONTAIN_NEWLINES_OR_CONTROL_CHARACTERS
    password != confirmation -> AppText.CampusNetwork.BROADBAND_PASSWORD_CONFIRMATION_MISMATCH
    else -> null
}

internal fun campusFailure(error: ApiError): String = when (error) {
    is ApiError.Authentication -> AppText.CampusNetwork.OPERATION_LOGIN_CHANGED_REENTER_REQUIRED
    is ApiError.Business -> AppText.CampusNetwork.REQUEST_NOT_ACCEPTED_REVIEW_AND_CONTACT_CARRIER
    is ApiError.Validation -> AppText.CampusNetwork.INCOMPLETE_DETAILS_REVIEW_INPUT_AND_ACCOUNT_ID
    is ApiError.Timeout -> AppText.Common.REQUEST_TIMEOUT_RESULT_UNCONFIRMED
    is ApiError.Network -> AppText.Common.CONNECTION_INTERRUPTED_RESULT_UNCONFIRMED
    is ApiError.Decode -> AppText.CampusNetwork.SERVICE_RESPONSE_CONTENT_UNCONFIRMED
    is ApiError.Http -> AppText.CampusNetwork.SERVICE_TEMPORARILY_FAILED_RESULT_UNCONFIRMED
    is ApiError.Storage -> AppText.CampusNetwork.LOCAL_STATE_INVALID_RESULT_UNCONFIRMED
}
