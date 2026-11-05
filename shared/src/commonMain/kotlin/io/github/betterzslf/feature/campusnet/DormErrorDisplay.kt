package io.github.betterzslf.feature.campusnet

import io.github.betterzslf.core.text.AppText

/**
 * Bridges the dorm dialer's code-indexed error table to the app's display copy.
 *
 * `DormError.message` is an English fallback and lookup key by design (see `DormErrorCodes.kt`);
 * the user-facing sentence always comes from `AppText.CampusNetwork.dormErrorText`, keyed by the
 * same server code. Keeping the mapping here means the protocol table stays copy-free and the copy
 * stays in `AppText`, which is the project's rule for every other feature.
 */
fun dormErrorDisplayText(error: DormError): String {
    val text = AppText.CampusNetwork.dormErrorText(error.code)
    // An unmapped code is a new code: show the code itself rather than an empty banner or an
    // English sentence, and the retry family still drives behaviour independently of the wording.
    return text.ifBlank { AppText.CampusNetwork.dormUnknownErrorText(error.code) }
}

/**
 * Codes whose meaning is "wait, do not hammer the gateway". Used to decide whether the UI offers a
 * retry button at all: [DormErrorFamily.FATAL] never does, because only the user can fix it.
 */
fun dormErrorRetryable(error: DormError): Boolean = error.family != DormErrorFamily.FATAL
