package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText

/** Safe display rows: neither a backend target id nor an unmasked account is exposed. */
data class CampusAccount(val key: String, val maskedAccount: String, val typeStatus: String, val canChangePassword: Boolean) {
    val typeStatusLabel: String get() = if (typeStatus.isBlank()) "" else AppText.CampusNetwork.typeStatus(typeStatus)
}

/** No authorization nonce in public UI state. The product binder captures it separately. */
data class CampusPasswordPreview(val maskedAccount: String, val maskedPhone: String)
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

/** Ephemeral generation keys stay controller-owned; preserve ids verbatim before eligibility checks. */
internal fun campusAccountsSnapshot(records: List<CampusAccountRecord>, generation: Long): CampusAccountsSnapshot {
    val ids = records.map { it.id }
    val duplicateIds = ids.filter(String::isNotBlank).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    val targets = mutableMapOf<String, String>()
    val rows = records.mapIndexed { index, record ->
        val id = record.id
        val key = "account:$generation:$index"
        val known = id.isNotBlank() && id == id.trim() && id.none(Char::isISOControl) && id !in duplicateIds && record.account.isNotBlank()
        if (known) targets[key] = id
        CampusAccount(key, campusMask(record.account), record.typeStatus.take(32), known)
    }
    return CampusAccountsSnapshot(rows, targets.toMap())
}

internal fun campusMask(value: String): String = when {
    value.isBlank() -> AppText.CampusNetwork.ACCOUNT_NOT_RETURNED
    value.length <= 4 -> "••••"
    else -> value.take(2) + "••••" + value.takeLast(2)
}

fun campusPhoneValidation(phone: String): String? =
    if (phone.length == 11 && phone.all { it in '0'..'9' }) null else AppText.Common.MOBILE_NUMBER_MUST_HAVE_ELEVEN_DIGITS

fun campusPasswordValidation(password: String, confirmation: String): String? = when {
    password.isBlank() -> AppText.CampusNetwork.NEW_BROADBAND_PASSWORD_REQUIRED
    password.any(Char::isISOControl) -> AppText.Common.PASSWORD_MUST_NOT_CONTAIN_NEWLINES_OR_CONTROL_CHARACTERS
    password != confirmation -> AppText.CampusNetwork.BROADBAND_PASSWORD_CONFIRMATION_MISMATCH
    else -> null
}

internal fun campusFailure(issue: CampusIssue): String = when (issue) {
    CampusIssue.AUTHENTICATION -> AppText.CampusNetwork.OPERATION_LOGIN_CHANGED_REENTER_REQUIRED
    CampusIssue.BUSINESS -> AppText.CampusNetwork.REQUEST_NOT_ACCEPTED_REVIEW_AND_CONTACT_CARRIER
    CampusIssue.VALIDATION -> AppText.CampusNetwork.INCOMPLETE_DETAILS_REVIEW_INPUT_AND_ACCOUNT_ID
    CampusIssue.TIMEOUT -> AppText.Common.REQUEST_TIMEOUT_RESULT_UNCONFIRMED
    CampusIssue.NETWORK -> AppText.Common.CONNECTION_INTERRUPTED_RESULT_UNCONFIRMED
    CampusIssue.MALFORMED -> AppText.CampusNetwork.SERVICE_RESPONSE_CONTENT_UNCONFIRMED
    CampusIssue.HTTP -> AppText.CampusNetwork.SERVICE_TEMPORARILY_FAILED_RESULT_UNCONFIRMED
    CampusIssue.STORAGE -> AppText.CampusNetwork.LOCAL_STATE_INVALID_RESULT_UNCONFIRMED
}
